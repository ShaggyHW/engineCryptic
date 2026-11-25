package com.projectx.quest.data

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.projectx.quest.runtime.CacheQuests
import com.projectx.quest.runtime.QuestStatus
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.projectx.core.ProjectPaths
import world.gregs.voidps.cache.type.data.QuestSkillRequirement
import world.gregs.voidps.cache.type.data.QuestType
import world.gregs.voidps.cache.type.data.QuestVarProgress
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CacheQuestCatalogTest {
    private val params = CacheQuestCatalog.QuestParams(disabled = 1, releaseYear = 2, length = 3)
    private val noDevNames: (Int) -> String? = { null }

    private fun type(
        id: Int,
        name: String?,
        progress: Boolean = true,
        year: Boolean = true,
        disabled: Boolean = false,
        length: Int? = null,
        members: Boolean = false,
        quests: List<Int>? = null,
        skills: List<QuestSkillRequirement>? = null,
        questPoints: Int = 0,
    ) = QuestType(
        id = id,
        name = name,
        varbitProgress = if (progress) listOf(QuestVarProgress(1000 + id, 1, 10)) else null,
        unknown8 = members,
        questRequirements = quests,
        skillRequirements = skills,
        questPointRequirement = questPoints,
        params = buildMap {
            if (year) put(2, 2026)
            if (disabled) put(1, 1)
            if (length != null) put(3, length)
        },
    )

    @Test
    fun `a new cache quest becomes a guide-less quest with its requirements`() {
        val types = listOf(
            type(0, "Secrets of Amberfell"),
            type(1, "Heralds of Crimson", length = 7, members = true, quests = listOf(0), questPoints = 40,
                skills = listOf(QuestSkillRequirement(1, 55), QuestSkillRequirement(22, 60))),
        )
        val stubs = CacheQuestCatalog.withoutGuides(types, listOf(), params, noDevNames)
        val heralds = stubs.single { it.cacheQuestId == 1 }
        assertEquals(
            Quest(
                slug = "heralds-of-crimson",
                name = "Heralds of Crimson",
                members = true,
                length = "longverylong",
                prereqQuests = listOf("Secrets of Amberfell"),
                questReqs = listOf(QuestReq("skill", "Defence", 55), QuestReq("skill", "Construction", 60), QuestReq("questpoints", level = 40)),
                cacheQuestId = 1,
            ),
            heralds,
        )
        assertTrue(heralds.steps.isEmpty())
        assertEquals(-1, heralds.stageVarbit)
    }

    @Test
    fun `quests that already have a guide are left out by id or by name`() {
        val guides = listOf(
            Quest(slug = "cooks-assistant", name = "Cook's Assistant", cacheQuestId = 5),
            Quest(slug = "restless-ghost", name = "Restless Ghost"),
        )
        val types = listOf(type(5, "Cook's Assistant (renamed)"), type(6, "The Restless Ghost"), type(7, "Brand New"))
        assertEquals(listOf("Brand New"), CacheQuestCatalog.withoutGuides(types, guides, params, noDevNames).map { it.name })
    }

    @Test
    fun `entries that are not real quests are left out`() {
        val types = listOf(
            type(0, null),
            type(1, "   "),
            type(2, "No Progress", progress = false),
            type(3, "Holly", disabled = true),
            type(4, "Unreleased", year = false),
            type(5, "Learning the Ropes"),
            type(6, "A Real Quest"),
        )
        val devNames = mapOf(5 to "quest_tut6", 6 to "quest_construction_tutorial")
        assertEquals(listOf("A Real Quest"), CacheQuestCatalog.withoutGuides(types, listOf(), params) { devNames[it] }.map { it.name })
    }

    @Test
    fun `slugs stay unique and a repeated name is listed once`() {
        val guides = listOf(Quest(slug = "fresh-start", name = "Something Else", cacheQuestId = 99))
        val types = listOf(type(1, "Fresh Start"), type(2, "Fresh start"), type(3, "There's No Place Like Home..."))
        val stubs = CacheQuestCatalog.withoutGuides(types, guides, params, noDevNames)
        assertEquals(listOf("fresh-start-1", "theres-no-place-like-home"), stubs.map { it.slug })
    }

    @Test
    fun `a saved guide replaces the stub it was written from`() {
        val types = listOf(type(41, "Heralds of Crimson"))
        val stub = CacheQuestCatalog.withoutGuides(types, listOf(), params, noDevNames).single()
        val written = stub.copy(name = "Heralds of Crimson (guide)", steps = listOf(QuestStep(text = "Talk to Wendle")))
        val reloaded = GuideFormat.parse(GuideFormat.print(written))
        assertEquals(written, reloaded)
        assertTrue(CacheQuestCatalog.withoutGuides(types, listOf(reloaded), params, noDevNames).isEmpty())
    }

    @Test
    fun `cache progress vars give the journal status`() {
        val quest = QuestType(id = 1, varbitProgress = listOf(QuestVarProgress(7, 2, 18)), varpProgress = listOf(QuestVarProgress(8, 1, 3)))
        fun status(varbit: Int, varp: Int) = CacheQuests.status(quest, varp = { varp }, varbit = { varbit })
        assertEquals(QuestStatus.NOT_STARTED, status(0, 0))
        assertEquals(QuestStatus.NOT_STARTED, status(1, 0), "below the started value")
        assertEquals(QuestStatus.IN_PROGRESS, status(2, 0))
        assertEquals(QuestStatus.IN_PROGRESS, status(18, 2), "every var must reach its completed value")
        assertEquals(QuestStatus.COMPLETED, status(18, 3))
        assertEquals(QuestStatus.UNKNOWN, CacheQuests.status(QuestType(id = 2), { 0 }, { 0 }))
    }

    @Test
    fun `the decoded cache lists the newest quests without guides`() {
        val root = assumeNotNull(ProjectPaths.root)
        val dump = File(root, "data/cache-unpacked").listFiles().orEmpty()
            .map { File(it, "types/quest.jsonl") }.filter { it.isFile }.maxByOrNull { it.parentFile.parentFile.name }
        assumeTrue(dump != null, "no decoded quest types on this machine")
        val params = CacheQuestCatalog.QuestParams.fromGamevals()
        assumeTrue(params.disabled != null && params.releaseYear != null, "no gameval dictionaries")

        val types = dump!!.readLines().filter { it.isNotBlank() }.map { decode(JsonParser.parseString(it).asJsonObject) }
        val guides = File(root, "client-plugin-engine/src/main/resources/quest-data/quests").listFiles { f -> f.name.endsWith(".json") }!!
            .map { GuideFormat.parse(it.readText()) }
        val stubs = CacheQuestCatalog.withoutGuides(types, guides)
        val names = stubs.map { it.name }.toSet()
        println("[CacheQuestCatalogTest] ${stubs.size} guide-less quests from ${dump.path}: ${names.sorted()}")

        val heralds = assertNotNull(stubs.firstOrNull { it.name == "Heralds of Crimson" })
        assertTrue(heralds.members)
        assertEquals(listOf("Secrets of Amberfell"), heralds.prereqQuests)
        assertTrue(heralds.questReqs.any { it.type == "skill" && it.name == "Smithing" })
        assertNotNull(heralds.length)
        assertTrue("Secrets of Amberfell" in names)
        assertNull(stubs.firstOrNull { it.name == "Holly" }, "disabled event placeholder")
        assertNull(stubs.firstOrNull { it.name == "Learning the Ropes" }, "tutorial")
        assertNull(stubs.firstOrNull { it.name == "Unstable Foundations" }, "tutorial")
        assertNull(stubs.firstOrNull { CacheQuests.sameName(it.name, "Cook's Assistant") }, "has a guide")
        assertEquals(stubs.size, stubs.map { it.slug }.toSet().size)
        assertTrue(guides.none { g -> stubs.any { it.slug == g.slug } })
    }

    private fun <T : Any> assumeNotNull(value: T?): T {
        assumeTrue(value != null, "no repo root")
        return value!!
    }

    private fun decode(o: JsonObject): QuestType {
        fun progress(key: String) = o.get(key)?.takeIf { it.isJsonArray }?.asJsonArray?.map {
            val p = it.asJsonObject
            QuestVarProgress(p.get("varId").asInt, p.get("startedValue").asInt, p.get("completedValue").asInt)
        }
        return QuestType(
            id = o.get("id").asInt,
            name = o.get("name")?.takeUnless { it.isJsonNull }?.asString,
            varpProgress = progress("varpProgress"),
            varbitProgress = progress("varbitProgress"),
            unknown8 = o.get("unknown8")?.asBoolean ?: false,
            questRequirements = o.get("questRequirements")?.takeIf { it.isJsonArray }?.asJsonArray?.map { it.asInt },
            skillRequirements = o.get("skillRequirements")?.takeIf { it.isJsonArray }?.asJsonArray?.map {
                QuestSkillRequirement(it.asJsonObject.get("skill").asInt, it.asJsonObject.get("level").asInt)
            },
            questPointRequirement = o.get("questPointRequirement")?.asInt ?: 0,
            params = o.get("params")?.takeIf { it.isJsonObject }?.asJsonObject?.entrySet()?.associate { (key, value) ->
                val primitive = value.asJsonPrimitive
                key.toInt() to (if (primitive.isNumber) primitive.asInt else primitive.asString)
            },
        )
    }
}
