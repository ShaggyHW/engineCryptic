package com.projectx.traversal.hops

import com.projectx.pathfinder.StepRule
import com.projectx.pathfinder.Steps
import com.projectx.pathfinder.WorldCollision
import com.projectx.traversal.local.LocalNav
import world.gregs.voidps.collision.CollisionFlag
import world.gregs.voidps.type.Tile

/**
 * The live map hop learning reads: the walking rule, which tiles the map covers, which of those hold live (not cache)
 * state, and each tile's walk flags in the [WalkFlags] layout the observation windows store. Swapped by
 * [installForTests], and by whatever provides a better live map.
 */
internal interface HopMap {
    fun walk(): StepRule

    /** The tile is inside the map at all. */
    fun contains(x: Int, y: Int, plane: Int): Boolean

    /** Covered and holding live state: a tile hops may be learned from. */
    fun known(x: Int, y: Int, plane: Int): Boolean

    /** [WalkFlags] bits of the tile; only read where [contains] holds. */
    fun flags(x: Int, y: Int, plane: Int): Int

    companion object {
        @Volatile
        var current: HopMap = LiveHopMap
            private set

        fun install(map: HopMap) {
            current = map
        }

        fun installForTests(map: HopMap?) {
            current = map ?: LiveHopMap
        }
    }
}

/**
 * The walk flags of an observation window, the bit layout hop data is shared in (`"f"` of an observation line):
 * walls on the four sides, wall corners, then a blocking loc, floor and floor decoration.
 */
internal object WalkFlags {
    const val WALL_W = 1 shl 0
    const val WALL_S = 1 shl 1
    const val WALL_E = 1 shl 2
    const val WALL_N = 1 shl 3
    const val CORNER_NW = 1 shl 4
    const val CORNER_SW = 1 shl 5
    const val CORNER_SE = 1 shl 6
    const val CORNER_NE = 1 shl 7
    const val BLOCK_LOC = 1 shl 8
    const val BLOCK_FLOOR = 1 shl 9
    const val BLOCK_DECOR = 1 shl 10
    const val BLOCK_ANY = BLOCK_LOC or BLOCK_FLOOR or BLOCK_DECOR
    const val WALK_MASK = 0x7FF

    private val FROM_COLLISION = intArrayOf(
        CollisionFlag.WALL_WEST, WALL_W,
        CollisionFlag.WALL_SOUTH, WALL_S,
        CollisionFlag.WALL_EAST, WALL_E,
        CollisionFlag.WALL_NORTH, WALL_N,
        CollisionFlag.WALL_NORTH_WEST, CORNER_NW,
        CollisionFlag.WALL_SOUTH_WEST, CORNER_SW,
        CollisionFlag.WALL_SOUTH_EAST, CORNER_SE,
        CollisionFlag.WALL_NORTH_EAST, CORNER_NE,
        CollisionFlag.OBJECT, BLOCK_LOC,
        CollisionFlag.FLOOR, BLOCK_FLOOR,
        CollisionFlag.FLOOR_DECORATION, BLOCK_DECOR,
    )

    fun fromCollision(flags: Int): Int {
        var out = 0
        for (i in FROM_COLLISION.indices step 2) if (flags and FROM_COLLISION[i] != 0) out = out or FROM_COLLISION[i + 1]
        return out
    }
}

/** [WorldCollision] with the scene's door changes, trusted where [LocalNav] trusts it. */
internal object LiveHopMap : HopMap {
    private const val INSTANCE_MIN_X = 6400

    override fun walk(): StepRule = Steps.live()

    override fun contains(x: Int, y: Int, plane: Int): Boolean =
        x in 1 until INSTANCE_MIN_X && WorldCollision.getFlags(x, y, plane) != -1

    override fun known(x: Int, y: Int, plane: Int): Boolean = LocalNav.trusted(Tile.of(x, y, plane))

    override fun flags(x: Int, y: Int, plane: Int): Int = WalkFlags.fromCollision(WorldCollision.getFlags(x, y, plane))
}
