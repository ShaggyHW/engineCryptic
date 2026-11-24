package com.projectx.traversal.nodes

import com.projectx.traversal.hops.HopModel
import com.projectx.traversal.local.LocalNav
import com.projectx.traversal.local.ServerWalk
import world.gregs.voidps.type.Tile
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * When to spend Surge and Dive on a walk. [scanDive] / [scanSurge] find the hop spots along the route ahead: the best
 * gain from each route tile toward the end of the walk ([RouteProgress]) and the route index it lands near. [decide]
 * compares casting the ready ability now with keeping it for a spot ahead, the cooldown turned into running steps
 * ([cycleSteps]):
 *
 *   V[i] = max(V[i+1], gain[i] + V[land[i] + cycle])
 *
 * Past the horizon (the route off the live map) a ready ability is worth an ordinary hop per cycle ([tail]). Every
 * Surge charge is scheduled on its own, as the same ability.
 */
internal object HopSchedule {
    const val HOLD_MARGIN = 4

    /** A hold into the next walk, past an interaction, costs no walking: it needs to beat casting now by less. */
    const val HOLD_MARGIN_BEYOND = 2

    /** An ordinary straight hop: what a ready ability is worth per cycle past the horizon, before anything is learned. */
    const val BASE_GAIN = 10

    const val PROPERTY_ENABLED = "projectx.hopSchedule"
    const val ENV_ENABLED = "RS3_HOP_SCHEDULE"

    /** Off for the whole client with the property `false` or the variable `0`: every hop then goes on the first-good-enough rule. */
    val ENABLED: Boolean = !"false".equals(System.getProperty(PROPERTY_ENABLED), ignoreCase = true) && System.getenv(ENV_ENABLED) != "0"

    /** The ordinary hop's gain and the two hold margins a decision is made with: learned ([ScheduleLearning]) or [PRIORS]. */
    data class Params(val base: Int, val marginWalk: Int, val marginBeyond: Int)

    val PRIORS = Params(BASE_GAIN, HOLD_MARGIN, HOLD_MARGIN_BEYOND)

    /**
     * Hop spots from the player's route index: index 0 is the player's, filled by the caller. Past [head] (the walk
     * being walked) come the legs after it ([concat]): [beyond] holds each index's tile there (null while an
     * interaction keeps hops off) and [beyondWhat] the interaction it comes after. [raw] is each tile's own gain before
     * [runTicks] valued it as a running player gets it.
     */
    class Spots(
        val gain: IntArray,
        val land: IntArray,
        val tailEnd: Int,
        val head: Int = gain.size,
        val beyond: Array<Tile?>? = null,
        val beyondWhat: Array<String?>? = null,
        val raw: IntArray = gain.clone(),
    ) {
        val horizon: Int get() = gain.size

        fun isBeyond(k: Int): Boolean = k >= head
    }

    /** A walk past an interaction: [dead] steps no hop goes in, then [walk]'s spots over [tiles]. */
    class Seg(val what: String, val dead: Int, val walk: Spots, val tiles: List<Tile>)

    /** Ordinary hops along a walk of [len] tiles the live map cannot show: [base] from every tile, less toward its end. */
    fun uniform(len: Int, base: Int = BASE_GAIN): Spots {
        val gain = IntArray(len)
        val land = IntArray(len)
        for (k in 0 until len) {
            val g = min(base, len - 1 - k)
            gain[k] = if (g >= HopRules.DIVE_MIN_GAIN) g else 0
            land[k] = k + gain[k]
        }
        return Spots(gain, land, len - 1)
    }

