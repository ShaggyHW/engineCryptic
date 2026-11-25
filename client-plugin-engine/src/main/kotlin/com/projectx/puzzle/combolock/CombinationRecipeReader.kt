package com.projectx.puzzle.combolock

import com.projectx.puzzle.PuzzleClient
import world.gregs.voidps.gameval.Gameval

object CombinationRecipeReader {
    const val INTERFACE_ID = 220

    private const val SCROLL_LINES = 15

    private val lineComponents: List<Int> by lazy {
        (1..SCROLL_LINES).map { Gameval.requireComponentId("messagescroll:messcroll$it") }
    }

    fun isOpen(): Boolean = PuzzleClient.isOpen(INTERFACE_ID)

    fun read(): String? {
        if (!PuzzleClient.isOpen(INTERFACE_ID)) return null
        val text = lineComponents.mapNotNull { component -> PuzzleClient.text(INTERFACE_ID, component)?.takeIf { it.isNotBlank() } }
        return CombinationRecipeParser.parse(text)
    }
}
