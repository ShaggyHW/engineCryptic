package com.projectx.quest.spec

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.projectx.quest.data.Quest
import com.projectx.quest.data.QuestAction
import com.projectx.quest.data.QuestCondition
import com.projectx.quest.data.QuestItemReq
import com.projectx.quest.data.QuestLibrary
import com.projectx.quest.data.TpHint
import org.projectx.core.ProjectPaths
import java.io.File
import kotlin.math.abs
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class BundledGuideLoadingTest {
    private val bundleDir: File = File(requireNotNull(ProjectPaths.root), "client-plugin-engine/src/main/resources/quest-data/quests")
    private val bundleFiles: List<File> by lazy { bundleDir.listFiles { f -> f.isFile && f.name.endsWith(".json") }!!.sortedBy { it.name } }

    @BeforeTest
    fun loadFromRepo() {
        QuestLibrary.reload()
    }

    private fun raw(file: File): JsonObject = file.reader(Charsets.UTF_8).use { JsonParser.parseReader(it).asJsonObject }

    @Test
    fun `every bundled guide file loads`() {
        assertTrue(bundleFiles.size > 300, "bundle has ${bundleFiles.size} files")
        assertEquals(bundleFiles.size, QuestLibrary.quests.size)
        assertEquals(bundleFiles.map { it.name.removeSuffix(".json") }.toSet(), QuestLibrary.quests.map { it.slug }.toSet())
        assertTrue(QuestLibrary.lastLoadSource.contains(bundleDir.absolutePath), QuestLibrary.lastLoadSource)
        assertTrue(QuestLibrary.lastLoadSource.contains("(${bundleFiles.size} quests)"), QuestLibrary.lastLoadSource)
    }

    @Test
    fun `reload returns the number of quests and stamps the load time`() {
        val before = System.currentTimeMillis()
        val count = QuestLibrary.reload()
        assertEquals(QuestLibrary.quests.size, count)
        assertTrue(QuestLibrary.lastLoadMs >= before)
    }

    @Test
    fun `quests are ordered by name ignoring case`() {
        val names = QuestLibrary.quests.map { it.name }
        assertEquals(names.sortedBy { it.lowercase() }, names)
    }

    @Test
    fun `every action and condition decodes as the schema says`() {
        val actionKinds = sortedSetOf<String>()
        val conditionKinds = sortedSetOf<String>()
        for (file in bundleFiles) {
            val json = raw(file)
            val quest = assertNotNull(QuestLibrary.bySlug(json.get("slug").asString))
            val rawSteps = json.getAsJsonArray("steps")
            assertEquals(rawSteps.size(), quest.steps.size, file.name)
            rawSteps.forEachIndexed { i, element ->
                val rawStep = element.asJsonObject
                val step = quest.steps[i]
                val where = "${file.name} step $i"
                assertEquals(rawStep.getAsJsonArray("actions").map { GuideJson.action(it.asJsonObject) }, step.actions, where)
                assertEquals(rawStep.getAsJsonArray("postconditions").map { GuideJson.condition(it.asJsonObject) }, step.postconditions, where)
                assertEquals(rawStep.getAsJsonArray("jumpconditions").map { GuideJson.condition(it.asJsonObject) }, step.jumpconditions, where)
                assertEquals(rawStep.get("jumpOffset").asInt, step.jumpOffset, where)
                assertEquals(rawStep.get("title")?.asString, step.title, where)
                assertEquals(rawStep.get("text")?.asString, step.text, where)
                assertEquals(rawStep.get("warning")?.asString, step.warning, where)
                assertEquals(rawStep.get("solverId")?.asString, step.solverId, where)
                rawStep.getAsJsonArray("actions").forEach { actionKinds += it.asJsonObject.get("t").asString }
                (rawStep.getAsJsonArray("postconditions") + rawStep.getAsJsonArray("jumpconditions")).forEach {
                    conditionKinds += it.asJsonObject.get("t").asString
                }
            }
        }
        assertTrue(actionKinds.containsAll(listOf("direction", "modelHighlight", "conversationHighlight", "inventoryHighlight", "pathGuide", "resetInstance", "continueConversation", "interfaceComponentHighlight")), "$actionKinds")
        assertTrue(conditionKinds.size >= 20, "$conditionKinds")
    }

    @Test
    fun `nothing in the bundle falls back to an unknown kind`() {
        val steps = QuestLibrary.quests.flatMap { it.steps }
        assertFalse(steps.flatMap { it.actions }.any { it is QuestAction.Unknown })
        assertFalse((steps.flatMap { it.postconditions } + steps.flatMap { it.jumpconditions }).any { it is QuestCondition.Unknown })
    }

    @Test
    fun `quest header fields come straight from the file`() {
        val cook = assertNotNull(QuestLibrary.bySlug("cooks-assistant"))
        assertEquals("Cook's Assistant", cook.name)
        assertFalse(cook.members)
        assertEquals("short", cook.length)
        assertEquals(978566400000L, cook.releaseDate)
        assertEquals(QuestItemReq("Bucket", 1925, 1, duringQuest = true), cook.neededItems.first { it.name == "Bucket" })
        assertEquals(QuestItemReq("Pot", -1, 1, duringQuest = true), cook.neededItems.first { it.name == "Pot" })
        assertNull(cook.steps[1].title)

        for (file in bundleFiles) {
            val json = raw(file)
            val q = assertNotNull(QuestLibrary.bySlug(json.get("slug").asString))
            assertEquals(json.get("stageVarbit").asInt, q.stageVarbit, file.name)
            assertEquals(json.get("stageVarbitCompleteValue").asInt, q.stageVarbitCompleteValue, file.name)
            assertEquals(json.get("cacheQuestId").asInt, q.cacheQuestId, file.name)
            assertEquals(json.get("members").asBoolean, q.members, file.name)
            assertEquals(json.get("length")?.asString, q.length, file.name)
            assertEquals(json.getAsJsonArray("prereqQuests").map { it.asString }, q.prereqQuests, file.name)
            assertEquals(json.getAsJsonArray("questReqs").size(), q.questReqs.size, file.name)
            assertEquals(json.getAsJsonArray("combatNPCs").size(), q.combatNPCs.size, file.name)
        }
    }

    @Test
    fun `teleport hints keep type hover and optional url`() {
        val hints = QuestLibrary.quests.flatMap { q -> q.steps.mapNotNull { it.tpHint } }
        assertTrue(hints.isNotEmpty())
        assertTrue(hints.all { it.type.isNotBlank() && it.hover.isNotBlank() })
        assertTrue(hints.any { it.url == null })
        assertTrue(hints.any { it.url != null })
        assertEquals(TpHint("text", "hover"), TpHint("text", "hover", null))
    }

    @Test
    fun `slug lookup is exact and name lookup ignores case`() {
        val cook = assertNotNull(QuestLibrary.bySlug("cooks-assistant"))
        assertSame(cook, QuestLibrary.byName("Cook's Assistant"))
        assertSame(cook, QuestLibrary.byName("COOK'S ASSISTANT"))
        assertSame(cook, QuestLibrary.byName("cook's assistant"))
        assertNull(QuestLibrary.bySlug("COOKS-ASSISTANT"))
        assertNull(QuestLibrary.bySlug("no-such-quest"))
        assertNull(QuestLibrary.byName("No Such Quest"))
    }

    @Test
    fun `every quest is reachable by its slug and by its name`() {
        for (q in QuestLibrary.quests) {
            assertEquals(q, QuestLibrary.bySlug(q.slug))
            assertEquals(q.name.lowercase(), QuestLibrary.byName(q.name.uppercase())?.name?.lowercase())
        }
    }

    @Test
    fun `steps carrying a solver id keep it`() {
        val solverSteps = QuestLibrary.quests.flatMap { q -> q.steps.mapNotNull { it.solverId?.let { id -> q.slug to id } } }
        assertTrue(solverSteps.contains("desperate-times" to "desperate-times.slide-puzzle"), "$solverSteps")
        assertTrue(solverSteps.all { (slug, id) -> id.isNotBlank() && slug.isNotBlank() })
    }

    @Test
    fun `instance flag is inferred from small coordinates only when the file omits it`() {
        val paths = QuestLibrary.quests.flatMap { q -> q.steps.flatMap { it.actions.filterIsInstance<QuestAction.PathGuide>() } }
        assertTrue(paths.any { it.instance })
        assertTrue(paths.any { !it.instance })
        val directions = QuestLibrary.quests.flatMap { q -> q.steps.flatMap { it.actions.filterIsInstance<QuestAction.Direction>() } }
        assertTrue(directions.any { !it.instance && abs(it.x) < 250 && abs(it.y) < 250 }, "explicit false must win over inference")
    }

    @Test
    fun `quest data classes compare by value`() {
        val a = Quest(slug = "a", name = "A")
        assertEquals(a, a.copy())
        assertEquals(-1, a.stageVarbit)
        assertEquals(-1, a.stageVarbitCompleteValue)
        assertEquals(-1, a.cacheQuestId)
        assertTrue(a.steps.isEmpty())
    }
}
