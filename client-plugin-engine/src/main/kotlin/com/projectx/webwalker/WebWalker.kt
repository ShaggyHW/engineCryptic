package com.projectx.webwalker

import com.projectx.script.Script
import com.projectx.script.api.Lodestone
import com.projectx.script.api.TeleportSpell
import com.projectx.script.api.localPlayer
import com.projectx.traversal.Traversal
import com.projectx.traversal.TraversalBuilder
import com.projectx.traversal.TraversalDriver
import com.projectx.traversal.TraversalNode
import com.projectx.traversal.TraversalOutcome
import com.projectx.traversal.nodes.DramenStaff
import com.projectx.traversal.nodes.FairyRingNode
import com.projectx.traversal.nodes.TraversalSupport
import com.projectx.traversal.pathing.NavGoalSet
import com.projectx.traversal.pathing.NavMacro
import com.projectx.traversal.pathing.NavOptions
import com.projectx.traversal.pathing.NavPathClient
import com.projectx.traversal.pathing.NavPathUnavailableException
import com.projectx.traversal.pathing.NavRequest
import com.projectx.traversal.pathing.NavRoute
import com.projectx.traversal.pathing.NavRouteMapper
import com.projectx.traversal.pathing.PathingTraversal
import com.projectx.traversal.pathing.RequirementsBuilder
import com.projectx.traversal.pathing.RingPermissions
import com.projectx.traversal.pathing.ResultsParseException
import com.projectx.traversal.pathing.Step
import com.projectx.traversal.pathing.mappers.LodestoneMapper
import world.gregs.voidps.type.Tile
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import kotlin.coroutines.cancellation.CancellationException

/**
 * Walks to any tile on the world map along a route from the navpath the launcher runs on this machine.
 *
 * A walk is a [PathingTraversal]: the route's steps become traversal nodes (walks, doors, objects, NPCs, fairy rings,
 * lodestones, spells and teleport items) and the traversal drives them. When a node gives up, the route is asked for
 * again from where the player stands, without the edge behind it; a fairy ring that sets the player down somewhere
 * else is left out of every route for half an hour. With no navpath running, a walk fails and says to start the
 * launcher.
 */
object WebWalker {
    const val DEFAULT_ARRIVE_DISTANCE = 2

    private const val INSTANCE_MIN_X = 6400
    private const val SEARCH_TIMEOUT_MS = 60_000L
    private const val SEARCH_POLL_MS = 50
    private const val DRIVE_POLL_MS = 50
    private const val MAX_RING_REPLANS = 4

    // A ring that drops the player this far from where it was dialled, nowhere near its landing, went elsewhere.
    private const val LANDED_ELSEWHERE_TILES = 20
    private const val RING_LANDING_SLACK = 12

    /** The options every web walk routes with; the switches below are views onto it. */
    val options = NavOptions()

    /**
     * Whether routes may spend teleport items and the Passage of the Abyss. On by default; turn it off in a script
     * that carries teleports for its own use, so the walker never spends a charge or a tab it was keeping.
     */
    var useItemTeleports: Boolean
        get() = options.useItems
        set(value) {
            options.useItems = value
        }

    /**
     * Whether walking may Dive and Surge along straight stretches of the route, as navpathService plans them. On by
     * default; turn it off in a script that saves those cooldowns for something else.
     */
    var useMovementAbilities: Boolean
        get() = options.hops
        set(value) {
            options.hops = value
        }

    /**
     * Whether walking may drink a Powerburst of acceleration from the backpack where a run of Dives and Surges along
     * the route saves several seconds. On by default and inert without the potion; turn it off to keep the doses.
     */
    var useAccelerationPotion: Boolean
        get() = options.useAccelerationPotion
        set(value) {
            options.useAccelerationPotion = value
        }

    /** Routes are always planned on the navpath the launcher runs on this machine; setting this has no effect. */
    @Deprecated("Routes are always planned on the launcher's navpath")
    @Volatile
    var useHostedPlanner: Boolean = true

