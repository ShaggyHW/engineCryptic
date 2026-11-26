package com.projectx.javaapi

import com.projectx.script.Wait
import org.projectx.core.game.skill.Skill
import java.util.function.BooleanSupplier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame

class WaitKotlinNamesTest {

    private val condition = BooleanSupplier { true }

    @Test
    fun `delays keep the Kotlin argument order`() {
        val fixed = assertIs<Wait.Millis>(Wait.delay(600))
        assertEquals(600, fixed.mean)
        assertEquals(0, fixed.variance)

        val spread = assertIs<Wait.Millis>(Wait.delay(600, 150))
        assertEquals(600, spread.mean)
        assertEquals(150, spread.variance)

        assertEquals(1200, assertIs<Wait.Millis>(Wait.delayTicks(2.0)).mean)
        assertEquals(400, assertIs<Wait.Millis>(Wait.delayBetween(400, 400)).mean)
    }

    @Test
    fun `conditions take the timeout first, as in Kotlin`() {
        val until = assertIs<Wait.Until>(Wait.delayUntil(3000, condition))
        assertEquals(3000, until.timeoutMillis)
        assertSame(condition, until.predicate)

        val polled = assertIs<Wait.Until>(Wait.delayUntil(3000, 40, condition))
        assertEquals(40, polled.pollMillis)

        val whileTrue = assertIs<Wait.While>(Wait.delayWhile(2500, condition))
        assertEquals(2500, whileTrue.timeoutMillis)
        assertSame(condition, whileTrue.predicate)
    }

    @Test
    fun `experience and idle waits match their Kotlin defaults`() {
        val drop = assertIs<Wait.XpDrop>(Wait.waitForXPDrop(Skill.MINING))
        assertEquals(Skill.MINING, drop.skill)
        assertEquals(15000, drop.timeoutMillis)

        val idle = assertIs<Wait.Idle>(Wait.waitUntilIdle(10, 2))
        assertEquals(10, idle.maxTicks)
        assertEquals(2, idle.idleChecks)
        assertEquals(true, idle.countAnimation)

        assertEquals(false, assertIs<Wait.Idle>(Wait.waitUntilStoppedMoving(10, 2)).countAnimation)
    }

    @Test
    fun `webWalkTo walks like webWalk`() {
        val walk = assertIs<Wait.WebWalk>(Wait.webWalkTo(3210, 3220, 1))
        assertEquals(3210, walk.x)
        assertEquals(3220, walk.y)
        assertEquals(1, walk.plane)
    }
}
