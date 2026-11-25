package com.projectx.util.spec

import com.projectx.util.getFormattedUnitsPerHour
import com.projectx.util.getFormattedXpPerHour
import com.projectx.util.getUnitsPerHour
import com.projectx.util.getXpPerHour
import com.projectx.util.hoursElapsed
import com.projectx.util.millisElapsed
import com.projectx.util.minutesElapsed
import com.projectx.util.secondsElapsed
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RatesSpecTest {

    private val slackMillis = 5_000L

    private fun ago(millis: Long) = System.currentTimeMillis() - millis

    @Test
    fun `elapsed time is measured against the wall clock`() {
        val millis = millisElapsed(ago(90_000))
        assertTrue(millis in 90_000..90_000 + slackMillis, "millis $millis")
        assertTrue(secondsElapsed(ago(90_000)) in 90f..95f)
        assertTrue(minutesElapsed(ago(90_000)) in 1.5f..1.6f)
        assertTrue(hoursElapsed(ago(5_400_000)) in 1.5f..1.502f)
    }

    @Test
    fun `a start time in the future gives negative elapsed time`() {
        assertTrue(millisElapsed(System.currentTimeMillis() + 60_000) < 0)
        assertTrue(hoursElapsed(System.currentTimeMillis() + 3_600_000) < 0f)
    }

    @Test
    fun `xp per hour divides the gain by the hours elapsed and truncates`() {
        val rate = getXpPerHour(1_000, 51_000, ago(3_600_000))
        assertTrue(rate in 49_900..50_000, "rate $rate")
        val halfHour = getXpPerHour(0, 10_000, ago(1_800_000))
        assertTrue(halfHour in 19_900..20_000, "rate $halfHour")
        val loss = getXpPerHour(10_000, 0, ago(3_600_000))
        assertTrue(loss in -10_000..-9_900, "rate $loss")
        assertEquals(0, getXpPerHour(500, 500, ago(3_600_000)))
    }

    @Test
    fun `units per hour divides the count by the hours elapsed`() {
        val rate = getUnitsPerHour(600, ago(7_200_000))
        assertTrue(rate in 297..300, "rate $rate")
    }

    @Test
    fun `formatted rates use thousands separators`() {
        val xp = getFormattedXpPerHour(0, 2_000_000, ago(3_600_000))
        assertTrue(Regex("""1,99\d,\d{3}|2,000,000""").matches(xp), xp)
        val units = getFormattedUnitsPerHour(12_000, ago(3_600_000))
        assertTrue(Regex("""11,9\d\d|12,000""").matches(units), units)
    }
}
