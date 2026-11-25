package com.projectx.traversal.nodes

import com.projectx.traversal.hops.HopModel
import com.projectx.traversal.local.LocalNav
import world.gregs.voidps.type.Direction
import world.gregs.voidps.type.Tile
import java.util.Locale
import kotlin.math.abs
import kotlin.math.sign

/** The walker's hop rules shared by the walker, the hop schedule and the acceleration replay: needs, dive picks, route steps. */
internal object HopRules {
    const val SURGE_MIN_GAIN = 8

    /**
     * At the route's end a surge may land this many walking steps past or beside its last tile and walk back; such a
     * landing needs only [SURGE_END_MIN_GAIN].
     */
    const val SURGE_OVERSHOOT_MAX = 2
    const val SURGE_END_MIN_GAIN = 4

    /** At the route's end a surge landing short of the last tile with at most this many steps left needs only the end gain. */
    const val SURGE_END_SHORT_MAX = 4
    const val DIVE_MAX_AHEAD = 30
    const val DIVE_AIM_MAX = 20

    /** The walker's own dives aim this far on: the tile just across a fence the route walks round is many route tiles on. */
    const val DIVE_SHORTCUT_AHEAD = 120
    const val DIVE_MIN_GAIN = 8

    /** A dive onto the walk's end saves about a tick and is still on cooldown for the next walk. */
    const val DIVE_END_MIN_GAIN = 7

    /** No hop is looked for with fewer route tiles than this left. */
    const val END_GATE_TILES = SURGE_END_MIN_GAIN + 1

    /** Walking steps the player runs on between a hop's cast and the tile it fires from: the hop arms for a tick. */
    const val ARM_STEPS = 2

    /** With no root on the live map hop progress is measured toward the route tile this far ahead. */
    const val SURGE_PROGRESS_AHEAD = 25
    const val PROGRESS_DEPTH = 80
    const val ROOT_REACH = 160
    const val ROOT_DEPTH = 200

    const val READY_DELAY_MEAN_MS = 700L
    const val READY_DELAY_MIN_MS = 150L
    const val READY_DELAY_MAX_MS = 2_500L

    const val TILES_PER_SECOND = 2.0 / 0.6
    const val STILL_FACING_TOLERANCE_DEG = 10.0

    private const val ROUTE_NEAR_LOOKAHEAD = 40

    /**
     * Steps a surge from [from] to [landing] must gain: [SURGE_MIN_GAIN], or [SURGE_END_MIN_GAIN] when the route ends
     * within [SURGE_PROGRESS_AHEAD] of [at] and the landing is at most [SURGE_OVERSHOOT_MAX] steps from its last tile,
     * or lands short of it with at most [SURGE_END_SHORT_MAX] steps left.
     */
    fun surgeNeed(route: List<Tile>, at: Int, from: Tile?, landing: Tile?): Int {
        if (at + SURGE_PROGRESS_AHEAD < route.size - 1) return SURGE_MIN_GAIN
        val back = endSteps(route, landing)
        if (back < 0 || landing == null) return SURGE_MIN_GAIN
        if (back <= SURGE_OVERSHOOT_MAX) return SURGE_END_MIN_GAIN
        val end = route.last()
        val shortOfEnd = from != null && landing.plane == end.plane && from.getLongestDelta(landing) <= from.getLongestDelta(end)
        return if (shortOfEnd && back <= SURGE_END_SHORT_MAX) SURGE_END_MIN_GAIN else SURGE_MIN_GAIN
    }

    /** Steps a dive to [landing] must gain: [DIVE_MIN_GAIN], or [DIVE_END_MIN_GAIN] landing by the walk's last tile. */
    fun diveNeed(route: List<Tile>, at: Int, landing: Tile?): Int {
        if (at + SURGE_PROGRESS_AHEAD < route.size - 1) return DIVE_MIN_GAIN
        val back = endSteps(route, landing)
        return if (back in 0..SURGE_OVERSHOOT_MAX) DIVE_END_MIN_GAIN else DIVE_MIN_GAIN
    }

