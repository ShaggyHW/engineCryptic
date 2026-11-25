package com.projectx.traversal.hops

import com.projectx.pathfinder.StepRule
import com.projectx.script.api.healthPercent
import com.projectx.script.api.inCombat
import world.gregs.voidps.type.Direction
import world.gregs.voidps.type.Tile
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Probes: hops the map says are blocked or short, cast anyway because one blocked edge, if a hop can cross it, would
 * make them worth it. Only on a learning client, only when the ability would otherwise sit idle, never through a door,
 * water or void, never in an instance, the Wilderness, a fight or at low health, and only where failing is harmless.
 *
 * Aggressive by design: any probe saving [PROBE_MIN_GAIN]+ steps qualifies, probes are only [MIN_GAP_MS] apart, and
 * an undecided edge is tried again after [EDGE_GAP_MS] until it is usable or refuted. A refuted edge is never probed
 * again; only a manual reset of the learned data brings it back. The give-up tallies live in [ScheduleStore].
 */
internal object HopExplorer {
    const val MIN_GAP_MS = 3_000L
    const val EDGE_GAP_MS = 10 * 60_000L
    const val MIN_EDGE_GAP_OVERRIDE_MS = 15_000L
    const val PROBE_MIN_GAIN = 3
    const val MAX_CANDIDATES = 40
    const val MIN_HEALTH_PCT = 60

    /** Stops for a surge probe of an edge whose way the player never faced, after which it is given up. */
    const val FACING_TRIES = 4

    /** Probes of an edge with no pass evidence, after which it is given up. */
    const val EDGE_TRIES = 4

    /** Surge probes from a tile with a read facing that went another way every time, after which they are given up. */
    const val MISFACE_TRIES = 2

    /** Probe stops on a tile nothing was cast from, after which it is given up. */
    const val STAND_TRIES = 3

    private const val INSTANCE_MIN_X = 6400

    data class Probe(
        val ability: String,
        val direction: Direction?,
        val aim: Tile?,
        val edge: Long,
        val landingIfOpen: Tile?,
        val landingIfBlocked: Tile?,
        val gain: Int,
    ) {
        override fun toString(): String =
            "$ability probe of ${HopEdges.describe(edge)}: lands $landingIfOpen if a hop crosses it (gain $gain), else $landingIfBlocked"
    }

    private val lastTried = ConcurrentHashMap<Long, Long>()
    private val probes = AtomicLong()

    @Volatile
    private var lastProbeAt = 0L

    @Volatile
    private var edgeGapOverrideMs = -1L

    /** Learning client, no probe for a while, and a safe place to fail. */
    fun allowedNow(server: Tile?): Boolean {
        if (System.currentTimeMillis() - lastProbeAt < MIN_GAP_MS) return false
        return allowedAt(server)
    }

    /** [allowedNow] without the gap since the last probe: a probe may go from [server] once the player gets there. */
    fun allowedAt(server: Tile?): Boolean {
        if (!HopLearning.enabled() || server == null) return false
        if (server.x >= INSTANCE_MIN_X || inWilderness(server)) return false
        return try {
            !inCombat && !(healthPercent < MIN_HEALTH_PCT)
        } catch (_: Throwable) {
            false
        }
    }

    fun inWilderness(t: Tile): Boolean = t.x in 2944..3391 && (t.y in 3520..4031 || t.y in 9920..10431)

    /** A surge probe from [server] facing [direction], or null. [gain] scores a landing; [Int.MIN_VALUE] is unknown. */
    fun surge(server: Tile, direction: Direction?, gain: (Tile) -> Int, minGain: Int): Probe? {
        if (direction == null || misfaced(server, direction) || surgeWayTested(server, direction)) return null
        val rule = HopKnowledge.rule()
        val refused = LinkedHashSet<Long>()
        val blocked = HopModel.surge(recording(rule, refused), server, direction)
        val blockedGain = if (blocked == server) 0 else gain(blocked)
        if (blockedGain == Int.MIN_VALUE || blockedGain < 0 || blockedGain >= minGain) return null
        var best: Probe? = null
        for ((tried, edge) in refused.withIndex()) {
            if (tried >= MAX_CANDIDATES) break
            if (!eligible(edge) || facingImpossible(edge, direction)) continue
            val open = HopModel.surge(with(rule, edge), server, direction)
            val g = gain(open)
            if (g >= minGain && (best == null || g > best.gain)) best = Probe(HopExplainer.SURGE, direction, null, edge, open, blocked, g)
        }
        return vet(best)
    }

