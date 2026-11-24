package com.projectx.traversal.hops

import java.io.IOException
import java.nio.channels.FileLock
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Locale
import java.util.Properties
import java.util.TreeMap
import java.util.TreeSet

/**
 * Where the hop schedule's learned values live, layered like [HopKnowledge]:
 * 1. shared: other contributors' `schedule-*.jsonl` ([SharedHopData]), re-read every [RELOAD_MS] when the checkout's
 *    files change. This machine's own exported lines are compared with its local store per key, the larger weight
 *    kept: they are the same samples;
 * 2. machine-local `schedule.jsonl` in [HopLearning.storeDir], on a learning client only: every client folds its own
 *    samples in under the store's lock every [FLUSH_MS], so none overwrites another's; re-read when it changes;
 * 3. this client's samples not flushed yet.
 *
 * Each key holds weighted sums ([Sums]), so layers and contributors simply add up. A key's weight is capped ([cap]) by
 * scaling its sums down, so old samples fade. What a key means is up to the caller.
 */
internal object ScheduleStore {
    data class Sums(val w: Double, val s1: Double, val s2: Double) {
        operator fun plus(o: Sums): Sums = Sums(w + o.w, s1 + o.s1, s2 + o.s2)

        fun capped(max: Double): Sums {
            if (w <= max || w <= 0) return this
            val k = max / w
            return Sums(w * k, s1 * k, s2 * k)
        }

        fun mean(): Double = if (w > 0) s1 / w else 0.0

        companion object {
            val NONE = Sums(0.0, 0.0, 0.0)
        }
    }

    const val FILE = "schedule.jsonl"
    const val LOCK_FILE = "schedule.jsonl.lock"
    const val RELOAD_MS = 30_000L
    const val FLUSH_MS = 30_000L

    /** The first version's store (one client's running means), converted once by [migrateProperties]. */
    const val OLD_FILE = "schedule.properties"

    /** Keys about probes on particular edges or tiles: tied to the hop knowledge, so dropped with it below [HopModel.DISCARD_BELOW]. */
    val PROBE_PREFIXES = listOf("ptry:", "face:", "misface:", "stand:", "daim:", "sway:")

    /** The most a key's samples weigh: interaction times follow changes faster than margins. */
    fun cap(key: String): Double = when {
        key.startsWith("dead:") || key.startsWith("lock:") -> 20.0
        key.startsWith("surge") || key.startsWith("dive") -> 200.0
        else -> 100.0
    }

    fun probeKey(key: String): Boolean = PROBE_PREFIXES.any(key::startsWith)

    private class Shared(val others: Map<String, Sums>, val own: Map<String, Sums>, val signature: String)

    private val lock = Any()

    @Volatile
    private var loaded = false
    private var shared: Map<String, Sums> = emptyMap()
    private var sharedOwn: Map<String, Sums> = emptyMap()
    private var local: Map<String, Sums> = emptyMap()
    private val pending = TreeMap<String, Sums>()
    private var sharedSignature = ""
    private var localMtime = 0L

    @Volatile
    private var nextCheck = 0L

    @Volatile
    private var busy = false
    private var lastFlush = 0L

    /** Every layer's sums for [key], capped. */
    fun get(key: String): Sums {
        ensure()
        synchronized(lock) {
            val others = shared[key] ?: Sums.NONE
            val stored = local[key] ?: Sums.NONE
            val exported = sharedOwn[key] ?: Sums.NONE
            val mine = if (stored.w >= exported.w) stored else exported
            return (others + mine + (pending[key] ?: Sums.NONE)).capped(cap(key))
        }
    }

    /** The keys starting with [prefix], in any layer. */
    fun keys(prefix: String): List<String> {
        ensure()
        synchronized(lock) {
            val out = TreeSet<String>()
            for (layer in listOf(shared, sharedOwn, local, pending)) layer.keys.filterTo(out) { it.startsWith(prefix) }
            return out.toList()
        }
    }

    /** A sample of [key]; kept only on a learning client, folded into the store within [FLUSH_MS]. */
    fun add(key: String, x: Double) {
        if (!HopLearning.enabled()) return
        ensure()
        synchronized(lock) { pending.merge(key, Sums(1.0, x, x * x), Sums::plus) }
        flushIfDue()
    }

    fun add(key: String, x: Int) = add(key, x.toDouble())

    fun describe(): String {
        ensure()
        synchronized(lock) {
            val dir = SharedHopData.dir()
            return "${shared.size} shared keys" + (if (dir != null) " (live from $dir)" else "") +
                if (HopLearning.enabled()) ", ${local.size} local keys (learning)" else " (read-only)"
        }
    }

