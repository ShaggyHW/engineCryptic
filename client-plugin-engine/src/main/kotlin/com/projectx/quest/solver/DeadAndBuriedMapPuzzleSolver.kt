package com.projectx.quest.solver

import com.projectx.puzzle.slide.SlideBoard
import com.projectx.quest.data.Quest
import com.projectx.quest.data.QuestStep

/** Dead and Buried's 5x5 map table: piece n belongs in cell n-1, north row first, gap in the south-east corner. */
internal object DeadAndBuriedMapPuzzleSolver : QuestStepSolver {
    override val id get() = "dead-and-buried.map-puzzle"

    private const val SIDE = 5
    private const val GAP = SIDE * SIDE - 1

    private val pieceNpcs by lazy { (1..GAP).map { Names.npc("quest_fort_raptor_map_puzzle_piece_$it") } }
    private val route = SlideRoute()

    override fun evaluate(quest: Quest, step: QuestStep, stepIndex: Int) = advise()

    private fun advise(): QuestStepSolver.Result {
        val cellOfNpc = pieceNpcs.withIndex().associate { (cell, npc) -> npc to cell }
        val board = boardFromNpcs(SIDE, cellOfNpc, GAP) ?: return NOTHING
        val piece = route.next(SlideBoard(SIDE, SIDE, board, GAP), SlideBoard.identityGoal(SIDE * SIDE))
            ?: return NOTHING
        if (piece == SlideRoute.DONE) return SOLVED
        return QuestStepSolver.Result(listOf(npcMark(pieceNpcs[piece], "Slide piece ${piece + 1}")))
    }
}