    /** [head] and the legs after it as one stretch: each leg's interaction is [Seg.dead] indices with no spot, then its walk. */
    fun concat(head: Spots, segs: List<Seg>): Spots {
        val n = head.horizon + segs.sumOf { it.dead + it.walk.horizon }
        val gain = IntArray(n)
        val land = IntArray(n)
        val raw = IntArray(n)
        val beyond = arrayOfNulls<Tile>(n)
        val what = arrayOfNulls<String>(n)
        head.gain.copyInto(gain, 0, 0, head.horizon)
        head.land.copyInto(land, 0, 0, head.horizon)
        head.raw.copyInto(raw, 0, 0, head.horizon)
        var off = head.horizon
        for (seg in segs) {
            repeat(seg.dead) {
                land[off] = off
                what[off] = seg.what
                off++
            }
            val walk = seg.walk
            for (k in 0 until walk.horizon) {
                gain[off + k] = walk.gain[k]
                raw[off + k] = walk.raw[k]
                land[off + k] = off + walk.land[k]
                beyond[off + k] = seg.tiles.getOrNull(k)
                what[off + k] = seg.what
            }
            off += walk.horizon
        }
        return Spots(gain, land, n - 1, head.horizon, beyond, what, raw)
    }

    /**
     * A spot as a running player gets it. The server tile moves [ServerWalk.TICK_STEPS] a tick and a hop fires from a
     * tick's tile, so half the time the run steps over spot k and the hop goes from k-1 or k+1: spot k is worth the
     * lesser of its own gain and its better neighbour's, landing where that hop lands. A one-tile peak is no spot.
     * Index 0 is the caller's and no neighbour.
     */
    fun runTicks(raw: IntArray, land: IntArray, tailEnd: Int): Spots {
        val n = raw.size
        val gain = IntArray(n)
        val at = IntArray(n)
        for (k in 1 until n) {
            val j = if (k + 1 < n && (k - 1 < 1 || raw[k + 1] >= raw[k - 1])) k + 1 else k - 1
            if (j < 1 || raw[k] <= raw[j]) {
                gain[k] = raw[k]
                at[k] = land[k]
            } else {
                gain[k] = raw[j]
                at[k] = if (raw[j] > 0) land[j] else k
            }
        }
        return Spots(gain, at, tailEnd, n, null, null, raw)
    }

    /** [cast]: go now; else [keptAt] (relative route index) is the spot it is kept for. [detail] is for the logs. */
    data class Decision(val cast: Boolean, val keptAt: Int, val keptGain: Int, val castValue: Double, val holdValue: Double, val detail: String)

    /** Running steps from a cast until the ability is used again: its cooldown and the reaction. */
    fun cycleSteps(cooldownMs: Long): Int {
        if (cooldownMs <= 0) return 0
        val steps = (cooldownMs + HopRules.READY_DELAY_MEAN_MS) / 1000.0 * HopRules.TILES_PER_SECOND
        return max(1, Math.round(steps).toInt())
    }

    /** What a ready ability at index [k] past the horizon is still worth: an ordinary hop per cycle and hop up to [tailEnd]. */
    fun tail(k: Int, tailEnd: Int, cycle: Int, base: Int = BASE_GAIN): Double {
        val left = tailEnd - k
        return if (left <= 0 || base <= 0) 0.0 else base * left.toDouble() / (cycle + base)
    }

    /** The backward pass: V[k] for k in [0, horizon]; V[horizon] is the tail there. */
    fun values(s: Spots, cycle: Int, base: Int = BASE_GAIN): DoubleArray {
        val n = s.horizon
        val v = DoubleArray(n + 1)
        v[n] = tail(n, s.tailEnd, cycle, base)
        for (k in n - 1 downTo 0) {
            val skip = v[k + 1]
            v[k] = if (s.gain[k] > 0) max(skip, s.gain[k] + value(v, s, next(s, k, cycle), cycle, base)) else skip
        }
        return v
    }

    private fun next(s: Spots, k: Int, cycle: Int): Int = max(k + 1, s.land[k]) + cycle

    private fun value(v: DoubleArray, s: Spots, k: Int, cycle: Int, base: Int): Double =
        if (k < s.horizon) v[k] else tail(k, s.tailEnd, cycle, base)

