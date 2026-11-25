package com.projectx.quest.spec

import com.projectx.quest.data.CombatNpcInfo
import com.projectx.quest.data.Quest
import com.projectx.quest.data.QuestAction
import com.projectx.quest.data.QuestCondition
import com.projectx.quest.data.QuestItemReq
import com.projectx.quest.data.QuestLibrary
import com.projectx.quest.data.QuestReq
import com.projectx.quest.data.QuestStep
import com.projectx.quest.data.TpHint
import com.projectx.quest.data.WorldLocation
import com.projectx.quest.editor.MutableStep
import com.projectx.quest.editor.QuestEditorState
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Invoked through [IsolatedLibrary] only, inside a class loader whose project root is a temp folder. */
class IsolatedLibraryScenarios {
    private fun questDir(root: String) = IsolatedLibrary.questDir(File(root))

    fun bundledGuidesSurviveSaving(root: String) {
        val loaded = QuestLibrary.reload()
        assertTrue(loaded > 300, "loaded $loaded")
        val before = QuestLibrary.quests

        assertEquals(0, QuestLibrary.splitBundleToFiles(), "existing files are kept unless overwriting")
        assertEquals(loaded, QuestLibrary.splitBundleToFiles(overwrite = true))
        assertEquals(before, QuestLibrary.quests)
        assertEquals(loaded, QuestLibrary.reload())
        assertEquals(before, QuestLibrary.quests)

        assertEquals(loaded, QuestLibrary.splitBundleToFiles(true))
        assertEquals(before, QuestLibrary.quests, "a second save of saved files changes nothing")
    }

    fun saveWritesOneFileAndRefreshesLookups(root: String) {
        QuestLibrary.reload()
        val cook = assertNotNull(QuestLibrary.bySlug("cooks-assistant"))
        val renamed = cook.copy(name = "Zzz Renamed Cook", steps = cook.steps.take(2))

        val file = assertNotNull(QuestLibrary.saveQuest(renamed))
        assertEquals(File(questDir(root), "cooks-assistant.json").canonicalFile, file.canonicalFile)
        assertEquals(file.absolutePath, QuestLibrary.lastSavedPath)
        assertEquals(renamed, QuestLibrary.bySlug("cooks-assistant"))
        assertEquals(renamed, QuestLibrary.byName("zzz renamed cook"))
        assertNull(QuestLibrary.byName("Cook's Assistant"))
        assertEquals(renamed, QuestLibrary.quests.last())

        val brandNew = Quest(slug = "spec-new-quest", name = "Spec New Quest")
        val created = assertNotNull(QuestLibrary.saveQuest(brandNew))
        assertTrue(created.isFile)
        assertEquals("spec-new-quest.json", created.name)
        assertEquals(brandNew, QuestLibrary.bySlug("spec-new-quest"))
        assertEquals(File(questDir(root), "spec-new-quest.json").canonicalFile, QuestLibrary.perQuestFile("spec-new-quest").canonicalFile)
    }

    fun everyKindRoundTrips(root: String) {
        val quest = SampleQuest.everyKind()
        assertNotNull(QuestLibrary.saveQuest(quest))
        assertEquals(1, QuestLibrary.reload())
        assertEquals(quest, QuestLibrary.bySlug(quest.slug))
    }

