package com.projectx.puzzle.combolock

import kotlin.math.PI
import kotlin.math.atan2

data class ClueItem(val name: String, val amount: Int, val tileX: Int, val tileY: Int)

object CombinationClueReader {

    private const val CODE_LENGTH = 4
    private const val COIN_PILES = 4
    private const val CLEAN_HERB_CODE = "HERB"
    private val HERBS = listOf(
        "guam", "marrentill", "tarromin", "harralander", "ranarr", "toadflax", "irit", "avantoe", "kwuarm",
        "snapdragon", "cadantine", "lantadyme", "dwarf weed", "torstol", "spirit weed", "wergali", "bloodweed",
        "fellstalk", "arbuck", "goutweed",
    )
    private val LETTER_RUN = Regex("""\p{L}+""")

    fun derive(items: List<ClueItem>, anchorX: Int, anchorY: Int): String? = when {
        items.isEmpty() -> null
        items.all { it.name.equals("coins", ignoreCase = true) } -> coinNumerals(items, anchorX, anchorY)
        items.size == 1 -> singleItemWord(items.single().name)
        items.size == CODE_LENGTH -> initialsFarthestFirst(items, anchorX, anchorY)
        else -> null
    }

    private fun coinNumerals(piles: List<ClueItem>, anchorX: Int, anchorY: Int): String? {
        if (piles.size != COIN_PILES) return null
        val clockwise = piles.sortedBy { bearingFromNorth(it.tileX - anchorX, it.tileY - anchorY) }
        val code = StringBuilder(COIN_PILES)
        for (pile in clockwise) code.append(romanNumeral(pile.amount) ?: return null)
        return code.toString()
    }

    private fun bearingFromNorth(east: Int, north: Int): Double {
        val turn = atan2(east.toDouble(), north.toDouble())
        return if (turn < 0) turn + 2 * PI else turn
    }

    private fun singleItemWord(name: String): String? {
        val lower = name.lowercase()
        if ("grimy" !in lower && HERBS.any { it in lower }) return CLEAN_HERB_CODE
        val words = LETTER_RUN.findAll(name).map { it.value }.toList()
        words.firstOrNull { it.length == CODE_LENGTH }?.let { return it.uppercase() }
        val only = words.singleOrNull() ?: return null
        return if (only.length > CODE_LENGTH) only.takeLast(CODE_LENGTH).uppercase() else null
    }

    private fun initialsFarthestFirst(items: List<ClueItem>, anchorX: Int, anchorY: Int): String? {
        val farthestFirst = items.sortedByDescending { squaredDistance(it.tileX - anchorX, it.tileY - anchorY) }
        val code = StringBuilder(CODE_LENGTH)
        for (item in farthestFirst) code.append(item.name.trim().firstOrNull()?.uppercaseChar() ?: return null)
        return code.toString()
    }

    private fun squaredDistance(dx: Int, dy: Int): Long = dx.toLong() * dx + dy.toLong() * dy
}