    /**
     * Cast now (the hop at index 0) or keep the ability for the first spot the best plan from index 1 hops at. Cast
     * when nothing inside the horizon beats it by the margin, or [cycle] is unknown; never kept for the tail alone.
     */
    fun decide(s: Spots, cycle: Int, p: Params = PRIORS): Decision {
        val g = s.gain[0]
        if (cycle <= 0 || s.horizon < 2) return Decision(true, -1, 0, g.toDouble(), 0.0, "no schedule")
        val v = values(s, cycle, p.base)
        val back = next(s, 0, cycle)
        val now = g + value(v, s, back, cycle, p.base)
        val hold = v[1]
        val first = firstHop(v, s, cycle, p.base)
        val margin = if (first >= 0 && s.isBeyond(first)) p.marginBeyond else p.marginWalk
        val kept = if (now >= hold - margin) -1 else first
        val detail = StringBuilder("cycle ").append(cycle).append(", horizon ").append(s.horizon)
            .append(", tail to +").append(s.tailEnd).append("; now +").append(g).append(" lands +").append(s.land[0])
            .append(", back at +").append(back).append(" worth ").append(HopRules.fmt(value(v, s, back, cycle, p.base)))
            .append("; margin ").append(margin).append(", ordinary hop +").append(p.base)
        if (kept > 0) {
            val keptBack = next(s, kept, cycle)
            detail.append("; at +").append(kept).append(" +").append(s.gain[kept]).append(" lands +").append(s.land[kept])
                .append(", back at +").append(keptBack).append(" worth ").append(HopRules.fmt(value(v, s, keptBack, cycle, p.base)))
        }
        if (kept < 0) return Decision(true, -1, 0, now, hold, detail.toString())
        return Decision(false, kept, s.gain[kept], now, hold, detail.toString())
    }

    /** The first index from 1 where the best plan hops, or -1 when it does not inside the horizon. */
    fun firstHop(v: DoubleArray, s: Spots, cycle: Int, base: Int = BASE_GAIN): Int {
        for (k in 1 until s.horizon) {
            if (s.gain[k] <= 0) continue
            if (s.gain[k] + value(v, s, next(s, k, cycle), cycle, base) >= v[k + 1]) return k
        }
        return -1
    }

    // ---- the other ability back soon ----

    /** The other ability back within this long: order the two hops instead of scheduling each alone. */
    const val PAIR_WAIT_MS = 3_000L

    /** The other ability castable but not cast: the pair still waits this long (a tick) for it. */
    const val PAIR_READY_GRACE_MS = 600L

    /** The other order must save this many steps more: the wait is short and sure. */
    const val PAIR_MARGIN = 2

    /** Steps the player walks on from a hop's landing before the walker sees it land (a tick's run). */
    const val LANDING_WALK = 2

    /**
     * [hold]: the ready hop waits for the other ability, which goes first and the ready one after it from its landing,
     * when that saves [PAIR_MARGIN] steps more than this hop now and the other one when it is back.
     */
    data class Pair(
        val hold: Boolean,
        val nowThenOther: Int,
        val otherThenNow: Int,
        val otherAt: Int,
        val otherGain: Int,
        val afterAt: Int,
        val afterGain: Int,
    )

    /** The ready hop [x] (gaining [gainNow], landing near [landNow]) against the other ability [y], back in [ySteps] running steps. */
    fun pair(x: Spots, y: Spots, gainNow: Int, landNow: Int, ySteps: Int): Pair {
        val ka = max(landNow, landNow + ySteps - LANDING_WALK)
        val a = gainNow + best(y, ka)
        val kb = max(1, ySteps)
        val yAt = bestAt(y, kb)
        val yGain = if (yAt < 0) 0 else y.gain[yAt]
        val after = if (yAt < 0) -1 else bestAt(x, max(yAt + 1, y.land[yAt]))
        val xGain = if (after < 0) 0 else x.gain[after]
        val b = yGain + xGain
        return Pair(yGain > 0 && b >= a + PAIR_MARGIN, a, b, yAt, yGain, after, xGain)
    }

