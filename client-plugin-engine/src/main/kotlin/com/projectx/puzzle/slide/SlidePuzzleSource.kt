package com.projectx.puzzle.slide

interface SlidePuzzleSource {
    val id: String
    val rows: Int
    val cols: Int

    fun isActive(): Boolean

    fun readBoard(): SlideBoard?

    fun goal(): IntArray = SlideBoard.identityGoal(rows * cols)
}
