package com.projectx.traversal.nodes

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FairyRingTravelLogTest {

    @Test
    fun `a single code row reads as its dial code`() {
        assertEquals("aip", FairyRingNode.travelLogCode("A I P"))
        assertEquals("bks", FairyRingNode.travelLogCode("B K S"))
        assertEquals("dis", FairyRingNode.travelLogCode("<col=ffffff>D I S</col>"))
    }

    @Test
    fun `a multi-hop route is not one code`() {
        assertNull(FairyRingNode.travelLogCode("A I R    D L R    D J Q    <col=ffffff>A J S</col>"))
        assertNull(FairyRingNode.travelLogCode("D I R    <col=ffffff>A K S</col>"))
    }

    @Test
    fun `blanks and letters off the dials are no code`() {
        assertNull(FairyRingNode.travelLogCode(""))
        assertNull(FairyRingNode.travelLogCode(null))
        assertNull(FairyRingNode.travelLogCode("A B C"))
    }

    @Test
    fun `ring codes resolve case-blind and only known ones do`() {
        assertEquals(FairyRing.ZANARIS, FairyRing.forCode("AIP"))
        assertNull(FairyRing.forCode("zzz"))
    }
}
