package com.projectx.traversal.hops

import com.projectx.game.nxt.OffsetTable
import com.projectx.pathfinder.StepRule
import world.gregs.voidps.type.Direction
import world.gregs.voidps.type.Tile
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.TreeMap

/**
 * The hop-knowledge overlay every client reads, in layers summed per key:
 * 1. shared: other contributors' `contrib-*.jsonl` lines ([SharedHopData]), re-read every [RELOAD_MS] when the
 *    checkout's files change. This machine's own exported lines are merged with its local store by the larger count
 *    ([Evidence.atLeast]): they are the same hops;
 * 2. the machine-local [HopStore], on a learning client only, re-read every [RELOAD_MS] when it changes;
 * 3. this client's evidence not flushed yet.
 *
 * The overlay only ever adds hop-passable edges and hop-stoppers to the hop rule; walking never reads it. Lookups are
 * lock-free reads of an immutable view.
 */
internal object HopKnowledge {
    const val RELOAD_MS = 30_000L
    const val FLUSH_MS = 30_000L

    private class View(val pass: Set<Long>, val stop: Set<Long>, val types: Set<String>, val all: Map<String, Evidence>)

    private class Shared(val others: MutableMap<String, Evidence>, val own: MutableMap<String, Evidence>, val signature: String)

    private val EMPTY = View(emptySet(), emptySet(), emptySet(), emptyMap())
    private val lock = Any()
    private val flushLock = Any()

    @Volatile
    private var view = EMPTY

    @Volatile
    private var loaded = false
    private var baseline: MutableMap<String, Evidence> = TreeMap()
    private var baselineOwn: MutableMap<String, Evidence> = TreeMap()

    @Volatile
    private var baselineSignature = ""
    private var local: MutableMap<String, Evidence> = TreeMap()
    private val pending = TreeMap<String, Evidence>()
    private var localMtime = -1L

    @Volatile
    private var nextReload = 0L

    @Volatile
    private var nextFlush = 0L

    @Volatile
    private var busy = false

    /** The client build evidence is stamped with: map edits arrive with updates. */
    fun build(): String = runCatching { OffsetTable.build }.getOrDefault("")

    /** Hops may cross the step from (x, y, plane) in [direction] though walking may not. */
    fun passable(x: Int, y: Int, plane: Int, direction: Direction): Boolean {
        val v = ensure()
        if (v.pass.isEmpty() && v.types.isEmpty()) return false
        val edge = HopEdges.pack(x, y, plane, direction)
        return edge in v.pass || (v.types.isNotEmpty() && HopBlockers.coveredByTypes(edge, v.types))
    }

    /** Hops stop at this step though walking passes it. */
    fun stops(x: Int, y: Int, plane: Int, direction: Direction): Boolean {
        val v = ensure()
        return v.stop.isNotEmpty() && HopEdges.pack(x, y, plane, direction) in v.stop
    }

    /** [walk], plus usable hop-passable edges whose far tile is [known], minus usable hop-stoppers. */
    fun rule(walk: StepRule, known: (Tile) -> Boolean): StepRule = StepRule { x, y, plane, d ->
        when {
            stops(x, y, plane, d) -> false
            walk.canStep(x, y, plane, d) -> true
            else -> passable(x, y, plane, d) && known(Tile.of(x + d.delta.x, y + d.delta.y, plane))
        }
    }

    /** The hop rule on the live map. */
    fun rule(): StepRule {
        val map = HopMap.current
        return rule(map.walk()) { map.contains(it.x, it.y, it.plane) }
    }

    /** Usable learned edges whose canonical tile lies in [minX]..[maxX] x [minY]..[maxY] on [plane]. */
    fun learnedIn(pass: Boolean, minX: Int, minY: Int, maxX: Int, maxY: Int, plane: Int): List<Long> {
        val v = ensure()
        return (if (pass) v.pass else v.stop).filter {
            HopEdges.plane(it) == plane && HopEdges.x(it) in minX..maxX && HopEdges.y(it) in minY..maxY
        }
    }

