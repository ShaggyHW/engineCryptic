package com.projectx.quest.solver

import com.projectx.quest.data.Quest
import com.projectx.quest.data.QuestAction
import com.projectx.quest.data.QuestStep
import com.projectx.quest.runtime.RecentChat
import world.gregs.voidps.type.Tile

/** Lunar Diplomacy's number sequences: the Ethereal Numerator shows the start of a known pattern in chat. */
object LunarDiplomacyNumberPatternSolver : QuestStepSolver {
    override val id get() = "lunar-diplomacy.number-pattern"

    private const val TILE_REACH = 15

    private val tileLoc by lazy { (0..9).associateWith { Names.loc("lunar_dream_number_$it") } }
    private val shownDigits = Regex("(?<!\\d)\\d(?!\\d)(?:\\s*,\\s*\\d(?!\\d)){2,}")

    override fun evaluate(quest: Quest, step: QuestStep, stepIndex: Int) = advise()

    private fun advise(): QuestStepSolver.Result {
        val shown = RecentChat.latestMatch(shownDigits)?.value?.filter(Char::isDigit)?.map(Char::digitToInt)
            ?: return adviceOnly("Waiting for the Ethereal Numerator to show a pattern.")
        return when (val next = continuationOf(shown)) {
            PatternContinuation.Unclear -> adviceOnly("Pattern ${shown.joinToString(", ")} - need more numbers to identify it.")
            PatternContinuation.Finished -> adviceOnly("Pattern complete - wait for the next one.")
            is PatternContinuation.Clicks -> QuestStepSolver.Result(
                listOf<QuestAction>(advice("Click in order: ${next.digits.joinToString(" -> ")}")) + tileMarks(next.digits.distinct()),
            )
        }
    }

    private fun tileMarks(digits: List<Int>): List<QuestAction> {
        val near = SolverWorld.locs(TILE_REACH)
        return digits.mapNotNull { digit ->
            val type = tileLoc.getValue(digit)
            val tile: Tile = near.firstOrNull { it.type == type || it.visible == type }?.tile ?: return@mapNotNull null
            locMark(type, "Click $digit", tile)
        }
    }
}

internal sealed class PatternContinuation {
    data object Unclear : PatternContinuation()
    data object Finished : PatternContinuation()
    data class Clicks(val digits: List<Int>) : PatternContinuation()
}

private val numeratorPatterns = listOf(
    "013467", "1112131415", "1122334", "112311451", "12345", "13579", "142536", "162534",
    "192837", "235689", "263748", "342516", "736251", "86420", "97531", "987654",
).map { row -> row.map(Char::digitToInt) }

/** What follows [shown] in the one known pattern it starts, if exactly one continuation fits. */
internal fun continuationOf(shown: List<Int>): PatternContinuation {
    val longer = numeratorPatterns.filter { it.size > shown.size && it.subList(0, shown.size) == shown }
    if (longer.isEmpty()) return if (shown in numeratorPatterns) PatternContinuation.Finished else PatternContinuation.Unclear
    val tails = longer.map { it.drop(shown.size) }.distinct()
    return tails.singleOrNull()?.let(PatternContinuation::Clicks) ?: PatternContinuation.Unclear
}