    /** A dive probe toward a route tile ahead of [nearest], or null. */
    fun dive(server: Tile, route: List<Tile>, nearest: Int, maxAhead: Int, aimMax: Int, gain: (Tile) -> Int, minGain: Int): Probe? {
        val rule = HopKnowledge.rule()
        val last = minOf(route.size - 1, nearest + maxAhead)
        var budget = MAX_CANDIDATES * 3
        var i = last
        while (i > nearest && budget > 0) {
            val aim = route[i--]
            if (aim.plane != server.plane || server.getLongestDelta(aim) > aimMax) continue
            if (diveAimTested(server, aim)) continue
            val refused = LinkedHashSet<Long>()
            val blocked = HopModel.dive(recording(rule, refused), server, aim)
            if (blocked != null && gain(blocked) >= minGain) continue
            for (edge in refused) {
                if (budget-- <= 0) break
                if (!eligible(edge)) continue
                val open = HopModel.dive(with(rule, edge), server, aim) ?: continue
                val g = gain(open)
                if (g >= minGain) vet(Probe(HopExplainer.DIVE, null, aim, edge, open, blocked, g))?.let { return it }
            }
        }
        return null
    }

    /** A probe was cast: the rate limit and the edge's retry gap start now. Its outcome is noted when it settles. */
    fun noteProbe(edge: Long) {
        lastProbeAt = System.currentTimeMillis()
        lastTried[edge] = lastProbeAt
        probes.incrementAndGet()
    }

    fun probeCount(): Long = probes.get()

    /** Retry an undecided edge after [ms] (at least [MIN_EDGE_GAP_OVERRIDE_MS]) instead of [EDGE_GAP_MS]; negative restores it. */
    fun setEdgeGapOverride(ms: Long) {
        edgeGapOverrideMs = if (ms < 0) -1 else maxOf(MIN_EDGE_GAP_OVERRIDE_MS, ms)
    }

    fun edgeGapMs(): Long = edgeGapOverrideMs.takeIf { it >= 0 } ?: EDGE_GAP_MS

    /** Weight = stops for the surge probe, sum = stops facing [direction]. */
    fun facingKey(edge: Long, direction: Direction): String = "face:${HopEdges.describe(edge)}:${HopEdges.name(direction)}"

    fun facingImpossible(edge: Long, direction: Direction): Boolean = givenUp(facingKey(edge, direction), FACING_TRIES)

    fun noteSurgeFacing(edge: Long, direction: Direction, faced: Boolean) =
        tally(facingKey(edge, direction), faced, FACING_TRIES) {
            "surge across ${HopEdges.describe(edge)} facing ${HopEdges.name(direction)} given up: the player never faced it in $FACING_TRIES stops for it"
        }

    /** Weight = probes of the edge, sum = probes that showed it passable. */
    fun triesKey(edge: Long): String = "ptry:${HopEdges.describe(edge)}"

    fun triesSpent(edge: Long): Boolean = givenUp(triesKey(edge), EDGE_TRIES)

    fun noteProbeTry(edge: Long, passed: Boolean) =
        tally(triesKey(edge), passed, EDGE_TRIES) { "probes of ${HopEdges.describe(edge)} given up: no hop crossed it in $EDGE_TRIES tries" }

    /** A dive probe is cast standing still, so its tile and aim decide where it goes: cast again, it goes the same way. */
    fun diveAimKey(from: Tile, aim: Tile): String = "daim:${from.x}:${from.y}:${from.plane}:${aim.x}:${aim.y}"

    fun diveAimTested(from: Tile, aim: Tile): Boolean = givenUp(diveAimKey(from, aim), 1)

    fun noteDiveAim(from: Tile, aim: Tile, passed: Boolean) = ScheduleStore.add(diveAimKey(from, aim), if (passed) 1 else 0)

