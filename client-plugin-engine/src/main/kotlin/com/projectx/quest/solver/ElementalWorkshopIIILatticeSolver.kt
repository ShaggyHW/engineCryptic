package com.projectx.quest.solver

import com.projectx.quest.data.Quest
import com.projectx.quest.data.QuestStep

/** Elemental Workshop III's lever panel: rows A-F and columns 1-7, each a named lever component. */
internal object ElementalLevers {
    const val PANEL = "elem3_controls"

    val panel by lazy { Names.iface(PANEL) }
    val movesLeft by lazy { Names.component(PANEL, "elem3_moves_left") }
    val reset by lazy { Names.component(PANEL, "negative_button_text") }

    /** Rows A-F are levers 1-6 and columns 1-7 are levers 7-13. */
    fun component(lever: Char): Int {
        val number = if (lever.isDigit()) 6 + lever.digitToInt() else lever.uppercaseChar() - 'A' + 1
        return Names.component(PANEL, "elem3_lever_$number")
    }
}

/**
 * Elemental Workshop III's lattice: six fixed phases of lever presses. Progress inside a phase is counted from
 * the moves-left number; which pair of phases is current comes from how many moves the game has granted.
 */
internal object ElementalWorkshopIIILatticeSolver : QuestStepSolver {
    override val id get() = "elemental-workshop-iii.lattice"

    private const val COMPLETE_STAGE = 5
    private const val RIGHT_COUNTER_LATCH = 50
    private const val UNLIMITED_MOVES = 101
    private const val PHASES_PER_GROUP = 2
    private const val COMPLETE = "Lattice puzzle complete - proceed with the quest."

    private val routine = listOf(
        "Right counter, north" to "C2F6 C2F6 C2E5 B3F2 B3F2 E4F3",
        "Right counter, south" to "A5C6 B5D6 C5E6 C5F6 D5E3 F4E",
        "Left counter, north" to "2B6A 5B6C 5B2C 5B3E 6F5B 3C6F 2C6E 3B5E 6F4E 6F4E 5",
        "Left counter, south" to "2C3B 2A3E 2B3F 2C3F 2C5F 2E",
        "Lower the tower" to "B2A3 B2F6 B2D3 F6C5 D6F5 C6F2 E5F2 E5D6 F4E6",
        "Spin & raise the tower" to "4E6F 4E6A 5B6E 5A6F 5B2A 3B4C 2B6E 5C2D 3E5C 3F6 B4C3",
    ).map { (title, presses) -> title to presses.filterNot(Char::isWhitespace) }

    private val stageVarbit by lazy { Names.varbit("elem_3_main") }
    private val rightCounterVarbit by lazy { Names.varbit("elem_3_moves_main") }
    private val firstNumber = Regex("\\d+")

    private var phaseAt = 0
    private var press = 0
    private var lastMovesLeft: Int? = null
    private var highestGroup: Int? = null

    override fun evaluate(quest: Quest, step: QuestStep, stepIndex: Int) = advise()

    private fun advise(): QuestStepSolver.Result {
        if (!SolverWorld.isOpen(ElementalLevers.panel)) {
            startOver()
            return adviceOnly("Open the elemental lattice puzzle - the solver guides each lever.")
        }
        val movesText = SolverWorld.text(ElementalLevers.panel, ElementalLevers.movesLeft).orEmpty()
        val movesLeft = firstNumber.find(movesText)?.value?.toIntOrNull()
        follow(movesLeft)
        if ((SolverWorld.varbit(stageVarbit) ?: 0) >= COMPLETE_STAGE) return QuestStepSolver.Result(listOf(advice(COMPLETE)), solved = true)
        if ("Moves" in movesText && movesText.none(Char::isDigit)) {
            val raised = "Tower spun and raised - do NOT pull the Reset lever. Head upstairs to make a body bar (the 'Body body' steps)."
            return QuestStepSolver.Result(listOf(advice(raised)), solved = true)
        }
        if (phaseAt > routine.lastIndex) return QuestStepSolver.Result(listOf(advice(COMPLETE)), solved = true)
        val (title, presses) = routine[phaseAt]
        val heading = "Phase ${phaseAt + 1}/${routine.size} ($title)"
        if (press >= presses.length) {
            if (phaseAt == routine.lastIndex) return adviceOnly("Finish spinning and raising the tower - do NOT pull Reset.")
            return QuestStepSolver.Result(listOf(
                advice("$heading done - pull the Reset lever (confirm) to continue."),
                buttonMark(ElementalLevers.panel, ElementalLevers.reset, "Reset"),
            ))
        }
        val lever = presses[press]
        return QuestStepSolver.Result(listOf(
            advice("$heading - step ${press + 1}/${presses.length}: pull lever $lever."),
            buttonMark(ElementalLevers.panel, ElementalLevers.component(lever), "Pull $lever"),
        ))
    }

    private fun follow(movesLeft: Int?) {
        val group = when {
            movesLeft != null && movesLeft >= UNLIMITED_MOVES -> 2
            else -> SolverWorld.varbit(rightCounterVarbit)?.let { if (it >= RIGHT_COUNTER_LATCH) 1 else 0 }
        }
        val top = highestGroup
        if (group != null && (top == null || group > top)) {
            highestGroup = group
            phaseAt = group * PHASES_PER_GROUP
            press = 0
        }
        if (movesLeft == null) return
        val before = lastMovesLeft
        lastMovesLeft = movesLeft
        if (before == null) return
        val change = movesLeft - before
        when {
            change < 0 -> press -= change
            change == 1 -> press = (press - 1).coerceAtLeast(0)
            change > 1 -> {
                val leadsGroup = phaseAt % PHASES_PER_GROUP == 0
                if (leadsGroup && press >= routine[phaseAt].second.length && phaseAt < routine.lastIndex) phaseAt++
                press = 0
            }
        }
    }

    private fun startOver() {
        phaseAt = 0
        press = 0
        lastMovesLeft = null
        highestGroup = null
    }
}
