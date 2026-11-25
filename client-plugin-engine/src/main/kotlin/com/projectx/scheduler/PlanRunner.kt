package com.projectx.scheduler

import kotlinx.serialization.json.JsonPrimitive
import kotlin.math.max

/** What a plan needs of the client. */
internal interface PlanHost {
    fun loggedIn(): Boolean

    /**
     * Starts [script] with [settings] on top of its saved ones; returns the name it runs under, or null when there is no
     * such script or it was refused.
     */
    fun start(script: String, settings: Map<String, JsonPrimitive>): String?

    fun isRunning(name: String): Boolean

    fun stop(name: String)

    fun now(): Long

    /** A number in [0, 1). */
    fun random(): Double
}

/**
 * Works through a [ScriptPlan], one call to [tick] at a time: waits for the player to be in game, runs each script for
 * its random while (a while spent logged out does not count), moves on early when a script stops by itself, and pauses
 * a random moment between scripts.
 */
internal class PlanRunner(val plan: ScriptPlan, private val host: PlanHost) {
    private sealed interface Phase {
        data class Waiting(val step: Int) : Phase
        data class Running(val step: Int, val name: String, val endsAt: Long) : Phase
        data class Stopping(val step: Int, val name: String, val since: Long) : Phase
        data class Pausing(val next: Int, val until: Long) : Phase
        data object Finished : Phase
    }

    private var phase: Phase = Phase.Waiting(0)
    private var loggedOutSince: Long? = null

    /** The last thing worth telling the user that went wrong, such as a script that would not start. */
    var problem: String? = null
        private set

    val finished: Boolean get() = phase == Phase.Finished

    /** The script the plan is running now, or null. */
    val current: String? get() = (phase as? Phase.Running)?.name ?: (phase as? Phase.Stopping)?.name

    fun tick() {
        val now = host.now()
        when (val p = phase) {
            is Phase.Waiting -> if (host.loggedIn()) begin(p.step, now)
            is Phase.Running -> running(p, now)
            is Phase.Stopping -> if (!host.isRunning(p.name) || now - p.since > STOP_GRACE_MS) pauseBefore(p.step + 1, now)
            is Phase.Pausing -> if (now >= p.until) next(p.next)
            Phase.Finished -> Unit
        }
    }

    private fun begin(step: Int, now: Long) {
        val wanted = plan.steps[step]
        val name = host.start(wanted.script, wanted.settings)
        if (name == null) {
            problem = "${wanted.script} would not start: check its name and that you own it"
            pauseBefore(step + 1, now)
            return
        }
        val minutes = wanted.minMinutes + (host.random() * (wanted.maxMinutes - wanted.minMinutes + 1)).toInt()
        phase = Phase.Running(step, name, now + minutes.coerceAtMost(wanted.maxMinutes) * MINUTE_MS)
        loggedOutSince = null
    }

    private fun running(p: Phase.Running, now: Long) {
        if (!host.loggedIn()) {
            if (loggedOutSince == null) loggedOutSince = now
            return
        }
        val away = loggedOutSince?.let { now - it } ?: 0L
        loggedOutSince = null
        val running = if (away > 0) p.copy(endsAt = p.endsAt + away).also { phase = it } else p
        when {
            !host.isRunning(running.name) -> pauseBefore(running.step + 1, now)
            now >= running.endsAt -> {
                host.stop(running.name)
                phase = Phase.Stopping(running.step, running.name, now)
            }
        }
    }

    private fun pauseBefore(next: Int, now: Long) {
        val span = max(0, plan.pauseMaxSeconds - plan.pauseMinSeconds)
        val seconds = plan.pauseMinSeconds + (host.random() * (span + 1)).toInt().coerceAtMost(span)
        phase = Phase.Pausing(next, now + seconds * 1000L)
    }

    private fun next(step: Int) {
        phase = when {
            step < plan.steps.size -> Phase.Waiting(step)
            plan.repeat -> Phase.Waiting(0)
            else -> Phase.Finished
        }
    }

    /** Stops the script the plan is running, if any, and ends the plan. */
    fun cancel() {
        current?.let { if (host.isRunning(it)) host.stop(it) }
        phase = Phase.Finished
    }

    /** One line for the launcher: where the plan is and what comes next. */
    fun describe(): String {
        val now = host.now()
        val total = plan.steps.size
        return when (val p = phase) {
            is Phase.Waiting -> "Waiting to be in game to start ${plan.steps[p.step].script} (${p.step + 1}/$total)"
            is Phase.Running -> "${p.name} (${p.step + 1}/$total), ${remaining(p.endsAt - now)} left"
            is Phase.Stopping -> "Stopping ${p.name} (${p.step + 1}/$total)"
            is Phase.Pausing -> {
                val upNext = when {
                    p.next < total -> plan.steps[p.next].script
                    plan.repeat -> plan.steps[0].script
                    else -> null
                }
                if (upNext == null) "Finishing" else "Next: $upNext in ${remaining(p.until - now)}"
            }
            Phase.Finished -> "Finished"
        }
    }

    private fun remaining(ms: Long): String {
        val seconds = (ms.coerceAtLeast(0) + 999) / 1000
        return if (seconds >= 60) "${(seconds + 59) / 60} min" else "${seconds}s"
    }

    private companion object {
        const val MINUTE_MS = 60_000L
        const val STOP_GRACE_MS = 30_000L
    }
}
