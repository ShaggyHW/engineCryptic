package com.projectx.traversal.nodes

import com.projectx.script.api.inventory
import com.projectx.traversal.hops.HopModel
import com.projectx.traversal.local.LocalNav
import com.projectx.traversal.local.ServerWalk
import org.projectx.core.game.combat.Effect
import world.gregs.voidps.type.Direction
import world.gregs.voidps.type.Tile
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * When to drink a Powerburst of acceleration on a walk: Surge and Dive cooldowns drop to 1.2 s for [BURST_MS], then the
 * potion has a [COOLDOWN_MS] cooldown shared by all powerbursts, and every dose is a consumable.
 *
 * [evaluate] replays the burst on the live map on a clock ([Burst]) with the walker's own rules: the predicted server
 * walk to the walker's click, Surge before Dive, a surge chained to a dive's line when a charge is back by its landing,
 * a dive chained to a surge's landing, each ability (each Surge charge) again [RECAST_S] after its cast, no hop while
 * one is in the air, and no dive in the burst's last tick (it fires after it, on its full cooldown). Gains are counted
 * toward the walk's end ([RouteProgress]).
 *
 * The saving is each hop's steps less the running it costs ([HOP_COST_S]), less the fraction of a hop each ability
 * that would come back inside the burst anyway loses ([Kit.anyway]). It drinks at [MIN_SAVING_S] when no point a little
 * further on saves [BETTER_AHEAD] times more.
 */
internal object AccelerationPlanner {
    const val POTION = "Powerburst of acceleration"
    const val BURST_MS = 6_000L
    const val COOLDOWN_MS = 120_000L

    /** The dose takes effect this long after the click. */
    const val EFFECT_S = 0.3

    /** The first hop goes this long after that: the boosted reaction and check. */
    const val FIRST_CAST_S = 0.25
    const val SURGE_FLIGHT_S = 0.6
    const val DIVE_FLIGHT_S = 0.8

    /** An ability (each Surge charge) is cast again this long after its cast: the 1.2 s cooldown and the reaction, drawn in flight. */
    const val RECAST_S = 1.3
    const val BOOSTED_COOLDOWN_S = 1.2

    /** A ready hop goes this soon after the last one landed. */
    const val NEXT_CAST_S = 0.05

    /** A server tick: a surge waits for the walk from a landing to start. */
    const val TICK_S = 0.6

    /** Steps the player has walked on from a hop's landing when the walker sees it land: the walk click sent with the cast. */
    const val LANDING_STEPS = 2

    /** With nothing to cast, the walker looks again about once a step. */
    const val RECHECK_S = 0.3

    /** Running a hop costs this beyond its own steps. */
    const val HOP_COST_S = 0.45
    const val TILES_PER_SECOND = HopRules.TILES_PER_SECOND
    const val MIN_SAVING_S = 12.0

    /** Wait when a point this far along the route (route tiles) would save [BETTER_AHEAD] times more. */
    val LOOK_AHEAD = intArrayOf(10, 20, 30, 45)
    const val BETTER_AHEAD = 1.25

    /** From the route's end to the first hop on the next route: its re-plan, its first click and a tick to start. */
    const val BEYOND_START_S = 0.9
    const val BEYOND_HOP_S = RECAST_S

    /** Steps each hop past the route gains: below a hop's 10, for a route nobody has looked at. */
    const val BEYOND_HOP_STEPS = 6

    /** What the burst would buy from one starting point; [plan] lists the hops, e.g. "D+10@0.6 S+10@1.4". */
    data class Estimate(val hops: Int, val steps: Int, val savingS: Double, val plan: String)

    /**
     * What the burst runs with: Surge / Dive usable, Surge's charges (2 with Double Surge), the hops the dose costs
     * abilities back inside the burst anyway (a fraction each), the walk node's look-ahead, and the seconds the walking
     * goes on past the route's end.
     */
    class Kit(val surge: Boolean, val dive: Boolean, surgeCharges: Int, val anyway: Double, val clickAhead: Int, beyondS: Double = 0.0) {
        val surgeCharges: Int = if (surge) max(1, surgeCharges) else 0
        val beyondS: Double = max(0.0, beyondS)
    }

