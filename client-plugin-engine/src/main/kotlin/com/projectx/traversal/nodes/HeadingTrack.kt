package com.projectx.traversal.nodes

import com.projectx.traversal.hops.HopModel
import world.gregs.voidps.type.Direction
import world.gregs.voidps.type.Tile
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sign

/**
 * The way the player is really moving, from the server tile's recent changes: the drawn angle lags mid-turn. Right
 * after a hop, before any walking gives a heading, the hop's own way stands in for 1.5 s.
 */
internal class HeadingTrack {
    private val track = ArrayDeque<Pair<Long, Tile>>()

    @Volatile
    var lastHopBearing = -1.0
        private set

    @Volatile
    var lastHopDirection: Direction? = null
        private set

    @Volatile
    var lastHopAt = 0L
        private set

    /** Where [surgeDirection] got its answer, for the logs. */
    var source = ""

    @Synchronized
    fun add(server: Tile?) {
        if (server == null) return
        val now = System.currentTimeMillis()
        track.addLast(now to server)
        while (track.isNotEmpty() && now - track.first().first > TRACK_MS) track.removeFirst()
    }

    @Synchronized
    fun newest(): Tile? = track.lastOrNull()?.second

    /** A hop moved the player from [from] to [to]: until a walking step, a surge goes its way. */
    fun hopLanded(from: Tile, to: Tile, direction: Direction?) {
        lastHopBearing = HopModel.bearing(from, to)
        lastHopDirection = direction
        lastHopAt = System.currentTimeMillis()
    }

    /** Bearing (0 N, 90 E) of the server tile's movement over the last ~0.5-2 s, else the last hop's for 1.5 s; -1 when unknown. */
    fun heading(): Double {
        val walked = walkedHeading()
        if (walked >= 0) return walked
        return if (System.currentTimeMillis() - lastHopAt <= HOP_FACING_MS) lastHopBearing else -1.0
    }

    @Synchronized
    private fun walkedHeading(): Double {
        val (t, now) = track.lastOrNull() ?: return -1.0
        for (i in track.indices.reversed()) {
            val (at, then) = track[i]
            if (t - at < HEADING_WINDOW_MS) continue
            if (then.plane != now.plane || now.getLongestDelta(then) < HEADING_MIN_TILES) return -1.0
            if (now.getLongestDelta(then) >= HOP_JUMP_TILES * 3) return -1.0
            return HopModel.bearing(then, now)
        }
        return -1.0
    }

    /** (dx, dy) of the server tile's latest change within the tracked ~2 s, or null when it did not move. */
    @Synchronized
    private fun lastServerStep(): IntArray? {
        if (track.size < 2) return null
        val newest = track.last().second
        for (i in track.indices.reversed()) {
            val t = track[i].second
            if (t == newest) continue
            if (t.plane != newest.plane) return null
            return intArrayOf(newest.x - t.x, newest.y - t.y)
        }
        return null
    }

    /**
     * The way a surge goes: one of 8, the player's last move (a hop counts as a step its own way, as it turns the
     * player). A knight's move ends on one of two ways the lagging drawn facing cannot tell apart: unknown. After a hop
     * with no step since, the hop's way for 1.5 s. Null when unknown.
     */
    fun surgeDirection(): Direction? {
        val d = lastServerStep()
        if (d != null) {
            val dx = d[0]
            val dy = d[1]
            val ax = abs(dx)
            val ay = abs(dy)
            source = "last step $dx,$dy"
            if (max(ax, ay) > 2) return hopFacing(HopModel.facing(dx, dy))
            if (ax == 0 || ay == 0 || ax == ay) return HopModel.direction(dx.sign, dy.sign)
            source = "knight's move $dx,$dy"
            return null
        }
        source = "last hop"
        return if (System.currentTimeMillis() - lastHopAt <= HOP_FACING_MS) hopFacing(lastHopDirection) else null
    }

    /**
     * The facing a hop left, while the walk queued since keeps to its line: a walk that turns the player does so on its
     * first step, a tick before the surge fires, so the facing at the cast is then unknown.
     */
    private fun hopFacing(hopDirection: Direction?): Direction? {
        val here = newest()
        if (hopDirection == null || here == null) return hopDirection
        val queued = TraversalSupport.lastWalkClickOrNull()
        if (HopRules.walkRunsAlong(hopDirection, here, queued)) return hopDirection
        source += " ($hopDirection), but the walk on to $queued turns the player"
        return null
    }

    companion object {
        /** The heading is read over at least this long and this many tiles of server movement. */
        const val HEADING_WINDOW_MS = 450L
        const val HEADING_MIN_TILES = 2

        /** A hop moves the server tile at least this far between two observations; walking does not. */
        const val HOP_JUMP_TILES = 3
        const val HOP_FACING_MS = 1_500L
        private const val TRACK_MS = 2_000L
    }
}
