package com.projectx.puzzle.slide

import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * Boards up to 3x3 are solved optimally by IDA*. Larger boards have their top rows and left columns placed one line at
 * a time by breadth-first searches over the positions of the blank and the one or two pieces being placed, until a 3x3
 * or smaller window is left for IDA*.
 */
object SlidePuzzleSolver {

    private const val OPTIMAL_SIDE = 3

    fun solve(board: SlideBoard, goal: IntArray, budgetMs: Long = 1000): List<SlideMove>? {
        require(goal.size == board.size) { "goal covers ${goal.size} slots but the board has ${board.size}" }
        when {
            board.isSolved(goal) -> return listOf()
            !reachable(board, goal) -> return null
        }
        val clock = Budget(budgetMs)
        val grid = WorkGrid(board)
        if (board.rows == 1 || board.cols == 1) {
            grid.walkBlankTo(goal.indexOf(board.blankId))
            return grid.moves
        }
        val window = reduceToWindow(grid, goal, clock) ?: return null
        return if (WindowSearch(window, goal, clock).run()) grid.moves else null
    }

    internal fun directionBetween(fromSlot: Int, toSlot: Int, cols: Int): Direction = when (toSlot - fromSlot) {
        -cols -> Direction.UP
        cols -> Direction.DOWN
        -1 -> Direction.LEFT
        1 -> Direction.RIGHT
        else -> error("slots $fromSlot and $toSlot are not neighbours on a $cols-wide board")
    }

    private fun reachable(board: SlideBoard, goal: IntArray): Boolean {
        val tiles = board.tiles
        val hole = board.blankId
        if (!tiles.sortedArray().contentEquals(goal.sortedArray())) return false
        if (tiles.count { it == hole } != 1) return false
        if (board.rows == 1 || board.cols == 1) return tiles.filter { it != hole } == goal.filter { it != hole }
        val home = HashMap<Int, Int>(goal.size * 2)
        goal.forEachIndexed { slot, piece -> home[piece] = slot }
        // A repeated piece can stand in for its twin, so either permutation parity is reachable.
        if (home.size < goal.size) return true
        val from = board.blankSlot()
        val to = home.getValue(hole)
        val blankTravel = abs(board.rowOf(from) - board.rowOf(to)) + abs(board.colOf(from) - board.colOf(to))
        return permutationParity(IntArray(tiles.size) { home.getValue(tiles[it]) }) == blankTravel % 2
    }

    private fun permutationParity(destination: IntArray): Int {
        val visited = BooleanArray(destination.size)
        var transpositions = 0
        for (start in destination.indices) {
            var cycle = 0
            var at = start
            while (!visited[at]) {
                visited[at] = true
                at = destination[at]
                cycle++
            }
            if (cycle > 1) transpositions += cycle - 1
        }
        return transpositions and 1
    }

    private fun reduceToWindow(grid: WorkGrid, goal: IntArray, clock: Budget): Window? {
        var window = Window(grid, 0, 0)
        while (window.height > OPTIMAL_SIDE || window.width > OPTIMAL_SIDE) {
            if (clock.spent) return null
            val topRow = window.height > OPTIMAL_SIDE && (window.height >= window.width || window.width <= OPTIMAL_SIDE)
            val line = if (topRow) IntArray(window.width) { it } else IntArray(window.height) { it * window.width }
            if (!LinePlacer(window, goal).place(line)) return null
            window = if (topRow) Window(grid, window.top + 1, window.left) else Window(grid, window.top, window.left + 1)
        }
        return window
    }
}

private class Budget(budgetMs: Long) {
    private val startedAt = System.nanoTime()
    private val allowance = TimeUnit.MILLISECONDS.toNanos(budgetMs)

    val spent: Boolean get() = System.nanoTime() - startedAt > allowance
}

private class WorkGrid(private val start: SlideBoard) {
    val rows: Int get() = start.rows
    val cols: Int get() = start.cols
    val blankId: Int get() = start.blankId
    val tiles: IntArray = start.tiles.copyOf()
    var blank = start.blankSlot()
        private set
    val moves = mutableListOf<SlideMove>()

