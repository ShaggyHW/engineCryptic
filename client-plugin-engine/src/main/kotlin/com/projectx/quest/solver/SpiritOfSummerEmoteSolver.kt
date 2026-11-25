package com.projectx.quest.solver

import com.projectx.quest.data.Quest
import com.projectx.quest.data.QuestStep
import com.projectx.ui.backend.dsl.utils.ImGuiColors

/**
 * Spirit of Summer's mimic: answer each of the man's emotes with a valid response, never the same one twice in a
 * row. Solved once the girl, seen inside her rings earlier, stands free of them.
 */
object SpiritOfSummerEmoteSolver : QuestStepSolver {
    override val id get() = "spirit-of-summer.emotes"

    private enum class Emote(val slot: Int, val seqName: String?) {
        Yes(0, "emote_yes"), No(1, "emote_no"), Bow(2, "emote_bow"), Angry(3, "emote_angry"),
        Think(4, null), Wave(5, "emote_wave"), Shrug(6, "emote_shrug"), Cheer(7, "emote_cheer"),
    }

    private val answers = mapOf(
        Emote.Yes to listOf(Emote.No, Emote.Angry, Emote.Wave),
        Emote.No to listOf(Emote.Angry, Emote.Wave, Emote.Cheer),
        Emote.Bow to listOf(Emote.Yes, Emote.No, Emote.Angry, Emote.Shrug),
        Emote.Angry to listOf(Emote.Bow, Emote.Shrug, Emote.Cheer),
        Emote.Wave to listOf(Emote.Angry, Emote.Bow, Emote.Shrug),
        Emote.Shrug to listOf(Emote.Yes, Emote.No, Emote.Wave, Emote.Cheer),
        Emote.Cheer to listOf(Emote.Yes, Emote.Bow, Emote.Wave),
    )

    private val emoteBySeq by lazy { Emote.entries.mapNotNull { e -> e.seqName?.let { Names.seq(it) to e } }.toMap() }
    private val man by lazy { Names.npc("sum1_ghost_erik_bonde_no_wander") }
    private val ringedGirl by lazy { (1..5).map { Names.npc("sum1_summer_in_barrier_0$it") }.toSet() }
    private val freedGirl by lazy { Names.npc("sum1_ghost_summer_bonde1") }
    private val emoteBook by lazy { Names.iface("emotes2") }
    private val emoteGrid by lazy { Names.component("emotes2", "main") }

    private var ringsSeen = false
    private var manWasEmoting = false
    private var chosen: Emote? = null
    private var lastGiven: Emote? = null

    override fun evaluate(quest: Quest, step: QuestStep, stepIndex: Int) = advise()

    private fun advise(): QuestStepSolver.Result {
        val around = SolverWorld.npcs()
        if (around.any { it.id in ringedGirl }) ringsSeen = true
        if (ringsSeen && around.any { it.id == freedGirl }) {
            ringsSeen = false
            manWasEmoting = false
            chosen = null
            lastGiven = null
            return SOLVED
        }
        val shown = around.firstOrNull { it.id == man }?.let { emoteBySeq[it.animation] ?: emoteBySeq[it.renderAnim] }
        if (shown != null && !manWasEmoting) {
            val options = answers.getValue(shown)
            val reply = options.firstOrNull { it != lastGiven } ?: options.first()
            chosen = reply
            lastGiven = reply
        }
        manWasEmoting = shown != null
        val press = chosen ?: Emote.Think
        return QuestStepSolver.Result(listOf(buttonMark(emoteBook, emoteGrid, press.name, ImGuiColors.CYAN, press.slot)))
    }
}