    private fun best(s: Spots, k: Int): Int {
        val i = bestAt(s, k)
        return if (i < 0) 0 else s.gain[i]
    }

    /** Index of the best gain in [s] from [k] over the next [LANDING_WALK] + 1 tiles, -1 when none. */
    private fun bestAt(s: Spots, k: Int): Int {
        var at = -1
        var i = max(1, k)
        while (i <= k + LANDING_WALK && i < s.horizon) {
            if (s.gain[i] > 0 && (at < 0 || s.gain[i] > s.gain[at])) at = i
            i++
        }
        return at
    }

    // ---- the scan ----

    /**
     * Dive spots: from each route tile in (nearest, nearest + horizon) the best landing over the aims the walker would
     * try, counted toward [p]; 0 under [HopRules.diveNeed]; valued as a run gets it. Index 0 is left for the caller.
     */
    fun scanDive(route: List<Tile>, nearest: Int, horizon: Int, p: RouteProgress, tailEnd: Int): Spots =
        scan(route, nearest, horizon, p, tailEnd, ::diveFrom)

    /** Surge spots: from each route tile where the route runs straight on through it, the surge along that way. */
    fun scanSurge(route: List<Tile>, nearest: Int, horizon: Int, p: RouteProgress, tailEnd: Int): Spots =
        scan(route, nearest, horizon, p, tailEnd, ::surgeFrom)

    private fun scan(
        route: List<Tile>,
        nearest: Int,
        horizon: Int,
        p: RouteProgress,
        tailEnd: Int,
        hopFrom: (List<Tile>, Int, RouteProgress) -> Tile?,
    ): Spots {
        val gain = IntArray(horizon)
        val land = IntArray(horizon)
        for (k in 1 until horizon) {
            val i = nearest + k
            land[k] = k
            val landing = hopFrom(route, i, p) ?: continue
            gain[k] = p.gain(route[i], landing)
            land[k] = landIndex(route, i, landing, p) - nearest
        }
        return runTicks(gain, land, tailEnd)
    }

    /** The landing of the dive [scanDive] takes from route[i]; null when none gains its need. */
    fun diveFrom(route: List<Tile>, i: Int, p: RouteProgress): Tile? {
        val from = route[i]
        if (!p.counts(from)) return null
        val landing = diveToward(route, i, from, p) ?: return null
        return if (p.gain(from, landing) >= HopRules.diveNeed(route, i, landing)) landing else null
    }

    /** The best dive landing from [from] (on or beside route[i]) over the route tiles after i in aim reach; null when none gains. */
    fun diveToward(route: List<Tile>, i: Int, from: Tile, p: RouteProgress): Tile? {
        var best = 0
        var bestLanding: Tile? = null
        for (j in min(route.size - 1, i + HopRules.DIVE_SHORTCUT_AHEAD) downTo i + 1) {
            val aim = route[j]
            if (aim.plane != from.plane || from.getLongestDelta(aim) > HopRules.DIVE_AIM_MAX) continue
            val landing = MovePrediction.diveLanding(from, aim) ?: continue
            val g = p.gain(from, landing)
            if (g > best) {
                best = g
                bestLanding = landing
            }
        }
        return bestLanding
    }

    /** The landing of the surge [scanSurge] takes from route[i] (the step onto it and the next two agree), or null. */
    fun surgeFrom(route: List<Tile>, i: Int, p: RouteProgress): Tile? {
        val d = HopRules.routeStep(route, i) ?: return null
        if (HopRules.routeStep(route, i + 1) != d || HopRules.routeStep(route, i + 2) != d) return null
        val from = route[i]
        if (!p.counts(from)) return null
        val landing = MovePrediction.surgeLanding(from, d)
        return if (p.gain(from, landing) >= HopRules.surgeNeed(route, i, from, landing)) landing else null
    }

    // ---- both ready: a same-tick combo as one move ----