    /** Why a dose cannot be drunk now, or null when it can. */
    fun notDrinkable(): String? {
        if (potionSlot() == null) return "no Powerburst of acceleration in the backpack"
        if (active()) return "the burst is already running"
        if (runCatching { Effect.POWERBURST_POTION_IS_ON_COOLDOWN.active }.getOrDefault(false)) return "the powerburst cooldown is running"
        return null
    }

    /** The burst is running: Surge and Dive cooldowns are 1.2 s right now. */
    fun active(): Boolean = runCatching { Effect.POWERBURST_OF_ACCELERATION.active }.getOrDefault(false)

    fun drink(): Boolean = potionSlot()?.click("Drink") == true

    private fun potionSlot() = runCatching { inventory.firstOrNull { it.id != -1 && it.name.startsWith(POTION) } }.getOrNull()

    /** What the burst's hops save with the player on [start] (route index [idx]), last facing [facing] (null: unknown). */
    fun simulate(route: List<Tile>, idx: Int, start: Tile, facing: Direction?, kit: Kit): Estimate {
        if (!kit.surge && !kit.dive) return Estimate(0, 0, 0.0, "")
        return Burst(route, kit).run(start, idx, facing)
    }

    /** The estimate from here when it is worth drinking now, else null with the reason in [why]. */
    fun evaluate(route: List<Tile>, nearest: Int, server: Tile, facing: Direction?, kit: Kit, why: StringBuilder): Estimate? {
        val here = simulate(route, nearest, server, facing, kit)
        if (here.savingS < MIN_SAVING_S) {
            why.append("saves ").append(fmt(here.savingS)).append(" s here (").append(planOf(here)).append("), need ").append(fmt(MIN_SAVING_S))
            return null
        }
        for (ahead in LOOK_AHEAD) {
            val i = nearest + ahead
            if (i + 1 >= route.size) break
            val t = route[i]
            if (!LocalNav.trusted(t)) break
            val there = simulate(route, i, t, HopRules.routeStep(route, i), kit)
            if (there.savingS > here.savingS * BETTER_AHEAD) {
                why.append("saves ").append(fmt(here.savingS)).append(" s here but ").append(fmt(there.savingS)).append(" s ")
                    .append(ahead).append(" tiles ahead")
                return null
            }
        }
        return here
    }

    /** The way the step into [walk]`[j]` goes; null for the first tile. */
    fun stepInto(walk: List<Tile>, j: Int): Direction? {
        if (j < 1 || j >= walk.size) return null
        val a = walk[j - 1]
        val b = walk[j]
        return MovePrediction.direction(b.x - a.x, b.y - a.y)
    }

    fun fmt(v: Double): String = HopRules.fmt(v)

    private fun planOf(e: Estimate): String = e.plan.ifEmpty { "no hop" }

    /**
     * One replay of the burst. The clock runs from the drink click; the player is on the server walk [walk] (started at
     * [walkAt]), or standing on a hop's landing for the hop chained to it.
     */
    private class Burst(val route: List<Tile>, val kit: Kit) {
        private class Hop(val surge: Boolean, val from: Tile, val landing: Tile)

        lateinit var root: RouteProgress
        val surgeAt = DoubleArray(kit.surgeCharges)
        var diveAt = 0.0
        var at = 0
        var walk: List<Tile> = emptyList()
        var click: Tile? = null
        var entry: Direction? = null
        var walkAt = 0.0
        var reclickAt = -1
        var walkIdx = 0
        var surgeHeldUntil = 0.0
        var chainFrom: Tile? = null
        var chainDir: Direction? = null
        var diveChainFrom: Tile? = null
        var diveChainDir: Direction? = null
        var hops = 0
        var steps = 0
        var net = 0.0
        var end = 0.0
        val plan = StringBuilder()

        fun run(start: Tile, idx: Int, facing: Direction?): Estimate {
            at = idx
            root = RouteProgress.of(route, idx, start)
            walkOn(start, facing, 0.0)
            end = EFFECT_S + BURST_MS / 1000.0
            var t = EFFECT_S + FIRST_CAST_S
            var routeEnded = false
            while (t <= end) {
                val here = pos(t)
                at = RoutePicks.nearestFrom(route, here, at)
                if (route.size - at < HopRules.DIVE_MIN_GAIN + 2) {
                    routeEnded = true
                    break
                }
                t = step(t, here)
            }
            if (routeEnded && kit.beyondS > 0) beyond(t)
            if (hops > 0) net -= kit.anyway * max(0.0, steps.toDouble() / hops - HOP_COST_S * TILES_PER_SECOND)
            return Estimate(hops, steps, max(0.0, net) / TILES_PER_SECOND, plan.toString().trim())
        }

