package com.projectx.traversal.pathing

import com.projectx.script.api.localPlayer
import kotlinx.coroutines.future.await
import world.gregs.voidps.type.Tile
import java.util.concurrent.CompletableFuture
import kotlin.math.max

/**
 * "Can I get there?" asked of navpath, two ways. [reachable] (`GET /reachable`) answers whether the player can walk
 * there, with no doors, shortcuts or teleports, in milliseconds. [playerCanReach] (`POST /route`) answers whether this
 * account can get there at all, by its levels and items, at the cost of a path search per probe.
 *
 * An object's or NPC's own tile is usually blocked, so both probe a footprint and the ring around it, nearest the
 * start first, and stop at the first yes. Standing on a probed tile is a yes without asking. A navpath that cannot
 * be reached ends the fan-out at once and answers no.
 *
 * The suspending forms run the requests off the game thread; the blocking ones must never be called on it.
 */
object Reachability {
    const val DEFAULT_PADDING = 1
    const val MAX_PROBES = 48
    const val MAX_ROUTE_PROBES = 12

    var client: NavPathClient = NavPathClient.local

    fun check(from: Tile, goal: Tile): ReachableResult = client.reachable(from, goal)

    fun reachableBlocking(from: Tile, southWest: Tile, sizeX: Int = 1, sizeY: Int = 1, padding: Int = 0): Boolean =
        probe(from, southWest, sizeX, sizeY, padding, MAX_PROBES, null, 0)

    suspend fun reachable(from: Tile, goal: Tile, padding: Int = 0): Boolean = reachable(from, goal, 1, 1, padding)

    suspend fun reachable(from: Tile, southWest: Tile, sizeX: Int, sizeY: Int, padding: Int): Boolean =
        offThread { reachableBlocking(from, southWest, sizeX, sizeY, padding) }

    suspend fun reachableFromLocalPlayer(southWest: Tile, sizeX: Int, sizeY: Int, padding: Int): Boolean =
        playerTile()?.let { reachable(it, southWest, sizeX, sizeY, padding) } == true

    /** The account's profile, read now; read it once on the game thread for several destinations. */
    fun currentRequirements(): List<Pair<String, Int>> = runCatching { RequirementsBuilder.requirements(true) }.getOrDefault(emptyList())

    fun playerCanReachBlocking(
        from: Tile,
        southWest: Tile,
        sizeX: Int = 1,
        sizeY: Int = 1,
        padding: Int = 0,
        requirements: List<Pair<String, Int>>,
        seed: Int = routeSeed(),
    ): Boolean = probe(from, southWest, sizeX, sizeY, padding, MAX_ROUTE_PROBES, requirements, seed)

    suspend fun playerCanReach(from: Tile, goal: Tile, padding: Int = 0): Boolean = playerCanReach(from, goal, 1, 1, padding)

    suspend fun playerCanReach(
        from: Tile,
        southWest: Tile,
        sizeX: Int,
        sizeY: Int,
        padding: Int,
        requirements: List<Pair<String, Int>> = currentRequirements(),
    ): Boolean {
        val seed = routeSeed()
        return offThread { playerCanReachBlocking(from, southWest, sizeX, sizeY, padding, requirements, seed) }
    }

    suspend fun playerCanReachFromLocalPlayer(southWest: Tile, sizeX: Int, sizeY: Int, padding: Int): Boolean =
        playerTile()?.let { playerCanReach(it, southWest, sizeX, sizeY, padding) } == true

    /** Whether [tile] is a node of navpath's walk graph; a refused answer reads as no. */
    fun inGraph(tile: Tile): Boolean = try {
        client.tileExists(tile)
    } catch (e: NavPathUnavailableException) {
        throw e
    } catch (rejected: RuntimeException) {
        false
    }

    /**
     * The tiles to route to for the entity standing on [goal]: the tile itself, then the [footprint] square centred on
     * it plus a [padding] ring, nearest [from] first, capped at [maxProbes].
     */
    fun routeGoals(from: Tile, goal: Tile, footprint: Int, padding: Int, maxProbes: Int): List<Tile> {
        if (maxProbes <= 0) return emptyList()
        val span = max(1, footprint) - 1
        val southWest = Tile.of(goal.x - span, goal.y - span, goal.plane)
        val side = 2 * span + 1
        val goals = ArrayList<Tile>(maxProbes)
        goals += goal
        for (tile in candidates(from, southWest, side, side, padding, maxProbes)) {
            if (goals.size >= maxProbes) break
            if (tile !in goals) goals += tile
        }
        return goals
    }

    /** A scene object's goals: the ring nearest [from] first, then the footprint itself, capped at [maxProbes]. */
    fun footprintGoals(from: Tile, southWest: Tile, sizeX: Int, sizeY: Int, padding: Int, maxProbes: Int): List<Tile> {
        if (maxProbes <= 0) return emptyList()
        val width = max(1, sizeX)
        val height = max(1, sizeY)
        val (inside, ring) = candidates(from, southWest, width, height, padding, Int.MAX_VALUE).partition {
            it.x >= southWest.x && it.x < southWest.x + width && it.y >= southWest.y && it.y < southWest.y + height
        }
        return (ring + inside).take(maxProbes)
    }

    /** Chebyshev distance from [from] to the footprint less [padding]: a lower bound on the walk, `<= 0` when there. */
    fun footprintGap(from: Tile, southWest: Tile, sizeX: Int, sizeY: Int, padding: Int): Int {
        val maxX = southWest.x + max(1, sizeX) - 1
        val maxY = southWest.y + max(1, sizeY) - 1
        val dx = max(0, max(southWest.x - from.x, from.x - maxX))
        val dy = max(0, max(southWest.y - from.y, from.y - maxY))
        return max(dx, dy) - max(0, padding)
    }

