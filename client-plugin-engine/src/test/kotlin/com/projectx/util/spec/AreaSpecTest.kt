package com.projectx.util.spec

import com.projectx.util.Area
import world.gregs.voidps.type.Tile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class AreaSpecTest {

    @Test
    fun `corner constructors order the corners`() {
        val area = Area(3210, 3220, 3200, 3215)
        assertEquals(3200.toShort(), area.minX)
        assertEquals(3210.toShort(), area.maxX)
        assertEquals(3215.toShort(), area.minY)
        assertEquals(3220.toShort(), area.maxY)
        assertEquals(area, Area(3200, 3215, 3210, 3220))
        assertEquals(area, Area(Tile(3210, 3215), Tile(3200, 3220, 2)))
    }

    @Test
    fun `the primary constructor keeps values as given`() {
        val inverted = Area(10.toShort(), 5.toShort(), 1.toShort(), 2.toShort())
        assertEquals(10.toShort(), inverted.minX)
        assertEquals(5.toShort(), inverted.maxX)
        assertFalse(inverted.inside(Tile(7, 1)))
        assertEquals(0, inverted.toTiles().size)
    }

    @Test
    fun `components follow min x, max x, min y, max y`() {
        val (a, b, c, d) = Area(1, 2, 3, 4)
        assertEquals(listOf<Short>(1, 3, 2, 4), listOf(a, b, c, d))
    }

    @Test
    fun `inside is inclusive on every edge and ignores the plane`() {
        val area = Area(100, 200, 102, 203)
        assertTrue(area.inside(Tile(100, 200)))
        assertTrue(area.inside(Tile(102, 203)))
        assertTrue(area.inside(Tile(101, 201, 3)))
        assertFalse(area.inside(Tile(99, 200)))
        assertFalse(area.inside(Tile(103, 200)))
        assertFalse(area.inside(Tile(100, 199)))
        assertFalse(area.inside(Tile(100, 204)))
    }

    @Test
    fun `a single tile area holds exactly that tile`() {
        val area = Area(Tile(50, 60), Tile(50, 60))
        assertTrue(area.inside(Tile(50, 60)))
        assertEquals(listOf(Tile(50, 60, 0)), area.toTiles().toList())
    }

    @Test
    fun `tiles run column by column on plane zero`() {
        val tiles = Area(10, 20, 11, 22).toTiles().toList()
        val expected = listOf(
            Tile(10, 20), Tile(10, 21), Tile(10, 22),
            Tile(11, 20), Tile(11, 21), Tile(11, 22),
        )
        assertEquals(expected, tiles)
        assertTrue(tiles.all { it.level == 0 })
    }

    @Test
    fun `tile count is the inclusive width times height`() {
        assertEquals(11 * 6, Area(3200, 3200, 3210, 3205).toTiles().size)
    }

    @Test
    fun `text forms`() {
        val area = Area(3210, 3220, 3200, 3215)
        assertEquals("[3200, 3215] x [3210, 3220]", area.toString())
        assertEquals("new Area(new WorldTile(3200, 3215), new WorldTile(3210, 3220))", area.toCopyString())
    }

    @Test
    fun `equality and copy are by value`() {
        val area = Area(1, 2, 3, 4)
        assertEquals(area.hashCode(), Area(3, 4, 1, 2).hashCode())
        assertNotEquals(area, Area(1, 2, 3, 5))
        assertEquals(Area(1, 2, 9, 4), area.copy(maxX = 9))
    }

    @Test
    fun `coordinates are stored as shorts`() {
        val area = Area(40_000, 0, 40_001, 0)
        assertEquals((40_000).toShort(), area.minX)
        assertEquals((40_001).toShort(), area.maxX)
    }
}
