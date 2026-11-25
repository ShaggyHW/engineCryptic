package com.projectx.traversal.nodes

import com.projectx.pathfinder.routeToTile
import com.projectx.script.Script
import com.projectx.traversal.TraversalNode
import com.projectx.traversal.TraversalOutcome
import com.projectx.traversal.WalkEquivalent
import com.projectx.traversal.local.LocalNav
import world.gregs.voidps.path.Route
import world.gregs.voidps.path.toTiles
import world.gregs.voidps.type.Tile
import kotlin.math.max
import kotlin.math.min

/**
 * A walk from [start] to [end]; over [tiles] when the route already gave them, so no route is asked for. With a trusted
 * live map the clicks come from [RouteWalker], like [ChebychevNode]'s, with the hops [abilities] allow.
 */
class PathNode(
    val start: Tile,
    val end: Tile,
    val reached: (() -> Boolean)? = null,
    tiles: List<Tile>? = null,
    val abilities: ClientAbilities = ClientAbilities.NONE,
) : TraversalNode(), WalkEquivalent {
    private val tiles: List<Tile>? = tiles?.takeIf { it.isNotEmpty() }?.toList()
    private var route: Route =
        if (this.tiles != null) Route(ArrayDeque(), alternative = false, success = true, distance = 0) else routeToTile(start, end)
    private var nextClick = 0L
    private var lastClick: Tile? = null
    private val walker = RouteWalker("PathNode", abilities)

    /** Live-map look-ahead, sampled once per node like [ChebychevNode]'s. */
    private val stepLimit = 2 * TraversalSupport.randomInt(TraversalSupport.FUTURE_PATH_STEP_MIN, TraversalSupport.FUTURE_PATH_STEP_MAX)

    override fun traversalStopped(outcome: TraversalOutcome) = walker.traversalStopped(outcome)

    override suspend fun process(script: Script): Boolean {
        if (tiles == null && !route.success) {
            route = routeToTile(start, end)
            println("[PathNode] Recalculating path: ${route.success} ${route.alternative} ${route.distance}")
            script.delay(RECALCULATE_MS, RECALCULATE_MS)
            return true
        }
        val step = walker.step(script, this, routeTiles(), stepLimit, { reached(script) }, ::traversalStopRequested, ::requestTraversalRepath)
        if (step == RouteWalker.Step.CONTINUE) return true
        if (step == RouteWalker.Step.ADVANCE) return false

        // A map not trusted yet: the leg is handed back once it is, so the live-map walk and its hops take over.
        val untilTrusted = TraversalSupport.serverTileOrNull()?.let { LocalNav.usableForHops(it) } != true
        val last = lastClick
        if (last != null && !near(last) && TraversalSupport.isMoving()) return true
        if (System.currentTimeMillis() <= nextClick) return true
        val clickTile = getNextClickPoint() ?: return true
        val minimap = TraversalSupport.rollMinimap()
        nextClick = System.currentTimeMillis() + TraversalSupport.clickPacingJitter()
        if (TraversalSupport.walkTo(clickTile, minimap)) {
            lastClick = clickTile
            TraversalSupport.awaitWalk(TraversalSupport.WALK_START_TIMEOUT_MS, TraversalSupport.WALK_LEG_TIMEOUT_MS) {
                traversalStopRequested() || near(clickTile) || reached(script) ||
                    untilTrusted && TraversalSupport.serverTileOrNull()?.let { LocalNav.usableForHops(it) } == true
            }
        }
        return true
    }

    private fun near(t: Tile) = TraversalSupport.within(TraversalSupport.serverTileOrNull(), t, LEG_NEAR)

    fun getNextClickPoint(): Tile? {
        val me = TraversalSupport.serverTileOrNull() ?: return null
        val path = tiles ?: if (route.success) route.toTiles(me.plane) else emptyList()
        if (path.isEmpty()) return null
        var closest = path.first()
        var bestDistance = Int.MAX_VALUE
        for (t in path) {
            val d = me.getDistance(t)
            if (d < bestDistance) {
                bestDistance = d
                closest = t
            }
        }
        val future = path.subList(max(path.indexOf(closest), 0), path.size)
        val lo = TraversalSupport.FUTURE_PATH_STEP_MIN
        val hi = min(TraversalSupport.FUTURE_PATH_STEP_MAX, future.size)
        var numFuture = if (hi <= lo) hi - 1 else TraversalSupport.randomInt(lo, hi)
        if (numFuture <= 1) return null
        numFuture = min(numFuture, future.size - 1)
        if (numFuture <= 1) return null
        return future[numFuture]
    }

    override fun reached(script: Script): Boolean = reached?.invoke() == true || TraversalSupport.within(TraversalSupport.serverTileOrNull(), end, REACHED_TILES)

    override fun routeTiles(): List<Tile> = tiles ?: if (route.success) route.toTiles(end.plane) else emptyList()

    override fun passableNow(): Boolean = true

    override fun copy(): TraversalNode = PathNode(start, end, null, tiles, abilities).also { it.route = this.route }

    override fun toString(): String = "[Path $start -> $end]"

    private companion object {
        const val LEG_NEAR = 3
        const val REACHED_TILES = 5
        const val RECALCULATE_MS = 250
    }
}
