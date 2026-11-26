package com.projectx.quest.solver

import com.projectx.quest.data.Quest
import com.projectx.quest.data.QuestAction
import com.projectx.quest.data.QuestStep

/**
 * Live help for one guide step, found by the step's `solverId`. Evaluated on the game thread every few frames,
 * possibly more than once per frame, so an evaluation with nothing changed must be harmless.
 */
internal interface QuestStepSolver {
    val id: String

    fun evaluate(
        quest: Quest, step: QuestStep, stepIndex: Int,
    ): Result

    /** [overlayActions] are shown beside the step's own; [solved] lets the guide advance by itself. */
    data class Result(val overlayActions: List<QuestAction> = listOf(), val solved: Boolean = false)
}

@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
internal annotation class QuestSolver(
    val id: String,
)
