package com.projectx.quest.solver

import com.projectx.quest.data.Quest
import com.projectx.quest.data.QuestStep

/** Elemental Workshop III's last five lever pulls, counted from the turn counter the game bumps on each one. */
object ElementalWorkshopIIITowerSolver : QuestStepSolver {
    override val id get() = "elemental-workshop-iii.tower-final"

    private val pulls = listOf('2' to "the tower drops", '4' to "the tower spins", 'B' to null, 'C' to "the tower rises", '3' to null)

    private val turnCounter by lazy { Names.varp("elem_3_turn_count") }

    private var made = 0
    private var lastTurns: Int? = null

    override fun evaluate(quest: Quest, step: QuestStep, stepIndex: Int) = advise()

    private fun advise(): QuestStepSolver.Result {
        if (!SolverWorld.isOpen(ElementalLevers.panel)) {
            made = 0
            lastTurns = null
            return adviceOnly("Return to the lattice room and open the lever interface to drop, spin and raise the tower.")
        }
        SolverWorld.varp(turnCounter)?.let { turns ->
            val before = lastTurns
            if (before != null && before != turns) made++
            lastTurns = turns
        }
        if (made >= pulls.size) {
            return QuestStepSolver.Result(listOf(advice("Tower dropped, spun and raised - head up to make the second body bar.")), solved = true)
        }
        val (lever, effect) = pulls[made]
        val effectNote = effect?.let { " - $it" }.orEmpty()
        return QuestStepSolver.Result(listOf(
            advice("Tower step ${made + 1}/${pulls.size}: pull lever $lever$effectNote. (Inverse position? the drop is 4 then 3.)"),
            buttonMark(ElementalLevers.panel, ElementalLevers.component(lever), "Pull $lever"),
        ))
    }
}
