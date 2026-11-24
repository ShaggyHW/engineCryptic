package com.projectx.game.collision

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CollisionGridTest {
    private val grid = CollisionGrid().apply { reset(3136, 3136) }

    private fun loc(x: Int, y: Int, shape: Int, rotation: Int, sizeX: Int = 1, sizeY: Int = 1, clipType: Int = 2, proj: Boolean = true) =
        CollisionLoc(x, y, 0, shape, rotation, sizeX, sizeY, clipType, proj)

    private fun mask(x: Int, y: Int) = grid.masks[0][(y - 3136) * CollisionGrid.DIM + (x - 3136)].toInt() and 0xFF

    @Test
    fun `a straight wall is stamped on both sides of its edge`() {
        grid.stamp(loc(3200, 3200, 0, 0))
        assertEquals(CollisionFlags.WALL_W or (CollisionFlags.WALL_W shl CollisionFlags.PROJ_SHIFT), grid.flagsAt(3200, 3200, 0))
        assertTrue(grid.flagsAt(3199, 3200, 0) and CollisionFlags.WALL_E != 0)
        grid.deriveMasks()
        assertEquals(0, mask(3200, 3200) and MoveBits.W)
        assertEquals(0, mask(3199, 3200) and MoveBits.E)
        assertEquals(0, mask(3199, 3201) and MoveBits.SE, "no diagonal through the wall")
    }

    @Test
    fun `an L wall takes its rotation's edge and the next one clockwise`() {
        grid.stamp(loc(3200, 3200, 2, 1))
        assertEquals(CollisionFlags.WALL_N or CollisionFlags.WALL_E, grid.flagsAt(3200, 3200, 0) and CollisionFlags.WALL_ANY)
        assertTrue(grid.flagsAt(3200, 3201, 0) and CollisionFlags.WALL_S != 0)
        assertTrue(grid.flagsAt(3201, 3200, 0) and CollisionFlags.WALL_W != 0)
    }

    @Test
    fun `a corner pillar blocks only the diagonal through it`() {
        grid.stamp(loc(3200, 3200, 3, 1))
        grid.deriveMasks()
        assertTrue(grid.flagsAt(3201, 3201, 0) and CollisionFlags.CORNER_SW != 0)
        assertEquals(0, mask(3200, 3200) and MoveBits.NE)
        assertTrue(mask(3200, 3200) and MoveBits.N != 0)
        assertTrue(mask(3200, 3200) and MoveBits.E != 0)
    }

    @Test
    fun `a footprint swaps its size on odd rotations and blocks every tile`() {
        grid.stamp(loc(3200, 3200, 10, 1, sizeX = 3, sizeY = 1))
        for (y in 3200..3202) assertTrue(grid.flagsAt(3200, y, 0) and CollisionFlags.BLOCK_LOC != 0)
        assertEquals(0, grid.flagsAt(3201, 3200, 0))
    }

    @Test
    fun `ground decor blocks only with clip type 1 and never projectiles, no clip type blocks nothing`() {
        grid.stamp(loc(3200, 3200, 22, 0, clipType = 1))
        grid.stamp(loc(3201, 3200, 22, 0, clipType = 2))
        grid.stamp(loc(3202, 3200, 10, 0, clipType = 0))
        assertEquals(CollisionFlags.BLOCK_DECOR, grid.flagsAt(3200, 3200, 0))
        assertEquals(0, grid.flagsAt(3201, 3200, 0))
        assertEquals(0, grid.flagsAt(3202, 3200, 0))
    }

    @Test
    fun `a bridge column blocks one plane lower and marks every plane`() {
        val terrain = SquareTerrain()
        terrain.set(1, 5, 5, SquareTerrain.BRIDGE or SquareTerrain.BLOCKED)
        terrain.set(0, 5, 5, SquareTerrain.BLOCKED)
        terrain.set(0, 6, 6, SquareTerrain.BLOCKED)
        grid.applyTerrain(50, 50, terrain)
        val x = 50 * 64 + 5
        assertTrue(grid.flagsAt(x, x, 0) and CollisionFlags.BLOCK_FLOOR != 0, "plane 1 blocked lands on plane 0")
        assertEquals(0, grid.flagsAt(x, x, 1) and CollisionFlags.BLOCK_FLOOR)
        assertTrue(grid.flagsAt(x, x, 3) and CollisionFlags.BRIDGE != 0)
        assertTrue(grid.flagsAt(x + 1, x + 1, 0) and CollisionFlags.BLOCK_FLOOR != 0)
        grid.applyTerrain(51, 51, null)
        assertTrue(grid.flagsAt(51 * 64, 51 * 64, 2) and CollisionFlags.TERRAIN_UNKNOWN != 0)
    }

    @Test
    fun `nothing steps out of the window`() {
        grid.deriveMasks()
        assertEquals(0, mask(3136, 3136) and (MoveBits.W or MoveBits.S or MoveBits.SW or MoveBits.NW or MoveBits.SE))
        assertEquals(MoveBits.N or MoveBits.E or MoveBits.NE, mask(3136, 3136))
    }
}