    /**
     * Routes to the entity on [goal], spending path searches only on tiles in the graph: the first answer [accept]
     * reads as non-null is the result, null when no candidate routes. Surge and Dive stay off.
     */
    fun <T : Any> routeToEntity(
        from: Tile,
        goal: Tile,
        footprint: Int = 1,
        padding: Int = DEFAULT_PADDING,
        maxProbes: Int = MAX_ROUTE_PROBES,
        requirements: List<Pair<String, Int>>,
        seed: Int,
        returnGeometry: Boolean = false,
        accept: (String) -> T?,
    ): T? = routeFirst(from, routeGoals(from, goal, footprint, padding, maxProbes), requirements, seed, returnGeometry, accept)

    fun <T : Any> routeToFootprint(
        from: Tile,
        southWest: Tile,
        sizeX: Int,
        sizeY: Int,
        padding: Int = DEFAULT_PADDING,
        maxProbes: Int = MAX_ROUTE_PROBES,
        requirements: List<Pair<String, Int>>,
        seed: Int,
        returnGeometry: Boolean = false,
        accept: (String) -> T?,
    ): T? = routeFirst(from, footprintGoals(from, southWest, sizeX, sizeY, padding, maxProbes), requirements, seed, returnGeometry, accept)

    /**
     * Of [candidates], the one nearest [from] by walked route length, routing to each through [routeToEntity] in order
     * of straight-line distance; null when none routes. The profile is read on the calling thread.
     */
    suspend fun <T> closestByRoute(from: Tile, candidates: List<T>, tileOf: (T) -> Tile, footprint: (T) -> Int = { 1 }): T? {
        if (candidates.isEmpty()) return null
        val requirements = currentRequirements()
        val seed = routeSeed()
        return offThread {
            var best: T? = null
            var bestLength = Int.MAX_VALUE
            for (candidate in candidates.sortedBy { squaredDistance(from, tileOf(it)) }) {
                val length = try {
                    routeToEntity(from, tileOf(candidate), footprint(candidate), requirements = requirements, seed = seed, accept = ResultsParser::walkedLength)
                } catch (e: NavPathUnavailableException) {
                    return@offThread best
                } ?: continue
                if (length < bestLength) {
                    best = candidate
                    bestLength = length
                }
            }
            best
        }
    }

    /** The tiles a probe visits: the footprint grown by [padding], nearest [from] first, capped at [maxProbes]. */
    fun candidates(from: Tile, southWest: Tile, sizeX: Int, sizeY: Int, padding: Int, maxProbes: Int = MAX_PROBES): List<Tile> {
        val pad = max(0, padding)
        val minX = southWest.x - pad
        val minY = southWest.y - pad
        val maxX = southWest.x + max(1, sizeX) - 1 + pad
        val maxY = southWest.y + max(1, sizeY) - 1 + pad
        val tiles = ArrayList<Tile>((maxX - minX + 1) * (maxY - minY + 1))
        for (x in minX..maxX) for (y in minY..maxY) tiles += Tile.of(x, y, southWest.plane)
        tiles.sortBy { squaredDistance(from, it) }
        return if (tiles.size <= maxProbes) tiles else tiles.subList(0, maxProbes).toList()
    }

    private fun <T : Any> routeFirst(
        from: Tile,
        goals: List<Tile>,
        requirements: List<Pair<String, Int>>,
        seed: Int,
        returnGeometry: Boolean,
        accept: (String) -> T?,
    ): T? {
        for (candidate in goals) {
            if (!inGraph(candidate)) continue
            val raw = try {
                client.route(NavRequest(from, candidate, requirements, seed = seed, onlyActions = !returnGeometry, returnGeometry = returnGeometry))
            } catch (e: NavPathUnavailableException) {
                throw e
            } catch (rejected: RuntimeException) {
                continue
            }
            runCatching { accept(raw) }.getOrNull()?.let { return it }
        }
        return null
    }

    private fun probe(
        from: Tile,
        southWest: Tile,
        sizeX: Int,
        sizeY: Int,
        padding: Int,
        maxProbes: Int,
        requirements: List<Pair<String, Int>>?,
        seed: Int,
    ): Boolean {
        val candidates = candidates(from, southWest, sizeX, sizeY, padding, maxProbes)
        if (candidates.any { it.x == from.x && it.y == from.y && it.plane == from.plane }) return true
        for (candidate in candidates) {
            try {
                if (requirements == null) {
                    if (check(from, candidate).reachable) return true
                } else if (routeExists(from, candidate, requirements, seed)) {
                    return true
                }
            } catch (e: NavPathUnavailableException) {
                return false
            } catch (rejected: RuntimeException) {
                continue
            }
        }
        return false
    }

    /** Any step counts: a journey made by a teleport alone has no move step and is still a way there. */
    private fun routeExists(from: Tile, goal: Tile, requirements: List<Pair<String, Int>>, seed: Int): Boolean {
        val route = ResultsParser.parse(client.route(NavRequest(from, goal, requirements, seed = seed)))
        return route.found && route.steps.isNotEmpty()
    }

    private fun routeSeed(): Int = runCatching { NavPathClient.generateSeed() }.getOrDefault(1)

    private fun playerTile(): Tile? = runCatching { localPlayer.tile }.getOrNull()

    private fun squaredDistance(from: Tile, to: Tile): Long {
        val dx = (to.x - from.x).toLong()
        val dy = (to.y - from.y).toLong()
        return dx * dx + dy * dy
    }

    private suspend fun <T> offThread(block: () -> T): T = CompletableFuture.supplyAsync(block, NavPathClient.executor).await()
}