    private fun ensure() {
        if (!loaded) {
            synchronized(lock) {
                if (!loaded) {
                    applyShared(readShared())
                    if (HopLearning.enabled()) {
                        migrateProperties()
                        local = readFile(localFile())
                        localMtime = mtime(localFile())
                    }
                    lastFlush = System.currentTimeMillis()
                    loaded = true
                }
            }
        }
        val now = System.currentTimeMillis()
        if (now >= nextCheck && !busy) {
            nextCheck = now + RELOAD_MS
            busy = true
            Thread.ofVirtual().name("schedule-reload").start {
                try {
                    reloadSharedIfChanged()
                    if (HopLearning.enabled()) {
                        flushIfDue()
                        reloadLocalIfChanged()
                    }
                } finally {
                    busy = false
                }
            }
        }
    }

    fun reloadSharedIfChanged() {
        val dir = SharedHopData.dir() ?: return
        if (SharedHopData.signature(dir, SharedHopData.SCHEDULE) == sharedSignature) return
        val fresh = readShared()
        synchronized(lock) { applyShared(fresh) }
        println("[hops] shared schedule changed in $dir: ${fresh.others.size} keys from others, ${fresh.own.size} of this machine's")
    }

    private fun reloadLocalIfChanged() {
        val m = mtime(localFile())
        if (m == localMtime) return
        val fresh = readFile(localFile())
        synchronized(lock) {
            local = fresh
            localMtime = m
        }
    }

    private fun flushIfDue() {
        val now = System.currentTimeMillis()
        val out: Map<String, Sums>
        synchronized(lock) {
            if (pending.isEmpty() || now - lastFlush < FLUSH_MS) return
            lastFlush = now
            out = TreeMap(pending)
            pending.clear()
        }
        val written = fold(out)
        synchronized(lock) {
            if (written == null) {
                for ((key, sums) in out) pending.merge(key, sums, Sums::plus)
                return
            }
            local = written
            localMtime = mtime(localFile())
        }
    }

    /** Folds [deltas] into the local store under its lock; what it holds afterwards, or null. */
    fun fold(deltas: Map<String, Sums>): Map<String, Sums>? {
        val file = localFile()
        var fileLock: FileLock? = null
        try {
            Files.createDirectories(file.parent)
            fileLock = HopStore.acquireLock(file.resolveSibling(LOCK_FILE))
            val current = TreeMap(readFile(file))
            for ((key, delta) in deltas) current[key] = ((current[key] ?: Sums.NONE) + delta).capped(cap(key))
            write(file, current, null)
            return current
        } catch (e: IOException) {
            println("[hops] could not write $file: $e")
            return null
        } catch (e: RuntimeException) {
            println("[hops] could not write $file: $e")
            return null
        } finally {
            HopStore.release(fileLock)
        }
    }

    fun localFile(): Path = HopLearning.storeDir().resolve(FILE)

    /**
     * [OLD_FILE] (`<name>.mean/.dev/.n`, `dead.<key>` ms) into sums: weight n (a timing 3), sum mean * n, sum of
     * squares (dev^2 + mean^2) * n; folded into the store, the old file renamed `.migrated`.
     */
    fun migrateProperties() {
        val old = HopLearning.storeDir().resolve(OLD_FILE)
        if (!Files.isRegularFile(old)) return
        val properties = Properties()
        try {
            Files.newBufferedReader(old).use(properties::load)
        } catch (e: IOException) {
            return
        } catch (e: RuntimeException) {
            return
        }
        val sums = TreeMap<String, Sums>()
        for (name in properties.stringPropertyNames()) {
            try {
                if (name.endsWith(".mean")) {
                    val key = name.dropLast(5)
                    val mean = properties.getProperty(name).toDouble()
                    val dev = properties.getProperty("$key.dev", "0").toDouble()
                    val n = minOf(cap(key), properties.getProperty("$key.n", "0").toDouble())
                    if (n > 0) sums[key] = Sums(n, mean * n, (dev * dev + mean * mean) * n)
                } else if (name.startsWith("dead.")) {
                    val ms = properties.getProperty(name).toDouble()
                    sums["dead:" + name.substring(5)] = Sums(3.0, 3 * ms, 3 * ms * ms)
                }
            } catch (_: NumberFormatException) {
            }
        }
        if (sums.isNotEmpty() && fold(sums) == null) return
        try {
            Files.move(old, old.resolveSibling("$OLD_FILE.migrated"), StandardCopyOption.REPLACE_EXISTING)
            println("[hops] $old converted into ${localFile()} (${sums.size} keys)")
        } catch (e: IOException) {
            println("[hops] $old converted but not renamed: $e")
        }
    }

