package com.projectx.traversal.nodes

import world.gregs.voidps.type.Tile
import java.util.concurrent.ConcurrentHashMap

/**
 * The waypoints of a tour the walk has to bring the player to, shared by every walk node of the tour. Until a stop is
 * visited the walker treats it as the end of the route: no click, hop or early start past it, and the processor's
 * progress check does not skip the node holding it ([holds]). Without them a route that came back near itself had its
 * waypoint cut off by the click choice.
 */
class TourStops(stops: Collection<Tile?>) {
    private val open: MutableSet<Tile> = ConcurrentHashMap.newKeySet()

    init {
        stops.filterNotNullTo(open)
    }

    /** Clears every stop within [VISIT_RADIUS] of [server] on its plane. */
    fun visit(server: Tile?) {
        if (server == null) return
        open.removeIf { it.plane == server.plane && it.getLongestDelta(server) <= VISIT_RADIUS }
    }

    /** Index of the first stop not yet visited on [route], or -1. */
    fun barrier(route: List<Tile>): Int {
        if (open.isEmpty()) return -1
        return route.indexOfFirst { it in open }
    }

    fun isOpen(tile: Tile): Boolean = tile in open

    /** [tiles] carry a stop not yet visited from [server]: a walk node over them holds the traversal there. */
    fun holds(tiles: List<Tile>, server: Tile?): Boolean {
        visit(server)
        return tiles.any { it in open }
    }

    override fun toString(): String = "TourStops$open"

    companion object {
        /** A stop counts as visited once the server tile is this close to it (Chebyshev, same plane). */
        const val VISIT_RADIUS = 2

        /** [route] up to and including the first stop not yet visited from [server]; the whole route outside a tour or once all are visited. */
        internal fun untilStop(route: List<Tile>, stops: TourStops?, server: Tile?): List<Tile> {
            if (stops == null) return route
            stops.visit(server)
            val stop = stops.barrier(route)
            return if (stop < 0) route else route.subList(0, stop + 1)
        }

        /**
         * A keypoint walk (the map not trusted yet) cut at the first open stop on [tiles]: the keypoints before it, then
         * the stop. Interpolated keypoints (on no route tile) just before the cut lie on the line past the stop and go too.
         */
        internal fun keypointsUntilStop(keypoints: List<Tile>, tiles: List<Tile>, stops: TourStops?, server: Tile?): List<Tile> {
            if (stops == null) return keypoints
            stops.visit(server)
            val stopIndex = stops.barrier(tiles)
            if (stopIndex < 0) return keypoints
            var cut = keypoints.indexOfFirst { tiles.indexOf(it) > stopIndex }.let { if (it < 0) keypoints.size else it }
            while (cut > 0 && cut < keypoints.size && tiles.indexOf(keypoints[cut - 1]) < 0) cut--
            val out = ArrayList(keypoints.subList(0, cut))
            val stop = tiles[stopIndex]
            if (out.isEmpty() || out.last() != stop) out += stop
            return out
        }
    }
}
