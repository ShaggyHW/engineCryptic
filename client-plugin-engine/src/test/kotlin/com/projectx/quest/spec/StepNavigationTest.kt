package com.projectx.quest.spec

import com.projectx.quest.data.Quest
import com.projectx.quest.data.QuestCondition
import com.projectx.quest.data.QuestStep
import com.projectx.quest.runtime.ActiveQuestState
import com.projectx.quest.runtime.StepAdvancer
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class StepNavigationTest {
    private val fourSteps = Quest(
        slug = "nav-spec",
        name = "Nav Spec",
        steps = List(4) { QuestStep(text = "step $it", postconditions = listOf(QuestCondition.Always)) },
    )

    /** Selection takes effect whether or not the varbit seek can reach a live client. */
    private fun choose(quest: Quest) {
        runCatching { ActiveQuestState.select(quest) }
    }

    @AfterTest
    fun clear() = ActiveQuestState.select(null)

    @Test
    fun `selecting nothing clears the active quest`() {
        choose(fourSteps)
        ActiveQuestState.select(null)
        assertNull(ActiveQuestState.selectedQuest)
        assertEquals(0, ActiveQuestState.currentStepIndex)
        assertEquals(-1, ActiveQuestState.lastVarbitValue)
    }

    @Test
    fun `selecting a quest starts it at the first step`() {
        choose(fourSteps)
        ActiveQuestState.setStep(3)
        ActiveQuestState.updateLastVarbit(9)
        choose(fourSteps.copy(slug = "other"))
        assertEquals("other", ActiveQuestState.selectedQuest?.slug)
        assertEquals(0, ActiveQuestState.currentStepIndex)
        assertEquals(-1, ActiveQuestState.lastVarbitValue)
    }

    @Test
    fun `next walks one past the last step and stops there`() {
        choose(fourSteps)
        repeat(4) { ActiveQuestState.next() }
        assertEquals(4, ActiveQuestState.currentStepIndex, "index == steps.size means finished")
        ActiveQuestState.next()
        assertEquals(4, ActiveQuestState.currentStepIndex)
    }

    @Test
    fun `previous stops at the first step`() {
        choose(fourSteps)
        ActiveQuestState.setStep(1)
        ActiveQuestState.previous()
        ActiveQuestState.previous()
        assertEquals(0, ActiveQuestState.currentStepIndex)
    }

    @Test
    fun `set step and jump are clamped to the finished position`() {
        choose(fourSteps)
        ActiveQuestState.setStep(99)
        assertEquals(4, ActiveQuestState.currentStepIndex)
        ActiveQuestState.setStep(-5)
        assertEquals(0, ActiveQuestState.currentStepIndex)
        ActiveQuestState.setStep(2)
        ActiveQuestState.jumpBy(-1)
        assertEquals(1, ActiveQuestState.currentStepIndex)
        ActiveQuestState.jumpBy(23)
        assertEquals(4, ActiveQuestState.currentStepIndex)
        ActiveQuestState.jumpBy(-50)
        assertEquals(0, ActiveQuestState.currentStepIndex)
    }

    @Test
    fun `reset goes back to the first step but keeps the quest`() {
        choose(fourSteps)
        ActiveQuestState.setStep(3)
        ActiveQuestState.reset()
        assertEquals(0, ActiveQuestState.currentStepIndex)
        assertSame(fourSteps, ActiveQuestState.selectedQuest)
    }

    @Test
    fun `moves that need a quest do nothing without one`() {
        ActiveQuestState.select(null)
        ActiveQuestState.next()
        ActiveQuestState.setStep(2)
        ActiveQuestState.jumpBy(3)
        ActiveQuestState.seekToVarbitStage()
        assertEquals(0, ActiveQuestState.currentStepIndex)
    }

    @Test
    fun `a quest without steps is finished at index zero`() {
        choose(Quest(slug = "empty", name = "Empty"))
        ActiveQuestState.next()
        ActiveQuestState.setStep(3)
        assertEquals(0, ActiveQuestState.currentStepIndex)
    }

    @Test
    fun `last varbit value is whatever was recorded`() {
        ActiveQuestState.updateLastVarbit(17)
        assertEquals(17, ActiveQuestState.lastVarbitValue)
    }

    @Test
    fun `the advancer never moves the step while the client is not logged in`() {
        choose(fourSteps)
        repeat(3) { StepAdvancer.maybeTick(autoAdvanceEnabled = true) }
        assertEquals(0, ActiveQuestState.currentStepIndex)
    }
}