    /** Same-tick combo spots: both hops together from route index k, gaining [gain] and landing near [land], in [diveFirst]'s order. */
    class Combos(val gain: IntArray, val land: IntArray, val diveFirst: BooleanArray) {
        val horizon: Int get() = gain.size
    }

    /** The better combo from one route tile; [diveTo] is the dive's tile when it goes first. */
    data class ComboAt(val gain: Int, val land: Int, val diveFirst: Boolean, val diveTo: Tile? = null)

    /** Dive range: a dive aimed this far or nearer lands on its aim when nothing is in the way. */
    const val DIVE_REACH = 10

    /**
     * Off-route dives tried for a combo go at least this far: the player runs on while a dive arms, so a short one
     * lands where the run takes the player anyway or never fires.
     */
    const val COMBO_DIVE_MIN_TILES = HopRules.DIVE_MIN_GAIN

    /** A same-tick combo is worth the tick the second hop would otherwise wait, in running steps. */
    const val COMBO_TICK_STEPS = ServerWalk.TICK_STEPS

    /** Planned route indices at most (the rest is valued by [tail]): bounds the joint table. */
    const val JOINT_MAX_HORIZON = 160

    /** [Combos] along the route: Surge -> Dive and Dive -> Surge from each tile, the better one, valued as a run gets it. */
    fun scanCombos(route: List<Tile>, nearest: Int, horizon: Int, p: RouteProgress, tailEnd: Int): Combos {
        val at = arrayOfNulls<ComboAt>(max(0, min(horizon, route.size - nearest)))
        for (k in at.indices) at[k] = comboAt(route, nearest + k, p)
        return combos(at, horizon, nearest, tailEnd)
    }

    /**
     * The better combo from route[i], or from [here] (the player's own tile) when given: from there Dive -> Surge also
     * tries every dive that lands where it is aimed, on the route or off it, and keeps the one whose surge (the way the
     * dive leaves the player facing) ends furthest on. Null when neither order goes.
     */
    fun comboAt(route: List<Tile>, i: Int, p: RouteProgress, here: Tile? = null): ComboAt? {
        if (i >= route.size) return null
        val from = here ?: route[i]
        if (!p.counts(from)) return null
        var best = 0
        var end: Tile? = null
        var diveTo: Tile? = null
        var diveFirst = false
        val firstSurge = surgeFrom(route, i, p)
        if (firstSurge != null) {
            val at = landIndex(route, i, firstSurge, p)
            val secondDive = diveToward(route, at, firstSurge, p)
            if (secondDive != null && p.gain(firstSurge, secondDive) >= HopRules.DIVE_MIN_GAIN) {
                best = p.gain(from, secondDive)
                end = secondDive
            }
        }
        val firstDive = diveFrom(route, i, p)
        if (firstDive != null) {
            val d = HopModel.facing(firstDive.x - from.x, firstDive.y - from.y)
            if (d != null) {
                val secondSurge = MovePrediction.surgeLanding(firstDive, d)
                val at = landIndex(route, i, firstDive, p)
                if (p.gain(firstDive, secondSurge) >= HopRules.surgeNeed(route, at, firstDive, secondSurge) && p.gain(from, secondSurge) > best) {
                    best = p.gain(from, secondSurge)
                    end = secondSurge
                    diveFirst = true
                    diveTo = firstDive
                }
            }
        }
        if (here != null) {
            for (dx in -DIVE_REACH..DIVE_REACH) {
                for (dy in -DIVE_REACH..DIVE_REACH) {
                    if (max(abs(dx), abs(dy)) < COMBO_DIVE_MIN_TILES) continue
                    val aim = Tile.of(from.x + dx, from.y + dy, from.plane)
                    val land = MovePrediction.diveLanding(from, aim)
                    if (land == null || land != aim || !p.counts(land)) continue
                    val d = HopModel.facing(dx, dy) ?: continue
                    val secondSurge = MovePrediction.surgeLanding(land, d)
                    val g = p.gain(from, secondSurge)
                    if (g <= best) continue
                    val at = landIndex(route, i, land, p)
                    if (p.gain(land, secondSurge) < HopRules.surgeNeed(route, at, land, secondSurge)) continue
                    best = g
                    end = secondSurge
                    diveFirst = true
                    diveTo = land
                }
            }
        }
        if (end == null) return null
        return ComboAt(best, landIndex(route, i, end, p), diveFirst, if (diveFirst) diveTo else null)
    }