        /** The route ran out at [t] with the burst on and the walking going on past it: ordinary hops at a flat rate. */
        private fun beyond(t: Double) {
            val first = t + BEYOND_START_S
            val last = min(end - TICK_S, t + kit.beyondS)
            if (first > last) return
            val n = 1 + floor((last - first) / BEYOND_HOP_S).toInt()
            hops += n
            steps += n * BEYOND_HOP_STEPS
            net += n * (BEYOND_HOP_STEPS - HOP_COST_S * TILES_PER_SECOND)
            plan.append(" +").append(n).append(" past the route")
        }

        /** The walker's pass at [t]: Surge, else Dive, else walk on. When to look next. */
        private fun step(t: Double, start: Tile): Double {
            var here = start
            val ahead = gauge()
            val surgeLanded = diveChainFrom
            if (surgeLanded != null) {
                diveChainFrom = null
                val h = if (diveAt <= t) dive(surgeLanded, ahead, HopRules.routeIndexNear(route, at, surgeLanded), listOf(surgeLanded)) else null
                if (h != null) return land(h, t, -1)
                walkOn(surgeLanded, diveChainDir, t)
                surgeHeldUntil = t + TICK_S
                here = pos(t)
            }
            val charge = readyCharge(t)
            if (charge >= 0) {
                val h = surge(t, here, ahead)
                if (h != null) return land(h, t, charge)
            }
            val diveLanded = chainFrom
            if (diveLanded != null) {
                chainFrom = null
                walkOn(diveLanded, chainDir, t)
                surgeHeldUntil = t + TICK_S
                here = pos(t)
            }
            val diveOn = kit.dive && t <= end - TICK_S
            if (diveOn && diveAt <= t) {
                val h = dive(here, ahead)
                if (h != null) return land(h, t, -1)
            }
            var next = t + RECHECK_S
            if (charge < 0 && !(diveOn && diveAt <= t)) next = max(next, nextReady())
            return next
        }

        private fun surge(t: Double, here: Tile, ahead: RouteProgress): Hop? {
            val from: Tile
            val d: Direction?
            val chained = chainFrom
            if (chained != null) {
                from = chained
                d = chainDir
            } else {
                if (t < surgeHeldUntil) return null
                from = here
                d = if (walkIdx > 0) stepInto(walk, walkIdx) else entry
            }
            if (d == null) return null
            val l = MovePrediction.surgeLanding(from, d)
            return if (ahead.gain(from, l) >= HopRules.surgeNeed(route, at, from, l)) Hop(true, from, l) else null
        }

        /** Fires from the server tile or the 2 walk tiles after it. */
        private fun dive(here: Tile, ahead: RouteProgress): Hop? =
            dive(here, ahead, at, walk.subList(walkIdx, min(walk.size, walkIdx + ServerWalk.TICK_STEPS + 1)))

        private fun dive(here: Tile, ahead: RouteProgress, from: Int, arming: List<Tile>): Hop? {
            val p = HopRules.pickDive(route, from, here, ahead, arming)
            val landing = p.landing
            return if (p.aim == null || landing == null) null else Hop(false, here, landing)
        }

