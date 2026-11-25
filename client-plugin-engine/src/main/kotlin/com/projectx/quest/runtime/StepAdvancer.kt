package com.projectx.quest.runtime

import com.projectx.game.bootstrap.Bootstrap
import com.projectx.game.nxt.MainState
import com.projectx.quest.data.Quest
import com.projectx.quest.data.QuestCondition
import com.projectx.quest.solver.QuestSolverRegistry
import com.projectx.script.api.varps

/** Moves the guide on by itself once a step's checkable conditions hold; at most one step per call. */
object StepAdvancer {
    private const val INTERVAL_MS = 250L

    private var lastRunMs = 0L

    fun maybeTick(autoAdvanceEnabled: Boolean) {
        val startedAt = System.currentTimeMillis()
        if (startedAt - lastRunMs < INTERVAL_MS) return
        lastRunMs = startedAt
        val quest = ActiveQuestState.selectedQuest?.takeIf { inGame() } ?: return
        if (quest.stageVarbit >= 0) recordStage(quest.stageVarbit)
        if (autoAdvanceEnabled) advance(quest, startedAt)
    }

    private fun advance(quest: Quest, stamp: Long) {
        val index = ActiveQuestState.currentStepIndex
        val step = quest.steps.getOrNull(index) ?: return
        val jump = step.jumpOffset
        if (jump != 0 && allHold(step.jumpconditions)) {
            ActiveQuestState.jumpBy(jump)
        } else {
            val solved = step.solverId != null && QuestSolverRegistry.evaluateOrCached(quest, step, index, stamp).solved
            if (solved || allHold(step.postconditions)) ActiveQuestState.next()
        }
    }

    private fun inGame(): Boolean = runCatching { Bootstrap.client.mainState }.getOrNull() == MainState.LOGGED_IN

    private fun recordStage(varbit: Int) {
        val stage = try {
            varps.getVarBit(varbit)
        } catch (_: Throwable) {
            return
        }
        if (stage != ActiveQuestState.lastVarbitValue) ActiveQuestState.updateLastVarbit(stage)
    }

    /** Conditions the helper cannot check are left out; an empty remainder never holds. */
    private fun allHold(conditions: List<QuestCondition>): Boolean {
        val checkable = conditions.filter(ConditionEvaluator::implementable)
        return checkable.isNotEmpty() && checkable.all(ConditionEvaluator::isMet)
    }
}
