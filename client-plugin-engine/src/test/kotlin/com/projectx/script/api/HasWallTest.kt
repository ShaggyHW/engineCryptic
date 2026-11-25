package com.projectx.script.api

import com.projectx.traversal.local.LiveMapFixture
import com.projectx.traversal.local.LiveMapFixture.Companion.t
import world.gregs.voidps.type.Direction
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HasWallTest {

    @AfterTest
    fun cleanup() = LiveMapFixture.uninstall()

    @Test
    fun `a wall closes the edge on both of its sides`() {
        LiveMapFixture().wallEast(100, 100).wallNorth(50, 50).install()

        assertTrue(hasWall(t(100, 100), Direction.EAST))
        assertTrue(hasWall(t(101, 100), Direction.WEST))
        assertTrue(hasWall(t(50, 50), Direction.NORTH))
        assertTrue(hasWall(t(50, 51), Direction.SOUTH))
        assertFalse(hasWall(t(100, 100), Direction.WEST))
        assertFalse(hasWall(t(100, 100), Direction.NORTH))
    }

    @Test
    fun `a blocked neighbour is no wall, and diagonals never are`() {
        LiveMapFixture().block(11, 10).wallEast(10, 10).install()

        assertFalse(hasWall(t(11, 11), Direction.WEST))
        assertFalse(hasWall(t(10, 10), Direction.NORTH_EAST))
        assertFalse(hasWall(t(10, 10), Direction.NONE))
    }
}
