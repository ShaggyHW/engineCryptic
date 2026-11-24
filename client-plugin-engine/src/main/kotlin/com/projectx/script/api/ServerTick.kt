package com.projectx.script.api

import com.projectx.script.Script

/**
 * The server's game tick, counted from the end-of-tick packet it sends once per 600 ms cycle. Combat, ability
 * cooldowns and prayers resolve on this clock, and a local 600 ms timer drifts away from it within a few ticks, so
 * anything that must land on a tick boundary (a rotation, a prayer flick) counts these instead.
 *
 * [count] only moves while the game connection is delivering packets: it stands still at the lobby and during a
 * disconnect.
 */
object ServerTick {
    @Volatile
    private var ticks = 0L

    @Volatile
    private var lastTickAt = 0L

    @Volatile
    private var lastTickNanos = 0L

    /** Server ticks seen since the engine loaded. */
    @JvmStatic
    val count: Long get() = ticks

    /** Milliseconds since the last server tick ended, or [Long.MAX_VALUE] before the first one. */
    @JvmStatic
    val millisSinceTick: Long get() = lastTickAt.let { if (it == 0L) Long.MAX_VALUE else System.currentTimeMillis() - it }

    /** Milliseconds into the current tick on the [System.nanoTime] clock, or -1 before the first tick. */
    @JvmStatic
    val millisIntoTick: Long get() = lastTickNanos.let { if (it == 0L) -1L else (System.nanoTime() - it) / NANOS_PER_MILLI }

    /** When the last tick ended on the [System.nanoTime] clock, or 0 before the first one. */
    @JvmStatic
    val lastTickNanoTime: Long get() = lastTickNanos

    /**
     * How far into its tick an action asked for now will be sent, given the [ActionPacer] holds it to a reaction after
     * the tick and a gap after the last action. Past [TICK_MILLIS] when it will go in a later tick; -1 before the first.
     */
    @JvmStatic
    fun millisIntoTickAtSend(): Long {
        val tick = lastTickNanos
        if (tick == 0L) return -1L
        val now = System.nanoTime()
        return (maxOf(now, ActionPacer.ACTIONS.nextSendNanos(now)) - tick) / NANOS_PER_MILLI
    }

    internal fun onTickEnd() {
        val nanos = System.nanoTime()
        lastTickAt = System.currentTimeMillis()
        lastTickNanos = nanos
        ticks++
        ActionPacer.serverTick(nanos)
    }

    const val TICK_MILLIS = 600L
    private const val NANOS_PER_MILLI = 1_000_000L
}

/** Waits for the next server tick to end. Returns false if none arrived within [timeoutMillis]. */
suspend fun Script.awaitServerTick(timeoutMillis: Long = 1800): Boolean = awaitServerTicks(1, timeoutMillis)

/** Waits for [ticks] more server ticks to end. Returns false if they did not all arrive within [timeoutMillis]. */
suspend fun Script.awaitServerTicks(ticks: Int, timeoutMillis: Long = ticks * 600L + 1200L): Boolean {
    val target = ServerTick.count + ticks
    delayUntil(timeoutMillis, pollingDelayMillis = 10) { ServerTick.count >= target }
    return ServerTick.count >= target
}
