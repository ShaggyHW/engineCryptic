package com.projectx.puzzle.combolock

object FremennikRiddle {
    const val MARKER = "my first is in"

    private const val ARTICLE = "the "
    private val KEYWORD_ANSWERS = listOf(
        "mage" to "MIND",
        "tar" to "TREE",
        "well" to "LIFE",
        "fish" to "FIRE",
        "water" to "TIME",
        "wizard" to "WIND",
    )

    fun answerFrom(text: String): String? {
        val spoken = text.lowercase()
        val markerAt = spoken.indexOf(MARKER)
        if (markerAt < 0) return null
        var cursor = skipWhitespace(spoken, markerAt + MARKER.length)
        if (spoken.startsWith(ARTICLE, cursor)) cursor = skipWhitespace(spoken, cursor + ARTICLE.length)
        var end = cursor
        while (end < spoken.length && spoken[end].isLetter()) end++
        val keyword = spoken.substring(cursor, end)
        return KEYWORD_ANSWERS.firstOrNull { it.first == keyword }?.second
    }

    fun answerMatching(contains: (String) -> Boolean): String? {
        for ((keyword, answer) in KEYWORD_ANSWERS) {
            if (contains("$MARKER $keyword") || contains("$MARKER $ARTICLE$keyword")) return answer
        }
        return null
    }

    private fun skipWhitespace(text: String, from: Int): Int {
        var at = from
        while (at < text.length && text[at].isWhitespace()) at++
        return at
    }
}
