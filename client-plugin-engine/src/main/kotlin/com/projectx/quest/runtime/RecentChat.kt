package com.projectx.quest.runtime

/** The newest chat lines with their arrival times, written by the chat hook and searched from anywhere. */
object RecentChat {
    private const val KEPT_LINES = 64
    private const val RECENT_WINDOW_MS = 8_000L

    private class Line(val receivedAt: Long, val message: String)

    private val lines = ArrayDeque<Line>(KEPT_LINES)

    fun record(message: String) {
        if (message.isNotBlank()) append(Line(System.currentTimeMillis(), message))
    }

    fun latestMatch(regex: Regex): MatchResult? = newestFirst().firstNotNullOfOrNull { regex.find(it.message) }

    fun containsSince(needle: String, sinceMillis: Long): Boolean =
        needle.isNotBlank() && newestSince(needle, sinceMillis) != null

    fun findSince(needle: String, sinceMillis: Long): String? = newestSince(needle, sinceMillis)

    fun containsRecent(needle: String, windowMs: Long = RECENT_WINDOW_MS): Boolean =
        containsSince(needle, System.currentTimeMillis() - windowMs)

    private fun append(line: Line) = synchronized(lines) {
        while (lines.size >= KEPT_LINES) lines.removeFirst()
        lines.addLast(line)
    }

    private fun newestFirst(): List<Line> = synchronized(lines) { lines.reversed() }

    private fun newestSince(needle: String, sinceMillis: Long): String? =
        newestFirst().firstOrNull { it.receivedAt >= sinceMillis && it.message.contains(needle, ignoreCase = true) }?.message
}