    /** [Combos] over [horizon] indices from [nearest], at[k] for route index nearest + k, valued as a run gets them but index 0 as it is. */
    fun combos(at: Array<ComboAt?>, horizon: Int, nearest: Int, tailEnd: Int): Combos {
        val raw = IntArray(horizon)
        val land = IntArray(horizon)
        val diveFirst = BooleanArray(horizon)
        for (k in 0 until horizon) {
            land[k] = k
            val c = at.getOrNull(k) ?: continue
            raw[k] = c.gain
            land[k] = c.land - nearest
            diveFirst[k] = c.diveFirst
        }
        val run = runTicks(raw.clone(), land, tailEnd)
        val gain = run.gain
        val lands = run.land
        gain[0] = raw[0]
        lands[0] = land[0]
        // A spot valued from its neighbour takes that neighbour's order too.
        for (k in 1 until horizon) {
            if (raw[k] > 0 || gain[k] <= 0) continue
            val j = if (k + 1 < horizon && raw[k + 1] > 0) k + 1 else k - 1
            diveFirst[k] = diveFirst[j]
        }
        return Combos(gain, lands, diveFirst)
    }

    /**
     * Both abilities ready: [Kind.SPLIT], each scheduled on its own ([decide]); [Kind.COMBO_NOW], the combo from here
     * is worth more than that; [Kind.COMBO_AHEAD], both kept for the combo at [at], which beats the best of casting
     * now by the hold margin.
     */
    data class Joint(
        val kind: Kind,
        val at: Int,
        val gain: Int,
        val diveFirst: Boolean,
        val nowValue: Double,
        val holdValue: Double,
        val detail: String,
    ) {
        enum class Kind { SPLIT, COMBO_NOW, COMBO_AHEAD }
    }

    /**
     * The joint plan for two ready abilities, played out in order along the route: V(k, ws, wd) with Surge back in ws
     * running steps and Dive in wd is the best of walking on, a surge or a dive when back, or the combo when both are.
     * A hop skips every spot it jumps past, so two abilities are never counted as independent. [margin] is the combo
     * hold margin ([ScheduleLearning.comboMargin]).
     */
    fun joint(
        surge: Spots,
        dive: Spots,
        c: Combos,
        cycleS: Int,
        cycleD: Int,
        ps: Params,
        pd: Params,
        margin: Int = max(ps.marginWalk, pd.marginWalk),
    ): Joint {
        val n = min(JOINT_MAX_HORIZON, min(c.horizon, min(surge.horizon, dive.horizon)))
        if (cycleS <= 0 || cycleD <= 0 || n < 2) return Joint(Joint.Kind.SPLIT, -1, 0, false, 0.0, 0.0, "no schedule")
        val t = JointTable(surge, dive, c, n, cycleS, cycleD, ps.base, pd.base)
        t.fill()
        val surgeNow = if (surge.gain[0] > 0) t.surgeAt(0, 0) else Double.NEGATIVE_INFINITY
        val diveNow = if (dive.gain[0] > 0) t.diveAt(0, 0) else Double.NEGATIVE_INFINITY
        val comboNow = if (c.gain[0] > 0) t.comboAt(0) else Double.NEGATIVE_INFINITY
        val now = max(comboNow, max(surgeNow, diveNow))
        val hold = t.v(1, 0, 0)
        val ahead = t.firstCombo(1)
        val nowWhat = when {
            now == Double.NEGATIVE_INFINITY -> "nothing"
            now == comboNow -> HopRules.fmt(now) + " (combo +" + c.gain[0] + " (" + order(c.diveFirst[0]) + "))"
            now == surgeNow -> HopRules.fmt(now) + " (surge +" + surge.gain[0] + ")"
            else -> HopRules.fmt(now) + " (dive +" + dive.gain[0] + ")"
        }
        val detail = "both ready: now " + nowWhat + " [combo " + fmtv(comboNow) + ", surge " + fmtv(surgeNow) +
            ", dive " + fmtv(diveNow) + "], best from +1 " + HopRules.fmt(hold) +
            (if (ahead > 0) " by a combo at +" + ahead + " +" + c.gain[ahead] + " (" + order(c.diveFirst[ahead]) + ")" else "") +
            "; margin " + margin + ", cycles " + cycleS + "/" + cycleD
        if (ahead > 0 && hold > now + margin) {
            return Joint(Joint.Kind.COMBO_AHEAD, ahead, c.gain[ahead], c.diveFirst[ahead], now, hold, detail)
        }
        if (comboNow > Double.NEGATIVE_INFINITY && comboNow >= max(surgeNow, diveNow) && comboNow >= hold) {
            return Joint(Joint.Kind.COMBO_NOW, 0, c.gain[0], c.diveFirst[0], now, hold, detail)
        }
        return Joint(Joint.Kind.SPLIT, -1, 0, false, now, hold, detail)
    }