    /** `{"k":"walk","w":3.0000,"s1":...,"s2":...,"m":8}` per key, sorted; with [contributor] a `"c"` too. */
    fun write(file: Path, sums: Map<String, Sums>, contributor: String?) {
        val text = buildString {
            for ((key, s) in TreeMap(sums)) {
                append("{\"k\":\"").append(key.replace("\\", "\\\\").replace("\"", "\\\"")).append('"')
                if (contributor != null) append(",\"c\":\"").append(contributor).append('"')
                append(",\"m\":").append(HopModel.VERSION)
                append(String.format(Locale.ROOT, ",\"w\":%.4f,\"s1\":%.4f,\"s2\":%.4f}\n", s.w, s.s1, s.s2))
            }
        }
        val temp = file.resolveSibling("${file.fileName}.tmp")
        Files.writeString(temp, text, StandardCharsets.UTF_8)
        Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    fun readFile(file: Path): Map<String, Sums> {
        val out = TreeMap<String, Sums>()
        if (!Files.isRegularFile(file)) return out
        try {
            parse(Files.readAllLines(file, StandardCharsets.UTF_8), null, out, null)
        } catch (e: IOException) {
            println("[hops] could not read $file: $e")
        } catch (e: RuntimeException) {
            println("[hops] could not read $file: $e")
        }
        return out
    }

    /**
     * Lines into [own] when [me] wrote them (and [own] is given), else into [others]. Lines from before a clean start
     * are dropped: below [HopModel.SCHEDULE_DISCARD_BELOW], and probe keys below [HopModel.DISCARD_BELOW] too.
     */
    fun parse(lines: List<String>, me: String?, others: MutableMap<String, Sums>, own: MutableMap<String, Sums>?) {
        for (line in lines) {
            if (line.isBlank()) continue
            val o = HopJson.parseOrNull(line) ?: continue
            val key = HopJson.string(o, "k") ?: continue
            val w = HopJson.double(o, "w") ?: continue
            val s1 = HopJson.double(o, "s1") ?: continue
            val s2 = HopJson.double(o, "s2") ?: continue
            if (w < 0) continue
            val floor = if (probeKey(key)) maxOf(HopModel.DISCARD_BELOW, HopModel.SCHEDULE_DISCARD_BELOW) else HopModel.SCHEDULE_DISCARD_BELOW
            val model = HopJson.int(o, "m")
            if (model == null || model < floor) continue
            val into = if (own != null && me != null && me == HopJson.string(o, "c")) own else others
            into.merge(key, Sums(w, s1, s2), Sums::plus)
        }
    }

    private fun applyShared(fresh: Shared) {
        shared = fresh.others
        sharedOwn = fresh.own
        sharedSignature = fresh.signature
    }

    private fun readShared(): Shared {
        val others = TreeMap<String, Sums>()
        val own = TreeMap<String, Sums>()
        val me = HopLearning.contributor()
        val dir = SharedHopData.dir()
        if (dir != null) {
            val signature = SharedHopData.signature(dir, SharedHopData.SCHEDULE)
            for (file in SharedHopData.files(dir, SharedHopData.SCHEDULE)) {
                try {
                    parse(Files.readAllLines(file, StandardCharsets.UTF_8), me, others, own)
                } catch (e: IOException) {
                    println("[hops] could not read $file: $e")
                } catch (e: RuntimeException) {
                    println("[hops] could not read $file: $e")
                }
            }
            return Shared(others, own, signature)
        }
        for (lines in SharedHopData.resourceFiles(SharedHopData.SCHEDULE)) parse(lines, me, others, own)
        return Shared(others, own, "")
    }

    private fun mtime(file: Path): Long = try {
        if (Files.isRegularFile(file)) Files.getLastModifiedTime(file).toMillis() else 0
    } catch (e: IOException) {
        0
    }

    /** Tests: replace every layer (null = empty), learning or not as [HopLearning] says. */
    fun installForTests(sharedLayer: Map<String, Sums>?, localLayer: Map<String, Sums>?) {
        synchronized(lock) {
            shared = sharedLayer?.let(::TreeMap) ?: emptyMap()
            sharedOwn = emptyMap()
            local = localLayer?.let(::TreeMap) ?: emptyMap()
            pending.clear()
            sharedSignature = ""
            loaded = true
            nextCheck = Long.MAX_VALUE
            lastFlush = System.currentTimeMillis()
        }
    }
}