        /** Counts the hop cast at [t] and sets up the walk after it. When to look next. */
        private fun land(h: Hop, t: Double, charge: Int): Double {
            val flight = if (h.surge) SURGE_FLIGHT_S else DIVE_FLIGHT_S
            val landed = t + flight
            val gain = gauge().gain(h.from, h.landing)
            hops++
            steps += gain
            net += gain - HOP_COST_S * TILES_PER_SECOND
            plan.append(if (h.surge) " S" else " D").append(if (gain >= 0) "+" else "").append(gain).append('@').append(fmt(t))
            val l = h.landing
            val dir = HopModel.facing(l.x - h.from.x, l.y - h.from.y)
            if (h.surge) surgeAt[charge] = t + RECAST_S else diveAt = t + RECAST_S
            chainFrom = null
            val surgeBack = nextSurge()
            if (!h.surge && dir != null && surgeBack - (RECAST_S - BOOSTED_COOLDOWN_S) <= landed && chains(l, dir)) {
                chainFrom = l
                chainDir = dir
                return max(landed + NEXT_CAST_S, surgeBack)
            }
            if (h.surge && kit.dive && diveAt <= landed + NEXT_CAST_S && landed + NEXT_CAST_S <= end - TICK_S &&
                dive(l, gauge(), HopRules.routeIndexNear(route, at, l), listOf(l)) != null
            ) {
                diveChainFrom = l
                diveChainDir = dir
                return max(landed + NEXT_CAST_S, diveAt)
            }
            // The walk click sent with the cast, chosen where it was cast, is walked from the landing in the landing's tick.
            val c = RoutePicks.choose(route, h.from, kit.clickAhead, at)?.tile
            val walking = landed - LANDING_STEPS / TILES_PER_SECOND
            if (c == null || c.plane != l.plane || l.getLongestDelta(c) <= RoutePicks.LEG_NEAR) walkOn(l, dir, walking) else walkTo(l, c, dir, walking)
            surgeHeldUntil = landed + TICK_S
            return landed + NEXT_CAST_S
        }

        private fun chains(landing: Tile, d: Direction): Boolean {
            val i = HopRules.routeIndexNear(route, at, landing)
            val s = MovePrediction.surgeLanding(landing, d)
            return gauge().gain(landing, s) >= HopRules.surgeNeed(route, i, landing, s)
        }

        /** [root], or the old measure from where the replay has got to when there is none. */
        private fun gauge(): RouteProgress {
            if (root.field != null) return root
            return RouteProgress.toward(route[min(route.size - 1, at + HopRules.SURGE_PROGRESS_AHEAD)])
        }

        /** The server tile at [t]: along the walk, clicking on near its click as the walker does. */
        private fun pos(t: Double): Tile {
            chainFrom?.let { return it }
            diveChainFrom?.let { return it }
            repeat(MAX_RECLICKS) {
                val k = max(0, floor((t - walkAt) * TILES_PER_SECOND).toInt())
                if (reclickAt < 0 || k < reclickAt) {
                    walkIdx = min(k, walk.size - 1)
                    return walk[walkIdx]
                }
                val from = walk[reclickAt]
                val near = RoutePicks.nearestFrom(route, from, at)
                val c = RoutePicks.choose(route, from, kit.clickAhead, near)?.tile
                if (c == null || c == click) {
                    reclickAt = -1
                } else {
                    at = near
                    walkTo(from, c, stepInto(walk, reclickAt), walkAt + reclickAt / TILES_PER_SECOND)
                }
            }
            walkIdx = min(walk.size - 1, max(0, floor((t - walkAt) * TILES_PER_SECOND).toInt()))
            return walk[walkIdx]
        }

        private fun walkOn(from: Tile, faced: Direction?, whenS: Double) {
            walkTo(from, RoutePicks.choose(route, from, kit.clickAhead, at)?.tile, faced, whenS)
        }

        private fun walkTo(from: Tile, c: Tile?, faced: Direction?, whenS: Double) {
            val w = if (c == null) null else LocalNav.serverPath(from, c)
            walk = if (w.isNullOrEmpty()) listOf(from) else w
            click = if (w == null) null else c
            entry = faced
            walkAt = whenS
            walkIdx = 0
            reclickAt = -1
            val target = click ?: return
            for (i in 1 until walk.size) {
                if (walk[i].getLongestDelta(target) <= RoutePicks.LEG_NEAR) {
                    reclickAt = i
                    break
                }
            }
        }

        private fun readyCharge(t: Double): Int = surgeAt.indexOfFirst { it <= t }

        private fun nextReady(): Double = min(if (kit.dive) diveAt else Double.MAX_VALUE, nextSurge())

        private fun nextSurge(): Double = surgeAt.minOrNull() ?: Double.MAX_VALUE

        companion object {
            private const val MAX_RECLICKS = 16
        }
    }
}
