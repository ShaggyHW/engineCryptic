package com.projectx.traversal.nodes

import com.projectx.pathfinder.WalkMap
import com.projectx.traversal.local.LocalNav
import world.gregs.voidps.type.Tile

/** Where the player is on a route and which route tile to click next, by live walking distance. */
internal object RoutePicks {
    /** Extra steps a click target may cost over its route distance before it counts as a detour. */
    const val SLACK = 3

    /** Walk clicks further than 40 tiles from the drawn tile are refused, and the drawn tile trails the server's. */
    const val MAX_CLICK = 38

    /** A leg is done once the server tile is this close to its click. */
    const val LEG_NEAR = 3

    /** A click sent before a hop landed is dropped once the pick from the landing is this many route tiles further on. */
    const val LANDING_RECLICK_GAIN = 6

    private const val NEAREST_LOOKAHEAD = 60
    private const val ROUTE_INDEX_BACK = 60

    /** One choice of click: [tile] null when nothing ahead is reachable; [atEnd] when the player already stands at the end. */
    data class Pick(val tile: Tile?, val index: Int, val nearestIndex: Int, val onRoute: Boolean, val atEnd: Boolean)

    /**
     * The click for a player on [server], or null when the map is untrusted. Scans from [fromIndex] so a route that
     * passes near itself never pulls the player back. With [exactEnd] the route's last tile must be stood on.
     */
    fun choose(route: List<Tile>, server: Tile, stepLimit: Int, fromIndex: Int, exactEnd: Boolean = false): Pick? {
        val field = LocalNav.field(server, minOf(WalkMap.DEFAULT_MAX_DEPTH, stepLimit * 2 + 20)) ?: return null
        val nearest = nearestIndex(route, server, fromIndex, route.size)
        if (nearest < 0) return null
        val best = server.getLongestDelta(route[nearest])
        val limit = minOf(stepLimit, MAX_CLICK)
        var onRoute = -1
        var anyReachable = -1
        for (i in nearest until route.size) {
            val tile = route[i]
            if (tile.plane != server.plane) break
            val delta = server.getLongestDelta(tile)
            val finalTile = exactEnd && i == route.size - 1
            if (if (finalTile) delta == 0 else delta <= 1) continue
            if (delta > limit) {
                if (i - nearest > 3 * limit) break
                continue
            }
            val d = field.distanceTo(tile)
            if (d < 0 || d > limit + SLACK) continue
            anyReachable = i
            if (d <= best + (i - nearest) + SLACK) onRoute = i
        }
        val atEnd = nearest == route.size - 1 && best <= (if (exactEnd) 0 else 1)
        return when {
            onRoute >= 0 -> Pick(route[onRoute], onRoute, nearest, true, atEnd)
            anyReachable >= 0 -> Pick(route[anyReachable], anyReachable, nearest, false, atEnd)
            else -> Pick(null, -1, nearest, false, atEnd)
        }
    }

    /**
     * The progress after [pick]: its nearest index, but only when the player is on the route there. Picked from afar
     * (a teleport onto the route still landing) the straight-line nearest tile can be any tile, often the end.
     */
    fun advance(progress: Int, pick: Pick, route: List<Tile>, server: Tile, stepLimit: Int): Int {
        val near = route[pick.nearestIndex]
        if (server.getLongestDelta(near) > minOf(stepLimit, MAX_CLICK)) return progress
        return maxOf(progress, pick.nearestIndex)
    }

    /**
     * The walk click to send with a hop just cast, chosen from its predicted [landing]: the click goes in the hop's tick
     * and the server walks it from the landing. [before] (from the cast tile) with no prediction or nothing to click from
     * the landing; null when the landing is the route's end.
     */
    fun clickAfterHop(route: List<Tile>, before: Pick?, landing: Tile?, stepLimit: Int, progress: Int, exactEnd: Boolean): Pick? {
        if (landing == null || before?.tile == null) return before
        val after = choose(route, landing, stepLimit, progress, exactEnd) ?: return before
        if (after.tile == null) return if (after.atEnd) null else before
        return after
    }

    /**
     * True when [lastClick], sent before a hop landed, is behind the player's place on the route ([fresh], the pick from
     * the landing) or [LANDING_RECLICK_GAIN]+ route tiles short of it. A click on no route tile (a line-up) is not judged.
     */
    fun clickBehindLanding(route: List<Tile>, lastClick: Tile?, fresh: Pick?): Boolean {
        if (lastClick == null || fresh?.tile == null || fresh.tile == lastClick) return false
        val at = routeIndexOf(route, fresh.nearestIndex, lastClick)
        if (at < 0) return false
        return at < fresh.nearestIndex || fresh.index - at >= LANDING_RECLICK_GAIN
    }

    /** Index of the route tile nearest [server] in the 60 from [from] ([nearestIndex]); [from] when none. */
    fun nearestFrom(route: List<Tile>, server: Tile, from: Int): Int {
        val start = from.coerceIn(0, route.size - 1)
        val i = nearestIndex(route, server, start, start + NEAREST_LOOKAHEAD)
        return if (i < 0) start else i
    }

    /**
     * Index of the route tile on [server]'s plane nearest it (Chebyshev) in [from, to), -1 when none. Ties go to the
     * first, except along one run of equally near tiles, where the straight-line nearest wins: a player beside a
     * straight stretch is equally near all of it. A later, separate run as near still loses.
     */
    fun nearestIndex(route: List<Tile>, server: Tile, from: Int, to: Int): Int {
        var nearest = -1
        var best = Int.MAX_VALUE
        var bestSquared = Int.MAX_VALUE
        var run = false
        for (i in from.coerceIn(0, maxOf(0, route.size - 1)) until minOf(to, route.size)) {
            val t = route[i]
            if (t.plane != server.plane) {
                run = false
                continue
            }
            val d = server.getLongestDelta(t)
            val dx = t.x - server.x
            val dy = t.y - server.y
            val squared = dx * dx + dy * dy
            if (d < best || run && d == best && squared < bestSquared) {
                best = d
                bestSquared = squared
                nearest = i
                run = true
            } else if (d != best) {
                run = false
            }
        }
        return nearest
    }

    /** Index of [tile] in [route] around [nearest] (60 back, [HopRules.ROOT_REACH] on); -1 when absent. */
    fun routeIndexOf(route: List<Tile>, nearest: Int, tile: Tile): Int {
        for (i in maxOf(0, nearest - ROUTE_INDEX_BACK) until minOf(route.size, nearest + HopRules.ROOT_REACH)) {
            if (route[i] == tile) return i
        }
        return -1
    }
}
