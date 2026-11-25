package com.projectx.traversal.nodes

import com.projectx.script.Script
import com.projectx.traversal.TraversalNode
import com.projectx.traversal.TraversalOutcome
import com.projectx.traversal.WalkEquivalent
import com.projectx.traversal.local.LocalNav
import world.gregs.voidps.type.Tile
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * A walk through keypoints. With a trusted live map the clicks come from [RouteWalker] over the route's tiles
 * ([givenRouteTiles], the move batch, when the route gave them; otherwise [end] itself): walking distance, through open doors, an early
 * hand-over, and the hops [abilities] allow. Untrusted, the keypoint walk runs. In a tour it walks to each of [stops]
 * rather than past it.
 */
class ChebychevNode(
    val start: Tile,
    val end: List<Tile>,
    val fallBack: (() -> Boolean)? = null,
    val reached: (() -> Boolean)? = null,
    givenRouteTiles: List<Tile>? = null,
    val abilities: ClientAbilities = ClientAbilities.NONE,
) : TraversalNode(), WalkEquivalent {
    @Deprecated("The shipped signature, kept for scripts built against it.", level = DeprecationLevel.HIDDEN)
    constructor(
        start: Tile,
        end: List<Tile>,
        fallBack: (() -> Boolean)? = null,
        reached: (() -> Boolean)? = null,
    ) : this(start, end, fallBack, reached, null, ClientAbilities.NONE)

    private val givenRouteTiles: List<Tile>? = givenRouteTiles?.takeIf { it.isNotEmpty() }?.toList()
    private var route: List<Tile>
    private var nextClick = 0L
    private var maxDistanceUsed: Int
    private var currentTargetTile: Tile? = null
    private val walker = RouteWalker("ChebychevNode", abilities)

    /** The tour's waypoints still to be visited, shared by its walk nodes; null outside a tour. */
    var stops: TourStops? = null
        private set

    init {
        maxDistanceUsed = TraversalSupport.randomInt(TraversalSupport.FUTURE_PATH_STEP_MIN, TraversalSupport.FUTURE_PATH_STEP_MAX)
        route = smoothPathChebyshev(start, end, maxDistanceUsed, fallBack)
    }

    private fun interpolateTilesChebyshevEx(a: Tile, b: Tile, maxDistance: Int, includeEnd: Boolean): List<Tile> {
        val dx = b.x - a.x
        val dy = b.y - a.y
        val steps = ceil(max(abs(dx), abs(dy)).toDouble() / maxDistance).toInt()
        if (steps <= 0) return if (includeEnd) listOf(b) else emptyList()
        val result = ArrayList<Tile>()
        val last = if (includeEnd) steps else steps - 1
        for (i in 1..last) {
            val t = i.toDouble() / steps
            result += Tile.of((a.x + dx * t).roundToInt(), (a.y + dy * t).roundToInt(), a.plane)
        }
        return result
    }

    /** [steps] with keypoints at most twice [maxDistance] apart; [fallBack] runs when the player is nowhere near them. */
    fun smoothPathChebyshev(startingTile: Tile, steps: List<Tile>, maxDistance: Int, fallBack: (() -> Boolean)? = null): List<Tile> {
        if (steps.isEmpty()) return listOf(startingTile)
        val result = ArrayList<Tile>()
        var current = steps.first()
        result += current
        for (next in steps) {
            if (current.withinDistance(next, maxDistance * 2)) result += next else result += interpolateTilesChebyshevEx(current, next, maxDistance * 2, true)
            current = result.last()
        }
        if (fallBack != null) {
            val me = TraversalSupport.serverTileOrNull()
            val hasToTeleport = me == null || result.none { me.getDistance(it) < TraversalSupport.FUTURE_PATH_STEP_MAX * 2 }
            if (hasToTeleport) fallBack()
        }
        return result
    }

    override fun traversalStopped(outcome: TraversalOutcome) = walker.traversalStopped(outcome)

    override suspend fun process(script: Script): Boolean {
        if (route.isNotEmpty()) {
            val step = walker.step(
                script, this, routeTiles(), (maxDistanceUsed * 2.0).roundToInt(), { reached(script) },
                ::traversalStopRequested, ::requestTraversalRepath, stops, endsTraversal(),
            )
            if (step == RouteWalker.Step.CONTINUE) return true
            if (step == RouteWalker.Step.ADVANCE) return false
        }
        // Only a map not trusted yet hands the leg back once it is; the walker's own fallbacks keep theirs.
        val untrusted = givenRouteTiles != null && TraversalSupport.serverTileOrNull()?.let { LocalNav.usableForHops(it) } != true
        return keypointWalk(script, untrusted)
    }

    /**
     * The keypoint walk: straight-line look-ahead over the smoothed keypoints. With [untilTrusted], the leg's wait ends as
     * soon as the live map is trusted at the player, so the live-map walk and its hops take over.
     */
    private suspend fun keypointWalk(script: Script, untilTrusted: Boolean): Boolean {
        if (route.isEmpty()) {
            fallBack?.invoke()
            return false
        }
        val current = currentTargetTile
        if (current != null && !near(current) && TraversalSupport.isMoving()) return true
        if (System.currentTimeMillis() <= nextClick) return true
        val clickTile = getNextClickPoint()
        if (clickTile == null) {
            println("[ChebychevNode] No click tile found (route=$route)")
            return true
        }
        currentTargetTile = clickTile
        val minimap = TraversalSupport.rollMinimap()
        nextClick = System.currentTimeMillis() + TraversalSupport.clickGapMs(TraversalSupport.WALK_PATH_CLICK_TIME / 2)
        if (TraversalSupport.walkTo(clickTile, minimap)) {
            TraversalSupport.awaitWalk(TraversalSupport.WALK_START_TIMEOUT_MS, TraversalSupport.WALK_LEG_TIMEOUT_MS) {
                traversalStopRequested() || near(clickTile) || untilTrusted && TraversalSupport.serverTileOrNull()?.let { LocalNav.usableForHops(it) } == true
            }
        }
        return true
    }

    private fun near(t: Tile) = TraversalSupport.within(TraversalSupport.serverTileOrNull(), t, NEAR_TILES)

    fun getNextClickPoint(): Tile? {
        if (route.isEmpty()) return null
        val me = TraversalSupport.serverTileOrNull() ?: return null
        val keys = TourStops.keypointsUntilStop(route, routeTiles(), stops, me)
        var closest = keys.first()
        var bestDistance = Int.MAX_VALUE
        for (t in keys) {
            val d = me.getDistance(t)
            if (d < bestDistance) {
                bestDistance = d
                closest = t
            }
        }
        val future = keys.subList(max(keys.indexOf(closest), 0), keys.size)
        if (future.isEmpty()) return null
        val stepLimit = (maxDistanceUsed * 2.0).roundToInt()
        var within = closest
        for (t in future) {
            if (me.withinDistance(t, stepLimit)) within = t else break
        }
        return Tile.of(within.x, within.y, within.plane)
    }

    override fun reached(script: Script): Boolean {
        if (reached?.invoke() == true) return true
        // A tour that comes back near its own end is not done until its stops are.
        if (holdsStop()) return false
        val server = TraversalSupport.serverTileOrNull()
        val last = end.lastOrNull() ?: return true
        // The traversal's last node ends ON its tile: "within 3" would complete the walk short of the goal.
        if (endsTraversal()) return server != null && server.matches(last)
        return TraversalSupport.within(server, last, NEAR_TILES)
    }

    /** No node follows this one: its end tile is the traversal's final tile. */
    fun endsTraversal(): Boolean = next == null

    override fun routeTiles(): List<Tile> = givenRouteTiles ?: end

    override fun passableNow(): Boolean = true

    /** Walks to each of [stops] on its tiles rather than past them. */
    fun withStops(stops: TourStops?) = apply { this.stops = stops }

    override fun holdsStop(): Boolean = stops?.holds(routeTiles(), TraversalSupport.serverTileOrNull()) == true

    override fun copy(): TraversalNode = ChebychevNode(start, end, null, null, givenRouteTiles, abilities).also {
        it.maxDistanceUsed = this.maxDistanceUsed
        it.route = this.route
        it.stops = this.stops
    }

    override fun toString(): String = "[Chebychev $start -> $end]"

    private companion object {
        const val NEAR_TILES = 3
    }
}