    fun handWrittenFilesDecodeWithDefaults(root: String) {
        assertEquals(1, QuestLibrary.reload())
        val q = assertNotNull(QuestLibrary.bySlug("legacy-quest"))
        assertEquals("Legacy Quest", q.name)
        assertNull(q.length)
        assertEquals(42, q.stageVarbit)

        val step = q.steps.single()
        assertNull(step.title)
        assertEquals("only text", step.text)
        assertEquals(0, step.jumpOffset)
        assertTrue(step.neededItems.isEmpty())
        assertTrue(step.jumpconditions.isEmpty())
        assertNull(step.solverId)
        assertNull(step.tpHint)

        assertEquals(
            listOf(
                QuestAction.Direction(10.0, 0.0, -20.0, instance = true),
                QuestAction.Direction(3200.0, 0.0, 3200.0, instance = false),
                QuestAction.Direction(10.0, 0.0, 20.0, instance = false, tile = true, distance = 3),
                QuestAction.ModelHighlight("npc", 77, emptyList(), displayName = "Legacy id"),
                QuestAction.ModelHighlight("object", -1, listOf(5, 6), displayName = "Located", atLocation = WorldLocation(1.0, 0.0, 2.0)),
                QuestAction.PathGuide(listOf(QuestAction.PathGuide.Waypoint(1.0, 0.0, 2.0), QuestAction.PathGuide.Waypoint(3.0, 7.0, 4.0)), instance = true),
                QuestAction.PathGuide(emptyList(), instance = false),
                QuestAction.PathGuide(emptyList(), instance = false),
                QuestAction.InterfaceComponentHighlight(1477, 9),
                QuestAction.TextHint("press the thing"),
                QuestAction.Unknown("someOldName"),
                QuestAction.Unknown("teleportBubble"),
            ),
            step.actions,
        )
        assertEquals(
            listOf(
                QuestCondition.DistanceToWithHeight(1.0, 800.0, 2.0, 2, false),
                QuestCondition.DistanceToWithHeight(1.0, 0.0, 2.0, 1, false),
                QuestCondition.DistanceTo(5.0, 0.0, 6.0, 3, false),
                QuestCondition.InterfaceOpen(-1),
                QuestCondition.NpcNearTile(),
                QuestCondition.InCombatWith(9),
                QuestCondition.ModelVisible("npc", 12, emptyList(), displayName = "Old id"),
                QuestCondition.CaptureConversationState("(\\d+)", "count"),
                QuestCondition.StateEquals("count", "3"),
                QuestCondition.Generic,
                QuestCondition.Unknown("legacyThing"),
                QuestCondition.Unknown("brandNewKind"),
            ),
            step.postconditions,
        )
    }

    fun legacyBundleIsOverriddenByPerQuestFiles(root: String) {
        assertEquals(3, QuestLibrary.reload())
        assertEquals(listOf("Bundle Only", "From File", "Only File"), QuestLibrary.quests.map { it.name })
        assertEquals("From File", QuestLibrary.bySlug("shared")?.name)
        assertNull(QuestLibrary.byName("From Bundle"))
        val source = QuestLibrary.lastLoadSource
        assertTrue(source.contains(IsolatedLibrary.legacyBundle(File(root)).path), source)
        assertTrue(source.contains("(2 quests)"), source)
        assertTrue(source.indexOf("quests.json") < source.indexOf("(2 quests)"), source)
    }

    fun unreadableFilesAreSkipped(root: String) {
        assertEquals(1, QuestLibrary.reload())
        assertNotNull(QuestLibrary.bySlug("good"))
        assertTrue(QuestLibrary.lastLoadSource.contains("(1 quests)"), QuestLibrary.lastLoadSource)
    }

    fun missingDirectoryFallsBackToClasspathUntilCreated(root: String) {
        assertFalse(questDir(root).exists())
        val count = QuestLibrary.reload()
        assertTrue(count > 300, "classpath gave $count")
        assertTrue(QuestLibrary.lastLoadSource.startsWith("classpath:/quest-data/quests"), QuestLibrary.lastLoadSource)
        assertTrue(QuestLibrary.lastLoadSource.contains("($count quests)"), QuestLibrary.lastLoadSource)

        val target = QuestLibrary.perQuestFile("x")
        assertEquals(File(questDir(root), "x.json").canonicalFile, target.canonicalFile)
        assertTrue(questDir(root).isDirectory, "asking for a path creates the directory")
        assertFalse(target.exists())

        assertEquals(0, QuestLibrary.reload(), "an existing directory is the only source, even when empty")
        assertEquals("(missing)", QuestLibrary.lastLoadSource)
        assertTrue(QuestLibrary.quests.isEmpty())
    }

