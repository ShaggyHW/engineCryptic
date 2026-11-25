package com.projectx.quest.spec

import com.projectx.quest.runtime.RecentChat
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RecentChatBehaviourTest {
    private fun token() = "tok${UUID.randomUUID().toString().replace("-", "")}"

    @Test
    fun `blank messages are never stored`() {
        RecentChat.record("")
        RecentChat.record("   ")
        RecentChat.record("\t\n")
        assertNull(RecentChat.latestMatch(Regex("^\\s*$")))
    }

    @Test
    fun `only the newest sixty-four messages are kept`() {
        val id = token()
        repeat(70) { RecentChat.record("$id line $it") }
        for (i in 0 until 6) assertNull(RecentChat.latestMatch(Regex("$id line $i$")), "line $i should have been evicted")
        for (i in 6 until 70) assertNotNull(RecentChat.latestMatch(Regex("$id line $i$")), "line $i should still be there")
    }

    @Test
    fun `latest match searches newest first and returns the regex match`() {
        val id = token()
        RecentChat.record("$id gained 1 xp")
        RecentChat.record("unrelated noise")
        RecentChat.record("$id gained 7 xp")
        val match = assertNotNull(RecentChat.latestMatch(Regex("$id gained (\\d+) xp")))
        assertEquals("7", match.groupValues[1])
        assertNull(RecentChat.latestMatch(Regex("${id.uppercase()} GAINED")), "the regex decides case sensitivity")
    }

    @Test
    fun `since queries ignore case and include messages at or after the cutoff`() {
        val id = token()
        val before = System.currentTimeMillis()
        RecentChat.record("The door is LOCKED ($id).")
        assertTrue(RecentChat.containsSince("door is locked ($id)", before))
        assertEquals("The door is LOCKED ($id).", RecentChat.findSince("DOOR IS locked ($id)", before))
        assertFalse(RecentChat.containsSince(id, System.currentTimeMillis() + 5_000))
        assertNull(RecentChat.findSince(id, System.currentTimeMillis() + 5_000))
    }

    @Test
    fun `find since returns the newest qualifying message`() {
        val id = token()
        val before = System.currentTimeMillis()
        RecentChat.record("$id first")
        RecentChat.record("$id second")
        assertEquals("$id second", RecentChat.findSince(id, before))
    }

    @Test
    fun `blank needles never match in contains queries`() {
        RecentChat.record("something to search ${token()}")
        assertFalse(RecentChat.containsSince("", 0L))
        assertFalse(RecentChat.containsSince("  ", 0L))
        assertFalse(RecentChat.containsRecent(""))
        assertFalse(RecentChat.containsRecent(" "))
    }

    @Test
    fun `contains recent looks back over a window ending now`() {
        val id = token()
        RecentChat.record("You find a ${id.uppercase()} in the chest.")
        assertTrue(RecentChat.containsRecent(id))
        assertTrue(RecentChat.containsRecent(id, 60_000L))
        assertFalse(RecentChat.containsRecent(id, -60_000L), "a window ending in the future excludes everything")
        assertFalse(RecentChat.containsRecent(token()))
    }
}
