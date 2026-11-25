package com.projectx.quest.spec.ui

import com.projectx.quest.data.Quest
import com.projectx.quest.data.QuestAction
import com.projectx.quest.data.QuestCondition
import com.projectx.quest.data.QuestItemReq
import com.projectx.quest.data.QuestStep
import com.projectx.quest.data.WorldLocation
import com.projectx.quest.editor.MutableQuest
import com.projectx.quest.editor.MutableStep
import com.projectx.quest.editor.PickKind
import com.projectx.quest.editor.PickResult
import com.projectx.quest.editor.QuestActionFactory
import com.projectx.quest.editor.QuestConditionFactory
import com.projectx.quest.editor.QuestEditorState
import com.projectx.quest.editor.TilePickResolver
import com.projectx.quest.editor.currentPlayerTilePick
import com.projectx.quest.editor.withAtLocation
import com.projectx.quest.editor.withLocation
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class QuestEditorSpecTest {

    @AfterTest
    fun leaveEditorClosed() = QuestEditorState.discard()

    private val spade = QuestItemReq("Spade", 952, 1)
    private val rope = QuestItemReq("Rope", 954, 2, duringQuest = true)

    private val sampleStep = QuestStep(
        title = "Dig",
        text = "Dig <b>here</b>",
        warning = "Mind the hole",
        actions = listOf(QuestAction.Direction(3208.0, 0.0, 3214.0, tile = true), QuestAction.ContinueConversation),
        postconditions = listOf(QuestCondition.InventoryContains(952, "Spade", 1)),
        jumpconditions = listOf(QuestCondition.ChatText("You fall")),
        jumpOffset = -2,
        neededItems = listOf(spade),
        recommendedItems = listOf(rope),
        solverId = "lunar-diplomacy.dice",
    )

    private val sampleQuest = Quest(
        slug = "spec-ui-edit",
        name = "Spec edit",
        members = true,
        length = "Short",
        timeline = "Fifth Age",
        releaseDate = 1_234L,
        neededItems = listOf(spade),
        recommendedItems = listOf(rope),
        steps = listOf(sampleStep, QuestStep(title = "Second")),
        stageVarbit = 100,
        stageVarbitCompleteValue = 7,
        cacheQuestId = 42,
    )

    @Test
    fun `a draft converts back to an equal quest`() {
        assertEquals(sampleQuest, MutableQuest.fromQuest(sampleQuest).toQuest())
    }

    @Test
    fun `a step draft converts back to an equal step`() {
        assertEquals(sampleStep, MutableStep.fromStep(sampleStep).toStep())
    }

    @Test
    fun `draft fields mirror the quest`() {
        val d = MutableQuest.fromQuest(sampleQuest)
        assertEquals("spec-ui-edit", d.slug)
        assertEquals("Spec edit", d.name)
        assertTrue(d.members)
        assertEquals("Short", d.length)
        assertEquals("Fifth Age", d.timeline)
        assertEquals(1_234L, d.releaseDate)
        assertEquals(100, d.stageVarbit)
        assertEquals(7, d.stageVarbitCompleteValue)
        assertEquals(42, d.cacheQuestId)
        assertEquals(2, d.steps.size)
        assertEquals("lunar-diplomacy.dice", d.steps[0].solverId)
        assertEquals(-2, d.steps[0].jumpOffset)
    }

    @Test
    fun `editing a draft leaves the source quest and earlier snapshots alone`() {
        val d = MutableQuest.fromQuest(sampleQuest)
        val snapshot = d.toQuest()
        d.name = "Renamed"
        d.steps[0].actions += QuestAction.TextHint("new")
        d.steps.add(MutableStep(title = "Third"))
        d.neededItems.clear()
        assertEquals(2, sampleQuest.steps.size)
        assertEquals(2, sampleQuest.steps[0].actions.size)
        assertEquals(sampleQuest, snapshot)
        val edited = d.toQuest()
        assertEquals("Renamed", edited.name)
        assertEquals(3, edited.steps.size)
        assertEquals(QuestAction.TextHint("new"), edited.steps[0].actions.last())
        assertTrue(edited.neededItems.isEmpty())
    }

    @Test
    fun `blank step strings are saved as absent`() {
        val s = MutableStep(title = "", text = "", warning = "", solverId = "").toStep()
        assertNull(s.title)
        assertNull(s.text)
        assertNull(s.warning)
        assertNull(s.solverId)
    }

    @Test
    fun `a new step draft starts empty`() {
        val s = MutableStep()
        assertNull(s.title); assertNull(s.text); assertNull(s.warning); assertNull(s.solverId)
        assertEquals(0, s.jumpOffset)
        assertTrue(s.actions.isEmpty() && s.postconditions.isEmpty() && s.jumpconditions.isEmpty())
        assertTrue(s.neededItems.isEmpty() && s.recommendedItems.isEmpty())
        assertEquals(QuestStep(), s.toStep())
    }

    @Test
    fun `a new quest draft has unset metadata`() {
        val q = MutableQuest("slug", "Name").toQuest()
        assertEquals(Quest(slug = "slug", name = "Name"), q)
    }

    @Test
    fun `action factory produces blank, editable actions`() {
        assertEquals(QuestAction.Direction(3208.0, 0.0, 3214.0, instance = false, tile = false, distance = -1), QuestActionFactory.direction(3208, 3214))
        assertEquals(12.5, QuestActionFactory.direction(1, 2, heightFine = 12.5).heightFine)
        assertEquals(QuestAction.ModelHighlight(kind = "object", typeId = -1, modelIds = emptyList(), displayName = ""), QuestActionFactory.modelHighlight())
        assertEquals(QuestAction.ModelHighlight(kind = "npc", typeId = -1, modelIds = emptyList(), displayName = "Hans"), QuestActionFactory.modelHighlight("npc", "Hans"))
        assertEquals(QuestAction.ConversationHighlight(""), QuestActionFactory.conversationHighlight())
        assertEquals(QuestAction.InventoryHighlight(itemId = -1, modelIds = emptyList(), displayName = ""), QuestActionFactory.inventoryHighlight())
        assertSame(QuestAction.ContinueConversation, QuestActionFactory.continueConversation())
        assertSame(QuestAction.ResetInstance, QuestActionFactory.resetInstance())
        assertEquals(QuestAction.PathGuide(emptyList(), instance = false), QuestActionFactory.pathGuide())
        assertEquals(QuestAction.TextHint(""), QuestActionFactory.textHint())
        assertEquals(QuestAction.TextHint("Go"), QuestActionFactory.textHint("Go"))
        assertEquals(QuestAction.InterfaceComponentHighlight(-1, -1, slotId = -1, label = "", color = null), QuestActionFactory.interfaceComponentHighlight())
    }

    @Test
    fun `condition factory produces blank, editable conditions`() {
        assertEquals(QuestCondition.DistanceTo(10.0, 0.0, 20.0, 2, false), QuestConditionFactory.distanceTo(10, 20))
        assertEquals(QuestCondition.DistanceTo(10.0, 3.0, 20.0, 9, false), QuestConditionFactory.distanceTo(10, 20, range = 9, heightFine = 3.0))
        assertEquals(QuestCondition.DistanceFrom(10.0, 0.0, 20.0, 5, false), QuestConditionFactory.distanceFrom(10, 20))
        assertEquals(QuestCondition.InventoryContains(-1, "", 1), QuestConditionFactory.inventoryContains())
        assertEquals(QuestCondition.InventoryContains(952, "Spade", 3), QuestConditionFactory.inventoryContains(952, "Spade", 3))
        assertEquals(QuestCondition.InventoryDoesNotContain(-1, ""), QuestConditionFactory.inventoryDoesNotContain())
        assertEquals(QuestCondition.ModelVisible(kind = "npc", typeId = -1, modelIds = emptyList(), displayName = ""), QuestConditionFactory.modelVisible())
        assertEquals(QuestCondition.ModelNotVisible(kind = "object", typeId = -1, modelIds = emptyList(), displayName = "Door"), QuestConditionFactory.modelNotVisible("object", "Door"))
        assertEquals(QuestCondition.ConversationText(""), QuestConditionFactory.conversationText())
        assertEquals(QuestCondition.ChatText("Oh dear"), QuestConditionFactory.chatText("Oh dear"))
    }

    @Test
    fun `location helpers return moved copies`() {
        val d = QuestAction.Direction(1.0, 2.0, 3.0, instance = true, tile = true, distance = 4)
        assertEquals(d.copy(x = 10.0, y = 30.0, heightFine = 20.0), d.withLocation(10.0, 30.0, 20.0))
        val m = QuestActionFactory.modelHighlight("npc", "Hans")
        val loc = WorldLocation(3200.0, 0.0, 3201.0)
        assertEquals(loc, m.withAtLocation(loc).atLocation)
        assertNull(m.withAtLocation(loc).withAtLocation(null).atLocation)
        assertEquals("Hans", m.withAtLocation(loc).displayName)
    }

    @Test
    fun `pick kinds keep their order`() {
        assertEquals(
            listOf("TILE", "OBJECT", "NPC", "ITEM", "GROUND_ITEM", "COMPONENT"),
            PickKind.entries.map { it.name },
        )
    }

    @Test
    fun `a pick result defaults every field it does not describe`() {
        val r = PickResult(PickKind.NPC)
        assertEquals(PickResult(PickKind.NPC, -1, "", 0, 0, 0, 0f, -1, -1, -1), r)
    }

    @Test
    fun `beginning an edit opens the window on a fresh draft`() {
        QuestEditorState.saveStatus.value = "stale"
        QuestEditorState.beginEdit(sampleQuest)
        val draft = assertNotNull(QuestEditorState.draft)
        assertEquals(sampleQuest, draft.toQuest())
        assertTrue(QuestEditorState.windowOpen.value)
        assertEquals("", QuestEditorState.saveStatus.value)
        assertTrue(QuestEditorState.selectedStepIndex.value in 0..1)
    }

    @Test
    fun `discarding drops the draft, closes the window and disarms a pick`() {
        QuestEditorState.beginEdit(sampleQuest)
        QuestEditorState.beginPick(setOf(PickKind.NPC), "examine an NPC") {}
        QuestEditorState.discard()
        assertNull(QuestEditorState.draft)
        assertFalse(QuestEditorState.windowOpen.value)
        assertFalse(QuestEditorState.pickMode.value)
        assertTrue(QuestEditorState.pendingAccepts.isEmpty())
    }

    @Test
    fun `saving with no draft does nothing and reports failure`() {
        QuestEditorState.discard()
        assertFalse(QuestEditorState.save())
    }

    @Test
    fun `arming a pick exposes its prompt and accepted kinds`() {
        QuestEditorState.beginPick(setOf(PickKind.OBJECT, PickKind.NPC), "examine something") {}
        assertTrue(QuestEditorState.pickMode.value)
        assertEquals("examine something", QuestEditorState.pickPrompt.value)
        assertEquals(setOf(PickKind.OBJECT, PickKind.NPC), QuestEditorState.pendingAccepts)
        QuestEditorState.cancelPick()
        assertFalse(QuestEditorState.pickMode.value)
        assertTrue(QuestEditorState.pendingAccepts.isEmpty())
    }

    @Test
    fun `a pick of an unaccepted kind is ignored and the pick stays armed`() {
        val got = mutableListOf<PickResult>()
        QuestEditorState.beginPick(setOf(PickKind.NPC), "examine an NPC") { got += it }
        QuestEditorState.offerPick(PickResult(PickKind.OBJECT, typeId = 1))
        QuestEditorState.drainPickDelivery()
        assertTrue(got.isEmpty())
        assertTrue(QuestEditorState.pickMode.value)
        assertEquals(setOf(PickKind.NPC), QuestEditorState.pendingAccepts)
    }

    @Test
    fun `an accepted pick disarms at once but reaches the sink only when drained, exactly once`() {
        val got = mutableListOf<PickResult>()
        QuestEditorState.beginPick(setOf(PickKind.NPC, PickKind.ITEM), "pick") { got += it }
        val hans = PickResult(PickKind.NPC, typeId = 0, name = "Hans")
        QuestEditorState.offerPick(hans)
        assertFalse(QuestEditorState.pickMode.value)
        assertTrue(QuestEditorState.pendingAccepts.isEmpty())
        assertTrue(got.isEmpty())
        QuestEditorState.offerPick(PickResult(PickKind.ITEM, typeId = 995))
        QuestEditorState.drainPickDelivery()
        QuestEditorState.drainPickDelivery()
        assertEquals(listOf(hans), got)
    }

    @Test
    fun `a pick offered while nothing is armed is dropped`() {
        val got = mutableListOf<PickResult>()
        QuestEditorState.beginPick(setOf(PickKind.TILE), "tile") { got += it }
        QuestEditorState.cancelPick()
        QuestEditorState.offerPick(PickResult(PickKind.TILE, tileX = 1, tileY = 2))
        QuestEditorState.drainPickDelivery()
        assertTrue(got.isEmpty())
    }

    @Test
    fun `a sink that throws does not break the editor`() {
        QuestEditorState.beginPick(setOf(PickKind.ITEM), "pick") { error("sink failed") }
        QuestEditorState.offerPick(PickResult(PickKind.ITEM, typeId = 995))
        QuestEditorState.drainPickDelivery()
        val got = mutableListOf<PickResult>()
        QuestEditorState.beginPick(setOf(PickKind.ITEM), "again") { got += it }
        QuestEditorState.offerPick(PickResult(PickKind.ITEM, typeId = 996))
        QuestEditorState.drainPickDelivery()
        assertEquals(996, got.single().typeId)
    }

    @Test
    fun `a tile pick accepts only tiles and delivers the coordinates`() {
        val got = mutableListOf<List<Number>>()
        QuestEditorState.beginPickTile { x, y, plane, height -> got += listOf(x, y, plane, height) }
        assertEquals(setOf(PickKind.TILE), QuestEditorState.pendingAccepts)
        assertTrue(QuestEditorState.pickPrompt.value.isNotBlank())
        QuestEditorState.offerPick(PickResult(PickKind.NPC, typeId = 1))
        QuestEditorState.offerPick(PickResult(PickKind.TILE, tileX = 3200, tileY = 3201, plane = 1, heightFine = 512f))
        QuestEditorState.drainPickDelivery()
        assertEquals(listOf(listOf<Number>(3200, 3201, 1, 512f)), got)
    }

    @Test
    fun `world lookups give nothing when no client is attached`() {
        assertNull(currentPlayerTilePick())
        assertNull(TilePickResolver.resolve(100f, 100f))
    }

    @Test
    fun `a tile hit is not instance-local unless stated`() {
        val hit = TilePickResolver.Hit(1, 2, 0, 3f, 4f, 5f)
        assertFalse(hit.instance)
        assertTrue(hit.copy(instance = true).instance)
    }
}
