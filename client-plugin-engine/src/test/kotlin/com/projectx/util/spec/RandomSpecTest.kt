package com.projectx.util.spec

import com.projectx.util.nonProfiledGaussian
import com.projectx.util.random
import com.projectx.util.randomD
import com.projectx.util.randomInclusive
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RandomSpecTest {

    private val samples = 20_000

    @Test
    fun `int ranges exclude the upper bound and reach every other value`() {
        val seen = (1..samples).map { random(3, 8) }.toSet()
        assertEquals(setOf(3, 4, 5, 6, 7), seen)
        val fromZero = (1..samples).map { random(4) }.toSet()
        assertEquals(setOf(0, 1, 2, 3), fromZero)
        assertEquals(setOf(-2, -1), (1..samples).map { random(-2, 0) }.toSet())
    }

    @Test
    fun `int ranges are roughly uniform`() {
        val counts = IntArray(10)
        repeat(samples) { counts[random(10)]++ }
        counts.forEach { assertTrue(abs(it - samples / 10) < samples / 10 / 5, "bucket count $it") }
    }

    @Test
    fun `long ranges exclude the upper bound`() {
        val seen = (1..samples).map { random(10_000_000_000L, 10_000_000_003L) }.toSet()
        assertEquals(setOf(10_000_000_000L, 10_000_000_001L, 10_000_000_002L), seen)
        assertEquals(setOf(0L, 1L), (1..samples).map { random(2L) }.toSet())
    }

    @Test
    fun `inclusive ranges include the upper bound`() {
        assertEquals(setOf(3, 4, 5), (1..samples).map { randomInclusive(3, 5) }.toSet())
        assertEquals(setOf(0, 1, 2), (1..samples).map { randomInclusive(2) }.toSet())
        assertEquals(setOf(7), (1..100).map { randomInclusive(7, 7) }.toSet())
    }

    @Test
    fun `an empty or inverted range is rejected`() {
        assertFailsWith<IllegalArgumentException> { random(5, 5) }
        assertFailsWith<IllegalArgumentException> { random(6, 5) }
        assertFailsWith<IllegalArgumentException> { random(0) }
        assertFailsWith<IllegalArgumentException> { random(3L, 3L) }
        assertFailsWith<IllegalArgumentException> { randomInclusive(5, 4) }
    }

    @Test
    fun `doubles stay inside the half open range`() {
        repeat(samples) {
            val unit = randomD()
            assertTrue(unit >= 0.0 && unit < 1.0)
            val ranged = randomD(-2.5, 7.5)
            assertTrue(ranged >= -2.5 && ranged < 7.5)
            val fromZero = randomD(3.0)
            assertTrue(fromZero >= 0.0 && fromZero < 3.0)
        }
        val mean = (1..samples).sumOf { randomD(10.0, 20.0) } / samples
        assertTrue(abs(mean - 15.0) < 0.15, "mean $mean")
    }

    @Test
    fun `non profiled gaussian uses the second argument as the variance`() {
        val values = (1..samples).map { nonProfiledGaussian(1_000, 10_000) }
        val mean = values.average()
        val sd = sqrt(values.sumOf { (it - mean) * (it - mean) } / values.size)
        assertTrue(abs(mean - 999.5) < 5, "mean $mean")
        assertTrue(abs(sd - 100.0) < 5, "standard deviation $sd")
        val withinOneSd = values.count { it in 900..1_100 }.toDouble() / samples
        assertTrue(abs(withinOneSd - 0.683) < 0.03, "share within one sd $withinOneSd")
    }

    @Test
    fun `non profiled gaussian is unbounded and truncates toward zero`() {
        val values = (1..samples).map { nonProfiledGaussian(0, 1) }
        val zeros = values.count { it == 0 }.toDouble() / samples
        assertTrue(abs(zeros - 0.683) < 0.03, "share of zeros $zeros")
        assertTrue(values.any { it < 0 } && values.any { it > 0 })
        assertTrue((1..samples).map { nonProfiledGaussian(10, 400) }.any { it < 0 })
    }

    @Test
    fun `non profiled gaussian with no variance is the mean, and negative variance yields zero`() {
        assertEquals(setOf(250), (1..100).map { nonProfiledGaussian(250, 0) }.toSet())
        assertEquals(setOf(0), (1..100).map { nonProfiledGaussian(250, -1) }.toSet())
    }
}
