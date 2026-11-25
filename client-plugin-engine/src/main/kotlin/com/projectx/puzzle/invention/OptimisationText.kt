package com.projectx.puzzle.invention

class Optimisation(val level: String, val baseXp: Int, val bonusXp: Int) {
    val perfect: Boolean
        get() = PERFECT_LEVEL.equals(level, ignoreCase = true)
}

private const val PERFECT_LEVEL = "Perfect"

object OptimisationText {

    private const val LABEL = "Optimisation:"
    private const val LINE_BREAK = "<br>"
    private val TAG_MARKUP = Regex("<[^>]*>")
    private val DIGITS = Regex("""\d+""")

    fun isPending(text: String): Boolean = text.contains("analys", ignoreCase = true)

    fun parse(text: String): Optimisation? {
        val level = when {
            text.isBlank() || isPending(text) -> return null
            else -> levelIn(text) ?: return null
        }
        val xp = DIGITS.findAll(TAG_MARKUP.replace(text, " ")).mapNotNull { it.value.toIntOrNull() }.take(2).toList()
        val first = xp.firstOrNull() ?: 0
        return Optimisation(level, first, xp.getOrElse(1) { first })
    }

    private fun levelIn(text: String): String? {
        var searchFrom = 0
        while (true) {
            val labelAt = text.indexOf(LABEL, searchFrom, ignoreCase = true)
            if (labelAt < 0) return null
            searchFrom = labelAt + LABEL.length
            var start = searchFrom
            while (start < text.length && text[start].isWhitespace()) start++
            val end = text.indexOf(LINE_BREAK, start, ignoreCase = true).takeIf { it >= 0 } ?: text.length
            val level = text.substring(start, end).trim()
            if (level.isNotEmpty() && level.none { it == '<' || it == '\n' || it == '\r' }) return level
        }
    }
}
