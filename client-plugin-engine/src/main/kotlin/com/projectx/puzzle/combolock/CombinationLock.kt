package com.projectx.puzzle.combolock

enum class TurnDirection { FORWARD, BACKWARD }

data class DialMove(val direction: TurnDirection, val clicks: Int) {
    val solved: Boolean
        get() = clicks == 0
}

object CombinationLock {

    private const val LETTERS = 26

    fun move(current: Char, target: Char): DialMove {
        val forward = Math.floorMod(target.uppercaseChar() - current.uppercaseChar(), LETTERS)
        val backward = (LETTERS - forward) % LETTERS
        return if (forward <= backward) DialMove(TurnDirection.FORWARD, forward) else DialMove(TurnDirection.BACKWARD, backward)
    }
}