    /** Walking steps from [landing] to the route's last tile on the live map; negative when unknown. */
    fun endSteps(route: List<Tile>, landing: Tile?): Int {
        if (landing == null) return -1
        return LocalNav.field(route.last(), PROGRESS_DEPTH)?.distanceTo(landing) ?: -1
    }

    /** A dive pick: [aim] null when none; the counts and the last arming refusal are for the logs. */
    data class DivePick(
        val aim: Tile?,
        val landing: Tile?,
        val gain: Int,
        val aims: Int = 0,
        val noLanding: Int = 0,
        val armHeld: Int = 0,
        val bestGain: Int = gain,
        val armedWhy: String? = null,
        val armedAim: Tile? = null,
        val armedLanding: Tile? = null,
    )

    /**
     * The dive cast from [from] (route index [at]) and firing from one of [arming]: of the route tiles within
     * [DIVE_SHORTCUT_AHEAD] and [DIVE_AIM_MAX], the one whose landing gains the most toward [progress] (ties: the
     * furthest), at least [diveNeed], and landing well from every arming tile.
     */
    fun pickDive(route: List<Tile>, at: Int, from: Tile, progress: RouteProgress, arming: List<Tile>): DivePick {
        val last = minOf(route.size - 1, at + DIVE_SHORTCUT_AHEAD)
        var aims = 0
        var noLanding = 0
        var armHeld = 0
        var bestGain = Int.MIN_VALUE
        var pickGain = Int.MIN_VALUE
        var pickAim: Tile? = null
        var pickLanding: Tile? = null
        var armedAim: Tile? = null
        var armedLanding: Tile? = null
        var armedWhy: String? = null
        for (i in last downTo at + 1) {
            val aim = route[i]
            if (aim.plane != from.plane || from.getLongestDelta(aim) > DIVE_AIM_MAX) continue
            aims++
            val landing = MovePrediction.diveLanding(from, aim)
            if (landing == null) {
                noLanding++
                continue
            }
            val gain = progress.gain(from, landing)
            bestGain = maxOf(bestGain, gain)
            val need = diveNeed(route, at, landing)
            if (gain < need || gain <= pickGain) continue
            // On the last stretch the player runs on toward the goal while the dive arms: a step or two less is still worth it.
            val armed = armedShort(arming, aim, progress, if (need < DIVE_MIN_GAIN) 1 else need)
            if (armed != null) {
                armHeld++
                armedWhy = armed
                armedAim = aim
                armedLanding = landing
                continue
            }
            pickAim = aim
            pickLanding = landing
            pickGain = gain
        }
        return DivePick(pickAim, pickLanding, pickGain, aims, noLanding, armHeld, bestGain, armedWhy, armedAim, armedLanding)
    }

    /**
     * Where a dive sent in one combo with a surge aims from the surge's landing [from]: the pick's aim when in reach,
     * else its landing when that is in reach and a straight dive lands there; null when neither.
     */
    fun comboDiveAim(from: Tile, pick: DivePick): Tile? {
        val aim = pick.aim
        if (aim != null && from.getLongestDelta(aim) <= HopModel.DIVE_RANGE) return aim
        val landing = pick.landing ?: return null
        if (landing == from || from.getLongestDelta(landing) > HopModel.DIVE_RANGE) return null
        return if (landing == MovePrediction.diveLanding(from, landing)) landing else null
    }

