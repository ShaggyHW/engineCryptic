package com.projectx.puzzle.combolock

object CombinationLockInterface {
    const val INTERFACE_ID = 79
    const val ENTER_COMPONENT = 17
    const val DIALS = 4

    private val lock: CombinationLockLayout get() = CombinationLockLayout.GENERIC

    fun isOpen(): Boolean = lock.isOpen()

    fun readDials(): CharArray? = lock.readDials()

    fun arrowComponent(dial: Int, direction: TurnDirection): Int = lock.arrowComponent(dial, direction)
}
