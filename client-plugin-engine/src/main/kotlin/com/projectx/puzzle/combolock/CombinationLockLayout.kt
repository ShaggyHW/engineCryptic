package com.projectx.puzzle.combolock

import com.projectx.puzzle.PuzzleClient
import world.gregs.voidps.gameval.Gameval

class CombinationLockLayout(
    val interfaceId: Int,
    private val letterComponents: IntArray,
    private val backwardArrows: IntArray,
    private val forwardArrows: IntArray,
    val enterComponent: Int,
) {
    val dials: Int = letterComponents.size

    fun isOpen(): Boolean = PuzzleClient.isOpen(interfaceId)

    fun readDials(): CharArray? {
        val letters = CharArray(dials)
        for ((dial, component) in letterComponents.withIndex()) {
            val shown = PuzzleClient.text(interfaceId, component)?.firstOrNull { !it.isWhitespace() } ?: return null
            val letter = shown.uppercaseChar()
            if (letter !in 'A'..'Z') return null
            letters[dial] = letter
        }
        return letters
    }

    fun arrowComponent(dial: Int, direction: TurnDirection): Int = when (direction) {
        TurnDirection.FORWARD -> forwardArrows[dial]
        TurnDirection.BACKWARD -> backwardArrows[dial]
    }

    companion object {
        private val DIAL_SUFFIXES = listOf("a", "b", "c", "d")

        val GENERIC: CombinationLockLayout by lazy { fromNames("quest_charos_escape_room_lock", "rd") }
        val SEER: CombinationLockLayout by lazy { fromNames("seer_combolock", "seer") }

        private fun fromNames(interfaceName: String, dialPrefix: String): CombinationLockLayout {
            fun component(suffix: String) = Gameval.requireComponentId("$interfaceName:$suffix")
            fun perDial(tail: String) = DIAL_SUFFIXES.map { component("$dialPrefix$it$tail") }.toIntArray()
            return CombinationLockLayout(
                interfaceId = Gameval.requireId(Gameval.INTERFACE, interfaceName),
                letterComponents = perDial(""),
                backwardArrows = perDial("_left"),
                forwardArrows = perDial("_right"),
                enterComponent = component("3x1_positive"),
            )
        }
    }
}