    private val planner = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "projectx-webwalker").apply { isDaemon = true }
    }

    /** Plans a route off the game thread. */
    @JvmStatic
    @JvmOverloads
    fun findPathAsync(
        startX: Int,
        startY: Int,
        destX: Int,
        destY: Int,
        plane: Int,
        arriveDistance: Int = 0,
    ): CompletableFuture<WebWalkResult> {
        val from = Tile.of(startX, startY, plane)
        val goal = WalkGoal.Near(Tile.of(destX, destY, plane), arriveDistance.coerceAtLeast(0))
        val request = request(from, goal, teleports = false, withoutSpells = emptySet())
        val goals = request?.let { goal.goalSet(from) }
        return CompletableFuture.supplyAsync({ navpath(from, goal, request, goals, teleports = false).first }, planner)
    }

    /**
     * Plans a route and blocks until it is ready. Never call it from a script body, which runs on the game thread;
     * use [findPathAsync], or the suspending [findPath] from Kotlin.
     */
    @JvmStatic
    @JvmOverloads
    fun findPath(startX: Int, startY: Int, destX: Int, destY: Int, plane: Int, arriveDistance: Int = 0): WebWalkResult =
        findPathAsync(startX, startY, destX, destY, plane, arriveDistance).join()

    /** Plans a route from [from] to [destination], suspending the script rather than blocking the game thread. */
    suspend fun findPath(script: Script, from: Tile, destination: Tile, arriveDistance: Int = 0): WebWalkResult =
        findPath(script, from, WalkGoal.Near(destination, arriveDistance.coerceAtLeast(0)))

    internal suspend fun findPath(script: Script, from: Tile, goal: WalkGoal): WebWalkResult {
        if (goal is WalkGoal.Tiles && goal.isEmpty) return WebWalkResult(WebWalkStatus.NO_PATH, "The area holds no tiles")
        // Requirements read varbits, levels, the money pouch and what is carried, which may only be touched here on
        // the game thread; the planner runs on its own thread.
        val permissions = RingPermissions.snapshot()
        val request = request(from, goal, teleports = false, withoutSpells = emptySet())?.copy(exclude = MeasuredLinks.setAsideNow())
        val goals = request?.let { goal.goalSet(from) }
        val future = CompletableFuture.supplyAsync({ planAllowed(from, goal, request, goals, teleports = false, permissions) }, planner)
        script.delayUntil(SEARCH_TIMEOUT_MS, SEARCH_POLL_MS) { future.isDone || script.stopped }
        if (!future.isDone) {
            future.cancel(false)
            return WebWalkResult(WebWalkStatus.STOPPED, "Route planning did not finish")
        }
        return future.join()
    }

    /** Walks until the player stands in [goal]; the route aims at whichever of its tiles is cheapest to reach. */
    internal suspend fun walk(script: Script, goal: WalkGoal, useLodestones: Boolean): WebWalkResult = walk(script, goal, useLodestones) { false }

    /**
     * As [walk], ending early with [WebWalkStatus.STOPPED] once [stop] holds, as a traversal's finished condition. Puts
     * back what a Dramen staff replaced on the way, once the walk is over.
     */
    internal suspend fun walk(script: Script, goal: WalkGoal, useLodestones: Boolean, stop: () -> Boolean): WebWalkResult {
        if (goal is WalkGoal.Tiles && goal.isEmpty) return WebWalkResult(WebWalkStatus.NO_PATH, "The area holds no tiles")
        return RouteWalk(script, goal, useLodestones, stop).run().also { if (DramenStaff.restorePending) DramenStaff.restore(script) }
    }

    /**
     * Walks the player to within [arriveDistance] tiles of [destination], counting diagonals as one. With
     * [useLodestones], a route may begin with a teleport when that gets there sooner: a lodestone, a spell, or - with
     * [useItemTeleports] - a carried teleport item or the Passage of the Abyss.
     */
    suspend fun walk(
        script: Script,
        destination: Tile,
        arriveDistance: Int = DEFAULT_ARRIVE_DISTANCE,
        useLodestones: Boolean = true,
    ): WebWalkResult = walk(script, WalkGoal.Near(destination, arriveDistance.coerceAtLeast(0)), useLodestones)

    /**
     * One web walk: routes once and drives the traversal. The traversal's own re-routes, from where the player stands
     * and without the edges given up on, are the only ones; once they are spent the result goes back to the script.
     */
    private class RouteWalk(
        private val script: Script,
        private val goal: WalkGoal,
        private val useLodestones: Boolean,
        private val stop: () -> Boolean,
    ) {
        private val exclude = HashSet<NavMacro>()
        private val stepByMacro = HashMap<NavMacro, Step>()
        private var path: WebPath? = null
        private var lodestone: Lodestone? = null
        private var ringNode: TraversalNode? = null
        private var ringTakeoff: Tile? = null

        suspend fun run(): WebWalkResult {
            if (script.stopped) return result(WebWalkStatus.STOPPED, "The script stopped while walking")
            if (stop()) return result(WebWalkStatus.STOPPED, "Stopped before arriving")
            val here = tile()
            if (here.x >= INSTANCE_MIN_X || goal.maxX >= INSTANCE_MIN_X) {
                return result(WebWalkStatus.NOT_IN_WORLD, "Web walking does not work inside instances")
            }
            if (goal.contains(here)) return arrived()
            val routing = options.copy().also {
                it.start = here
                it.goal = goal.centre
                it.goalArea = null
                it.teleports = useLodestones
            }
            val goals = goal.goalSet(here)
            val routed = try {
                PathingTraversal.routeNodes<Nothing>(script, routing, goals, exclude, ::appendArrival)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: NavPathUnavailableException) {
                return result(WebWalkStatus.NO_PATH, "No route: ${e.message}")
            } catch (e: PathingTraversal.NoRouteException) {
                return result(WebWalkStatus.NO_PATH, "No route: ${e.message}")
            } catch (e: ResultsParseException) {
                return result(WebWalkStatus.NO_PATH, "No route: an answer that does not parse: ${e.message}")
            } catch (e: RuntimeException) {
                return result(WebWalkStatus.NO_PATH, "No route: ${e.message ?: e.javaClass.simpleName}")
            }
            remember(here, routed)
            val driver = TraversalDriver(
                { goal.contains(tile()) },
                { stop() || script.stopped },
                routed.nodes,
                Traversal.ROUTE_NO_MOVEMENT_TIMEOUT_MILLIS,
            )
            driver.repathWith {
                val start = tile()
                PathingTraversal.reroute<Nothing>(script, routing, goals, exclude, driver.currentNode, ::appendArrival)
                    ?.also { remember(start, it) }?.nodes
            }
            val outcome = drive(driver)
            return when {
                outcome == TraversalOutcome.FINISHED || goal.contains(tile()) -> arrived()
                script.stopped -> result(WebWalkStatus.STOPPED, "The script stopped while walking")
                outcome == TraversalOutcome.INTERRUPTED -> result(WebWalkStatus.STOPPED, "Stopped before arriving")
                else -> result(WebWalkStatus.STUCK, "$outcome on ${driver.currentNode}, short of $goal")
            }
        }

        private fun arrived() = result(WebWalkStatus.ARRIVED, "Arrived at $goal")

        /** Ends the route on the goal itself: the last walk counts as done a few tiles short of its end. */
        private fun appendArrival(builder: TraversalBuilder<Nothing>, routed: NavOptions) {
            builder.walkExact(routed.goal ?: goal.centre, false) { goal.contains(tile()) }
        }

        private suspend fun drive(driver: TraversalDriver): TraversalOutcome {
            while (driver.step(script)) {
                watch(driver)
                script.delay(DRIVE_POLL_MS)
            }
            watch(driver)
            return driver.outcome
        }

        private var lastNode: TraversalNode? = null

        /** Notes a lodestone passed, and turns a fairy ring that landed elsewhere into a re-route without it. */
        private fun watch(driver: TraversalDriver) {
            val node = driver.currentNode
            val previous = lastNode
            if (previous != null && previous !== node) {
                (stepOf(previous) as? Step.Lodestone)?.let { LodestoneMapper.resolve(it.value) }?.let { lodestone = it }
            }
            lastNode = node
            watchRing(driver, node)
        }

        private fun watchRing(driver: TraversalDriver, node: TraversalNode) {
            if (node !is FairyRingNode) {
                ringNode = null
                return
            }
            val ring = stepOf(node) as? Step.FairyRing ?: return
            val here = tile()
            if (ringNode !== node) {
                ringNode = node
                ringTakeoff = here
                return
            }
            val takeoff = ringTakeoff ?: return
            val travelled = here.plane != takeoff.plane || here.getLongestDelta(takeoff) > LANDED_ELSEWHERE_TILES
            val nearLanding = here.plane == ring.to.plane && here.getLongestDelta(ring.to) <= RING_LANDING_SLACK
            if (!travelled || nearLanding || TraversalSupport.isMoving() || TraversalSupport.isAnimating()) return
            val macro = node.navMacro ?: return
            // A ring the account hasn't unlocked sends you to Zanaris instead; dialling it again only loops.
            println("[WebWalk] $node left us at $here, not its destination; leaving it out for now")
            MeasuredLinks.setAside(macro)
            exclude += macro
            ringTakeoff = null
            if (!driver.processor.requestRepath()) driver.processor.requestFail("fairy ring to ${ring.code} landed elsewhere")
        }

        private fun stepOf(node: TraversalNode): Step? = node.navMacro?.let(stepByMacro::get)

        private fun remember(start: Tile, routed: PathingTraversal.Routed) {
            for (step in routed.route.steps) step.macro?.let { stepByMacro[it] = step }
            path = (runCatching { NavRouteMapper.map(start, routed.route) }.getOrNull() as? NavRouteMapper.Mapped.Route)?.path ?: path
        }

        private fun result(status: WebWalkStatus, message: String) = WebWalkResult(status, message, path, lodestone)

        private fun tile(): Tile = TraversalSupport.serverTileOrNull() ?: localPlayer.tile
    }

    /** Plans, leaving out and planning again around any fairy ring on the route this account can't use. */
    private fun planAllowed(
        from: Tile,
        goal: WalkGoal,
        request: NavRequest?,
        goals: NavGoalSet?,
        teleports: Boolean,
        permissions: RingPermissions,
    ): WebWalkResult {
        var asked = request
        repeat(MAX_RING_REPLANS) {
            val (planned, route) = navpath(from, goal, asked, goals, teleports)
            val ring = route?.let { permissions.forbiddenRing(it.steps) } ?: return planned
            val macro = ring.macro ?: return WebWalkResult(WebWalkStatus.NO_PATH, "The route needs the fairy ring to ${ring.code}, which this account can't use")
            asked = asked?.let { it.copy(exclude = it.exclude + macro) }
        }
        return WebWalkResult(WebWalkStatus.NO_PATH, "Every route found needs a fairy ring this account can't use")
    }

    /** What this account can use on the way, read on the game thread; null inside an instance or when it can't be read. */
    private fun request(from: Tile, goal: WalkGoal, teleports: Boolean, withoutSpells: Set<TeleportSpell>): NavRequest? {
        if (from.x >= INSTANCE_MIN_X || goal.maxX >= INSTANCE_MIN_X) return null
        val routing = options.copy().also { it.teleports = teleports }
        return runCatching { RequirementsBuilder.request(from, goal.centre, routing, withoutSpells) }.getOrNull()
    }

    /** The result, with the route it was planned from when one was. */
    private fun navpath(from: Tile, goal: WalkGoal, request: NavRequest?, goals: NavGoalSet?, teleports: Boolean): Pair<WebWalkResult, NavRoute?> {
        if (from.x >= INSTANCE_MIN_X || goal.maxX >= INSTANCE_MIN_X) {
            return WebWalkResult(WebWalkStatus.NOT_IN_WORLD, "Web walking does not work inside instances") to null
        }
        request ?: return WebWalkResult(WebWalkStatus.NO_PATH, "Couldn't read what this account can use on the way") to null
        val outcome = runCatching { NavPathClient.local.plan(request, goals, teleports) }
            .getOrElse { NavPathClient.Outcome.Failed("route planning failed: ${it.javaClass.simpleName}") }
        return when (outcome) {
            is NavPathClient.Outcome.Planned -> WebWalkResult(WebWalkStatus.PATH_FOUND, "Route found", outcome.path) to outcome.route
            is NavPathClient.Outcome.Failed -> WebWalkResult(WebWalkStatus.NO_PATH, "No route: ${outcome.reason}") to null
        }
    }
}
