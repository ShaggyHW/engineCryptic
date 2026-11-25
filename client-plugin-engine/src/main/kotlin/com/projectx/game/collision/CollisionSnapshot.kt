package com.projectx.game.collision

import com.projectx.game.collision.CollisionGrid.Companion.DIM
import com.projectx.game.collision.CollisionGrid.Companion.PLANES

internal enum class CollisionStatus {
    /** Nothing published, or the player and the world are gone (the last grid is kept, frozen). */
    NOT_READY,
    OK,

    /** Some square's terrain is unknown or one of its loc walks was cut short. */
    DEGRADED,

    /** An instanced region: live locs only, and no cache to check the terrain against. */
    INSTANCE,
}

/**
 * The worker's publish header. [squaresUnloaded] counts the window squares the client has not loaded (a square that
 * does not exist is not counted); [squaresStatic] how many of those are served from static data, and [staticMask]
 * which, bit `(squareY - centreY + 1) * 3 + (squareX - centreX + 1)`.
 */
internal data class CollisionHeader(
    val originX: Int,
    val originY: Int,
    val version: Int,
    val status: CollisionStatus,
    val buildMicros: Int = 0,
    val walkMicros: Int = 0,
    val locCount: Int = 0,
    val squaresMissingTerrain: Int = 0,
    val squaresTruncated: Int = 0,
    val unresolvedDefs: Int = 0,
    val builtAtMs: Long = 0L,
    val squaresUnloaded: Int = 0,
    val squaresStatic: Int = 0,
    val staticMask: Int = 0,
) {
    val isPublished: Boolean get() = version > 0

    /** Window squares with neither live nor static data. */
    val squaresUncovered: Int get() = (squaresUnloaded - squaresStatic).coerceAtLeast(0)

    val maxX: Int get() = originX + DIM - 1
    val maxY: Int get() = originY + DIM - 1

    /** The window is centred on the square of ([x], [y]): the worker has caught up with the player. */
    fun centredOn(x: Int, y: Int): Boolean = version > 0 && x shr 6 == (originX shr 6) + 1 && y shr 6 == (originY shr 6) + 1

    fun contains(x: Int, y: Int, plane: Int): Boolean =
        version > 0 && plane in 0 until PLANES && x >= originX && x < originX + DIM && y >= originY && y < originY + DIM

    companion object {
        val NONE = CollisionHeader(0, 0, 0, CollisionStatus.NOT_READY)
    }
}

/** The locs one window square stamped into a publish, for asking which of them block a step. */
internal class SquareLocs(val locs: List<CollisionLoc>, val ids: IntArray)

/** One loc that stamps walk bits on the cells of a step: its from cell, its to cell, and a diagonal's two side cells. */
internal data class CollisionBlocker(
    val id: Int,
    val shape: Int,
    val rotation: Int,
    val clipType: Int,
    val blocksProjectile: Boolean,
    val bitsFrom: Int,
    val bitsTo: Int,
    val bitsSides: Int,
)

/** A jump of the local tile the worker saw: when (wall clock) and the tile it landed on. */
internal data class CollisionJump(val atMs: Long, val x: Int, val y: Int, val plane: Int)

/** One published version of the live map, all four planes. Immutable: grab one for a burst of queries. */
internal class CollisionSnapshot(
    val header: CollisionHeader,
    private val masks: Array<ByteArray>?,
    private val flags: Array<IntArray>?,
    val squares: List<SquareLocs> = emptyList(),
) {
    val version: Int get() = header.version
    val originX: Int get() = header.originX
    val originY: Int get() = header.originY
    val isAvailable: Boolean get() = masks != null

    fun contains(x: Int, y: Int, plane: Int): Boolean = masks != null && header.contains(x, y, plane)

    private fun index(x: Int, y: Int): Int = (y - header.originY) * DIM + (x - header.originX)

    /** The move bits of a tile; 0 off the window. */
    fun mask(x: Int, y: Int, plane: Int): Int = if (contains(x, y, plane)) masks!![plane][index(x, y)].toInt() and 0xFF else 0

    /** The raw flag word of a tile; 0 off the window, which is unknown rather than open. */
    fun flags(x: Int, y: Int, plane: Int): Int = if (contains(x, y, plane)) flags!![plane][index(x, y)] else 0

    fun canMove(x: Int, y: Int, plane: Int, moveBit: Int): Boolean = mask(x, y, plane) and moveBit != 0

    fun isStatic(x: Int, y: Int, plane: Int): Boolean = flags(x, y, plane) and CollisionFlags.STATIC != 0

    fun isBlocked(x: Int, y: Int, plane: Int): Boolean = flags(x, y, plane) and CollisionFlags.BLOCK_ANY != 0

    fun walls(x: Int, y: Int, plane: Int): Int = flags(x, y, plane) and CollisionFlags.WALL_ANY

    fun corners(x: Int, y: Int, plane: Int): Int = flags(x, y, plane) and CollisionFlags.CORNER_ANY

    fun planeMasks(plane: Int): ByteArray? = masks?.getOrNull(plane)

    fun planeFlags(plane: Int): IntArray? = flags?.getOrNull(plane)

    internal fun withHeader(header: CollisionHeader) = CollisionSnapshot(header, masks, flags, squares)

    companion object {
        val EMPTY = CollisionSnapshot(CollisionHeader.NONE, null, null)
    }
}
