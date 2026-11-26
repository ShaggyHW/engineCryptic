package com.projectx.quest.solver

import com.projectx.quest.data.Quest
import com.projectx.quest.data.QuestAction
import com.projectx.quest.data.QuestStep
import java.util.concurrent.ConcurrentHashMap

/** Every known solver by id, and a one-entry memo so callers sharing a stamp share one evaluation. */
internal object QuestSolverRegistry {
    private val byId = ConcurrentHashMap<String, QuestStepSolver>()

    private class Memo(val stamp: Long, val slug: String, val stepIndex: Int, val solverId: String, val result: QuestStepSolver.Result)

    private val memoLock = Any()
    private var memo: Memo? = null

    fun register(solver: QuestStepSolver) = byId.set(solver.id, solver)

    fun unregister(id: String) = byId.remove(id).let { }

    fun get(id: String): QuestStepSolver? = byId[id]

    fun all(): List<QuestStepSolver> = byId.values.toList()

    fun ids(): List<String> = byId.keys.sorted()

    fun evaluateOrCached(quest: Quest, step: QuestStep, index: Int, stamp: Long): QuestStepSolver.Result {
        val named = step.solverId ?: return NOTHING
        val found = byId[named] ?: return NOTHING
        synchronized(memoLock) {
            memo?.takeIf { it.stamp == stamp && it.slug == quest.slug && it.stepIndex == index && it.solverId == named }?.let { return it.result }
            val fresh = runCatching { found.evaluate(quest, step, index) }.getOrElse { failure ->
                println("[QuestSolver] $named failed: $failure")
                NOTHING
            }
            memo = Memo(stamp, quest.slug, index, named, fresh)
            return fresh
        }
    }

    fun overlayActionsFor(quest: Quest, step: QuestStep, index: Int, stamp: Long): List<QuestAction> =
        evaluateOrCached(quest, step, index, stamp).overlayActions
}