    private fun order(diveFirst: Boolean) = if (diveFirst) "dive->surge" else "surge->dive"

    private fun fmtv(x: Double): String = if (x == Double.NEGATIVE_INFINITY) "-" else HopRules.fmt(x)

    /** [joint]'s table: V over (index, surge wait, dive wait), filled backwards. */
    private class JointTable(
        private val s: Spots,
        private val d: Spots,
        private val c: Combos,
        private val n: Int,
        private val cs: Int,
        private val cd: Int,
        private val bs: Int,
        private val bd: Int,
    ) {
        private val v = DoubleArray(n * (cs + 1) * (cd + 1))
        private val act = ByteArray(v.size)

        private fun idx(k: Int, ws: Int, wd: Int) = (k * (cs + 1) + ws) * (cd + 1) + wd

        /** V(k, ws, wd); past the horizon each ability's tail from when it is back. */
        fun v(k: Int, ws: Int, wd: Int): Double {
            if (k >= n) return tail(k + ws, s.tailEnd, cs, bs) + tail(k + wd, d.tailEnd, cd, bd)
            return v[idx(k, min(ws, cs), min(wd, cd))]
        }

        /** A hop cannot gain more than the stretch has left: steps past its end are not saved. */
        private fun capped(gain: Int, k: Int, tailEnd: Int) = min(gain, max(0, tailEnd - k))

        fun surgeAt(k: Int, wd: Int): Double {
            val l = max(k + 1, s.land[k])
            return capped(s.gain[k], k, s.tailEnd) + v(l, cs, max(0, wd - ServerWalk.TICK_STEPS))
        }

        fun diveAt(k: Int, ws: Int): Double {
            val l = max(k + 1, d.land[k])
            return capped(d.gain[k], k, d.tailEnd) + v(l, max(0, ws - ServerWalk.TICK_STEPS), cd)
        }

        fun comboAt(k: Int): Double {
            val l = max(k + 1, c.land[k])
            return capped(c.gain[k], k, min(s.tailEnd, d.tailEnd)) + COMBO_TICK_STEPS + v(l, cs, cd)
        }

        fun fill() {
            for (k in n - 1 downTo 0) {
                for (ws in 0..cs) {
                    for (wd in 0..cd) {
                        var best = v(k + 1, max(0, ws - 1), max(0, wd - 1))
                        var a = WALK
                        if (k > 0 && ws == 0 && s.gain[k] > 0) {
                            val x = surgeAt(k, wd)
                            if (x > best) {
                                best = x
                                a = SURGE
                            }
                        }
                        if (k > 0 && wd == 0 && d.gain[k] > 0) {
                            val x = diveAt(k, ws)
                            if (x > best) {
                                best = x
                                a = DIVE
                            }
                        }
                        if (k > 0 && ws == 0 && wd == 0 && c.gain[k] > 0) {
                            val x = comboAt(k)
                            if (x > best) {
                                best = x
                                a = COMBO
                            }
                        }
                        val i = idx(k, ws, wd)
                        v[i] = best
                        act[i] = a
                    }
                }
            }
        }

        /** The index from [from] where the best plan (both back) first acts, when that act is a combo; else -1. */
        fun firstCombo(from: Int): Int {
            for (k in from until n) {
                val a = act[idx(k, 0, 0)]
                if (a == WALK) continue
                return if (a == COMBO) k else -1
            }
            return -1
        }

        private companion object {
            const val WALK: Byte = 0
            const val SURGE: Byte = 1
            const val DIVE: Byte = 2
            const val COMBO: Byte = 3
        }
    }

