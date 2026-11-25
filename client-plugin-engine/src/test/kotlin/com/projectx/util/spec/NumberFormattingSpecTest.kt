package com.projectx.util.spec

import com.projectx.util.format
import com.projectx.util.formatElapsedTime
import com.projectx.util.toBitString
import kotlin.test.Test
import kotlin.test.assertEquals

class NumberFormattingSpecTest {

    @Test
    fun `whole numbers use US thousands separators`() {
        assertEquals("0", format(0))
        assertEquals("999", format(999))
        assertEquals("1,000", format(1000))
        assertEquals("12,345,678", format(12_345_678))
        assertEquals("-1,234,567", format(-1_234_567))
        assertEquals("2,147,483,647", format(Int.MAX_VALUE))
        assertEquals("-2,147,483,648", format(Int.MIN_VALUE))
    }

    @Test
    fun `decimals default to two places with a dot and no grouping`() {
        assertEquals("1.50", format(1.5f))
        assertEquals("1.50", format(1.5))
        assertEquals("0.67", format(2.0 / 3.0))
        assertEquals("1234567.89", format(1_234_567.891))
        assertEquals("0.00", format(0.0))
    }

    @Test
    fun `decimal places are configurable and round half up`() {
        assertEquals("1", format(2.0 / 3.0, 0))
        assertEquals("0.667", format(2.0 / 3.0, 3))
        assertEquals("0.13", format(0.125))
        assertEquals("0.13", format(0.125f))
        assertEquals("3", format(2.5, 0))
        assertEquals("-3", format(-2.5, 0))
        assertEquals("10.0000", format(10f, 4))
    }

    @Test
    fun `negative values and specials keep their sign and names`() {
        assertEquals("-0.00", format(-0.001))
        assertEquals("-12.35", format(-12.345f, 2))
        assertEquals("NaN", format(Double.NaN))
        assertEquals("Infinity", format(Double.POSITIVE_INFINITY))
    }

    @Test
    fun `elapsed time is zero padded hours minutes and seconds`() {
        assertEquals("00:00:00", formatElapsedTime(0, 0))
        assertEquals("00:00:00", formatElapsedTime(999, 0))
        assertEquals("00:00:01", formatElapsedTime(1_000, 0))
        assertEquals("01:02:03", formatElapsedTime(3_723_000, 0))
        assertEquals("01:02:03", formatElapsedTime(10_003_723_999, 10_000_000_000))
        assertEquals("23:59:59", formatElapsedTime(86_399_000, 0))
        assertEquals("24:00:00", formatElapsedTime(86_400_000, 0))
        assertEquals("100:00:00", formatElapsedTime(360_000_000, 0))
    }

    @Test
    fun `elapsed time before the start shows negative fields`() {
        assertEquals("00:00:-1", formatElapsedTime(0, 1_500))
        assertEquals("00:-1:-1", formatElapsedTime(0, 61_000))
    }

    @Test
    fun `bytes print as two nibbles`() {
        assertEquals("0000 0000", 0.toByte().toBitString())
        assertEquals("0000 0101", 5.toByte().toBitString())
        assertEquals("1010 0000", 0xA0.toByte().toBitString())
        assertEquals("1111 1111", (-1).toByte().toBitString())
        assertEquals("1000 0000", Byte.MIN_VALUE.toBitString())
    }

    @Test
    fun `ints print as eight nibbles, most significant first`() {
        assertEquals("0000 0000 0000 0000 0000 0000 0000 0001", 1.toBitString())
        assertEquals("0000 0000 0000 0000 0000 0001 0000 0000", 256.toBitString())
        assertEquals("1111 1111 1111 1111 1111 1111 1111 1111", (-1).toBitString())
        assertEquals("1000 0000 0000 0000 0000 0000 0000 0000", Int.MIN_VALUE.toBitString())
        assertEquals("0111 1111 1111 1111 1111 1111 1111 1111", Int.MAX_VALUE.toBitString())
    }
}
