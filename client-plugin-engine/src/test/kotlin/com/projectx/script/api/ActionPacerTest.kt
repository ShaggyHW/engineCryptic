package com.projectx.script.api

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ActionPacerTest {
    private val ms = 1_000_000L

    @Test
    fun `the predicted send is the slot reserve then gives`() {
        var draws = 0
        val reactions = longArrayOf(230 * ms, 410 * ms, 150 * ms)
        val t0 = 1_000_000 * ms
        val pacer = ActionPacer(50 * ms, { 100 * ms }, { reactions[minOf(draws++, reactions.size - 1)] }, t0)

        val withoutReaction = ActionPacer(50 * ms, { 100 * ms }, null, t0)
        assertEquals(t0, withoutReaction.nextSendNanos(t0))

        val tick = t0 + 1000 * ms
        ActionPacer.serverTick(tick)
        val asked = tick + 33 * ms
        val predicted = pacer.nextSendNanos(asked)
        assertEquals(tick + 230 * ms, predicted)
        assertEquals(1, draws)
        assertEquals(predicted, pacer.nextSendNanos(asked + ms))
        assertEquals(1, draws)
        assertEquals(predicted, asked + pacer.reserve(asked))
        assertEquals(1, draws)

        val second = pacer.nextSendNanos(asked)
        assertEquals(predicted + 100 * ms, second)
        assertEquals(second, asked + pacer.reserve(asked))
        assertEquals(1, draws)

        val tick2 = tick + 600 * ms
        ActionPacer.serverTick(tick2)
        val asked2 = tick2 + 10 * ms
        val predicted2 = pacer.nextSendNanos(asked2)
        assertEquals(2, draws)
        assertEquals(tick2 + 410 * ms, predicted2)
        assertEquals(predicted2, asked2 + pacer.reserve(asked2))
    }

    @Test
    fun `follow-up clicks go at once but still take a slot`() = runBlocking {
        ActionPacer.serverTick(System.nanoTime() - 2_000 * ms)
        ActionPacer.ACTIONS.await()
        val before = System.nanoTime()
        val sent = ActionPacer.ACTIONS.followUp { true } && ActionPacer.ACTIONS.followUp { true }
        val took = (System.nanoTime() - before) / ms
        assertTrue(sent && took < 20, "two follow-up clicks took $took ms")
        val now = System.nanoTime()
        assertTrue(ActionPacer.ACTIONS.nextSendNanos(now) - now >= (ActionPacer.ACTION_GAP_MS - 5) * ms)
        assertFalse(ActionPacer.ACTIONS.followUp { false })
    }

    @Test
    fun `the flood floor refuses an immediate second slot`() {
        val t0 = 9_000 * ms
        val pacer = ActionPacer(50 * ms, t0)

        assertTrue(pacer.tryReserveNow(t0))
        assertFalse(pacer.tryReserveNow(t0 + 10 * ms))
        assertTrue(pacer.tryReserveNow(t0 + 50 * ms))
    }

    @Test
    fun `a human pause stays within its bounds`() {
        repeat(1_000) {
            val v = HumanTiming.between(15, 35)
            assertTrue(v in 15..35, "drew $v")
        }
    }
}
