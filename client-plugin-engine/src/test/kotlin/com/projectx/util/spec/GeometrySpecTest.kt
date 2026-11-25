package com.projectx.util.spec

import com.projectx.util.ANGLE_DIRECTION_DELTA
import com.projectx.util.componentIdFromHash
import com.projectx.util.getAngleTo
import com.projectx.util.getDirDelta
import com.projectx.util.hashFromInterface
import com.projectx.util.interfaceIdFromHash
import world.gregs.voidps.type.Tile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GeometrySpecTest {

    @Test
    fun `angles are fourteen bit with south at zero turning through west`() {
        assertEquals(0, getAngleTo(0, -1))
        assertEquals(4096, getAngleTo(-1, 0))
        assertEquals(8192, getAngleTo(0, 1))
        assertEquals(12288, getAngleTo(1, 0))
        assertEquals(0, getAngleTo(0, -50))
        assertEquals(4096, getAngleTo(-50, 0))
    }

    @Test
    fun `diagonal angles`() {
        assertEquals(2048, getAngleTo(-1, -1))
        assertEquals(6144, getAngleTo(-1, 1))
        assertEquals(10240, getAngleTo(1, 1))
        assertEquals(14336, getAngleTo(1, -1))
    }

    @Test
    fun `angles always fall in the fourteen bit range`() {
        for (dx in -20..20) for (dy in -20..20) {
            val angle = getAngleTo(dx, dy)
            assertTrue(angle in 0..16383, "($dx, $dy) gave $angle")
        }
        assertEquals(8192, getAngleTo(0, 0))
    }

    @Test
    fun `tile angle is measured from the first tile to the second`() {
        val from = Tile(3200, 3200)
        assertEquals(8192, getAngleTo(from, Tile(3200, 3210)))
        assertEquals(0, getAngleTo(from, Tile(3200, 3190, 1)))
        assertEquals(12288, getAngleTo(from, Tile(3201, 3200)))
        assertEquals(getAngleTo(-3, 7), getAngleTo(Tile(10, 10), Tile(7, 17)))
    }

    @Test
    fun `direction deltas list the eight neighbours from south anticlockwise through west`() {
        val expected = listOf(0 to -1, -1 to -1, -1 to 0, -1 to 1, 0 to 1, 1 to 1, 1 to 0, 1 to -1)
        assertEquals(expected, ANGLE_DIRECTION_DELTA.toList())
    }

    @Test
    fun `direction delta picks the 2048 wide bucket the angle starts in`() {
        assertEquals(0 to -1, getDirDelta(0))
        assertEquals(0 to -1, getDirDelta(2047))
        assertEquals(-1 to -1, getDirDelta(2048))
        assertEquals(-1 to 0, getDirDelta(4096))
        assertEquals(0 to 1, getDirDelta(8192))
        assertEquals(1 to 0, getDirDelta(12288))
        assertEquals(1 to -1, getDirDelta(16383))
        assertNull(getDirDelta(16384))
        assertNull(getDirDelta(-1))
    }

    @Test
    fun `cardinal and diagonal steps map back to their own delta`() {
        for ((dx, dy) in ANGLE_DIRECTION_DELTA) assertEquals(dx to dy, getDirDelta(getAngleTo(dx, dy)))
    }

    @Test
    fun `interface hash packs the interface above sixteen bits`() {
        assertEquals(1477 * 65536 + 25, hashFromInterface(1477, 25))
        assertEquals(0, hashFromInterface(0, 0))
        assertEquals(65535, hashFromInterface(0, 65535))
        assertEquals(1477, interfaceIdFromHash(96_796_697))
        assertEquals(25, componentIdFromHash(96_796_697))
    }

    @Test
    fun `interface hash round trips for real interface and component ids`() {
        for (iface in listOf(0, 1, 149, 1477, 1670, 32767)) for (component in listOf(0, 1, 7, 255, 4000, 65535)) {
            val hash = hashFromInterface(iface, component)
            assertEquals(iface, interfaceIdFromHash(hash))
            assertEquals(component, componentIdFromHash(hash))
        }
    }

    @Test
    fun `out of range parts carry or borrow instead of being masked`() {
        val minusOne = hashFromInterface(1477, -1)
        assertEquals(1476, interfaceIdFromHash(minusOne))
        assertEquals(65535, componentIdFromHash(minusOne))
        assertEquals(1478, interfaceIdFromHash(hashFromInterface(1477, 65536)))
        assertEquals(-1, interfaceIdFromHash(-1))
        assertEquals(65535, componentIdFromHash(-1))
        assertEquals(40000 - 65536, interfaceIdFromHash(hashFromInterface(40000, 3)))
        assertEquals(3, componentIdFromHash(hashFromInterface(40000, 3)))
    }
}
