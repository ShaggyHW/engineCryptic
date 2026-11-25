package com.projectx.traversal.nodes

import com.projectx.pathfinder.Steps
import com.projectx.pathfinder.WalkField
import com.projectx.traversal.local.LocalNav
import world.gregs.voidps.type.Tile

/**
 * What a hop's gain is counted toward: walking steps on the live map to [root], the walk's last tile ([end]) or the
 * furthest route tile within [HopRules.ROOT_REACH] the player's own walk reaches. A hop across a wall (a corner slide,
 * an edge learned hop-passable) then counts the whole walk round it. With no root ([field] null) gains are counted
 * toward [ahead], [HopRules.SURGE_PROGRESS_AHEAD] route tiles on.
 */
internal class RouteProgress(val field: WalkField?, val root: Tile?, val rootIndex: Int, val end: Boolean, val ahead: Tile) {

    fun gain(from: Tile, landing: Tile?): Int = if (field != null) gain(from, landing, field) else gain(from, landing, ahead)

    /**
     * The root reaches [from], or a tile a step off it. Off that, [gain] takes the whole search depth as the distance
     * from [from] and every hop from it reads as a huge gain, so the spot scan skips such route tiles.
     */
    fun counts(from: Tile): Boolean = field == null || stepsOff(field, from) >= 0

    override fun toString(): String = if (field == null) "$ahead" else (if (end) "the walk's end " else "") + "$root"

    companion object {
        fun toward(ahead: Tile) = RouteProgress(null, null, -1, false, ahead)

        /** The measure for a player on [from] whose nearest route index is [nearest]. */
        fun of(route: List<Tile>, nearest: Int, from: Tile?): RouteProgress {
            val ahead = route[minOf(route.size - 1, nearest + HopRules.SURGE_PROGRESS_AHEAD)]
            val here = from?.let { LocalNav.field(it, HopRules.ROOT_DEPTH) } ?: return toward(ahead)
            for (i in minOf(route.size - 1, nearest + HopRules.ROOT_REACH) downTo nearest + 1) {
                val tile = route[i]
                if (tile.plane != from.plane || here.distanceTo(tile) < 0) continue
                val field = LocalNav.field(tile, HopRules.ROOT_DEPTH) ?: continue
                return RouteProgress(field, tile, i, i == route.size - 1, ahead)
            }
            return toward(ahead)
        }

        /** Walking steps a hop from [from] to [landing] saves toward [ahead]; [Int.MIN_VALUE] when the map cannot say. */
        fun gain(from: Tile, landing: Tile?, ahead: Tile): Int {
            if (landing == null || landing == from) return 0
            return gain(from, landing, LocalNav.field(ahead, HopRules.PROGRESS_DEPTH))
        }

        /** Walking steps [field], rooted where progress is measured to, says the hop saves. */
        fun gain(from: Tile, landing: Tile?, field: WalkField?): Int {
            if (landing == null || landing == from) return 0
            if (field == null) return Int.MIN_VALUE
            var before = stepsOff(field, from)
            val after = field.distanceTo(landing)
            if (after < 0) return Int.MIN_VALUE
            if (before < 0) before = field.maxDepth
            return before - after
        }

        /**
         * [field]'s steps to [tile], or one more than its steps to a neighbour a legal step leads off to when nothing
         * walks into [tile]: the player can stand where the search, run out from the goal, never enters. Negative when
         * neither reaches it.
         */
        fun stepsOff(field: WalkField, tile: Tile): Int {
            val d = field.distanceTo(tile)
            if (d != LocalNav.UNREACHABLE) return d
            var best = LocalNav.UNREACHABLE
            for (direction in Steps.SERVER_ORDER) {
                val next = Tile.of(tile.x + direction.delta.x, tile.y + direction.delta.y, tile.plane)
                val dn = field.distanceTo(next)
                if (dn >= 0 && (best < 0 || dn + 1 < best) && LocalNav.distance(tile, next, 1) == 1) best = dn + 1
            }
            return best
        }

        /**
         * A probe's gain measure from stand tile [stand]: steps toward [ahead] saved, or null when the live map does not
         * walk [stand] to [ahead]; off that field every landing would read as the field's whole depth.
         */
        fun probeGain(stand: Tile, ahead: Tile): ((Tile) -> Int)? {
            val field = LocalNav.field(ahead, HopRules.PROGRESS_DEPTH) ?: return null
            if (stepsOff(field, stand) < 0) return null
            return { landing -> gain(stand, landing, field) }
        }
    }
}
