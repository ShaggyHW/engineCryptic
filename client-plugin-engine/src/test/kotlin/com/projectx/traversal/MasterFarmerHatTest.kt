package com.projectx.traversal

import world.gregs.voidps.type.Tile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MasterFarmerHatTest {

    @Test
    fun `the chooser line gives the habitat count`() {
        assertEquals(31, MasterFarmerHat.parseHabitatLine("3. Herblore Habitat (31)"))
        assertEquals(0, MasterFarmerHat.parseHabitatLine("3. Herblore Habitat (0)"))
        assertEquals(MasterFarmerHat.UNLIMITED, MasterFarmerHat.parseHabitatLine("3. Herblore Habitat (unlimited)"))
    }

    @Test
    fun `other chooser lines give no count`() {
        assertEquals(MasterFarmerHat.UNKNOWN, MasterFarmerHat.parseHabitatLine("1. Morytania allotment (3)"))
        assertEquals(MasterFarmerHat.UNKNOWN, MasterFarmerHat.parseHabitatLine("6. Cancel"))
        assertEquals(MasterFarmerHat.UNKNOWN, MasterFarmerHat.parseHabitatLine(null))
    }

    @Test
    fun `the landing box takes a scattered landing on its plane only`() {
        assertTrue(MasterFarmerHat.inLandingBox(Tile.of(2953, 2933, 0)))
        assertTrue(MasterFarmerHat.inLandingBox(Tile.of(2949, 2937, 0)))
        assertFalse(MasterFarmerHat.inLandingBox(Tile.of(2953, 2933, 1)))
        assertFalse(MasterFarmerHat.inLandingBox(Tile.of(2276, 3311, 1)))
    }

    @Test
    fun `the restock buys what slots and coins allow`() {
        assertEquals(2, JujuSpiritbag.buyCount(5, 10_000))
        assertEquals(1, JujuSpiritbag.buyCount(1, 10_000))
        assertEquals(1, JujuSpiritbag.buyCount(5, 3_999))
        assertEquals(0, JujuSpiritbag.buyCount(5, 1_999))
        assertEquals(0, JujuSpiritbag.buyCount(0, 10_000))
        assertEquals(0, JujuSpiritbag.buyCount(-1, 10_000))
    }
}