    /** A copy of [s] whose arrays the caller may change: spots are cached and shared. */
    fun copy(s: Spots): Spots = Spots(s.gain.clone(), s.land.clone(), s.tailEnd, s.head, s.beyond, s.beyondWhat, s.raw.clone())

    /**
     * Where the walk goes on after a hop from route[from] lands on [landing] (at least [from]): the first route tile
     * no further from the root than the landing. With no root, the nearest route tile.
     */
    fun landIndex(route: List<Tile>, from: Int, landing: Tile, p: RouteProgress): Int {
        val field = p.field
        val d = field?.distanceTo(landing) ?: -1
        if (field != null && d >= 0) {
            for (i in from until min(route.size, from + HopRules.ROOT_REACH)) {
                val di = RouteProgress.stepsOff(field, route[i])
                if (di in 0..d) return i
            }
        }
        return max(from, HopRules.routeIndexNear(route, from, landing))
    }

    // ---- past the walk ----

    /** Running steps an interaction of [deadMs] keeps hops off. */
    fun deadSteps(deadMs: Long): Int = Math.round(deadMs / 1000.0 * HopRules.TILES_PER_SECOND).toInt()

    /** The legs after a walk ([Interactions.after]) as stretches to [concat], each walk's spots from [spotsOf]. */
    fun segs(legs: List<Interactions.Leg>, spotsOf: (List<Tile>) -> Spots): List<Seg> =
        legs.map { Seg(it.what, deadSteps(it.deadMs), spotsOf(it.tiles), it.tiles) }

    /**
     * Hop spots along a walk past an interaction: scanned toward its own last tile when the live map holds it, else
     * ordinary hops of [base]. Its first tile is an ordinary dive and no surge: the interaction leaves the player with
     * no step to face by.
     */
    fun legSpots(surge: Boolean, tiles: List<Tile>, base: Int = ScheduleLearning.baseGain(surge)): Spots {
        val n = tiles.size
        if (n == 0) return uniform(0, base)
        val p = if (LocalNav.trusted(tiles[0]) && LocalNav.trusted(tiles[n - 1])) RouteProgress.of(tiles, 0, tiles[0]) else null
        val ordinary = uniform(n, base)
        val walk = when {
            p == null || p.field == null || !p.end -> ordinary
            surge -> scanSurge(tiles, 0, n, p, n - 1)
            else -> scanDive(tiles, 0, n, p, n - 1)
        }
        walk.gain[0] = if (surge) 0 else ordinary.gain[0]
        walk.land[0] = if (surge) 0 else ordinary.land[0]
        walk.raw[0] = walk.gain[0]
        return walk
    }

    /** The tile of spot [k] planned from route index [at]: on this route, or on a later walk; the route's last tile when neither. */
    fun spotTile(route: List<Tile>, at: Int, s: Spots, k: Int): Tile {
        if (k >= 0 && !s.isBeyond(k) && at + k < route.size) return route[at + k]
        val beyond = s.beyond
        if (beyond != null && k >= 0 && k < beyond.size) beyond[k]?.let { return it }
        return route.last()
    }
}