    fun slide(slot: Int) {
        moves.add(SlideMove(slot, blank, SlidePuzzleSolver.directionBetween(slot, blank, cols)))
        tiles[blank] = tiles[slot]
        tiles[slot] = blankId
        blank = slot
    }

    fun walkBlankTo(target: Int) {
        val step = if (target > blank) 1 else -1
        while (blank != target) slide(blank + step)
    }
}

/** The unsolved bottom-right part of the grid, with cells numbered row-major from its own top-left corner. */
private class Window(val grid: WorkGrid, val top: Int, val left: Int) {
    val height = grid.rows - top
    val width = grid.cols - left
    val cells = height * width
    val adjacent: Array<IntArray> = Array(cells) { cell ->
        val row = cell / width
        val col = cell % width
        buildList {
            if (row > 0) add(cell - width)
            if (row < height - 1) add(cell + width)
            if (col > 0) add(cell - 1)
            if (col < width - 1) add(cell + 1)
        }.toIntArray()
    }

    fun slotOf(cell: Int): Int = (top + cell / width) * grid.cols + left + cell % width

    fun cellOf(slot: Int): Int = (slot / grid.cols - top) * width + slot % grid.cols - left

    fun pieceAt(cell: Int): Int = grid.tiles[slotOf(cell)]

    fun blankCell(): Int = cellOf(grid.blank)

    fun play(clicks: IntArray, count: Int = clicks.size) {
        for (i in 0 until count) grid.slide(slotOf(clicks[i]))
    }
}

private class LinePlacer(private val window: Window, private val goal: IntArray) {
    private val cells = window.cells
    private val settled = BooleanArray(cells)

    fun place(line: IntArray): Boolean {
        if (line.any { goal[window.slotOf(it)] == window.grid.blankId }) return false
        for (i in 0 until line.size - 2) {
            val target = line[i]
            window.play(routeOne(goal[window.slotOf(target)], target) ?: return false)
            settled[target] = true
        }
        val first = line[line.size - 2]
        val second = line[line.size - 1]
        window.play(routePair(goal[window.slotOf(first)], first, goal[window.slotOf(second)], second) ?: return false)
        return true
    }

    private fun holders(piece: Int): List<Int> = (0 until cells).filter { !settled[it] && window.pieceAt(it) == piece }

    private fun routeOne(piece: Int, target: Int): IntArray? {
        val blank = window.blankCell()
        val frontier = Frontier(cells * cells)
        for (cell in holders(piece)) frontier.seed(blank * cells + cell)
        while (frontier.hasNext()) {
            val state = frontier.next()
            val hole = state / cells
            val tile = state % cells
            if (tile == target) return frontier.clicksTo(state) { it / cells }
            for (step in window.adjacent[hole]) {
                if (settled[step]) continue
                frontier.offer(state, step * cells + if (step == tile) hole else tile)
            }
        }
        return null
    }

    private fun routePair(firstPiece: Int, firstTarget: Int, secondPiece: Int, secondTarget: Int): IntArray? {
        val blank = window.blankCell()
        val square = cells * cells
        val frontier = Frontier(square * cells)
        for (a in holders(firstPiece)) for (b in holders(secondPiece)) {
            if (a != b) frontier.seed(blank * square + a * cells + b)
        }
        while (frontier.hasNext()) {
            val state = frontier.next()
            val hole = state / square
            val a = state / cells % cells
            val b = state % cells
            if (a == firstTarget && b == secondTarget) return frontier.clicksTo(state) { it / square }
            for (step in window.adjacent[hole]) {
                if (settled[step]) continue
                val movedA = if (step == a) hole else a
                val movedB = if (step == b) hole else b
                frontier.offer(state, step * square + movedA * cells + movedB)
            }
        }
        return null
    }
}

private class Frontier(states: Int) {
    private val cameFrom = IntArray(states) { UNVISITED }
    private val queue = IntArray(states)
    private var head = 0
    private var tail = 0

