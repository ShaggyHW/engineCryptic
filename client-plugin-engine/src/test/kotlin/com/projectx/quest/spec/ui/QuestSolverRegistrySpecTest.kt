package com.projectx.quest.spec.ui

import com.projectx.quest.data.Quest
import com.projectx.quest.data.QuestAction
import com.projectx.quest.data.QuestStep
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
import com.projectx.quest.solver.QuestSolver
import com.projectx.quest.solver.QuestSolverRegistry
import com.projectx.quest.solver.QuestStepSolver
import com.projectx.quest.solver.SkeletonPuzzleSolver
import com.projectx.quest.solver.SpiritOfSummerEmoteSolver
import com.projectx.quest.solver.TheBrinkOfExtinctionPedestalsSolver
import com.projectx.quest.solver.TheElderKilnScalesSolver
import com.projectx.quest.solver.registerExampleSolvers
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class QuestSolverRegistrySpecTest {

    private class CountingSolver(override val id: String, private val solved: Boolean = false) : QuestStepSolver {
        var calls = 0
        override fun evaluate(quest: Quest, step: QuestStep, stepIndex: Int): QuestStepSolver.Result {
            calls++
            return QuestStepSolver.Result(listOf(QuestAction.TextHint("call $calls")), solved)
        }
    }

    private val registered = mutableListOf<String>()

    private fun install(solver: QuestStepSolver): QuestStepSolver {
        QuestSolverRegistry.register(solver)
        registered += solver.id
        return solver
    }

    @AfterTest
    fun cleanUp() = registered.forEach(QuestSolverRegistry::unregister)

    private val quest = Quest(slug = "spec-ui-quest", name = "Spec quest")

    private fun stepFor(solverId: String?) = QuestStep(title = "step", solverId = solverId)

    @Test
    fun `a default result has no actions and is not solved`() {
        val r = QuestStepSolver.Result()
        assertTrue(r.overlayActions.isEmpty())
        assertFalse(r.solved)
    }

    @Test
    fun `register, get and unregister by id`() {
        val solver = install(CountingSolver("spec.ui.lookup"))
        assertSame(solver, QuestSolverRegistry.get("spec.ui.lookup"))
        assertTrue(solver in QuestSolverRegistry.all())
        QuestSolverRegistry.unregister("spec.ui.lookup")
        assertNull(QuestSolverRegistry.get("spec.ui.lookup"))
        assertFalse(QuestSolverRegistry.all().any { it.id == "spec.ui.lookup" })
    }

    @Test
    fun `registering a second solver with the same id replaces the first`() {
        install(CountingSolver("spec.ui.replace"))
        val second = install(CountingSolver("spec.ui.replace"))
        assertSame(second, QuestSolverRegistry.get("spec.ui.replace"))
        assertEquals(1, QuestSolverRegistry.ids().count { it == "spec.ui.replace" })
    }

    @Test
    fun `ids are returned sorted`() {
        install(CountingSolver("spec.ui.zz"))
        install(CountingSolver("spec.ui.aa"))
        install(CountingSolver("spec.ui.mm"))
        val ids = QuestSolverRegistry.ids()
        assertEquals(ids.sorted(), ids)
        assertTrue(ids.containsAll(listOf("spec.ui.aa", "spec.ui.mm", "spec.ui.zz")))
    }

    @Test
    fun `unregistering an unknown id is harmless`() {
        QuestSolverRegistry.unregister("spec.ui.never-registered")
        assertNull(QuestSolverRegistry.get("spec.ui.never-registered"))
    }

    @Test
    fun `a step without a solver id evaluates to an empty result`() {
        val r = QuestSolverRegistry.evaluateOrCached(quest, stepFor(null), 0, 1L)
        assertEquals(QuestStepSolver.Result(), r)
    }

    @Test
    fun `a step naming an unregistered solver evaluates to an empty result`() {
        val r = QuestSolverRegistry.evaluateOrCached(quest, stepFor("spec.ui.missing"), 0, 2L)
        assertEquals(QuestStepSolver.Result(), r)
        assertTrue(QuestSolverRegistry.overlayActionsFor(quest, stepFor("spec.ui.missing"), 0, 2L).isEmpty())
    }

    @Test
    fun `a throwing solver yields an empty result instead of an exception`() {
        install(object : QuestStepSolver {
            override val id = "spec.ui.throws"
            override fun evaluate(quest: Quest, step: QuestStep, stepIndex: Int): QuestStepSolver.Result =
                error("boom")
        })
        val r = QuestSolverRegistry.evaluateOrCached(quest, stepFor("spec.ui.throws"), 0, 3L)
        assertEquals(QuestStepSolver.Result(), r)
    }

    @Test
    fun `the solver's result is passed through`() {
        install(CountingSolver("spec.ui.solved", solved = true))
        val r = QuestSolverRegistry.evaluateOrCached(quest, stepFor("spec.ui.solved"), 4, 100L)
        assertTrue(r.solved)
        assertEquals(listOf<QuestAction>(QuestAction.TextHint("call 1")), r.overlayActions)
    }

    @Test
    fun `repeat calls with the same stamp and step reuse one evaluation`() {
        val solver = install(CountingSolver("spec.ui.cache")) as CountingSolver
        val step = stepFor("spec.ui.cache")
        val first = QuestSolverRegistry.evaluateOrCached(quest, step, 2, 500L)
        val second = QuestSolverRegistry.evaluateOrCached(quest, step, 2, 500L)
        val actions = QuestSolverRegistry.overlayActionsFor(quest, step, 2, 500L)
        assertEquals(1, solver.calls)
        assertEquals(first, second)
        assertEquals(first.overlayActions, actions)
    }

    @Test
    fun `a new stamp re-evaluates`() {
        val solver = install(CountingSolver("spec.ui.stamp")) as CountingSolver
        val step = stepFor("spec.ui.stamp")
        QuestSolverRegistry.evaluateOrCached(quest, step, 0, 600L)
        val later = QuestSolverRegistry.evaluateOrCached(quest, step, 0, 601L)
        assertEquals(2, solver.calls)
        assertEquals(listOf<QuestAction>(QuestAction.TextHint("call 2")), later.overlayActions)
    }

    @Test
    fun `the same stamp for a different step index or quest re-evaluates`() {
        val solver = install(CountingSolver("spec.ui.key")) as CountingSolver
        val step = stepFor("spec.ui.key")
        QuestSolverRegistry.evaluateOrCached(quest, step, 0, 700L)
        QuestSolverRegistry.evaluateOrCached(quest, step, 1, 700L)
        QuestSolverRegistry.evaluateOrCached(quest.copy(slug = "spec-ui-other"), step, 1, 700L)
        assertEquals(3, solver.calls)
    }

    @Test
    fun `the solver annotation is retained at runtime and carries an id`() {
        @QuestSolver(id = "spec.ui.annotated")
        class Annotated
        val annotation = Annotated::class.java.getAnnotation(QuestSolver::class.java)
        assertNotNull(annotation)
        assertEquals("spec.ui.annotated", annotation.id)
    }

    private val persistedIds = mapOf(
        NoopAutoAdvanceSolver to "example.noop-advance",
        SkeletonPuzzleSolver to "example.skeleton-puzzle",
        EyesOfGlouphriePuzzleSolver to "eyes-of-glouphrie.oaknocks-puzzle",
        ElementalWorkshopIIJunctionBoxSolver to "elemental-workshop-ii.junction-box",
        ElementalWorkshopIIILatticeSolver to "elemental-workshop-iii.lattice",
        ElementalWorkshopIIITowerSolver to "elemental-workshop-iii.tower-final",
        DesperateTimesCombinationLockSolver to "desperate-times.combination-lock",
        DesperateTimesSlidePuzzleSolver to "desperate-times.slide-puzzle",
        DeadAndBuriedMapPuzzleSolver to "dead-and-buried.map-puzzle",
        FremennikTrialsCombinationLockSolver to "the-fremennik-trials.combination-lock",
        SpiritOfSummerEmoteSolver to "spirit-of-summer.emotes",
        LunarDiplomacyDiceSolver to "lunar-diplomacy.dice",
        LunarDiplomacyNumberPatternSolver to "lunar-diplomacy.number-pattern",
        LunarDiplomacyDreamPuffSolver to "lunar-diplomacy.dream-puff",
        TheElderKilnScalesSolver to "the-elder-kiln.ancient-scales",
        TheBrinkOfExtinctionPedestalsSolver to "the-brink-of-extinction.memory-pedestals",
    )

    @Test
    fun `every built-in solver keeps the id the quest files refer to`() {
        persistedIds.forEach { (solver, id) -> assertEquals(id, solver.id) }
    }

    @Test
    fun `startup registration makes every built-in solver resolvable by its id`() {
        registerExampleSolvers()
        persistedIds.forEach { (solver, id) -> assertSame(solver, QuestSolverRegistry.get(id), id) }
        assertTrue(QuestSolverRegistry.ids().containsAll(persistedIds.values))
    }
}
