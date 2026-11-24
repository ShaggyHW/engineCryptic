package com.projectx.game.nxt.entity

import world.gregs.voidps.type.Direction
import world.gregs.voidps.type.Tile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BearingsTest {

    @Test
    fun `a yaw of zero faces south and a quarter turn faces west`() {
        assertEquals(180, Bearings.ofYaw(1f, 0f))
        assertEquals(270, Bearings.ofYaw(0f, 1f))
        assertEquals(0, Bearings.ofYaw(-1f, 0f))
        assertEquals(90, Bearings.ofYaw(0f, -1f))
    }

    @Test
    fun `a scaled transform answers and a degenerate one does not`() {
        assertEquals(270, Bearings.ofYaw(0f, 3.5f))
        assertEquals(-1, Bearings.ofYaw(0f, 0f))
    }

    @Test
    fun `bearing between tiles is clockwise from north`() {
        val here = Tile.of(3200, 3200, 0)
        assertEquals(0, Bearings.between(here, Tile.of(3200, 3205, 0)))
        assertEquals(90, Bearings.between(here, Tile.of(3205, 3200, 0)))
        assertEquals(225, Bearings.between(here, Tile.of(3195, 3195, 0)))
        assertEquals(-1, Bearings.between(here, here))
    }

    @Test
    fun `difference is the smaller angle and direction snaps to the nearest octant`() {
        assertEquals(20, Bearings.difference(350, 10))
        assertEquals(180, Bearings.difference(0, 180))
        assertEquals(Direction.NORTH, Bearings.direction(350.0))
        assertEquals(Direction.EAST, Bearings.direction(100.0))
        assertEquals(Direction.NORTH_WEST, Bearings.direction(-45.0))
    }

    @Test
    fun `the server tile is the newest tile-centre waypoint`() {
        val queue = floatArrayOf(
            3200 * 512f + 256f, 3200 * 512f + 256f,
            3201 * 512f + 256f, 3201 * 512f + 256f,
            3201 * 512f + 512f, 3201 * 512f + 512f,
        )
        assertEquals(3201 to 3201, lastQueuedTileCentre(queue))
    }

    @Test
    fun `a queue of half steps or junk has no server tile`() {
        assertNull(lastQueuedTileCentre(floatArrayOf(3201 * 512f + 512f, 3201 * 512f, -1f, 5f)))
        assertNull(lastQueuedTileCentre(FloatArray(0)))
    }
}
