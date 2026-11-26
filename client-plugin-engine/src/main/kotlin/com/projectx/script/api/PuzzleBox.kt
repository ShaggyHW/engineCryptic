package com.projectx.script.api

import com.projectx.game.input.Key
import com.projectx.game.interfaces.IFSlot
import com.projectx.puzzle.slide.InterfaceSlidePuzzleSource
import com.projectx.puzzle.slide.SlidePuzzleSolver
import com.projectx.script.Script
import com.projectx.util.gaussian
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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

/** The open puzzle box's board reads solved. False when no box is open or the board can't be read. */
val puzzleBoxSolved: Boolean get() = puzzleBoxOpen && puzzleBoard.readBoard()?.isSolved(SOLVED) == true

/** One click on the open puzzle box's close button, not waited for. False when no box is open or the click failed. */
fun clickPuzzleBoxClose(): Boolean = puzzleBoxOpen && IFSlot(puzzleClose ushr 16, puzzleClose and 0xFFFF).click(1)

/** Closes the open puzzle box: its close button, then Escape if that didn't shut it. True once no box is open. */
suspend fun Script.closePuzzleBox(): Boolean {
    if (!puzzleBoxOpen) return true
    clickPuzzleBoxClose()
    delayUntil(gaussian(1500L, 90_000L)) { !puzzleBoxOpen }
    if (puzzleBoxOpen) pressKey(Key.ESCAPE)
    delayUntil(gaussian(2400L, 360_000L)) { !puzzleBoxOpen }
    return !puzzleBoxOpen
}

/**
 * Solves the open sliding-tile puzzle box one slide at a time with the arrow keys, waiting for each slide to land
 * before the next and planning again whenever the board isn't what it expected. Presses Check and closes the box once
 * solved. Returns true when the picture is complete, false when no box is open, it couldn't be solved, or [stopWhen]
 * came true. [stopWhen] is asked before every slide and leaves the box open where it stood, so a caller can deal with
 * whatever stopped it (an attacker, a time limit) and solve on later without cancelling a keystroke halfway.
 * Java: `Wait.solvePuzzleBox`.
 */

@JvmSynthetic
suspend fun Script.solvePuzzleBox(stopWhen: () -> Boolean = { false }): Boolean {
    repeat(MAX_REPLANS) {
        if (!puzzleBoxOpen || stopWhen()) return false
        val board = puzzleBoard.readBoard() ?: return false
        if (board.isSolved(SOLVED)) return checkAndClose()
        // The search can take its whole budget: off the game thread, or the client freezes while it plans.
        val moves = withContext(Dispatchers.Default) { SlidePuzzleSolver.solve(board, SOLVED, budgetMs = 3000L) } ?: return false
        var expected = board
        for (move in moves) {
            if (stopWhen()) return false
            // The tiles move in a client script that a synthetic click doesn't run; the arrow keys move the gap instead.
            pressKey(gapDirection(move.blankSlot, move.tileSlot) ?: return@repeat)
            expected = expected.applied(move)
            delayUntil(gaussian(1800L, 250_000L)) { puzzleBoard.readBoard()?.tiles?.contentEquals(expected.tiles) == true }
            if (puzzleBoard.readBoard()?.tiles?.contentEquals(expected.tiles) != true) return@repeat
            delay(170, 2500)
        }
    }
    return puzzleBoxSolved && checkAndClose()
}

private suspend fun Script.checkAndClose(): Boolean {
    delay(420, 22_500)
    IFSlot(puzzleCheck ushr 16, puzzleCheck and 0xFFFF).click(1)
    delay(900, 90_000)
    closePuzzleBox()
    return true
}

private const val MAX_REPLANS = 6

private fun gapDirection(gap: Int, tile: Int): Key? = when (tile - gap) {
    -SIDE -> Key.UP
    SIDE -> Key.DOWN
    -1 -> Key.LEFT
    1 -> Key.RIGHT
    else -> null
}