    /** A copy of the combined evidence for [key]. */
    fun get(key: String): Evidence? = ensure().all[key]?.copy()

    fun typeUsable(typeKey: String): Boolean = typeKey in ensure().types

    fun size(): Int = ensure().all.size

    /** Applies [change] to [key]'s local evidence and queues it for the store; nothing on a client that does not learn. */
    fun record(key: String, change: (Evidence) -> Unit) {
        if (!HopLearning.enabled()) return
        ensure()
        synchronized(lock) {
            val delta = Evidence()
            change(delta)
            HopLearning.contributor()?.let(delta.c::add)
            pending.merge(key, delta) { a, b -> a.also { it.add(b) } }
            local.merge(key, delta.copy()) { a, b -> a.also { it.add(b) } }
            rebuildView()
        }
        scheduleFlush(false)
    }

    /** Writes pending evidence now (end of a run, a settled hop, tests). */
    fun flushNow() = scheduleFlush(true)

    private fun scheduleFlush(now: Boolean) {
        val t = System.currentTimeMillis()
        if (!now && t < nextFlush) return
        nextFlush = t + FLUSH_MS
        if (now) flush() else Thread.ofVirtual().name("hops-flush").start(::flush)
    }

    private fun flush() {
        synchronized(flushLock) {
            val deltas = takePending() ?: return
            val stored = HopStore.flush(deltas)
            synchronized(lock) {
                if (stored == null) {
                    for ((key, delta) in deltas) pending.merge(key, delta) { a, b -> a.also { it.add(b) } }
                } else {
                    local = withPending(stored)
                    localMtime = mtime(HopStore.knowledgeFile())
                    rebuildView()
                }
            }
        }
    }

    private fun takePending(): Map<String, Evidence>? = synchronized(lock) {
        if (pending.isEmpty()) null else TreeMap(pending).also { pending.clear() }
    }

    private fun ensure(): View {
        if (!loaded) {
            synchronized(lock) {
                if (!loaded) {
                    applyShared(readShared())
                    if (HopLearning.enabled()) {
                        HopStore.discardIfOld()
                        HopStore.rotate()
                        HopStore.rebuildIfModelChanged()
                        local = HopStore.load()
                        localMtime = mtime(HopStore.knowledgeFile())
                    }
                    rebuildView()
                    loaded = true
                    val dir = SharedHopData.dir()
                    println(
                        "[hops] knowledge: ${baseline.size + baselineOwn.size} shared keys" +
                            (if (dir != null) " (live from $dir)" else "") +
                            (if (HopLearning.enabled()) ", ${local.size} local keys (learning, contributor ${HopLearning.contributor()})" else " (read-only)") +
                            ", ${view.pass.size} usable hop edges, ${view.stop.size} stoppers, ${view.types.size} types",
                    )
                }
            }
        }
        val now = System.currentTimeMillis()
        if (now >= nextReload && !busy) {
            nextReload = now + RELOAD_MS
            busy = true
            Thread.ofVirtual().name("hops-reload").start {
                try {
                    reloadSharedIfChanged()
                    if (HopLearning.enabled()) reloadLocalIfChanged()
                } finally {
                    busy = false
                }
            }
        }
        return view
    }

    /** Re-reads the checkout's contributor files when they changed on disk (a pull, an export). */
    fun reloadSharedIfChanged() {
        val dir = SharedHopData.dir() ?: return
        if (SharedHopData.signature(dir, SharedHopData.CONTRIB) == baselineSignature) return
        val shared = readShared()
        synchronized(lock) {
            applyShared(shared)
            rebuildView()
        }
        println(
            "[hops] shared knowledge changed in $dir: ${shared.others.size + shared.own.size} keys now, " +
                "${view.pass.size} usable hop edges, ${view.stop.size} stoppers, ${view.types.size} types",
        )
    }

    private fun reloadLocalIfChanged() {
        val m = mtime(HopStore.knowledgeFile())
        if (m == localMtime) return
        val fresh = HopStore.load()
        synchronized(lock) {
            local = withPending(fresh)
            localMtime = m
            rebuildView()
        }
    }

