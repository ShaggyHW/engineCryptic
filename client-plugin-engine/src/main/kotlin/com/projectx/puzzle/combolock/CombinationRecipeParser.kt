package com.projectx.puzzle.combolock

object CombinationRecipeParser {

    private const val NAME_SEPARATOR = " of "
    private val AMOUNT = Regex("""\d+(?:,\d+)*""")

    fun parse(lines: List<String>, dials: Int = 4): String? {
        val entries = lines.mapNotNull(::ingredient)
        if (entries.size != dials) return null
        val numerals = entries.map { it.quantity?.let(::romanNumeral) }
        if (numerals.all { it != null }) return numerals.joinToString("")
        val initials = StringBuilder(dials)
        for (item in entries) initials.append(item.label.firstOrNull(Char::isLetter)?.uppercaseChar() ?: return null)
        return initials.toString()
    }

    private class Ingredient(val quantity: Int?, val label: String)

    private fun ingredient(line: String): Ingredient? {
        if (!line.trimStart().startsWith('-')) return null
        val split = line.indexOf(NAME_SEPARATOR, ignoreCase = true)
        if (split < 0) return null
        val label = line.substring(split + NAME_SEPARATOR.length).trim()
        if (label.isEmpty()) return null
        val quantity = AMOUNT.find(line)?.value?.replace(",", "")?.toIntOrNull()
        return Ingredient(quantity, label)
    }
}
