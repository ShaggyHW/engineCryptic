package com.projectx.script.api

import world.gregs.voidps.gameval.Gameval
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PrayerFlagsTest {

    @Test
    fun `every prayer's flag is a varbit the game names`() {
        val unknown = PrayerBook.entries.flatMap { book ->
            PrayerFlags.entries(book).values.filter { Gameval.id(Gameval.VARBIT, it) == null }
        }
        assertTrue(unknown.isEmpty(), "no varbit named: $unknown")
    }

    @Test
    fun `both books are covered in full`() {
        assertEquals(46, PrayerFlags.entries(PrayerBook.NORMAL).size, "19 single prayers and 9 tiered ones of three")
        assertEquals(45, PrayerFlags.entries(PrayerBook.CURSES).size)
    }

    @Test
    fun `the tiers of a prayer share its flag`() {
        assertEquals(PrayerFlags.varbitName(PrayerBook.NORMAL, "Thick Skin"), PrayerFlags.varbitName(PrayerBook.NORMAL, "Steel Skin"))
        assertEquals("combatv2_prayer_piety", PrayerFlags.varbitName(PrayerBook.NORMAL, "Piety"))
        assertEquals("curse_sorrow", PrayerFlags.varbitName(PrayerBook.CURSES, "Sorrow"))
    }
}
