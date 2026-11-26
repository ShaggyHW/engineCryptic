package com.projectx.quest.solver

import com.projectx.quest.data.Quest
import com.projectx.quest.data.QuestStep

/** Advances its step as soon as the step is reached. */
internal object NoopAutoAdvanceSolver : QuestStepSolver {
    override val id get() = "example.noop-advance"

    override fun evaluate(quest: Quest, step: QuestStep, stepIndex: Int) = SOLVED
}

/** A do-nothing starting point for a new puzzle solver. */
internal object SkeletonPuzzleSolver : QuestStepSolver {
    override val id get() = "example.skeleton-puzzle"

    override fun evaluate(quest: Quest, step: QuestStep, stepIndex: Int) = NOTHING
}

internal fun registerExampleSolvers() = listOf(
    NoopAutoAdvanceSolver,
    SkeletonPuzzleSolver,
    EyesOfGlouphriePuzzleSolver,
    ElementalWorkshopIIJunctionBoxSolver,
    ElementalWorkshopIIILatticeSolver,
    ElementalWorkshopIIITowerSolver,
    DesperateTimesCombinationLockSolver,
    DesperateTimesSlidePuzzleSolver,
    DeadAndBuriedMapPuzzleSolver,
    FremennikTrialsCombinationLockSolver,
    SpiritOfSummerEmoteSolver,
    LunarDiplomacyDiceSolver,
    LunarDiplomacyNumberPatternSolver,
    LunarDiplomacyDreamPuffSolver,
    TheElderKilnScalesSolver,
    TheBrinkOfExtinctionPedestalsSolver,
).forEach(QuestSolverRegistry::register)
