package com.projectx.quest.solver

import com.projectx.puzzle.combolock.ClueItem
import com.projectx.puzzle.combolock.CombinationClueReader
import com.projectx.puzzle.combolock.CombinationLockLayout
import com.projectx.puzzle.combolock.CombinationPlaqueReader
import com.projectx.puzzle.combolock.CombinationRecipeReader
import com.projectx.quest.data.Quest
import com.projectx.quest.data.QuestAction
import com.projectx.quest.data.QuestStep
import world.gregs.voidps.type.Tile

/**
 * Desperate Times' chest locks. Each room's code comes from a clue interface (the recipe scroll or the
 * lodestone plaque), remembered for the room, or failing that from the items laid on the floor.
 */
object DesperateTimesCombinationLockSolver : QuestStepSolver {
    override val id get() = "desperate-times.combination-lock"

    private const val ROOM_REACH = 8
    private const val PLAQUE = "quest_charos_escape_room_plaque"

    private val recipeNpc by lazy { Names.npc("quest_charos_escape_room_recipe") }
    private val plaqueLoc by lazy { Names.loc(PLAQUE) }
    private val codeForRoom = RoomMemory<String>(ROOM_REACH)

    override fun evaluate(quest: Quest, step: QuestStep, stepIndex: Int) = advise()

    private fun advise(): QuestStepSolver.Result {
        if (DesperateTimesSlidePuzzleSolver.isSolutionReady()) return SOLVED
        val here = SolverWorld.playerTile()
        val recipeOpen = CombinationRecipeReader.isOpen()
        val plaqueOpen = CombinationPlaqueReader.isOpen()
        val readNow = (if (recipeOpen) CombinationRecipeReader.read() else null) ?: (if (plaqueOpen) CombinationPlaqueReader.read() else null)
        if (readNow != null && here != null) codeForRoom.remember(readNow, here)
        val code = readNow ?: codeForRoom.recall(here) ?: here?.let(::codeFromFloor)
        code?.let { dialGuidance(CombinationLockLayout.GENERIC, it) }?.let { return QuestStepSolver.Result(it) }
        if (code == null && !recipeOpen && !plaqueOpen) return QuestStepSolver.Result(clueSources())
        return NOTHING
    }

    private fun codeFromFloor(here: Tile): String? {
        val laid = SolverWorld.groundNear(here, ROOM_REACH).map { ClueItem(it.name, it.amount, it.tile.x, it.tile.y) }
        return CombinationClueReader.derive(laid, here.x, here.y)
    }

    private fun clueSources(): List<QuestAction> = listOf(
        npcMark(recipeNpc, "Read recipe"),
        QuestAction.ModelHighlight("object", plaqueLoc, listOf(), candidateObjectTypeIds = listOf(plaqueLoc), displayName = "Read plaque"),
    )
}
