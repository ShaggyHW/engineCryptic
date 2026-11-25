package com.projectx.quest.solver

import com.projectx.quest.data.Quest
import com.projectx.quest.data.QuestAction
import com.projectx.quest.data.QuestStep
import com.projectx.quest.runtime.QuestInstanceTracker
import world.gregs.voidps.type.Tile

/**
 * Lunar Diplomacy's dream-puff chasm. Every puff is the same loc, so the safe route is learned from the player's
 * own jumps: a puff that led further from the start is safe, one that dropped them back is not.
 */
object LunarDiplomacyDreamPuffSolver : QuestStepSolver {
    override val id get() = "lunar-diplomacy.dream-puff"

    private const val PUFF_REACH = 12
    private const val SETTLED_TICKS = 3

    private val puffLoc by lazy { Names.loc("quest_lunar_dream_puff_platform_multi") }

    private class Standing(val puff: Tile, val progress: Int, var ticks: Int = 1)

    private var watching = false
    private var instanceSeen: QuestInstanceTracker.Origin? = null
    private var start: Tile? = null
    private var standing: Standing? = null
    private val safe: MutableSet<Tile> = LinkedHashSet()
    private val unsafe: MutableSet<Tile> = HashSet()

    override fun evaluate(quest: Quest, step: QuestStep, stepIndex: Int) = advise()

    private fun advise(): QuestStepSolver.Result {
        val instance = QuestInstanceTracker.origin
        if (!watching || instance != instanceSeen) {
            watching = true
            instanceSeen = instance
            start = null
            standing = null
            safe.clear()
            unsafe.clear()
        }
        val here = SolverWorld.playerTile() ?: return adviceOnly("Move to the chasm to start tracking the dream-puff route.")
        val puff = puffUnder(here)
        if (puff != null) stepOnto(puff, progressAt(here)) else stepOff(here)
        val summary = if (safe.isEmpty()) {
            "Jump the puffs to learn the safe route - it will highlight as you go."
        } else {
            "Safe puffs learned: ${safe.size} (highlighted). Retrace them after a fall."
        }
        return QuestStepSolver.Result(listOf<QuestAction>(advice(summary)) + safe.map { locMark(puffLoc, "Safe puff", it) })
    }

    private fun puffUnder(here: Tile): Tile? = SolverWorld.locs(PUFF_REACH).firstOrNull { loc ->
        (loc.type == puffLoc || loc.visible == puffLoc) && loc.tile.plane == here.plane && here in loc.footprint()
    }?.tile

    private fun progressAt(here: Tile): Int = start?.takeIf { it.plane == here.plane }?.let { chebyshev(it, here) } ?: 0

    private fun stepOnto(puff: Tile, progress: Int) {
        val previous = standing
        if (previous != null && previous.puff == puff) {
            previous.ticks++
        } else {
            if (previous != null && progress > previous.progress) confirm(previous.puff, safeToJump = true)
            standing = Standing(puff, progress)
        }
        if ((standing?.ticks ?: 0) >= SETTLED_TICKS) confirm(puff, safeToJump = true)
    }

    private fun stepOff(here: Tile) {
        if (start == null) start = here
        val left = standing
        if (left != null) {
            confirm(left.puff, safeToJump = progressAt(here) > left.progress)
            standing = null
        } else if (progressAt(here) == 0) {
            start = here
        }
    }

    private fun confirm(puff: Tile, safeToJump: Boolean) {
        if (safeToJump) {
            unsafe.remove(puff)
            safe.add(puff)
        } else {
            safe.remove(puff)
            unsafe.add(puff)
        }
    }
}
