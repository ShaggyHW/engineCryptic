package com.projectx.quest.solver

import com.projectx.quest.data.Quest
import com.projectx.quest.data.QuestAction
import com.projectx.quest.data.QuestStep
import world.gregs.voidps.type.Tile

/**
 * The Brink of Extinction's memory pedestals: the game flashes a growing sequence and the player repeats it.
 * Flashes close together form a burst; a quiet gap ends the burst and folds it into the remembered sequence.
 */
object TheBrinkOfExtinctionPedestalsSolver : QuestStepSolver {
    override val id get() = "the-brink-of-extinction.memory-pedestals"

    private const val ROOM_REACH = 15
    private const val BURST_GAP_MS = 2_200L

    private val restingLoc by lazy { Names.loc("tzhaar3_memory_pedestal") }
    private val litLoc by lazy { Names.loc("tzhaar3_memory_pedestal_on") }

    private class Pedestal(val number: Int, val tile: Tile, val type: Int, val lit: Boolean)

    private var layout: Set<Tile> = emptySet()
    private var litBefore: Set<Int> = emptySet()
    private var burst: MutableList<Int>? = null
    private var lastFlashAt = 0L
    private var sequence: List<Int> = listOf()

    override fun evaluate(quest: Quest, step: QuestStep, stepIndex: Int) = advise()

    private fun advise(): QuestStepSolver.Result {
        val pedestals = SolverWorld.locs(ROOM_REACH)
            .filter { it.visible == restingLoc || it.visible == litLoc }
            .onePerTile { it.tile }
            .mapIndexed { index, loc -> Pedestal(index + 1, loc.tile, loc.visible, loc.visible == litLoc) }
        if (pedestals.size <= 1) {
            startOver()
            return adviceOnly("Enter the pedestal room - I'll watch which ones light up and record the order.")
        }
        val tiles = pedestals.map { it.tile }.toSet()
        if (tiles != layout) {
            startOver()
            layout = tiles
        }
        watch(pedestals, System.currentTimeMillis())
        val open = burst
        return when {
            open != null -> QuestStepSolver.Result(
                listOf<QuestAction>(advice("Watching sequence: ${open.joinToString(" ")}")) + pedestals.filter { it.lit }.map(::mark),
            )
            sequence.isEmpty() -> adviceOnly("Touch a pedestal to start, then watch the flash order.")
            else -> QuestStepSolver.Result(listOf<QuestAction>(advice("Touch order: ${sequence.joinToString(" -> ")}")) + pedestals.map(::mark))
        }
    }

    private fun watch(pedestals: List<Pedestal>, now: Long) {
        burst?.let { if (now - lastFlashAt > BURST_GAP_MS) closeBurst(it) }
        for (pedestal in pedestals) {
            if (!pedestal.lit || pedestal.number in litBefore) continue
            val current = burst ?: mutableListOf<Int>().also { burst = it }
            current += pedestal.number
            lastFlashAt = now
        }
        litBefore = pedestals.filter { it.lit }.mapTo(HashSet()) { it.number }
    }

    private fun closeBurst(flashes: List<Int>) {
        sequence = foldBurst(sequence, flashes.toList())
        burst = null
    }

    private fun mark(pedestal: Pedestal) = locMark(pedestal.type, pedestal.number.toString(), pedestal.tile)

    private fun startOver() {
        layout = emptySet()
        litBefore = emptySet()
        burst = null
        lastFlashAt = 0L
        sequence = listOf()
    }
}

/** How a finished burst of flashes changes the remembered [sequence]. */
internal fun foldBurst(sequence: List<Int>, flashes: List<Int>): List<Int> = when {
    flashes == sequence -> sequence
    flashes.size > sequence.size && flashes.subList(0, sequence.size) == sequence -> flashes
    flashes.size < sequence.size && sequence.subList(0, flashes.size) == flashes -> sequence
    flashes.size >= 2 || sequence.isEmpty() -> flashes
    else -> sequence
}
