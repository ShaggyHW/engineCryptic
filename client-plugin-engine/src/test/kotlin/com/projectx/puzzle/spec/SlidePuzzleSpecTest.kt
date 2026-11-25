package com.projectx.puzzle.spec

import com.projectx.puzzle.slide.Direction
import com.projectx.puzzle.slide.InterfaceSlidePuzzleSource
import com.projectx.puzzle.slide.SlideBoard
import com.projectx.puzzle.slide.SlideMove
import com.projectx.puzzle.slide.SlidePuzzleSolver
import com.projectx.puzzle.slide.SlidePuzzleSource
import java.util.ArrayDeque
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SlidePuzzleSpecTest {

    private fun board(rows: Int, cols: Int, vararg tiles: Int, blank: Int = rows * cols - 1) =
        SlideBoard(rows, cols, tiles, blank)

    @Test
    fun `slots are numbered row by row`() {
        val board = SlideBoard(3, 4, SlideBoard.identityGoal(12), 11)
        assertEquals(12, board.size)
        assertEquals(3, board.rows)
        assertEquals(4, board.cols)
        assertEquals(1, board.rowOf(6))
        assertEquals(2, board.colOf(6))
        assertEquals(6, board.slotOf(1, 2))
        assertEquals(11, board.slotOf(2, 3))
        assertEquals(11, board.blankId)
        assertEquals(11, board.blankSlot())
    }

    @Test
    fun `the identity goal puts every piece on its own slot`() {
        assertContentEquals(intArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8), SlideBoard.identityGoal(9))
        assertContentEquals(intArrayOf(), SlideBoard.identityGoal(0))
    }

    @Test
    fun `a board must be a positive grid with one piece per slot`() {
        assertFailsWith<IllegalArgumentException> { SlideBoard(0, 3, IntArray(0), 0) }
        assertFailsWith<IllegalArgumentException> { SlideBoard(3, -1, IntArray(3), 0) }
        assertFailsWith<IllegalArgumentException> { SlideBoard(2, 2, IntArray(5) { it }, 3) }
    }

    @Test
    fun `asking for a missing blank fails`() {
        val board = board(2, 2, 0, 1, 2, 3, blank = 7)
        assertFailsWith<IllegalArgumentException> { board.blankSlot() }
    }

    @Test
    fun `solved means the pieces match the goal exactly`() {
        val goal = SlideBoard.identityGoal(4)
        assertTrue(board(2, 2, 0, 1, 2, 3).isSolved(goal))
        assertFalse(board(2, 2, 0, 1, 3, 2).isSolved(goal))
        assertFalse(board(2, 2, 0, 1, 2, 3).isSolved(SlideBoard.identityGoal(9)))
    }

    @Test
    fun `applying a move slides the tile into the hole and leaves the original untouched`() {
        val start = board(3, 3, 0, 1, 2, 3, 4, 5, 6, 8, 7)
        val moved = start.applied(SlideMove(tileSlot = 8, blankSlot = 7, direction = Direction.LEFT))
        assertContentEquals(intArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8), moved.tiles)
        assertEquals(8, moved.blankSlot())
        assertContentEquals(intArrayOf(0, 1, 2, 3, 4, 5, 6, 8, 7), start.tiles)
        assertEquals(3, moved.rows)
        assertEquals(3, moved.cols)
        assertEquals(8, moved.blankId)
    }

    @Test
    fun `the signature follows the arrangement`() {
        val a = board(2, 2, 0, 1, 2, 3)
        val b = board(2, 2, 0, 1, 2, 3)
        val c = board(2, 2, 0, 1, 3, 2)
        assertEquals(a.signature(), b.signature())
        assertNotEquals(a.signature(), c.signature())
        assertNotEquals(board(2, 2, 1, 0, 2, 3).signature(), board(2, 2, 0, 1, 2, 3).signature())
    }

    @Test
    fun `moves are value objects naming the clicked slot, the hole and the travel direction`() {
        val move = SlideMove(4, 1, Direction.UP)
        assertEquals(SlideMove(4, 1, Direction.UP), move)
        val (tile, hole, direction) = move
        assertEquals(4, tile)
        assertEquals(1, hole)
        assertEquals(Direction.UP, direction)
        assertEquals(SlideMove(4, 5, Direction.RIGHT), move.copy(blankSlot = 5, direction = Direction.RIGHT))
        assertEquals(listOf(Direction.UP, Direction.DOWN, Direction.LEFT, Direction.RIGHT), Direction.entries)
    }

    @Test
    fun `the direction between neighbouring slots is the way the tile travels`() {
        assertEquals(Direction.UP, SlidePuzzleSolver.directionBetween(7, 2, 5))
        assertEquals(Direction.DOWN, SlidePuzzleSolver.directionBetween(2, 7, 5))
        assertEquals(Direction.LEFT, SlidePuzzleSolver.directionBetween(8, 7, 5))
        assertEquals(Direction.RIGHT, SlidePuzzleSolver.directionBetween(7, 8, 5))
        assertFailsWith<IllegalStateException> { SlidePuzzleSolver.directionBetween(0, 2, 5) }
        assertFailsWith<IllegalStateException> { SlidePuzzleSolver.directionBetween(0, 6, 5) }
    }

    @Test
    fun `a solved board needs no moves`() {
        for ((rows, cols) in listOf(2 to 2, 3 to 3, 4 to 4, 5 to 5, 3 to 5)) {
            val goal = SlideBoard.identityGoal(rows * cols)
            assertEquals(emptyList(), SlidePuzzleSolver.solve(SlideBoard(rows, cols, goal.copyOf(), rows * cols - 1), goal))
        }
    }

    @Test
    fun `a board one slide away is solved in one slide`() {
        val start = board(3, 3, 0, 1, 2, 3, 4, 8, 6, 7, 5)
        val moves = assertNotNull(SlidePuzzleSolver.solve(start, SlideBoard.identityGoal(9)))
        assertEquals(listOf(SlideMove(8, 5, Direction.UP)), moves)
    }

    @Test
    fun `a goal of the wrong size is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            SlidePuzzleSolver.solve(board(2, 2, 0, 1, 2, 3), SlideBoard.identityGoal(9))
        }
    }

    @Test
    fun `small boards are solved in the fewest possible slides`() {
        val rng = Random(1931)
        for ((rows, cols) in listOf(2 to 2, 2 to 3, 3 to 2, 3 to 3)) {
            val goal = SlideBoard.identityGoal(rows * cols)
            repeat(40) {
                val start = scrambled(rows, cols, goal, rows * cols - 1, rng, 60)
                val moves = assertNotNull(SlidePuzzleSolver.solve(start, goal))
                assertReaches(start, goal, moves)
                assertEquals(shortestDistance(start, goal), moves.size, "${rows}x$cols ${start.tiles.toList()}")
            }
        }
    }

    @Test
    fun `the hardest eight puzzle takes thirty one slides`() {
        val start = board(3, 3, 7, 5, 6, 1, 4, 3, 2, 8, 0)
        val goal = SlideBoard.identityGoal(9)
        val moves = assertNotNull(SlidePuzzleSolver.solve(start, goal))
        assertReaches(start, goal, moves)
        assertEquals(31, moves.size)
    }

    @Test
    fun `a three by three goal may put the hole anywhere`() {
        val goal = intArrayOf(3, 0, 5, 7, 8, 1, 6, 2, 4)
        val rng = Random(115)
        repeat(30) {
            val start = scrambled(3, 3, goal, 8, rng, 40)
            val moves = assertNotNull(SlidePuzzleSolver.solve(start, goal))
            assertReaches(start, goal, moves)
            assertEquals(shortestDistance(start, goal), moves.size)
        }
    }

    @Test
    fun `small boards that cannot be solved give nothing once the budget is spent`() {
        val twoByTwo = board(2, 2, 1, 0, 2, 3)
        assertNull(SlidePuzzleSolver.solve(twoByTwo, SlideBoard.identityGoal(4), budgetMs = 50))
        val threeByThree = board(3, 3, 1, 0, 2, 3, 4, 5, 6, 7, 8)
        assertNull(SlidePuzzleSolver.solve(threeByThree, SlideBoard.identityGoal(9), budgetMs = 100))
    }

    @Test
    fun `a puzzle box picture is solved within the default budget`() {
        val start = board(
            5, 5,
            5, 0, 2, 4, 9,
            1, 7, 3, 8, 14,
            6, 11, 12, 13, 19,
            10, 15, 16, 24, 18,
            20, 21, 17, 22, 23,
            blank = 24,
        )
        val goal = SlideBoard.identityGoal(25)
        val moves = assertNotNull(SlidePuzzleSolver.solve(start, goal))
        assertReaches(start, goal, moves)
    }

    @Test
    fun `large and rectangular boards are solved by any valid sequence`() {
        val rng = Random(24)
        for ((rows, cols) in listOf(4 to 4, 5 to 5, 3 to 4, 4 to 3, 2 to 5, 5 to 2, 4 to 5, 6 to 6)) {
            val goal = SlideBoard.identityGoal(rows * cols)
            repeat(15) {
                val start = scrambled(rows, cols, goal, rows * cols - 1, rng, 2000)
                val moves = assertNotNull(SlidePuzzleSolver.solve(start, goal), "${rows}x$cols ${start.tiles.toList()}")
                assertReaches(start, goal, moves)
            }
        }
    }

    @Test
    fun `a large board that cannot be solved gives nothing`() {
        val tiles = SlideBoard.identityGoal(25).also { it[0] = 1; it[1] = 0 }
        assertNull(SlidePuzzleSolver.solve(SlideBoard(5, 5, tiles, 24), SlideBoard.identityGoal(25), budgetMs = 200))
    }

    @Test
    fun `solving does not change the board it is given`() {
        val start = board(3, 3, 1, 2, 5, 0, 4, 8, 3, 6, 7)
        val before = start.tiles.copyOf()
        SlidePuzzleSolver.solve(start, SlideBoard.identityGoal(9))
        assertContentEquals(before, start.tiles)
    }

    @Test
    fun `an interface puzzle reports its shape and solves to the identity`() {
        val source = InterfaceSlidePuzzleSource("puzzle-box", 1931, 18, 5, 5, 39405, 24)
        assertEquals("puzzle-box", source.id)
        assertEquals(1931, source.interfaceId)
        assertEquals(18, source.gridComponentId)
        assertEquals(5, source.rows)
        assertEquals(5, source.cols)
        assertContentEquals(SlideBoard.identityGoal(25), source.goal())
    }

    @Test
    fun `a puzzle source solves to the identity unless it says otherwise`() {
        val plain = object : SlidePuzzleSource {
            override val id = "plain"
            override val rows = 2
            override val cols = 3
            override fun isActive() = true
            override fun readBoard(): SlideBoard? = null
        }
        assertContentEquals(intArrayOf(0, 1, 2, 3, 4, 5), plain.goal())
        val custom = object : SlidePuzzleSource by plain {
            override fun goal() = intArrayOf(5, 4, 3, 2, 1, 0)
        }
        assertContentEquals(intArrayOf(5, 4, 3, 2, 1, 0), custom.goal())
    }

    private fun assertReaches(start: SlideBoard, goal: IntArray, moves: List<SlideMove>) {
        var current = start
        for (move in moves) {
            assertEquals(current.blankSlot(), move.blankSlot, "move $move does not start at the hole")
            val dr = current.rowOf(move.blankSlot) - current.rowOf(move.tileSlot)
            val dc = current.colOf(move.blankSlot) - current.colOf(move.tileSlot)
            val expected = when (dr to dc) {
                -1 to 0 -> Direction.UP
                1 to 0 -> Direction.DOWN
                0 to -1 -> Direction.LEFT
                0 to 1 -> Direction.RIGHT
                else -> error("move $move is not between neighbours")
            }
            assertEquals(expected, move.direction)
            current = current.applied(move)
        }
        assertTrue(current.isSolved(goal), "moves end at ${current.tiles.toList()}")
    }

    private fun scrambled(rows: Int, cols: Int, goal: IntArray, blank: Int, rng: Random, steps: Int): SlideBoard {
        val tiles = goal.copyOf()
        var hole = tiles.indexOf(blank)
        repeat(steps) {
            val options = neighbours(hole, rows, cols)
            val next = options[rng.nextInt(options.size)]
            tiles[hole] = tiles[next]
            tiles[next] = blank
            hole = next
        }
        return SlideBoard(rows, cols, tiles, blank)
    }

    private fun neighbours(slot: Int, rows: Int, cols: Int) = buildList {
        val r = slot / cols
        val c = slot % cols
        if (r > 0) add(slot - cols)
        if (r < rows - 1) add(slot + cols)
        if (c > 0) add(slot - 1)
        if (c < cols - 1) add(slot + 1)
    }

    private fun shortestDistance(start: SlideBoard, goal: IntArray): Int {
        val target = goal.toList()
        val seen = HashMap<List<Int>, Int>()
        val queue = ArrayDeque<List<Int>>()
        seen[start.tiles.toList()] = 0
        queue.add(start.tiles.toList())
        while (queue.isNotEmpty()) {
            val state = queue.poll()
            val depth = seen.getValue(state)
            if (state == target) return depth
            val hole = state.indexOf(start.blankId)
            for (next in neighbours(hole, start.rows, start.cols)) {
                val child = state.toMutableList().also { it[hole] = it[next]; it[next] = start.blankId }
                if (seen.putIfAbsent(child, depth + 1) == null) queue.add(child)
            }
        }
        error("unreachable goal")
    }
}
