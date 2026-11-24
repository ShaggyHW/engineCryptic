package com.projectx.game.collision

/** Collision properties of a loc type, as the client holds them after decoding. */
internal data class LocProps(val sizeX: Int, val sizeY: Int, val clipType: Int, val blocksProjectile: Boolean)

internal enum class LiveLocSource { PLACEMENT, SPAWN, REMOVED }

/** One loc read from the client: its tile, its own loc type pointer, and the client's (bridge shifted) plane, 255 under a bridge. */
internal data class LiveLoc(
    val x: Int,
    val y: Int,
    val id: Int,
    val locType: Long,
    val plane: Int,
    val shape: Int,
    val rotation: Int,
    val source: LiveLocSource,
)

/** A walk of one square's locs: null [locs] when the square is not loaded. */
internal class SquareWalk(val locs: List<LiveLoc>?, val truncated: Boolean)

internal data class LocalTile(val x: Int, val y: Int, val plane: Int)

internal sealed interface LocTypeRead {
    data class Read(val props: LocProps) : LocTypeRead
    data object Unreadable : LocTypeRead
    data object OtherId : LocTypeRead
}

/** What the collision worker reads from the running client. Safe to call from the worker thread. */
internal interface LiveWorld {
    fun localTile(): LocalTile?

    /** A cheap identity of the square's placement list; 0 when the square is not loaded (or is a stub). */
    fun placementStamp(squareX: Int, squareY: Int): Long

    /** Every placed loc of the square; shape 23 (the generated half of an L wall) is left out. */
    fun placements(squareX: Int, squareY: Int): SquareWalk

    /** Spawned locs and markers for static locs the client hides or deleted. */
    fun dynamic(squareX: Int, squareY: Int): SquareWalk

    /** The square's terrain settings from memory, null when unreadable. */
    fun terrain(squareX: Int, squareY: Int): SquareTerrain?

    /** The collision properties at [locType], refused when its own id is not [expectId] (a freed type reused). */
    fun locType(locType: Long, expectId: Int): LocTypeRead
}

internal enum class CacheTerrainResult { OK, MISSING, CORRUPT, NO_CACHE }

/** A square as the cache describes it: its placements (doors in their default state) and terrain. */
internal class StaticSquare(val squareX: Int, val squareY: Int, val locs: List<CollisionLoc>, val ids: IntArray, val terrain: SquareTerrain?)

/** The cache side: terrain when memory has none, loc definitions, and whole squares for teleport arrivals. */
internal interface StaticWorld {
    fun terrain(squareX: Int, squareY: Int): Pair<CacheTerrainResult, SquareTerrain?>

    fun locProps(id: Int): LocProps?

    /** Null when the square, its files or any of its loc definitions is missing: a partial square is worse than none. */
    fun square(squareX: Int, squareY: Int): StaticSquare?
}
