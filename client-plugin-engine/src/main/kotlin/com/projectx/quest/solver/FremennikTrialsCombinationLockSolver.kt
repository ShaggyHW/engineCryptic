package com.projectx.quest.solver

import com.projectx.game.hooks.impl.InterfaceTextCapture
import com.projectx.puzzle.combolock.CombinationLockLayout
import com.projectx.puzzle.combolock.FremennikRiddle
import com.projectx.quest.data.Quest
import com.projectx.quest.data.QuestStep
import com.projectx.quest.runtime.RecentChat

/** Peer the Seer's door: the riddle's keyword names the four-letter answer, which then drives the lock's dials. */
internal object FremennikTrialsCombinationLockSolver : QuestStepSolver {
    override val id get() = "the-fremennik-trials.combination-lock"

    @Volatile private var answer: String? = null

    override fun evaluate(quest: Quest, step: QuestStep, stepIndex: Int) = advise()

    private fun advise(): QuestStepSolver.Result {
        val word = answer ?: heardAnswer()?.also { answer = it } ?: return NOTHING
        dialGuidance(CombinationLockLayout.SEER, word)?.let { return QuestStepSolver.Result(it) }
        return adviceOnly("Riddle answer: $word")
    }

    private fun heardAnswer(): String? {
        val spoken = runCatching { InterfaceTextCapture.textFor(InterfaceTextCapture.DIALOG_INTERFACES) }.getOrNull()
        return spoken?.let(FremennikRiddle::answerFrom) ?: FremennikRiddle.answerMatching { RecentChat.containsRecent(it) }
    }
}
