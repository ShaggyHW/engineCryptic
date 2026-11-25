package com.projectx.traversal.hops

import com.projectx.script.api.npcs
import world.gregs.voidps.type.Direction
import world.gregs.voidps.type.Tile
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Records every hop of a learning client and turns it into evidence: [begin] at the cast captures the window,
 * [Pending.observe] while it arms, [Pending.finish] once it landed, was refused or never happened. Explaining,
 * attributing and writing run on a virtual thread, so the walk never waits. A client that does not learn gets null.
 */
internal object HopRecorder {
    /** At most this many edges per hop are attributed to loc types; each may be a blocking lookup. */
    const val MAX_ATTRIBUTED = 4
    const val MAX_STARTS = 8

    private val sequence = AtomicInteger()
    private val settled = AtomicLong()
    private val surprises = AtomicLong()

    /** Hops recorded on this client since it started. */
    fun settledCount(): Long = settled.get()

    /** Of [settledCount], the non-probe hops that landed other than the learned map said. */
    fun surpriseCount(): Long = surprises.get()

    class Pending internal constructor(internal val observation: HopObservation) {
        private var done = false

        val isProbe: Boolean get() = observation.probe

        /** A server tile seen before the jump: the hop may have started from it while it armed. */
        @Synchronized
        fun observe(server: Tile?) {
            if (done || server == null || server.plane != observation.plane) return
            val last = observation.starts.lastOrNull()
            if (last != null && (server == last || server.getLongestDelta(last) > 2)) return
            if (observation.starts.size < MAX_STARTS) observation.starts += server
        }

        /** The player walked to [server] between the cast's decision and its click: the hop starts there or later. */
        @Synchronized
        fun walked(server: Tile?) {
            if (done || server == null || server.plane != observation.plane || server in observation.starts) return
            if (observation.starts.size < MAX_STARTS) observation.starts += server
        }

        @Synchronized
        fun finish(landed: Tile?, outcome: String, message: String? = null) {
            if (done) return
            done = true
            observation.landed = landed
            observation.outcome = outcome
            observation.message = message ?: ""
            Thread.ofVirtual().name("hops-explain").start { settle(observation) }
        }

        /** A walk click went out after the cast: the first tile seen after the jump may be past the landing. */
        @Synchronized
        fun noteWalkClick() {
            if (!done) observation.held = false
        }
    }

    /**
     * Starts recording a hop cast from the server tile [from]: Surge facing [direction], or Dive clicked on [aim].
     * [probeEdge] is the edge a probe tests, -1 for a normal hop.
     */
    fun begin(ability: String, from: Tile?, direction: Direction?, aim: Tile?, probeEdge: Long = -1): Pending? =
        begin(ability, from, direction, aim, probeEdge, manual = false)

    /** A hop the user cast by hand and will confirm: its strong evidence is manual. Nothing is written unless it finishes. */
    fun beginManual(ability: String, from: Tile?, direction: Direction?, aim: Tile?): Pending? =
        begin(ability, from, direction, aim, -1, manual = true)

    private fun begin(ability: String, from: Tile?, direction: Direction?, aim: Tile?, probeEdge: Long, manual: Boolean): Pending? {
        if (!HopLearning.enabled() || from == null) return null
        val o = HopObservation()
        o.id = "${HopLearning.contributor()}-${System.currentTimeMillis()}-${sequence.incrementAndGet()}"
        o.time = System.currentTimeMillis()
        o.ability = ability
        o.from = from
        o.starts += from
        o.direction = direction
        o.aim = aim
        o.probe = probeEdge >= 0
        o.probeEdge = probeEdge
        o.manual = manual
        o.build = HopKnowledge.build()
        o.capture(from)
        val spec = HopExplainer.Spec(ability, direction, aim)
        o.predicted = HopExplainer.run(spec, HopModel.live(), from)
        o.predictedLearned = HopExplainer.run(spec, HopKnowledge.rule(), from)
        try {
            for (npc in npcs.values) {
                val t = npc.tile
                if (t.plane == o.plane && t.getLongestDelta(from) <= HopObservation.R + 2) o.markOccupied(t.x, t.y)
            }
        } catch (_: Throwable) {
        }
        return Pending(o)
    }

