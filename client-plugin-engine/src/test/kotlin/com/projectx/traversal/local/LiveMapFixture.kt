package com.projectx.traversal.local

import com.projectx.game.collision.CollisionFlags
import com.projectx.game.collision.CollisionGrid
import com.projectx.game.collision.CollisionMap
import com.projectx.game.collision.CollisionStatus
import world.gregs.voidps.type.Tile

/**
 * A synthetic live map in window-local coordinates, published through [CollisionMap.installForTests] with its masks
 * derived exactly as the worker derives them.
 */
internal class LiveMapFixture {
    private val grid = CollisionGrid().apply { reset(OX, OY) }

    /** A wall on the east edge of local (x, y), and the matching west edge of (x + 1, y). */
    fun wallEast(x: Int, y: Int, projectile: Boolean = true) = apply {
        set(x, y, CollisionFlags.WALL_E, projectile)
        set(x + 1, y, CollisionFlags.WALL_W, projectile)
    }

    /** A wall on the north edge of local (x, y), and the matching south edge of (x, y + 1). */
    fun wallNorth(x: Int, y: Int, projectile: Boolean = true) = apply {
        set(x, y, CollisionFlags.WALL_N, projectile)
        set(x, y + 1, CollisionFlags.WALL_S, projectile)
    }

    fun block(x: Int, y: Int) = apply { set(x, y, CollisionFlags.BLOCK_LOC, true) }

    private fun set(x: Int, y: Int, bits: Int, projectile: Boolean) {
        val cell = y * CollisionGrid.DIM + x
        grid.flags[0][cell] = grid.flags[0][cell] or bits or (if (projectile) bits shl CollisionFlags.PROJ_SHIFT else 0)
    }

    fun install(status: CollisionStatus = CollisionStatus.OK, squaresUnloaded: Int = 0, squaresStatic: Int = 0) {
        grid.deriveMasks()
        CollisionMap.installForTests(
            OX, OY, version++, status,
            Array(CollisionGrid.PLANES) { grid.masks[it].copyOf() },
            Array(CollisionGrid.PLANES) { grid.flags[it].copyOf() },
            squaresUnloaded, squaresStatic,
        )
    }

    companion object {
        const val OX = 3136
        const val OY = 3136
        private var version = 1

        fun t(x: Int, y: Int): Tile = Tile.of(OX + x, OY + y, 0)

        /** A wall along x = 100|101 from y = 80 to 120, the edge at [gapY] left open (-1 for none). */
        fun wallWithGap(gapY: Int): LiveMapFixture {
            val map = LiveMapFixture()
            for (y in 80..120) if (y != gapY) map.wallEast(100, y)
            return map
        }

        fun uninstall() {
            CollisionMap.installForTests(0, 0, 0, CollisionStatus.NOT_READY, null, null)
            CollisionMap.installJumpForTests(null)
        }
    }
}
