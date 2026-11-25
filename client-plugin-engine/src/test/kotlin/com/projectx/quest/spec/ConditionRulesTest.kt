package com.projectx.quest.spec

import com.projectx.quest.data.Quest
import com.projectx.quest.data.QuestAction
import com.projectx.quest.data.QuestCondition
import com.projectx.quest.data.WorldLocation
import com.projectx.quest.runtime.ActiveQuestState
import com.projectx.quest.runtime.ConditionEvaluator
import com.projectx.quest.runtime.QuestInstanceTracker
import com.projectx.quest.runtime.QuestTargetMatcher
import com.projectx.quest.runtime.RecentChat
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConditionRulesTest {
    private val noIds = QuestCondition.ModelVisible("npc", -1, emptyList(), displayName = "Nobody")

    @BeforeTest
    fun outsideAnyInstance() {
        QuestInstanceTracker.reset()
        ActiveQuestState.select(null)
    }

    @AfterTest
    fun clear() = ActiveQuestState.select(null)

    private fun choose(quest: Quest) {
        runCatching { ActiveQuestState.select(quest) }
    }

    @Test
    fun `kinds the helper can check by itself are implementable`() {
        val checkable = listOf(
            QuestCondition.DistanceTo(1.0, 0.0, 1.0, 1),
            QuestCondition.DistanceFrom(1.0, 0.0, 1.0, 1),
            QuestCondition.DistanceToWithHeight(1.0, 0.0, 1.0, 1),
            QuestCondition.DistanceFromWithHeight(1.0, 0.0, 1.0, 1),
            QuestCondition.InventoryContains(1, "a", 1),
            QuestCondition.InventoryDoesNotContain(1, "a"),
            noIds,
            QuestCondition.ModelNotVisible("npc", -1, emptyList(), displayName = "x"),
            QuestCondition.QuestInterfaceOpen,
            QuestCondition.InterfaceOpen(),
            QuestCondition.NpcNearTile(),
            QuestCondition.ConversationActive,
            QuestCondition.ConversationInactive,
            QuestCondition.ConversationText("x"),
            QuestCondition.ChatText("x"),
            QuestCondition.InInstance,
            QuestCondition.NotInInstance,
            QuestCondition.Always,
        )
        checkable.forEach { assertTrue(ConditionEvaluator.implementable(it), "$it") }
    }

    @Test
    fun `kinds that need the user are not implementable and never met`() {
        val manual = listOf(
            QuestCondition.Manual,
            QuestCondition.ChangedInstance,
            QuestCondition.CaptureConversationState("p", "k"),
            QuestCondition.StateEquals("k", "v"),
            QuestCondition.InCombatWith(1, "Rat"),
            QuestCondition.ItemClicked(1),
            QuestCondition.Generic,
            QuestCondition.Unknown("anything"),
        )
        manual.forEach {
            assertFalse(ConditionEvaluator.implementable(it), "$it")
            assertFalse(ConditionEvaluator.isMet(it), "$it")
        }
    }

    @Test
    fun `quest progress conditions depend on the active quest having a stage varbit`() {
        assertFalse(ConditionEvaluator.implementable(QuestCondition.QuestStarted))
        assertFalse(ConditionEvaluator.implementable(QuestCondition.QuestComplete))
        assertFalse(ConditionEvaluator.isMet(QuestCondition.QuestStarted))
        assertFalse(ConditionEvaluator.isMet(QuestCondition.QuestComplete))

        choose(Quest(slug = "no-varbit", name = "No Varbit", stageVarbit = -1))
        assertFalse(ConditionEvaluator.implementable(QuestCondition.QuestStarted))
        assertFalse(ConditionEvaluator.isMet(QuestCondition.QuestComplete))

        choose(Quest(slug = "varbit", name = "Varbit", stageVarbit = 0, stageVarbitCompleteValue = 5))
        assertTrue(ConditionEvaluator.implementable(QuestCondition.QuestStarted))
        assertTrue(ConditionEvaluator.implementable(QuestCondition.QuestComplete))
    }

    @Test
    fun `always is met`() {
        assertTrue(ConditionEvaluator.isMet(QuestCondition.Always))
    }

    @Test
    fun `chat text matches recent chat ignoring case`() {
        val id = "chat${UUID.randomUUID().toString().take(8)}"
        assertFalse(ConditionEvaluator.isMet(QuestCondition.ChatText(id)))
        RecentChat.record("Congratulations, you found the $id!")
        assertTrue(ConditionEvaluator.isMet(QuestCondition.ChatText(id.uppercase())))
        assertTrue(ConditionEvaluator.isMet(QuestCondition.ChatText("found the $id")))
        assertFalse(ConditionEvaluator.isMet(QuestCondition.ChatText("   ")))
    }

    @Test
    fun `blank dialogue text and unset interface ids never match`() {
        assertFalse(ConditionEvaluator.isMet(QuestCondition.ConversationText("")))
        assertFalse(ConditionEvaluator.isMet(QuestCondition.ConversationText("  \n ")))
        assertFalse(ConditionEvaluator.isMet(QuestCondition.InterfaceOpen(-1)))
    }

    @Test
    fun `instance relative targets without an origin count as out of range`() {
        assertNull(QuestInstanceTracker.origin)
        assertFalse(QuestInstanceTracker.isInInstance)
        assertFalse(ConditionEvaluator.isMet(QuestCondition.DistanceTo(1.0, 0.0, 1.0, 50, instance = true)))
        assertFalse(ConditionEvaluator.isMet(QuestCondition.DistanceToWithHeight(1.0, 0.0, 1.0, 50, instance = true)))
        assertTrue(ConditionEvaluator.isMet(QuestCondition.DistanceFrom(1.0, 0.0, 1.0, 50, instance = true)))
        assertTrue(ConditionEvaluator.isMet(QuestCondition.DistanceFromWithHeight(1.0, 0.0, 1.0, 50, instance = true)))
        assertFalse(ConditionEvaluator.isMet(QuestCondition.NpcNearTile(typeId = 5, tileX = 1, tileY = 1, instance = true)))
    }

    @Test
    fun `targets with no ids to match are never visible`() {
        assertFalse(ConditionEvaluator.isMet(noIds))
        assertFalse(ConditionEvaluator.isMet(noIds.copy(kind = "object")))
        assertFalse(ConditionEvaluator.isMet(noIds.copy(kind = "model", quantity = 2)))
        assertTrue(ConditionEvaluator.isMet(QuestCondition.ModelNotVisible("npc", -1, emptyList(), displayName = "Nobody")))
        assertTrue(ConditionEvaluator.isMet(QuestCondition.ModelNotVisible("object", -1, emptyList(), displayName = "")))
        assertFalse(ConditionEvaluator.isMet(QuestCondition.ModelVisible("grounditem", -1, emptyList(), displayName = "")))
        assertTrue(ConditionEvaluator.isMet(QuestCondition.ModelNotVisible("grounditem", -1, emptyList(), displayName = " ")))
        assertFalse(ConditionEvaluator.isMet(QuestCondition.NpcNearTile()))
    }

    @Test
    fun `describe gives the panel text for every kind`() {
        val expected = mapOf(
            QuestCondition.DistanceTo(3200.9, 0.0, 3201.2, 4) to "Within 4 tiles of (3200, 3201)",
            QuestCondition.DistanceFrom(10.0, 0.0, 20.0, 2) to "More than 2 tiles from (10, 20)",
            QuestCondition.DistanceToWithHeight(1.0, 812.6, 2.0, 3) to "Within 3 tiles of (1, 2) at height 812",
            QuestCondition.DistanceFromWithHeight(1.0, 5.0, 2.0, 3) to "More than 3 tiles from (1, 2) at height 5",
            QuestCondition.NotInInstance to "Not in an instance",
            QuestCondition.ChangedInstance to "Changed instance",
            QuestCondition.Manual to "(manual advance)",
            QuestCondition.Always to "(always true)",
            QuestCondition.CaptureConversationState("p", "count") to "Capture conversation state: \$count",
            QuestCondition.StateEquals("count", "3") to "State count == 3",
            QuestCondition.InventoryContains(1, "Bucket", 2) to "Inventory contains 2× Bucket",
            QuestCondition.InventoryDoesNotContain(1, "Bucket") to "Inventory does NOT contain Bucket",
            QuestCondition.ModelVisible("npc", 1, emptyList(), displayName = "Cook") to "Cook visible",
            QuestCondition.ModelNotVisible("npc", 1, emptyList(), displayName = "Cook") to "Cook not visible",
            QuestCondition.QuestStarted to "Quest started (manual advance)",
            QuestCondition.QuestComplete to "Quest complete (manual advance)",
            QuestCondition.QuestInterfaceOpen to "Quest dialog open",
            QuestCondition.InterfaceOpen() to "Interface open (set id)",
            QuestCondition.InterfaceOpen(1500) to "Interface 1500 open",
            QuestCondition.NpcNearTile(typeId = 7, tileX = 3, tileY = 4, distance = 2) to "NPC #7 within 2 of (3, 4)",
            QuestCondition.NpcNearTile(displayName = "Imp", tileX = 3, tileY = 4, distance = 1, instance = true) to "Imp within 1 of (3, 4) [instance-local]",
            QuestCondition.ConversationActive to "Conversation active",
            QuestCondition.ConversationInactive to "Conversation inactive",
            QuestCondition.ConversationText("Hello") to "Dialog says: \"Hello\"",
            QuestCondition.ChatText("Oh dear") to "Chat says: \"Oh dear\"",
            QuestCondition.InInstance to "Player in instance",
            QuestCondition.InCombatWith(5, "Rat") to "In combat with Rat",
            QuestCondition.InCombatWith(5) to "In combat with NPC #5",
            QuestCondition.InCombatWith(-1) to "In combat with (unknown enemy)",
            QuestCondition.ItemClicked(42) to "Item #42 clicked",
            QuestCondition.Generic to "(generic - manual advance)",
            QuestCondition.Unknown("oddKind") to "(unsupported: oddKind)",
        )
        expected.forEach { (condition, text) -> assertEquals(text, ConditionEvaluator.describe(condition)) }
    }

    @Test
    fun `describe cuts quoted text to sixty characters`() {
        val long = "x".repeat(100)
        assertEquals("Dialog says: \"${"x".repeat(60)}\"", ConditionEvaluator.describe(QuestCondition.ConversationText(long)))
        assertEquals("Chat says: \"${"x".repeat(60)}\"", ConditionEvaluator.describe(QuestCondition.ChatText(long)))
    }

    @Test
    fun `dialogue snapshot is empty while debugging is off`() {
        ConditionEvaluator.debugCaptureEnabled = false
        ConditionEvaluator.tickSnapshot()
        assertTrue(ConditionEvaluator.captureAllDialogText().isEmpty())
    }

    @Test
    fun `target specs carry the matching fields of each source`() {
        val at = WorldLocation(1.0, 2.0, 3.0)
        val highlight = QuestAction.ModelHighlight("object", 9, listOf(1), listOf(2), listOf(3), "Door", "all", true, 4, at)
        assertEquals(QuestTargetMatcher.Spec(9, listOf(2), listOf(3), "Door", "object", at, instance = true, animated = false), QuestTargetMatcher.specFor(highlight))

        val visible = QuestCondition.ModelVisible("npc", 5, listOf(1), listOf(6), listOf(7), "Guard", animated = true, instance = true, quantity = 2, atLocation = at)
        assertEquals(QuestTargetMatcher.Spec(5, listOf(6), listOf(7), "Guard", "npc", at, instance = true, animated = true), QuestTargetMatcher.specFor(visible))

        val hidden = QuestCondition.ModelNotVisible("model", 5, listOf(1), listOf(6), listOf(7), "Guard", instance = false, atLocation = null)
        assertEquals(QuestTargetMatcher.Spec(5, listOf(6), listOf(7), "Guard", "model", null, instance = false, animated = false), QuestTargetMatcher.specFor(hidden))

        val near = QuestCondition.NpcNearTile(8, "Imp", 1, 2, 0, 3, instance = true, candidateNpcTypeIds = listOf(9))
        assertEquals(QuestTargetMatcher.Spec(typeId = 8, candidateNpcTypeIds = listOf(9), displayName = "Imp", kind = "npc"), QuestTargetMatcher.specFor(near))
    }

    @Test
    fun `matcher finds nothing when there is nothing to match on`() {
        val empty = QuestTargetMatcher.Spec()
        assertEquals(-1, empty.typeId)
        assertEquals("", empty.kind)
        assertTrue(QuestTargetMatcher.findAll(empty).isEmpty())
        assertNull(QuestTargetMatcher.findFirst(empty))
        assertFalse(QuestTargetMatcher.isPresent(empty))
        assertFalse(QuestTargetMatcher.countAtLeast(empty, 1))
        assertFalse(QuestTargetMatcher.anyNpcNearTile(empty, 3200, 3200, 0, 5))
        assertTrue(QuestTargetMatcher.findGroundItems(empty).isEmpty(), "only grounditem specs look at ground items")
        assertTrue(QuestTargetMatcher.findAll(QuestTargetMatcher.Spec(typeId = 5, kind = "grounditem")).isEmpty(), "ground items are never scene entities")
        assertTrue(QuestTargetMatcher.findGroundItems(QuestTargetMatcher.Spec(kind = "grounditem")).isEmpty())
    }
}