    private fun settle(o: HopObservation) {
        try {
            val r = HopExplainer.explain(o)
            val attribute = LinkedHashSet<Long>()
            attribute += r.pass
            attribute += r.confirmed
            attribute += r.failed
            if (o.probe) attribute += o.probeEdge
            for (edge in attribute.take(MAX_ATTRIBUTED)) {
                val blockers = HopBlockers.resolve(edge) ?: continue
                o.blockers[HopEdges.key(HopEdges.PASS, edge)] = blockers.map { intArrayOf(it.locId, it.shape) }
            }
            applyResult(o, r, null)
            settled.incrementAndGet()
            if (!o.probe && r.kind !in QUIET) surprises.incrementAndGet()
            val from = o.from
            if (o.probe && o.probeEdge >= 0 && r.kind != HopExplainer.Kind.NO_CAST) {
                val passed = o.probeEdge in r.pass || o.probeEdge in r.confirmed
                HopExplorer.noteProbeTry(o.probeEdge, passed)
                val aim = o.aim
                val direction = o.direction
                if (o.ability == HopExplainer.DIVE && from != null && aim != null) HopExplorer.noteDiveAim(from, aim, passed)
                if (o.ability == HopExplainer.SURGE && from != null && direction != null && o.landed != null && HopExplainer.otherWay(o) == null) {
                    HopExplorer.noteSurgeWayTested(from, direction, passed)
                }
            }
            val direction = o.direction
            if (o.probe && o.ability == HopExplainer.SURGE && direction != null && from != null && o.landed != null) {
                HopExplorer.noteSurgeWay(from, direction, HopExplainer.otherWay(o) == null)
            }
            HopStore.append(o.toJson())
            // The 30 s flush only fires on the next piece of evidence, which may never come before the client closes.
            HopKnowledge.flushNow()
            if (r.kind != HopExplainer.Kind.MATCH || o.probe) {
                println(
                    "[hops] ${o.ability}" + (if (o.probe) " probe of ${HopEdges.describe(o.probeEdge)}" else "") +
                        " from ${o.from}" + (o.aim?.let { " at $it" } ?: " facing ${o.direction}") +
                        ": predicted ${o.predicted}" +
                        (if (o.predictedLearned != null && o.predictedLearned != o.predicted) " (learned ${o.predictedLearned})" else "") +
                        ", ${o.outcome} ${o.landed} -> $r",
                )
            }
        } catch (e: RuntimeException) {
            println("[hops] could not settle hop ${o.id}: $e")
        }
    }

    private val QUIET = setOf(HopExplainer.Kind.MATCH, HopExplainer.Kind.LEARNED_OK, HopExplainer.Kind.NO_CAST)

    /** Folds an explanation into evidence: into [into] when given (rebuilding the store), else into [HopKnowledge]. */
    fun applyResult(o: HopObservation, r: HopExplainer.Result, into: MutableMap<String, Evidence>?) {
        val day = HopStore.day(o.time)
        val build = o.build
        var probeFailed = false
        val manual = o.manual && r.strong
        when (r.kind) {
            HopExplainer.Kind.PASS -> {
                for (e in r.pass) pass(o, e, true, r.strong, day, build, into)
                probeFailed = o.probeEdge !in r.pass && HopExplainer.probeRefuted(o)
            }
            HopExplainer.Kind.LEARNED_OK -> {
                for (e in r.confirmed) pass(o, e, true, r.strong, day, build, into)
                probeFailed = o.probeEdge !in r.confirmed && HopExplainer.probeRefuted(o)
            }
            HopExplainer.Kind.LEARNED_FAILED -> for (e in r.failed) {
                val wasPass = e in o.learnedPass
                put(into, HopEdges.key(if (wasPass) HopEdges.PASS else HopEdges.STOP, e)) { ev ->
                    ev.failure(day, build)
                    if (manual) ev.manualFailure(day, build)
                }
                if (wasPass) types(o, e, false, false, day, build, into)
            }
            HopExplainer.Kind.STOP -> {
                for (e in r.stop) {
                    put(into, HopEdges.key(HopEdges.STOP, e)) { ev ->
                        ev.success(r.strong, day, build)
                        if (manual) ev.manualSuccess(day, build)
                    }
                }
                probeFailed = HopExplainer.probeRefuted(o)
            }
            HopExplainer.Kind.REFUSED -> {
                for (e in r.stop) put(into, HopEdges.key(HopEdges.STOP, e)) { ev -> ev.success(false, day, build) }
                probeFailed = HopExplainer.probeRefuted(o)
            }
            HopExplainer.Kind.MATCH -> probeFailed = HopExplainer.probeRefuted(o)
            else -> {}
        }
        if (probeFailed && o.probeEdge >= 0) {
            put(into, HopEdges.key(HopEdges.PASS, o.probeEdge)) { ev -> ev.failure(day, build) }
            types(o, o.probeEdge, false, false, day, build, into)
        }
        if (o.probe && o.probeEdge >= 0 && r.kind != HopExplainer.Kind.NO_CAST) {
            put(into, HopEdges.key(HopEdges.PASS, o.probeEdge)) { ev -> ev.pd = day }
        }
    }

    private fun pass(o: HopObservation, e: Long, success: Boolean, strong: Boolean, day: Long, build: String, into: MutableMap<String, Evidence>?) {
        put(into, HopEdges.key(HopEdges.PASS, e)) { ev ->
            ev.success(strong, day, build)
            if (o.manual && strong) ev.manualSuccess(day, build)
        }
        if (strong) types(o, e, success, true, day, build, into)
    }

    /** Type evidence for the locs that block [e], when the hop knew them. */
    private fun types(o: HopObservation, e: Long, success: Boolean, strong: Boolean, day: Long, build: String, into: MutableMap<String, Evidence>?) {
        val place = HopEdges.key(HopEdges.PASS, e)
        val blockers = o.blockers[place] ?: return
        for (b in blockers) {
            put(into, HopEdges.typeKey(b[0], b[1])) { ev ->
                if (success) ev.success(strong, day, build) else ev.failure(day, build)
                ev.place(place)
            }
        }
    }

    private fun put(into: MutableMap<String, Evidence>?, key: String, change: (Evidence) -> Unit) {
        if (into == null) {
            HopKnowledge.record(key, change)
            return
        }
        val ev = into.getOrPut(key) { Evidence() }
        change(ev)
        HopLearning.contributor()?.let(ev.c::add)
    }
}
