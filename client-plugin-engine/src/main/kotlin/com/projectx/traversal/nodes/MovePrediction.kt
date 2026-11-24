package com.projectx.traversal.nodes

import com.projectx.script.api.localPlayer
import com.projectx.traversal.hops.HopKnowledge
import com.projectx.traversal.hops.HopModel
import com.projectx.traversal.local.LocalNav
import world.gregs.voidps.type.Direction
import world.gregs.voidps.type.Tile

/**
 * Where Surge and Dive land on the live map, by [HopModel]'s trajectory rule over [HopKnowledge.rule]: the live walk
 * layer ("You can't dive through something you can't walk through!") plus the hop-passable edges and hop-stoppers
 * learned so far.
 */
internal object MovePrediction {
    const val SURGE_RANGE = HopModel.SURGE_RANGE
    const val DIVE_RANGE = HopModel.DIVE_RANGE

    private const val HELPS_NEAR_TARGET = 2
    private const val HELPS_MIN_STEPS = 5
    private const val HELPS_FIELD_DEPTH = 48

    /** The local player's drawn facing bearing in degrees (0 N, 90 E), or -1 when unresolved. */
    fun facingBearing(): Int = runCatching { localPlayer.facingAngle }.getOrDefault(-1)

    fun bearing(from: Tile, to: Tile): Double = HopModel.bearing(from, to)

    /** Where a surge from [from] facing the 8-way direction nearest [bearingDegrees] lands. */
    fun surgeLanding(from: Tile, bearingDegrees: Double): Tile = surgeLanding(from, HopModel.fromBearing(bearingDegrees))

    /** Where a surge from [from] facing [direction] lands; [from] when it cannot move. */
    fun surgeLanding(from: Tile, direction: Direction?): Tile =
        if (direction == null) from else HopModel.surge(HopKnowledge.rule(), from, direction)

    fun direction(dx: Int, dy: Int): Direction? = HopModel.direction(dx, dy)

    /** Landing on [landing] instead of walking from [from] is worth it: near [target], or enough walking steps closer. */
    fun helps(from: Tile, landing: Tile?, target: Tile): Boolean {
        if (landing == null || landing == from || landing.plane != target.plane) return false
        if (landing.getLongestDelta(target) <= HELPS_NEAR_TARGET) return true
        val field = LocalNav.field(target, HELPS_FIELD_DEPTH) ?: return false
        val before = field.distanceTo(from)
        val after = field.distanceTo(landing)
        return after >= 0 && (before < 0 || after <= before - HELPS_MIN_STEPS)
    }

    /** A dive from [from] lands exactly on [to]: in reach, walkable, and the hop's walk gets there. */
    fun diveClear(from: Tile?, to: Tile?): Boolean =
        from != null && to != null && from.plane == to.plane && from != to &&
            from.getLongestDelta(to) <= DIVE_RANGE && LocalNav.walkable(to) &&
            HopModel.greedy(HopKnowledge.rule(), from, to) != null

    /** Where a dive from [from] clicked on [aim] lands, or null when the game refuses it. */
    fun diveLanding(from: Tile, aim: Tile): Tile? = HopModel.dive(HopKnowledge.rule(), from, aim)
}