    /** The same for a surge probe: its tile and the way it went decide where it lands; other ways from the tile stay open. */
    fun surgeWayKey(from: Tile, direction: Direction): String = "sway:${from.x}:${from.y}:${from.plane}:${HopEdges.name(direction)}"

    fun surgeWayTested(from: Tile, direction: Direction): Boolean = givenUp(surgeWayKey(from, direction), 1)

    fun noteSurgeWayTested(from: Tile, direction: Direction, passed: Boolean) =
        ScheduleStore.add(surgeWayKey(from, direction), if (passed) 1 else 0)

    /** Weight = surge probes from the tile with this read facing, sum = probes that went that way. */
    fun misfaceKey(t: Tile, direction: Direction): String = "misface:${t.x}:${t.y}:${t.plane}:${HopEdges.name(direction)}"

    fun misfaced(t: Tile, direction: Direction): Boolean = givenUp(misfaceKey(t, direction), MISFACE_TRIES)

    fun noteSurgeWay(t: Tile, direction: Direction, wentThatWay: Boolean) =
        tally(misfaceKey(t, direction), wentThatWay, MISFACE_TRIES) {
            "surge probes from $t facing ${HopEdges.name(direction)} given up: the surge went another way $MISFACE_TRIES times"
        }

    /** Weight = probe stops on the tile, sum = stops that cast a probe. */
    fun standKey(t: Tile): String = "stand:${t.x}:${t.y}:${t.plane}"

    fun standGivenUp(t: Tile): Boolean = givenUp(standKey(t), STAND_TRIES)

    fun noteStand(t: Tile, cast: Boolean) =
        tally(standKey(t), cast, STAND_TRIES) { "probe stand $t given up: no probe cast from it in $STAND_TRIES stops" }

    private fun givenUp(key: String, tries: Int): Boolean {
        val s = ScheduleStore.get(key)
        return s.w >= tries && s.s1 <= 0
    }

    private fun tally(key: String, hit: Boolean, tries: Int, gaveUp: () -> String) {
        val was = givenUp(key, tries)
        ScheduleStore.add(key, if (hit) 1 else 0)
        if (!was && givenUp(key, tries)) println("[hops] ${gaveUp()}")
    }

    /** Cheap checks: both tiles on the live map, no floor block, not decided or given up, not tried within the gap. */
    fun eligible(edge: Long): Boolean {
        val tried = lastTried[edge]
        if (tried != null && System.currentTimeMillis() - tried < edgeGapMs()) return false
        val map = HopMap.current
        val x = HopEdges.x(edge)
        val y = HopEdges.y(edge)
        val p = HopEdges.plane(edge)
        val tx = HopEdges.toX(edge)
        val ty = HopEdges.toY(edge)
        if (!map.contains(x, y, p) || !map.contains(tx, ty, p)) return false
        if ((map.flags(x, y, p) or map.flags(tx, ty, p)) and WalkFlags.BLOCK_FLOOR != 0) return false
        if (triesSpent(edge)) return false
        val evidence = HopKnowledge.get(HopEdges.key(HopEdges.PASS, edge)) ?: return true
        return !evidence.hasManual() && !evidence.usableEdge() && evidence.f == 0
    }

    /** The edge's blockers are known and none of them is a door. */
    private fun vet(probe: Probe?): Probe? {
        if (probe == null) return null
        val blockers = HopBlockers.resolve(probe.edge)
        if (blockers.isNullOrEmpty() || HopBlockers.isDoor(blockers)) return null
        return probe
    }

    private fun recording(rule: StepRule, refused: MutableSet<Long>): StepRule = StepRule { x, y, p, d ->
        val ok = rule.canStep(x, y, p, d)
        if (!ok && HopMap.current.contains(x + d.delta.x, y + d.delta.y, p)) refused += HopEdges.pack(x, y, p, d)
        ok
    }

    private fun with(rule: StepRule, open: Long): StepRule =
        StepRule { x, y, p, d -> rule.canStep(x, y, p, d) || HopEdges.pack(x, y, p, d) == open }

    fun resetForTests() {
        lastProbeAt = 0
        lastTried.clear()
        edgeGapOverrideMs = -1
    }
}
