package com.projectx.quest.editor

import com.projectx.quest.data.CombatNpcInfo
import com.projectx.quest.data.Quest
import com.projectx.quest.data.QuestReq
import com.projectx.quest.data.QuestStep
import com.projectx.quest.data.TpHint
import com.projectx.quest.spec.IsolatedLibrary
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class EditorFixesTest {

    @AfterTest
    fun closeEditor() = QuestEditorState.discard()

    private val extras = Quest(
        slug = "editor-extras",
        name = "Editor extras",
        combatNPCs = listOf(CombatNpcInfo("Goblin", "5-12", 3)),
        prereqQuests = listOf("Cook's Assistant"),
        questReqs = listOf(QuestReq("skill", "Agility", 30), QuestReq("misc", text = "Be brave")),
        steps = listOf(QuestStep(title = "Teleport", tpHint = TpHint("icon", "Lodestone", "lode.png")), QuestStep(title = "Walk")),
    )

    private fun withRoot(scenario: String) {
        val root = IsolatedLibrary.newRoot()
        try {
            IsolatedLibrary.run(scenario, root)
        } finally {
            IsolatedLibrary.discard(root)
        }
    }

    @Test
    fun `a draft keeps requirements and teleport hints it has no fields for`() {
        val draft = MutableQuest.fromQuest(extras)
        draft.name = "Renamed"
        draft.steps[1].text = "edited"
        draft.steps.add(MutableStep.fromStep(draft.steps[0].toStep()))
        val saved = draft.toQuest()
        assertEquals(extras.combatNPCs, saved.combatNPCs)
        assertEquals(extras.prereqQuests, saved.prereqQuests)
        assertEquals(extras.questReqs, saved.questReqs)
        assertEquals(extras.steps[0].tpHint, saved.steps[0].tpHint)
        assertEquals(extras.steps[0].tpHint, saved.steps[2].tpHint, "a duplicated step keeps its teleport hint")
        assertEquals(extras.copy(name = "Renamed", steps = listOf(extras.steps[0], extras.steps[1].copy(text = "edited"), extras.steps[0])), saved)
    }

    @Test
    fun `a quest round-trips through a draft with every field set`() {
        assertEquals(extras, MutableQuest.fromQuest(extras).toQuest())
    }

    @Test
    fun `saving from the editor writes requirements and teleport hints back to the file`() = withRoot("editorSaveKeepsWhatItCannotEdit")

    @Test
    fun `editing a quest with no guide opens an empty draft that keeps its identity`() {
        val guideless = Quest(slug = "guideless", name = "Guideless", cacheQuestId = 321)
        QuestEditorState.selectedStepIndex.value = 4
        QuestEditorState.beginEdit(guideless)
        val draft = assertNotNull(QuestEditorState.draft)
        assertTrue(draft.steps.isEmpty())
        assertEquals("guideless", draft.slug)
        assertEquals("Guideless", draft.name)
        assertEquals(321, draft.cacheQuestId)
        assertEquals(0, QuestEditorState.selectedStepIndex.value)
        assertTrue(QuestEditorState.windowOpen.value)
        assertEquals(guideless, draft.toQuest())
    }

    @Test
    fun `a guide written for a guide-less quest saves under its slug`() = withRoot("guideWrittenForGuidelessQuestReplacesIt")
}
