package com.projectx.scheduler

import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlanRunnerTest {
    private class FakeHost(var loggedIn: Boolean = true, private val known: Set<String> = setOf("Fishing", "Mining", "Agility")) : PlanHost {
        var time = 0L
        var rolled = 0.0
        val running = mutableSetOf<String>()
        val started = mutableListOf<String>()
        val settingsGiven = mutableListOf<Map<String, JsonPrimitive>>()
        val stopped = mutableListOf<String>()

        override fun loggedIn() = loggedIn
        override fun start(script: String, settings: Map<String, JsonPrimitive>): String? {
            if (script !in known) return null
            running += script
            started += script
            settingsGiven += settings
            return script
        }
        override fun isRunning(name: String) = name in running
        override fun stop(name: String) {
            stopped += name
            running -= name
        }
        override fun now() = time
        override fun random() = rolled

        fun advance(minutes: Double, runner: PlanRunner) {
            val end = time + (minutes * 60_000).toLong()
            while (time < end) {
                time += 1_000
                runner.tick()
            }
        }
    }

    private fun plan(repeat: Boolean = false, vararg steps: PlanStep) =
        ScriptPlan("Test", steps.toList(), repeat, pauseMinSeconds = 10, pauseMaxSeconds = 10)

    @Test
    fun `each script runs for its while, then the next one starts after a pause`() {
        val host = FakeHost()
        val runner = PlanRunner(plan(false, PlanStep("Fishing", 30, 30), PlanStep("Mining", 20, 20)), host)
        runner.tick()
        assertEquals(listOf("Fishing"), host.started)
        host.advance(29.9, runner)
        assertTrue(host.isRunning("Fishing"))
        host.advance(0.2, runner)
        assertEquals(listOf("Fishing"), host.stopped)
        host.advance(0.5, runner)
        assertEquals(listOf("Fishing", "Mining"), host.started)
        host.advance(21.0, runner)
        assertTrue(runner.finished)
        assertEquals("Finished", runner.describe())
    }

    @Test
    fun `a run length is drawn between the shortest and longest`() {
        val host = FakeHost().apply { rolled = 0.999 }
        val runner = PlanRunner(plan(false, PlanStep("Fishing", 10, 20)), host)
        runner.tick()
        host.advance(19.9, runner)
        assertTrue(host.isRunning("Fishing"), "a roll near 1 runs close to the longest")
        host.advance(0.2, runner)
        assertFalse(host.isRunning("Fishing"))
    }

    @Test
    fun `a repeating plan starts over after its last script`() {
        val host = FakeHost()
        val runner = PlanRunner(plan(true, PlanStep("Fishing", 1, 1), PlanStep("Mining", 1, 1)), host)
        runner.tick()
        host.advance(3.0, runner)
        assertEquals(listOf("Fishing", "Mining", "Fishing"), host.started)
        assertFalse(runner.finished)
    }

    @Test
    fun `a script that stops by itself moves the plan on early`() {
        val host = FakeHost()
        val runner = PlanRunner(plan(false, PlanStep("Fishing", 60, 60), PlanStep("Mining", 5, 5)), host)
        runner.tick()
        host.advance(1.0, runner)
        host.running -= "Fishing"
        host.advance(0.5, runner)
        assertEquals(listOf("Fishing", "Mining"), host.started)
        assertEquals(emptyList(), host.stopped, "a script that ended is not stopped again")
    }

    @Test
    fun `nothing starts until the player is in game, and time logged out does not count`() {
        val host = FakeHost(loggedIn = false)
        val runner = PlanRunner(plan(false, PlanStep("Fishing", 10, 10)), host)
        host.advance(5.0, runner)
        assertEquals(emptyList(), host.started)
        assertTrue(runner.describe().startsWith("Waiting to be in game"))
        host.loggedIn = true
        runner.tick()
        host.advance(5.0, runner)
        host.loggedIn = false
        host.advance(30.0, runner)
        host.loggedIn = true
        host.advance(4.9, runner)
        assertTrue(host.isRunning("Fishing"), "the half hour logged out is added back")
        host.advance(0.2, runner)
        assertFalse(host.isRunning("Fishing"))
    }

    @Test
    fun `a script that will not start is reported and skipped`() {
        val host = FakeHost()
        val runner = PlanRunner(plan(false, PlanStep("Not a script", 5, 5), PlanStep("Agility", 5, 5)), host)
        runner.tick()
        assertNotNull(runner.problem)
        host.advance(0.5, runner)
        assertEquals(listOf("Agility"), host.started)
    }

    @Test
    fun `cancelling stops the running script and ends the plan`() {
        val host = FakeHost()
        val runner = PlanRunner(plan(false, PlanStep("Fishing", 5, 5)), host)
        runner.tick()
        runner.cancel()
        assertEquals(listOf("Fishing"), host.stopped)
        assertTrue(runner.finished)
        assertNull(runner.current)
    }

    @Test
    fun `a plan is read from the launcher's json and checked`() {
        val ok = ScriptPlan.parse("""{"name":"Skills","repeat":true,"steps":[{"script":" Fishing ","min_minutes":30,"max_minutes":60}]}""").getOrThrow()
        assertEquals("Fishing", ok.steps.single().script)
        assertEquals(30, ok.steps.single().minMinutes)
        assertTrue(ok.repeat)
        assertEquals(ScriptPlan.DEFAULT_PAUSE_MIN_SECONDS, ok.pauseMinSeconds)
        assertTrue(ScriptPlan.parse("""{"name":"x","steps":[]}""").isFailure)
        assertTrue(ScriptPlan.parse("""{"name":"x","steps":[{"script":"Fishing","min_minutes":60,"max_minutes":30}]}""").isFailure)
        assertTrue(ScriptPlan.parse("not json").isFailure)
    }

    @Test
    fun `a step's own settings go to its script`() {
        val plan = ScriptPlan.parse(
            """{"name":"x","steps":[{"script":"Fishing","min_minutes":5,"max_minutes":5,"settings":{"bank":true,"spot":"Barbarian","count":3,"nested":{"a":1}}}]}""",
        ).getOrThrow()
        val host = FakeHost()
        PlanRunner(plan, host).tick()
        assertEquals(
            mapOf("bank" to JsonPrimitive(true), "spot" to JsonPrimitive("Barbarian"), "count" to JsonPrimitive(3L)),
            host.settingsGiven.single(),
        )
    }
}