    private fun withPending(stored: Map<String, Evidence>): MutableMap<String, Evidence> {
        val out = TreeMap(stored)
        for ((key, delta) in pending) out.merge(key, delta.copy()) { a, b -> a.also { it.add(b) } }
        return out
    }

    private fun mtime(file: Path): Long = try {
        if (Files.isRegularFile(file)) Files.getLastModifiedTime(file).toMillis() else 0
    } catch (e: IOException) {
        0
    }

    private fun applyShared(shared: Shared) {
        baseline = shared.others
        baselineOwn = shared.own
        baselineSignature = shared.signature
    }

    private fun readShared(): Shared {
        val others = TreeMap<String, Evidence>()
        val own = TreeMap<String, Evidence>()
        val me = HopLearning.contributor()
        val dir = SharedHopData.dir()
        if (dir != null) {
            val signature = SharedHopData.signature(dir, SharedHopData.CONTRIB)
            for (file in SharedHopData.files(dir, SharedHopData.CONTRIB)) {
                try {
                    split(Files.readAllLines(file, StandardCharsets.UTF_8), me, others, own)
                } catch (e: IOException) {
                    println("[hops] could not read $file: $e")
                } catch (e: RuntimeException) {
                    println("[hops] could not read $file: $e")
                }
            }
            return Shared(others, own, signature)
        }
        for (lines in SharedHopData.resourceFiles(SharedHopData.CONTRIB)) split(lines, me, others, own)
        return Shared(others, own, "")
    }

    /**
     * Shared lines into [own] when [me] alone wrote them, else into [others]. A line from a model below
     * [HopModel.DISCARD_BELOW] (`"m"`, missing = old) is ignored.
     */
    fun split(lines: List<String>, me: String?, others: MutableMap<String, Evidence>, own: MutableMap<String, Evidence>) {
        val one = TreeMap<String, Evidence>()
        for (line in lines) {
            if (line.isBlank()) continue
            val o = HopJson.parseOrNull(line) ?: continue
            val model = HopJson.int(o, "m")
            if (model == null || model < HopModel.DISCARD_BELOW) continue
            one.clear()
            HopStore.readLines(listOf(line), one)
            for ((key, e) in one) {
                val mine = me != null && e.c.size == 1 && me in e.c
                (if (mine) own else others).merge(key, e) { a, b -> a.also { it.add(b) } }
            }
        }
    }

    private fun rebuildView() {
        val all = HashMap<String, Evidence>()
        for ((key, e) in baseline) all[key] = e.copy()
        val mine = HashMap<String, Evidence>()
        for ((key, e) in baselineOwn) mine[key] = e.copy()
        for ((key, e) in local) mine.merge(key, e.copy(), Evidence::atLeast)
        for ((key, e) in mine) all.merge(key, e) { a, b -> a.also { it.add(b) } }
        val pass = HashSet<Long>()
        val stop = HashSet<Long>()
        val types = HashSet<String>()
        for ((key, e) in all) {
            if (key.startsWith(HopEdges.TYPE + ":")) {
                if (e.usableType()) types += key
                continue
            }
            if (!e.usableEdge()) continue
            val packed = HopEdges.parse(key)
            if (packed < 0) continue
            if (key.startsWith(HopEdges.PASS + ":")) pass += packed else stop += packed
        }
        view = View(pass, stop, types, all)
    }

    /** Tests: replace both layers (null = empty) and mark loaded. */
    fun installForTests(baselineLines: List<String>?, localEvidence: Map<String, Evidence>?) {
        synchronized(lock) {
            baseline = TreeMap()
            baselineOwn = TreeMap()
            if (baselineLines != null) split(baselineLines, HopLearning.contributor(), baseline, baselineOwn)
            baselineSignature = ""
            local = if (localEvidence == null) TreeMap() else TreeMap(localEvidence)
            pending.clear()
            localMtime = -1
            nextReload = Long.MAX_VALUE
            rebuildView()
            loaded = true
        }
    }

    fun pendingForTests(): Map<String, Evidence> = synchronized(lock) { TreeMap(pending) }
}