    fun seed(state: Int) {
        if (cameFrom[state] != UNVISITED) return
        cameFrom[state] = ORIGIN
        queue[tail++] = state
    }

    fun offer(from: Int, state: Int) {
        if (cameFrom[state] != UNVISITED) return
        cameFrom[state] = from
        queue[tail++] = state
    }

    fun hasNext(): Boolean = head < tail

    fun next(): Int = queue[head++]

    /** Each transition moves the blank onto the clicked cell, so the clicks are the blank cells along the way. */
    fun clicksTo(end: Int, blankOf: (Int) -> Int): IntArray {
        val reversed = ArrayList<Int>()
        var state = end
        while (cameFrom[state] != ORIGIN) {
            reversed += blankOf(state)
            state = cameFrom[state]
        }
        return IntArray(reversed.size) { reversed[reversed.size - 1 - it] }
    }
}

private const val UNVISITED = -1
private const val ORIGIN = -2

private class WindowSearch(private val window: Window, goal: IntArray, private val clock: Budget) {
    private val cells = window.cells
    private val pieceIndex = HashMap<Int, Int>()
    private val blankIndex: Int
    private val board: IntArray
    private val distance: IntArray
    private val clicks = IntArray(MAX_DEPTH)
    private var bound = 0
    private var expanded = 0
    private var solutionLength = 0

    init {
        val target = IntArray(cells) { indexOf(goal[window.slotOf(it)]) }
        board = IntArray(cells) { indexOf(window.pieceAt(it)) }
        blankIndex = indexOf(window.grid.blankId)
        distance = IntArray(pieceIndex.size * cells)
        for (piece in 0 until pieceIndex.size) {
            if (piece == blankIndex) continue
            for (cell in 0 until cells) {
                distance[piece * cells + cell] = target.indices.filter { target[it] == piece }.minOf { manhattan(it, cell) }
            }
        }
    }

    private fun indexOf(piece: Int): Int = pieceIndex.getOrPut(piece) { pieceIndex.size }

    private fun manhattan(a: Int, b: Int): Int =
        abs(a / window.width - b / window.width) + abs(a % window.width - b % window.width)

    fun run(): Boolean {
        var estimate = 0
        for (cell in 0 until cells) estimate += distance[board[cell] * cells + cell]
        val blank = board.indexOf(blankIndex)
        bound = estimate
        while (true) {
            when (val outcome = descend(blank, 0, NO_PREVIOUS, estimate)) {
                FOUND -> {
                    window.play(clicks, solutionLength)
                    return true
                }
                OUT_OF_TIME, DEAD_END -> return false
                else -> bound = outcome
            }
        }
    }

    private fun descend(blank: Int, depth: Int, previous: Int, estimate: Int): Int {
        val total = depth + estimate
        if (total > bound) return total
        if (estimate == 0) {
            solutionLength = depth
            return FOUND
        }
        if (++expanded and CLOCK_CHECK_MASK == 0 && clock.spent) return OUT_OF_TIME
        if (depth == MAX_DEPTH) return DEAD_END
        var cheapest = DEAD_END
        for (next in window.adjacent[blank]) {
            if (next == previous) continue
            val piece = board[next]
            val change = distance[piece * cells + blank] - distance[piece * cells + next]
            board[blank] = piece
            board[next] = blankIndex
            clicks[depth] = next
            val outcome = descend(next, depth + 1, blank, estimate + change)
            board[next] = piece
            board[blank] = blankIndex
            if (outcome == FOUND || outcome == OUT_OF_TIME) return outcome
            if (outcome < cheapest) cheapest = outcome
        }
        return cheapest
    }

}

private const val FOUND = -1
private const val OUT_OF_TIME = -2
private const val NO_PREVIOUS = -1
private const val DEAD_END = Int.MAX_VALUE
private const val MAX_DEPTH = 128
private const val CLOCK_CHECK_MASK = 0x3FFF
