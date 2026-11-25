package com.projectx.quest.spec.ui

import com.projectx.quest.data.Quest
import com.projectx.quest.data.QuestAction
import com.projectx.quest.data.QuestStep
import com.projectx.quest.runtime.RecentChat
import com.projectx.quest.solver.DeadAndBuriedMapPuzzleSolver
import com.projectx.quest.solver.DesperateTimesCombinationLockSolver
import com.projectx.quest.solver.DesperateTimesSlidePuzzleSolver
import com.projectx.quest.solver.ElementalWorkshopIIILatticeSolver
import com.projectx.quest.solver.ElementalWorkshopIIITowerSolver
import com.projectx.quest.solver.ElementalWorkshopIIJunctionBoxSolver
import com.projectx.quest.solver.EyesOfGlouphriePuzzleSolver
import com.projectx.quest.solver.FremennikTrialsCombinationLockSolver
import com.projectx.quest.solver.LunarDiplomacyDiceSolver
import com.projectx.quest.solver.LunarDiplomacyDreamPuffSolver
import com.projectx.quest.solver.LunarDiplomacyNumberPatternSolver
import com.projectx.quest.solver.NoopAutoAdvanceSolver
import com.projectx.quest.solver.QuestStepSolver
import com.projectx.quest.solver.SkeletonPuzzleSolver
import com.projectx.quest.solver.SpiritOfSummerEmoteSolver
import com.projectx.quest.solver.TheBrinkOfExtinctionPedestalsSolver
import com.projectx.ui.backend.dsl.utils.ImGuiColors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Solvers evaluated with no game client attached: every read fails, so each shows its "nothing live" behaviour. */
class OfflineSolverSpecTest {

    private val quest = Quest(slug = "spec-ui-offline", name = "Offline")
    private val step = QuestStep(title = "puzzle")

    private fun QuestStepSolver.run() = evaluate(quest, step, 0)

    private fun assertSingleHint(r: QuestStepSolver.Result): String {
        assertFalse(r.solved)
        assertEquals(1, r.overlayActions.size, "actions: ${r.overlayActions}")
        return assertIs<QuestAction.TextHint>(r.overlayActions[0]).text
    }

    @Test
    fun `the noop example always reports solved with no actions`() {
        val r = NoopAutoAdvanceSolver.run()
        assertTrue(r.solved)
        assertTrue(r.overlayActions.isEmpty())
    }

    @Test
    fun `the skeleton example never solves and never highlights`() {
        assertEquals(QuestStepSolver.Result(), SkeletonPuzzleSolver.run())
    }

    @Test
    fun `slide puzzles with nothing to read stay silent`() {
        assertEquals(QuestStepSolver.Result(), DeadAndBuriedMapPuzzleSolver.run())
        assertEquals(QuestStepSolver.Result(), DesperateTimesSlidePuzzleSolver.run())
        assertFalse(DesperateTimesSlidePuzzleSolver.isSolutionReady())
    }

    @Test
    fun `junction box closed names the three fixed pipe pairs`() {
        val text = assertSingleHint(ElementalWorkshopIIJunctionBoxSolver.run())
        for (hole in listOf("top-left", "bottom-right", "top-middle", "top-right", "bottom-left", "bottom-middle")) {
            assertTrue(hole in text, "hint should mention $hole: $text")
        }
    }

    @Test
    fun `lattice and tower solvers ask for the lever interface when it is closed`() {
        assertSingleHint(ElementalWorkshopIIILatticeSolver.run())
        assertSingleHint(ElementalWorkshopIIITowerSolver.run())
    }

    @Test
    fun `oaknock's machine falls back to a three-line reference card`() {
        val r = EyesOfGlouphriePuzzleSolver.run()
        assertFalse(r.solved)
        assertEquals(3, r.overlayActions.size)
        r.overlayActions.forEach { assertIs<QuestAction.TextHint>(it) }
        val card = r.overlayActions.joinToString("\n") { (it as QuestAction.TextHint).text }
        assertTrue("R=1" in card && "V=7" in card && "C=1" in card && "P=5" in card, card)
    }

    @Test
    fun `desperate times lock with no known code points at both clue sources`() {
        val r = DesperateTimesCombinationLockSolver.run()
        assertFalse(r.solved)
        val (recipe, plaque) = r.overlayActions.map { assertIs<QuestAction.ModelHighlight>(it) }
        assertEquals("npc", recipe.kind)
        assertEquals(26311, recipe.typeId)
        assertEquals(listOf(26311), recipe.candidateNpcTypeIds)
        assertEquals("object", plaque.kind)
        assertEquals(37670, plaque.typeId)
        assertEquals(listOf(37670), plaque.candidateObjectTypeIds)
        assertEquals(2, r.overlayActions.size)
    }

    @Test
    fun `spirit of summer highlights Think when the man's emote can't be read`() {
        val r = SpiritOfSummerEmoteSolver.run()
        assertFalse(r.solved)
        val button = assertIs<QuestAction.InterfaceComponentHighlight>(r.overlayActions.single())
        assertEquals(590, button.interfaceId)
        assertEquals(11, button.componentId)
        assertEquals(4, button.slotId)
        assertEquals("Think", button.label)
        assertEquals(ImGuiColors.CYAN, button.color)
    }

    @Test
    fun `scene-reading solvers ask the player to get into position`() {
        assertSingleHint(LunarDiplomacyDiceSolver.run())
        assertSingleHint(LunarDiplomacyDreamPuffSolver.run())
        assertSingleHint(TheBrinkOfExtinctionPedestalsSolver.run())
    }

    @Test
    fun `number pattern continues a known sequence from chat`() {
        RecentChat.record("The Numerator shows: 1, 3, 5")
        val text = assertSingleHint(LunarDiplomacyNumberPatternSolver.run())
        assertEquals("Click in order: 7 -> 9", text)
    }

    @Test
    fun `number pattern resolves a sequence only one pattern starts with`() {
        RecentChat.record("Sequence 9, 8, 7, 6")
        assertEquals("Click in order: 5 -> 4", assertSingleHint(LunarDiplomacyNumberPatternSolver.run()))
        RecentChat.record("Sequence 1, 1, 2, 3")
        assertEquals("Click in order: 1 -> 1 -> 4 -> 5 -> 1", assertSingleHint(LunarDiplomacyNumberPatternSolver.run()))
    }

    @Test
    fun `number pattern waits when the prefix fits patterns that continue differently`() {
        RecentChat.record("Pattern: 1, 1, 2")
        val text = assertSingleHint(LunarDiplomacyNumberPatternSolver.run())
        assertTrue(text.startsWith("Pattern 1, 1, 2"), text)
    }

    @Test
    fun `number pattern waits on a sequence no pattern starts with`() {
        RecentChat.record("Pattern: 5, 5, 5")
        val text = assertSingleHint(LunarDiplomacyNumberPatternSolver.run())
        assertTrue(text.startsWith("Pattern 5, 5, 5"), text)
    }

    @Test
    fun `number pattern recognises a finished pattern`() {
        RecentChat.record("Done: 8, 6, 4, 2, 0")
        assertEquals("Pattern complete - wait for the next one.", assertSingleHint(LunarDiplomacyNumberPatternSolver.run()))
    }

    @Test
    fun `fremennik riddle heard in chat becomes a panel hint until the lock opens`() {
        RecentChat.record("My first is in fish, but not in the sea.")
        val text = assertSingleHint(FremennikTrialsCombinationLockSolver.run())
        assertEquals("Riddle answer: FIRE", text)
    }
}
