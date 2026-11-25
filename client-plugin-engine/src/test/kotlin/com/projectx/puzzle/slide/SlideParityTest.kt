package com.projectx.puzzle.slide

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SlideParityTest {

    private fun swappedPair(size: Int) = SlideBoard.identityGoal(size).also { it[0] = 1; it[1] = 0 }

    private fun elapsedMs(block: () -> Unit): Long {
        val start = System.nanoTime()
        block()
        return (System.nanoTime() - start) / 1_000_000
    }

    @Test
    fun `boards with the wrong parity are refused without spending the budget`() {
        for ((rows, cols) in listOf(2 to 2, 3 to 3, 2 to 3, 4 to 4, 5 to 5, 3 to 5)) {
            val size = rows * cols
            val board = SlideBoard(rows, cols, swappedPair(size), size - 1)
            val took = elapsedMs { assertNull(SlidePuzzleSolver.solve(board, SlideBoard.identityGoal(size), budgetMs = 60_000)) }
            assertTrue(took < 1_000, "${rows}x$cols took $took ms")
        }
    }

    @Test
    fun `the permutation parity must match the distance the blank has to travel`() {
        val goal = SlideBoard.identityGoal(9)
        fun solvable(vararg tiles: Int) = SlidePuzzleSolver.solve(SlideBoard(3, 3, tiles, 8), goal, budgetMs = 60_000) != null
        assertTrue(solvable(0, 1, 2, 3, 4, 5, 6, 8, 7), "odd permutation, blank one step away")
        assertTrue(solvable(1, 0, 3, 2, 4, 5, 6, 7, 8), "even permutation, blank home")
        assertTrue(!solvable(1, 0, 2, 3, 4, 5, 6, 7, 8), "odd permutation, blank home")
        assertTrue(!solvable(1, 0, 2, 3, 4, 5, 6, 8, 7), "even permutation, blank one step away")
    }

    @Test
    fun `a board whose pieces differ from the goal is refused at once`() {
        val board = SlideBoard(3, 3, intArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8), 8)
        val goal = intArrayOf(0, 1, 2, 3, 4, 5, 6, 9, 8)
        assertNull(SlidePuzzleSolver.solve(board, goal, budgetMs = 60_000))
    }

    @Test
    fun `a single row only moves its blank, so the order of the other pieces must already match`() {
        val goal = SlideBoard.identityGoal(6)
        val reachable = SlideBoard(1, 6, intArrayOf(0, 5, 1, 2, 3, 4), 5)
        val moves = assertNotNull(SlidePuzzleSolver.solve(reachable, goal))
        assertEquals(4, moves.size)
        assertTrue(moves.fold(reachable) { board, move -> board.applied(move) }.isSolved(goal))
        assertNull(SlidePuzzleSolver.solve(SlideBoard(1, 6, intArrayOf(1, 0, 2, 3, 4, 5), 5), goal, budgetMs = 60_000))
    }
}
