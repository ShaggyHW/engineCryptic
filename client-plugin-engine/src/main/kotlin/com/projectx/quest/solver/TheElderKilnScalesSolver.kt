package com.projectx.quest.solver

import com.projectx.quest.data.Quest
import com.projectx.quest.data.QuestStep

/** The Elder Kiln's ancient scales: reads the TzHaar equation off the scale and says how much TokKul balances it. */
internal object TheElderKilnScalesSolver : QuestStepSolver {
    override val id get() = "the-elder-kiln.ancient-scales"

    private const val SCALES = "tzhaar2_scales"

    private val scales by lazy { Names.iface(SCALES) }
    private val numerals by lazy { (1..4).map { Names.component(SCALES, "number_$it") } }
    private val signs by lazy { (1..3).map { Names.component(SCALES, "sign_$it") } }

    override fun evaluate(quest: Quest, step: QuestStep, stepIndex: Int) = advise()

    private fun advise(): QuestStepSolver.Result {
        val reading = if (SolverWorld.isOpen(scales)) readScale() else null
        if (reading == null) {
            return adviceOnly(
                "Open a scale - I'll read its equation and give the exact TokKul to place.",
                "TzHaar numerals are base-12: a..l = 1..12, \"a f\" = 1×12+6 = 18 (not 16!). Xo = add, Zi = subtract.",
            )
        }
        return adviceOnly("Scale: ${reading.written} = ${reading.total}", "Place ${reading.total} TokKul on the scale.")
    }

    private fun readScale(): ScaleReading? {
        val values = numerals.map { SolverWorld.text(scales, it) ?: return null }
        val operators = signs.map { SolverWorld.text(scales, it) ?: return null }
        return scaleReading(values, operators)
    }
}

internal class ScaleReading(val written: String, val total: Int)

/** Base-12 TzHaar numeral: glyphs a..l are 1..12, most significant first; null for anything else or zero. */
internal fun tzhaarNumeral(text: String): Int? {
    val glyphs = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    if (glyphs.isEmpty()) return null
    var value = 0
    for (glyph in glyphs) {
        val letter = glyph.singleOrNull()?.lowercaseChar() ?: return null
        if (letter !in 'a'..'l') return null
        value = value * 12 + (letter - 'a' + 1)
    }
    return if (value == 0) null else value
}

/** The scale's equation evaluated left to right, `Xo` adding and `Zi` subtracting; null unless all of it reads. */
internal fun scaleReading(numerals: List<String>, operators: List<String>): ScaleReading? {
    if (numerals.size != operators.size + 1) return null
    val values = numerals.map { tzhaarNumeral(it) ?: return null }
    val adds = operators.map {
        when (it.trim().lowercase()) {
            "xo" -> true
            "zi" -> false
            else -> return null
        }
    }
    var total = values.first()
    val written = StringBuilder(values.first().toString())
    adds.forEachIndexed { i, add ->
        val next = values[i + 1]
        total += if (add) next else -next
        written.append(if (add) " + " else " - ").append(next)
    }
    return ScaleReading(written.toString(), total)
}