    /**
     * The tiles a player walking to [walkClick] may stand on 1..[ARM_STEPS] steps from [server]: every tile that far
     * along a shortest walk to the click. Empty when the map cannot say or the player is already on the click.
     */
    fun armingTiles(server: Tile?, walkClick: Tile?): List<Tile> {
        if (server == null || walkClick == null || walkClick.plane != server.plane) return emptyList()
        val toClick = LocalNav.field(walkClick) ?: return emptyList()
        val left = toClick.distanceTo(server)
        if (left <= 0) return emptyList()
        val near = LocalNav.field(server, ARM_STEPS) ?: return emptyList()
        val steps = minOf(ARM_STEPS, left)
        val plane = server.plane
        val out = ArrayList<Tile>()
        for (dx in -steps..steps) {
            for (dy in -steps..steps) {
                val x = server.x + dx
                val y = server.y + dy
                val k = near.distanceTo(x, y, plane)
                if (k in 1..steps && toClick.distanceTo(x, y, plane) == left - k) out += Tile.of(x, y, plane)
            }
        }
        return out
    }

    /** Why a dive at [aim] goes wrong from one of [arming]: refused there, or saving under [need]; null when it lands well from all. */
    fun armedShort(arming: List<Tile>, aim: Tile, progress: RouteProgress, need: Int): String? {
        for (from in arming) {
            val landing = MovePrediction.diveLanding(from, aim) ?: return "it is refused from $from, where it fires"
            val gain = progress.gain(from, landing)
            if (gain < need) return "from $from, where it fires, it lands $landing (gains $gain)"
        }
        return null
    }

    /** The way the route steps onto index [i], or null at its start, across planes or off its end. */
    fun routeStep(route: List<Tile>, i: Int): Direction? {
        if (i < 1 || i >= route.size) return null
        val a = route[i - 1]
        val b = route[i]
        if (a.plane != b.plane) return null
        return MovePrediction.direction((b.x - a.x).sign, (b.y - a.y).sign)
    }

    /** The way the route's next two steps after [nearest] both go, or null round a bend or at the end. */
    fun routeAhead(route: List<Tile>, nearest: Int): Direction? {
        val first = routeStep(route, nearest + 1)
        return if (first != null && first == routeStep(route, nearest + 2)) first else null
    }

    /** Index of the route tile nearest [tile] in the 40 after [from] (ties: the furthest); [from] when none is on its plane. */
    fun routeIndexNear(route: List<Tile>, from: Int, tile: Tile): Int {
        var best = from
        var bestDistance = Int.MAX_VALUE
        for (i in minOf(route.size - 1, from + ROUTE_NEAR_LOOKAHEAD) downTo from + 1) {
            val r = route[i]
            if (r.plane != tile.plane) continue
            val d = r.getLongestDelta(tile)
            if (d < bestDistance) {
                best = i
                bestDistance = d
            }
        }
        return best
    }

    /** A walk from [here] to [toward] (none: null or [here]) keeps facing [direction]: [toward] lies on its line. */
    fun walkRunsAlong(direction: Direction, here: Tile, toward: Tile?): Boolean {
        if (toward == null || toward.plane != here.plane || toward == here) return true
        val dx = toward.x - here.x
        val dy = toward.y - here.y
        return dx.sign == direction.delta.x && dy.sign == direction.delta.y && (dx == 0 || dy == 0 || abs(dx) == abs(dy))
    }

    /** [bearing] (degrees, 0 N) as one of the 8 ways when within [STILL_FACING_TOLERANCE_DEG] of it; else null. */
    fun snap8(bearing: Int): Direction? {
        if (bearing < 0) return null
        val snapped = Math.round(bearing / 45.0) * 45.0
        val off = abs(((bearing - snapped) % 360.0 + 540.0) % 360.0 - 180.0)
        return if (off <= STILL_FACING_TOLERANCE_DEG) HopModel.fromBearing(snapped % 360.0) else null
    }

    /** [at] lies on the surge line from [from] facing [direction], within the surge's range. */
    fun onSurgeLine(from: Tile?, direction: Direction?, at: Tile?): Boolean {
        if (from == null || direction == null || at == null || from.plane != at.plane) return false
        val dx = at.x - from.x
        val dy = at.y - from.y
        return (0..HopModel.SURGE_RANGE).any { dx == direction.delta.x * it && dy == direction.delta.y * it }
    }

    fun fmt(value: Double): String = String.format(Locale.ROOT, "%.1f", value)
}
