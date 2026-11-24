package com.projectx.game.nxt.entity

import world.gregs.voidps.type.Direction
import world.gregs.voidps.type.Tile
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.roundToInt

/** Compass bearings in whole degrees clockwise from north (0 N, 90 E, 180 S, 270 W). */
object Bearings {
    private val CLOCKWISE = arrayOf(
        Direction.NORTH, Direction.NORTH_EAST, Direction.EAST, Direction.SOUTH_EAST,
        Direction.SOUTH, Direction.SOUTH_WEST, Direction.WEST, Direction.NORTH_WEST,
    )

    /**
     * The bearing of a render transform whose yaw puts [cos] at m[0] and [sin] at m[8]. That yaw is 0 facing south
     * and +90 facing west, hence the half turn. -1 for a degenerate rotation; a scaled model still answers.
     */
    @JvmStatic
    fun ofYaw(cos: Float, sin: Float): Int {
        if (!(hypot(cos, sin) > 1e-3f)) return -1
        val degrees = atan2(sin, cos) * (180f / 3.14159265f)
        val rounded = if (degrees < 0f) -Math.round(-degrees) else Math.round(degrees)
        return Math.floorMod(rounded + 180, 360)
    }

    /** The bearing from [from] to [to], or -1 when they share x and y. */
    @JvmStatic
    fun between(from: Tile, to: Tile): Int {
        val dx = to.x - from.x
        val dy = to.y - from.y
        if (dx == 0 && dy == 0) return -1
        return Math.floorMod(Math.toDegrees(atan2(dx.toDouble(), dy.toDouble())).roundToInt(), 360)
    }

    /** The smaller angle between two bearings, 0..180. */
    @JvmStatic
    fun difference(a: Int, b: Int): Int {
        val d = Math.floorMod(a - b, 360)
        return minOf(d, 360 - d)
    }

    /** The nearest of the eight directions to [degrees]; any value, it wraps. */
    @JvmStatic
    fun direction(degrees: Double): Direction = CLOCKWISE[Math.floorMod(Math.round(degrees / 45.0).toInt(), 8)]
}

/**
 * The tile the server has an entity on: the last tile-centre waypoint still queued for drawing. [fine] holds the
 * queue's fine (x512) scene positions as x, y pairs, oldest first. Half-step waypoints are skipped; null when the
 * queue holds no tile centre, so the caller falls back to the drawn tile.
 */
internal fun lastQueuedTileCentre(fine: FloatArray): Pair<Int, Int>? {
    var i = fine.size / 2 - 1
    while (i >= 0) {
        val fx = fine[i * 2]
        val fy = fine[i * 2 + 1]
        if (fx > 0f && fy > 0f && fx < MAX_FINE && fy < MAX_FINE) {
            val ix = fx.toInt()
            val iy = fy.toInt()
            if (ix and 511 == 256 && iy and 511 == 256) return (ix shr 9) to (iy shr 9)
        }
        i--
    }
    return null
}

private const val MAX_FINE = 33554432f
