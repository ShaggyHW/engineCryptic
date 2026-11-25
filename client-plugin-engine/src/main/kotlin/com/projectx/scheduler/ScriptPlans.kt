package com.projectx.scheduler

import com.projectx.game.bootstrap.Bootstrap
import com.projectx.game.bootstrap.EngineCommands
import com.projectx.game.nxt.MainState
import com.projectx.script.ConfigurableScript
import com.projectx.script.ScriptConfigStore
import com.projectx.script.ScriptExecutor
import com.projectx.store.RunEnd
import com.projectx.store.RunReports
import java.util.concurrent.ThreadLocalRandom

/**
 * The script plan this client is running, set by the launcher over the control socket. The plan advances on the game
 * thread, once a second, in [tick]; the socket only hands it over, stops it, or asks where it is.
 */
internal object ScriptPlans {
    private const val TICK_MS = 1_000L

    private var runner: PlanRunner? = null
    private var lastTick = 0L

    @Synchronized
    fun set(plan: ScriptPlan) {
        runner?.cancel()
        runner = PlanRunner(plan, EnginePlanHost)
        println("[ScriptPlans] running plan ${plan.name}: ${plan.steps.joinToString(" > ") { "${it.script} ${it.minMinutes}-${it.maxMinutes} min" }}${if (plan.repeat) ", repeating" else ""}")
    }

    /** Ends the plan and the script it is running; false when there was none. */
    @Synchronized
    fun stop(): Boolean {
        val current = runner ?: return false
        current.cancel()
        runner = null
        println("[ScriptPlans] plan ${current.plan.name} stopped")
        return true
    }

    /** `none`, or the plan's name, where it is, and the last problem, separated by `|`. */
    @Synchronized
    fun status(): String {
        val current = runner ?: return "none"
        return listOf(current.plan.name, current.describe(), current.problem.orEmpty()).joinToString("|") { it.replace('|', '/') }
    }

    @Synchronized
    fun tick() {
        val now = System.currentTimeMillis()
        if (now - lastTick < TICK_MS) return
        lastTick = now
        val current = runner ?: return
        current.tick()
        if (current.finished) println("[ScriptPlans] plan ${current.plan.name} finished")
    }
}

private object EnginePlanHost : PlanHost {
    override fun loggedIn(): Boolean = Bootstrap.client.mainState == MainState.LOGGED_IN

    override fun start(script: String): String? {
        val meta = EngineCommands.find(script) ?: return null
        if (!ScriptExecutor.isScriptRunning(meta.scriptClass)) {
            val instance = meta.scriptClass.getDeclaredConstructor().newInstance()
            if (instance is ConfigurableScript) ScriptConfigStore.applyTo(instance)
            ScriptExecutor.activate(instance)
        }
        return meta.name.takeIf { ScriptExecutor.isScriptRunning(meta.scriptClass) }
    }

    override fun isRunning(name: String): Boolean = EngineCommands.find(name)?.let { ScriptExecutor.isScriptRunning(it.scriptClass) } == true

    override fun stop(name: String) {
        val meta = EngineCommands.find(name) ?: return
        val instance = ScriptExecutor.getScriptInstance(meta.scriptClass) ?: return
        RunReports.markStop(meta.scriptClass, RunEnd.QUEUE)
        instance.stop()
    }

    override fun now(): Long = System.currentTimeMillis()

    override fun random(): Double = ThreadLocalRandom.current().nextDouble()
}
