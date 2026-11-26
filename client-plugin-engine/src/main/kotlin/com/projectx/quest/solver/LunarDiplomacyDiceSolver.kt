package com.projectx.quest.solver

import com.projectx.quest.data.Quest
import com.projectx.quest.data.QuestAction
import com.projectx.quest.data.QuestStep
import com.projectx.quest.runtime.RecentChat
import world.gregs.voidps.type.Tile

/** Lunar Diplomacy's dicing: rolling a die turns it to the opposite face; reach the Ethereal Fluke's sum. */
internal object LunarDiplomacyDiceSolver : QuestStepSolver {
    override val id get() = "lunar-diplomacy.dice"

    private const val DICE_REACH = 15

    private val faceOfLoc by lazy {
        (1..6).flatMap { face ->
            listOf(Names.loc("quest_lunar_dice_${face}up") to face, Names.loc("quest_lunar_dice_${face}up_multi") to face)
        }.toMap()
    }
    private val calledNumber = Regex("\\sis[:\\s]*(\\d+)", RegexOption.IGNORE_CASE)

    private class Die(val type: Int, val tile: Tile, val face: Int)

    private var target: Int? = null

    override fun evaluate(quest: Quest, step: QuestStep, stepIndex: Int) = advise()

    private fun advise(): QuestStepSolver.Result {
        val dice = SolverWorld.locs(DICE_REACH)
            .mapNotNull { loc -> faceOfLoc[loc.type]?.let { Die(loc.type, loc.tile, it) } }
            .onePerTile { it.tile }
        if (dice.none()) {
            target = null
            return adviceOnly("Stand by the dice and talk to the Ethereal Fluke to start.")
        }
        RecentChat.latestMatch(calledNumber)?.groupValues?.get(1)?.toIntOrNull()?.let { target = it }
        val sum = target ?: return adviceOnly("Waiting for the Ethereal Fluke to call a number.")
        val toRoll = diceToRoll(dice.map { it.face }, sum) ?: return adviceOnly("Target $sum - no valid combination for these dice.")
        if (toRoll.isEmpty()) return QuestStepSolver.Result(listOf(advice("Dice sum to $sum - puzzle complete.")), solved = true)
        val noun = if (toRoll.size > 1) "dice" else "die"
        val marks = toRoll.map { dice[it] }.map { locMark(it.type, "Roll -> ${7 - it.face}", it.tile) }
        return QuestStepSolver.Result(listOf<QuestAction>(advice("Target $sum - roll ${toRoll.size} highlighted $noun.")) + marks)
    }
}

/**
 * Which dice to roll so [faces] sum to [target], each die ending on its low or high face. Fewest rolls wins, ties
 * go to the smallest high-face mask, so the chosen set only shrinks as the player rolls. Null when no mix works.
 */
internal fun diceToRoll(faces: List<Int>, target: Int): List<Int>? {
    val low = faces.map { minOf(it, 7 - it) }
    var best: List<Int>? = null
    for (mask in 0 until (1 shl faces.size)) {
        val ends = low.mapIndexed { die, face -> if ((mask shr die) and 1 == 1) 7 - face else face }
        if (ends.sum() != target) continue
        val rolls = ends.indices.filter { ends[it] != faces[it] }
        if (best == null || rolls.size < best.size) best = rolls
    }
    return best
}
