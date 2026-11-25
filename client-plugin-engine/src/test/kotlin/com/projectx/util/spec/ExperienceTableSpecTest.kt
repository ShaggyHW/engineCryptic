package com.projectx.util.spec

import com.projectx.util.getLevelForXp
import com.projectx.util.getXpForLevel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ExperienceTableSpecTest {

    private val knownThresholds = mapOf(
        1 to 0,
        2 to 83,
        3 to 174,
        4 to 276,
        5 to 388,
        10 to 1_154,
        20 to 4_470,
        30 to 13_363,
        40 to 37_224,
        50 to 101_333,
        60 to 273_742,
        70 to 737_627,
        80 to 1_986_068,
        90 to 5_346_332,
        92 to 6_517_253,
        99 to 13_034_431,
        100 to 14_391_160,
        110 to 38_737_661,
        120 to 104_273_167,
    )

    @Test
    fun `xp for level follows the standard RuneScape table`() {
        knownThresholds.forEach { (level, xp) -> assertEquals(xp, getXpForLevel(level), "level $level") }
    }

    @Test
    fun `levels below one need no xp`() {
        assertEquals(0, getXpForLevel(0))
        assertEquals(0, getXpForLevel(-5))
    }

    @Test
    fun `the table strictly increases`() {
        for (level in 2..120) assertTrue(getXpForLevel(level) > getXpForLevel(level - 1), "level $level")
    }

    @Test
    fun `level for xp is the highest level whose threshold has been reached`() {
        assertEquals(1, getLevelForXp(0))
        assertEquals(1, getLevelForXp(82))
        assertEquals(2, getLevelForXp(83))
        assertEquals(2, getLevelForXp(173))
        assertEquals(3, getLevelForXp(174))
        assertEquals(98, getLevelForXp(13_034_430))
        assertEquals(99, getLevelForXp(13_034_431))
        assertEquals(99, getLevelForXp(14_391_159))
    }

    @Test
    fun `negative xp is level one and the level caps at 120`() {
        assertEquals(1, getLevelForXp(-1))
        assertEquals(1, getLevelForXp(Int.MIN_VALUE))
        assertEquals(119, getLevelForXp(104_273_166))
        assertEquals(120, getLevelForXp(104_273_167))
        assertEquals(120, getLevelForXp(200_000_000))
        assertEquals(120, getLevelForXp(Int.MAX_VALUE))
    }

    @Test
    fun `thresholds round trip through level for xp`() {
        for (level in 1..120) {
            assertEquals(level, getLevelForXp(getXpForLevel(level)), "at the threshold of $level")
            if (level > 1) assertEquals(level - 1, getLevelForXp(getXpForLevel(level) - 1), "just below $level")
        }
    }
}