    fun emptyDirectoryLoadsNothing(root: String) {
        assertEquals(0, QuestLibrary.reload())
        assertEquals("(missing)", QuestLibrary.lastLoadSource)
        assertNull(QuestLibrary.bySlug("cooks-assistant"))
    }

    fun editorSaveKeepsWhatItCannotEdit(root: String) {
        val original = SampleQuest.everyKind()
        assertNotNull(QuestLibrary.saveQuest(original))
        QuestEditorState.beginEdit(assertNotNull(QuestLibrary.bySlug(original.slug)))
        val draft = assertNotNull(QuestEditorState.draft)
        draft.name = "Spec Every Kind, edited"
        draft.steps[0].text = "edited text"
        assertTrue(QuestEditorState.save())
        QuestEditorState.discard()

        assertEquals(1, QuestLibrary.reload())
        val saved = assertNotNull(QuestLibrary.bySlug(original.slug))
        assertEquals(original.combatNPCs, saved.combatNPCs)
        assertEquals(original.prereqQuests, saved.prereqQuests)
        assertEquals(original.questReqs, saved.questReqs)
        assertEquals(original.steps[0].tpHint, saved.steps[0].tpHint)
        val expected = original.copy(
            name = "Spec Every Kind, edited",
            steps = listOf(original.steps[0].copy(text = "edited text")) + original.steps.drop(1),
        )
        assertEquals(expected, saved)
    }

    fun guideWrittenForGuidelessQuestReplacesIt(root: String) {
        val guideless = Quest(slug = "spec-guideless", name = "Spec Guideless", members = true, stageVarbit = 5, stageVarbitCompleteValue = 10, cacheQuestId = 77)
        QuestEditorState.beginEdit(guideless)
        val draft = assertNotNull(QuestEditorState.draft)
        assertTrue(draft.steps.isEmpty())
        assertEquals(0, QuestEditorState.selectedStepIndex.value)
        assertTrue(QuestEditorState.windowOpen.value)
        draft.steps.add(MutableStep(title = "New step", text = "Talk to the guide"))
        assertTrue(QuestEditorState.save())
        QuestEditorState.discard()

        assertEquals(1, QuestLibrary.reload())
        val written = assertNotNull(QuestLibrary.bySlug("spec-guideless"))
        assertEquals(guideless.copy(steps = listOf(QuestStep(title = "New step", text = "Talk to the guide"))), written)
    }
}

