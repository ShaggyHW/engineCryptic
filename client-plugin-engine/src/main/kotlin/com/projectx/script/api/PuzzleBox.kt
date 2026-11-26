package com.projectx.script.api

import com.projectx.game.input.Key
import com.projectx.game.interfaces.IFSlot
import com.projectx.puzzle.slide.InterfaceSlidePuzzleSource
import com.projectx.puzzle.slide.SlidePuzzleSolver
import com.projectx.script.Script
import com.projectx.util.gaussian
import world.gregs.voidps.gameval.Gameval

private val puzzleWindow by lazy { Gameval.requireId(Gameval.INTERFACE, "slide_puzzle") }
private val puzzleGrid by lazy { Gameval.requireComponentHash("slide_puzzle:puzzle_layer") }
private val puzzleClose by lazy { Gameval.requireComponentHash("slide_puzzle:trail_close") }
private val puzzleCheck by lazy { Gameval.requireComponentHash("slide_puzzle:check_button_layer") }
private const val SIDE = 5
private const val FIRST_TILE_VARBIT = 39405
private const val BLANK_PIECE = 24
private val SOLVED = IntArray(SIDE * SIDE) { it }

private val puzzleBoard by lazy {
    InterfaceSlidePuzzleSource("puzzle-box", puzzleWindow, puzzleGrid and 0xFFFF, SIDE, SIDE, FIRST_TILE_VARBIT, BLANK_PIECE)
}

/** Whether a sliding-tile puzzle box (a treasure-trail puzzle) is open. */
val puzzleBoxOpen: Boolean get() = interfaces.isOpen(puzzleWindow)

/**
 * Solves the open sliding-tile puzzle box one slide at a time with the arrow keys, waiting for each slide to land
 * before the next and planning again whenever the board isn't what it expected. Presses Check and closes the box once
 * solved. Returns true when the picture is complete, false when no box is open or it couldn't be solved.
 * Java: `Wait.solvePuzzleBox`.
 */
@JvmSynthetic
suspend fun Script.solvePuzzleBox(): Boolean {
    repeat(MAX_REPLANS) {
        if (!puzzleBoxOpen) return false
        val board = puzzleBoard.readBoard() ?: return false
        if (board.isSolved(SOLVED)) {
            delay(420, 22_500)
            IFSlot(puzzleCheck ushr 16, puzzleCheck and 0xFFFF).click(1)
            delay(900, 90_000)
            if (puzzleBoxOpen) IFSlot(puzzleClose ushr 16, puzzleClose and 0xFFFF).click(1)
            delayUntil(gaussian(1500L, 90_000L)) { !puzzleBoxOpen }
            if (puzzleBoxOpen) pressKey(Key.ESCAPE)
            delayUntil(gaussian(2400L, 360_000L)) { !puzzleBoxOpen }
            return true
        }
        val moves = SlidePuzzleSolver.solve(board, SOLVED, budgetMs = 3000L) ?: return false
        var expected = board
        for (move in moves) {
            // The tiles move in a client script that a synthetic click doesn't run; the arrow keys move the gap instead.
            pressKey(gapDirection(move.blankSlot, move.tileSlot) ?: return@repeat)
            expected = expected.applied(move)
            delayUntil(gaussian(1800L, 250_000L)) { puzzleBoard.readBoard()?.tiles?.contentEquals(expected.tiles) == true }
            if (puzzleBoard.readBoard()?.tiles?.contentEquals(expected.tiles) != true) return@repeat
            delay(170, 2500)
        }
    }
    return puzzleBoard.readBoard()?.isSolved(SOLVED) == true
}

private const val MAX_REPLANS = 6

private fun gapDirection(gap: Int, tile: Int): Key? = when (tile - gap) {
    -SIDE -> Key.UP
    SIDE -> Key.DOWN
    -1 -> Key.LEFT
    1 -> Key.RIGHT
    else -> null
}
