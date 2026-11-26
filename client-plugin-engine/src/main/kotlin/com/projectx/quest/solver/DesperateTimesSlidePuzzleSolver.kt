package com.projectx.quest.solver

import com.projectx.puzzle.slide.SlideBoard
import com.projectx.puzzle.slide.SlidePuzzleSolver
import com.projectx.quest.data.Quest
import com.projectx.quest.data.QuestStep

/**
 * Desperate Times' 3x3 sliding blocks. The chest's word grid gives the target layout but shuts while the player
 * slides, so the last grid read is kept for the room; the blocks themselves are NPCs in the world.
 */
internal object DesperateTimesSlidePuzzleSolver : QuestStepSolver {
    override val id get() = "desperate-times.slide-puzzle"

    private const val SIDE = 3
    private const val GAP = 8
    private const val ROOM_REACH = 16
    private const val CHEST = "quest_charos_escape_room_chest"

    private class Symbol(val word: String, npcName: String) {
        val npc by lazy { Names.npc("quest_charos_escape_room_sliding_block_puzzle_tile_$npcName") }
    }

    private val symbols = listOf(
        Symbol("Diamond", "07"), Symbol("Two Dots", "05"), Symbol("Square", "02"), Symbol("House", "04"),
        Symbol("Cross", "08"), Symbol("Circle", "01"), Symbol("Arches", "06"), Symbol("Triangle", "03"),
    )

    private val chest by lazy { Names.iface(CHEST) }
    private val cells by lazy { (1..SIDE * SIDE).map { Names.component(CHEST, "button_text_$it") } }
    private val goalForRoom = RoomMemory<IntArray>(ROOM_REACH)
    private val route = SlideRoute()

    fun isSolutionReady(): Boolean = SolverWorld.isOpen(chest) && readGoal() != null

    override fun evaluate(quest: Quest, step: QuestStep, stepIndex: Int) = advise()

    private fun advise(): QuestStepSolver.Result {
        val here = SolverWorld.playerTile()
        val shown = if (SolverWorld.isOpen(chest)) readGoal() else null
        if (shown != null && here != null) goalForRoom.remember(shown, here)
        val goal = shown ?: goalForRoom.recall(here) ?: return NOTHING
        val board = boardFromNpcs(SIDE, symbols.withIndex().associate { (symbol, it) -> it.npc to symbol }, GAP)
            ?: return NOTHING
        val slide = route.next(SlideBoard(SIDE, SIDE, board, GAP), goal) ?: return NOTHING
        if (slide == SlideRoute.DONE) return SOLVED
        val symbol = symbols[slide]
        return QuestStepSolver.Result(listOf(npcMark(symbol.npc, "Slide ${symbol.word}")))
    }

    private fun readGoal(): IntArray? {
        val layout = IntArray(SIDE * SIDE)
        for ((cell, component) in cells.withIndex()) {
            val word = SolverWorld.text(chest, component)?.trim() ?: return null
            layout[cell] = if (word.isEmpty()) GAP else symbols.indexOfFirst { it.word.equals(word, ignoreCase = true) }.takeIf { it >= 0 } ?: return null
        }
        return layout.takeIf { it.sorted() == (0..GAP).toList() }
    }
}

/** Solves a slide board toward a goal, reusing the last plan while the board and goal are unchanged. */
internal class SlideRoute {
    private var plannedFor: Pair<List<Int>, List<Int>>? = null
    private var firstPiece: Int? = null

    /** The piece the next move slides, [DONE] when the board already matches, or null when there is no route. */
    fun next(board: SlideBoard, goal: IntArray): Int? {
        val key = board.tiles.toList() to goal.toList()
        if (key != plannedFor) {
            val moves = SlidePuzzleSolver.solve(board, goal)
            firstPiece = when {
                moves == null -> null
                moves.isEmpty() -> DONE
                else -> board.tiles[moves.first().tileSlot]
            }
            plannedFor = key
        }
        return firstPiece
    }

    companion object {
        const val DONE = -1
    }
}