object SampleQuest {
    fun everyKind(): Quest = Quest(
        slug = "spec-every-kind",
        name = "Spec Every Kind",
        members = true,
        length = "long",
        timeline = null,
        releaseDate = 1234L,
        neededItems = listOf(QuestItemReq("Rope", 954, 2, duringQuest = true)),
        recommendedItems = listOf(QuestItemReq("Food", -1, 5)),
        combatNPCs = listOf(CombatNpcInfo("Goblin", "5", 3)),
        prereqQuests = listOf("Cook's Assistant"),
        questReqs = listOf(QuestReq("skill", "Agility", 30), QuestReq("misc", text = "Be brave"), QuestReq("ironmanOnlySkill", "Mining", 10, ironmanOnly = true)),
        steps = listOf(
            QuestStep(
                title = "All actions",
                text = "text",
                warning = "careful",
                tpHint = TpHint("icon", "Lodestone", "lode.png"),
                actions = listOf(
                    QuestAction.Direction(3200.5, 12.0, 3201.0),
                    QuestAction.Direction(4.0, 0.0, 5.0, instance = true, tile = true, distance = 2),
                    QuestAction.Direction(4.0, 0.0, 5.0, instance = false),
                    QuestAction.ModelHighlight("npc", 100, listOf(1, 2), listOf(3), listOf(4), "Bob", "all", true, 6, WorldLocation(1.0, 2.0, 3.0)),
                    QuestAction.ModelHighlight("object", -1, emptyList(), displayName = "Door"),
                    QuestAction.ConversationHighlight("Yes."),
                    QuestAction.InventoryHighlight(995, listOf(7), listOf(8, 9), "Coins"),
                    QuestAction.InventoryHighlight(-1, emptyList(), displayName = "Key"),
                    QuestAction.ContinueConversation,
                    QuestAction.ResetInstance,
                    QuestAction.InterfaceComponentHighlight(1477, 3, 4, "Slot", 0x11223344),
                    QuestAction.InterfaceComponentHighlight(1477, 5),
                    QuestAction.PathGuide(listOf(QuestAction.PathGuide.Waypoint(3200.0, 1.0, 3200.0), QuestAction.PathGuide.Waypoint(3205.0, 0.0, 3210.0))),
                    QuestAction.PathGuide(listOf(QuestAction.PathGuide.Waypoint(1.0, 0.0, 2.0)), instance = true),
                    QuestAction.TextHint("hint"),
                    QuestAction.Unknown("mystery"),
                ),
                postconditions = listOf(
                    QuestCondition.DistanceTo(3200.0, 0.0, 3200.0, 4),
                    QuestCondition.DistanceFrom(1.0, 0.0, 2.0, 5, instance = true),
                    QuestCondition.DistanceToWithHeight(3200.0, 900.0, 3200.0, 0),
                    QuestCondition.DistanceFromWithHeight(3200.0, 100.0, 3200.0, 7, true),
                    QuestCondition.NotInInstance,
                    QuestCondition.ChangedInstance,
                    QuestCondition.Manual,
                    QuestCondition.Always,
                    QuestCondition.CaptureConversationState("p", "k"),
                    QuestCondition.StateEquals("k", "v"),
                    QuestCondition.InventoryContains(995, "Coins", 10, listOf(1, 2)),
                    QuestCondition.InventoryContains(-1, "Key", 1),
                    QuestCondition.InventoryDoesNotContain(954, "Rope", listOf(3)),
                    QuestCondition.ModelVisible("npc", 5, listOf(1), listOf(2), listOf(3), "Guard", animated = true, instance = true, quantity = 2, atLocation = WorldLocation(4.0, 0.0, 5.0)),
                    QuestCondition.ModelVisible("grounditem", -1, emptyList(), displayName = "Bones"),
                    QuestCondition.ModelNotVisible("object", 6, emptyList(), displayName = "Rock", instance = true, atLocation = WorldLocation(1.0, 1.0, 1.0)),
                    QuestCondition.QuestStarted,
                    QuestCondition.QuestComplete,
                    QuestCondition.QuestInterfaceOpen,
                    QuestCondition.InterfaceOpen(1500),
                    QuestCondition.InterfaceOpen(),
                    QuestCondition.NpcNearTile(10, "Imp", 3, 4, 1, 5, true, listOf(11)),
                    QuestCondition.NpcNearTile(),
                ),
                jumpconditions = listOf(
                    QuestCondition.ConversationActive,
                    QuestCondition.ConversationInactive,
                    QuestCondition.ConversationText("hello there"),
                    QuestCondition.ChatText("You get"),
                    QuestCondition.InInstance,
                    QuestCondition.InCombatWith(12, "Rat"),
                    QuestCondition.ItemClicked(13),
                    QuestCondition.Generic,
                    QuestCondition.Unknown("legacy"),
                ),
                jumpOffset = -2,
                neededItems = listOf(QuestItemReq("Rope", 954, 1)),
                solverId = "spec.solver",
            ),
            QuestStep(),
        ),
        stageVarbit = 99,
        stageVarbitCompleteValue = 10,
        cacheQuestId = 7,
    )
}
