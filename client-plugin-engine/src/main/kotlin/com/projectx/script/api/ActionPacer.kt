package com.projectx.script.api

import kotlinx.coroutines.delay
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.TimeUnit
import kotlin.math.exp
import kotlin.math.roundToLong

/**
 * Spaces actions like a player: each gap is drawn and never under the flood floor, and an action asked for right
 * after a server tick waits out a reaction time from that tick, so sends do not land at one phase of every tick. The
 * reaction is drawn once per tick, so [nextSendNanos] says when the next action will go before it is asked for.
 *
 * Slots are reserved, not locked. Only callers that ask for a slot are paced; firing a DoAction directly is not.
 */
class ActionPacer(
    private val floorNanos: Long,
    private val gapNanos: () -> Long,
    private val reactionNanos: (() -> Long)?,
    nowNanos: Long,
) {
    constructor(gapNanos: Long, nowNanos: Long) : this(gapNanos, { gapNanos }, null, nowNanos)

    private var lastSlot = nowNanos - floorNanos
    private var nextSlot = nowNanos
    private var reactionTick = 0L
    private var tickReaction = 0L
    private var reactionDrawn = false

    private fun gap(): Long = later(gapNanos(), floorNanos)

    private fun tickReaction(tickNanos: Long, draw: () -> Long): Long {
        if (!reactionDrawn || reactionTick != tickNanos) {
            reactionTick = tickNanos
            tickReaction = draw()
            reactionDrawn = true
        }
        return tickReaction
    }

    private fun slotAt(nowNanos: Long): Long {
        var slot = later(nextSlot, nowNanos)
        val reaction = reactionNanos
        if (reaction != null && serverTicked) {
            val tick = serverTickNanos
            slot = later(slot, tick + tickReaction(tick, reaction))
        }
        return slot
    }

    /** When an action asked for at [nowNanos] would be sent, without taking the slot. Exact until another action or tick. */
    @Synchronized
    fun nextSendNanos(nowNanos: Long): Long = slotAt(nowNanos)

    /** Reserves the next slot at or after [nowNanos]; returns how long to wait for it. */
    @Synchronized
    fun reserve(nowNanos: Long): Long {
        val slot = slotAt(nowNanos)
        lastSlot = slot
        nextSlot = slot + gap()
        return slot - nowNanos
    }

    /** Takes a slot now whatever the gap, so the next ordinary action still keeps its gap after it. */
    @Synchronized
    fun takeNow(nowNanos: Long) {
        lastSlot = nowNanos
        nextSlot = later(nextSlot, nowNanos + gap())
    }

    /** Takes a slot at [nowNanos] when the flood floor allows it; never queues. */
    @Synchronized
    fun tryReserveNow(nowNanos: Long): Boolean {
        if (nowNanos - lastSlot < floorNanos) return false
        lastSlot = nowNanos
        nextSlot = later(nextSlot, nowNanos + gap())
        return true
    }

    /** Waits for this action's slot. */
    @JvmSynthetic
    suspend fun await() {
        val wait = reserve(System.nanoTime())
        if (wait > 0) delay(TimeUnit.NANOSECONDS.toMillis(wait).coerceAtLeast(1))
    }

    /**
     * Sends a follow-up click of one input at once (a Dive, its tile and a Surge pressed in one motion). It still takes
     * a slot, so the next ordinary action keeps its gap and no flood can build.
     */
    fun followUp(action: () -> Boolean): Boolean {
        takeNow(System.nanoTime())
        return action()
    }

    companion object {
        const val ACTION_GAP_MS = 50L

        @Volatile
        private var serverTickNanos = 0L

        @Volatile
        private var serverTicked = false

        @JvmField
        val ACTIONS = ActionPacer(
            TimeUnit.MILLISECONDS.toNanos(ACTION_GAP_MS),
            { TimeUnit.MILLISECONDS.toNanos(HumanTiming.actionGapMs()) },
            { TimeUnit.MILLISECONDS.toNanos(HumanTiming.reactionMs()) },
            System.nanoTime(),
        )

        /** Marks a server tick at [nowNanos] on the [System.nanoTime] clock. */
        @JvmStatic
        fun serverTick(nowNanos: Long) {
            serverTickNanos = nowNanos
            serverTicked = true
        }

        private fun later(a: Long, b: Long): Long = if (a - b > 0) a else b
    }
}

/** How long a player takes to react, how far apart two inputs land, and how a nominally fixed pause wanders. */
object HumanTiming {
    private const val REACTION_MEDIAN_MS = 230L
    private const val REACTION_SIGMA = 0.35
    private const val REACTION_MIN_MS = 110L
    private const val REACTION_MAX_MS = 900L
    private const val GAP_MEDIAN_MS = 120L
    private const val GAP_SIGMA = 0.4
    private const val GAP_MAX_MS = 500L
    private const val SPAN_MAX_MS = 1L shl 32

    /** How long after a game change a player's input follows it (median about 230 ms). */
    @JvmStatic
    fun reactionMs(): Long = logNormal(REACTION_MEDIAN_MS, REACTION_SIGMA, REACTION_MIN_MS, REACTION_MAX_MS)

    /** Spacing between two consecutive inputs (median about 120 ms), never under the flood floor. */
    @JvmStatic
    fun actionGapMs(): Long = logNormal(GAP_MEDIAN_MS, GAP_SIGMA, ActionPacer.ACTION_GAP_MS, GAP_MAX_MS)

    /** A bell-shaped value in [lo]..[hi] around the midpoint, redrawn when it lands outside. */
    @JvmStatic
    fun between(lo: Long, hi: Long): Long {
        val low = minOf(lo, hi)
        val high = maxOf(lo, hi)
        val span = high - low
        val random = ThreadLocalRandom.current()
        if (span <= 0 || span > SPAN_MAX_MS) return if (span <= 0) low else random.nextLong(low, high + 1)
        val mean = low + span / 2
        val sd = maxOf(1L, span / 4)
        repeat(8) {
            val v = (mean + random.nextGaussian() * sd).roundToLong()
            if (v in low..high) return v
        }
        return mean
    }

    private fun logNormal(median: Long, sigma: Double, min: Long, max: Long): Long =
        (median * exp(ThreadLocalRandom.current().nextGaussian() * sigma)).roundToLong().coerceIn(min, max)
}
