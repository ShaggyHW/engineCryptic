package com.projectx.quest.spec

import com.projectx.quest.data.Quest
import com.projectx.quest.data.QuestReq
import com.projectx.quest.runtime.CacheQuests
import com.projectx.quest.runtime.QuestStatus
import com.projectx.quest.runtime.QuestStatusEvaluator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class QuestStatusRulesTest {
    @Test
    fun `status order is fixed`() {
        assertEquals(listOf("UNKNOWN", "NOT_STARTED", "IN_PROGRESS", "COMPLETED"), QuestStatus.entries.map { it.name })
    }

    @Test
    fun `a quest with no stage varbit has unknown status`() {
        assertEquals(QuestStatus.UNKNOWN, QuestStatusEvaluator.statusOf(Quest(slug = "s", name = "S")))
        assertEquals(QuestStatus.UNKNOWN, QuestStatusEvaluator.statusOf(Quest(slug = "s", name = "S", stageVarbit = -7, stageVarbitCompleteValue = 3)))
    }

    @Test
    fun `requirements the helper cannot check are treated as met`() {
        assertFalse(QuestStatusEvaluator.isReqUnmet(QuestReq("combat", level = 138)))
        assertFalse(QuestStatusEvaluator.isReqUnmet(QuestReq("misc", text = "Have a friend")))
        assertFalse(QuestStatusEvaluator.isReqUnmet(QuestReq("somethingNew", level = 99)))
        assertFalse(QuestStatusEvaluator.isReqUnmet(QuestReq("skill", "Basket weaving", 99)))
        assertFalse(QuestStatusEvaluator.isReqUnmet(QuestReq("skill", null, 99)))
    }

    @Test
    fun `members gating is off, so nothing is members blocked`() {
        val members = Quest(slug = "m", name = "M", members = true)
        assertFalse(QuestStatusEvaluator.isMembersBlocked(members))
        assertFalse(QuestStatusEvaluator.isLocked(members))
    }

    @Test
    fun `unknown prerequisites never lock a quest`() {
        assertFalse(QuestStatusEvaluator.isPrereqUnmet("A Quest That Does Not Exist Anywhere"))
        val q = Quest(slug = "p", name = "P", prereqQuests = listOf("A Quest That Does Not Exist Anywhere"), questReqs = listOf(QuestReq("misc", text = "x")))
        assertFalse(QuestStatusEvaluator.isLocked(q))
    }

    @Test
    fun `cache quest names compare loosely`() {
        assertTrue(CacheQuests.sameName("The Restless Ghost", "restless ghost"))
        assertTrue(CacheQuests.sameName("Recipe for Disaster: Freeing the Mountain Dwarf", "recipe for disaster - freeing the mountain dwarf"))
        assertTrue(CacheQuests.sameName("  ", "!!"))
        assertFalse(CacheQuests.sameName("Dragon Slayer", "Dragon Slayer II"))
        assertFalse(CacheQuests.sameName("Thesis", "sis"), "only a leading \"the \" word is dropped")
        assertFalse(CacheQuests.sameName("Desert Treasure", "Desert Treasure 2"))
    }
}
