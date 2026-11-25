package com.projectx.quest.runtime

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RecentChatTest {

    @Test
    fun `findSince returns the newest matching message`() {
        val since = System.currentTimeMillis()
        RecentChat.record("You can't dive there. (alpha)")
        RecentChat.record("You can't DIVE there. (beta)")

        assertEquals("You can't DIVE there. (beta)", RecentChat.findSince("can't dive there", since))
        assertTrue(RecentChat.containsSince("can't dive there", since))
    }

    @Test
    fun `findSince ignores messages before the cutoff`() {
        RecentChat.record("A zebra-unicorn walks past.")

        assertNull(RecentChat.findSince("zebra-unicorn", System.currentTimeMillis() + 60_000L))
        assertFalse(RecentChat.containsSince("zebra-unicorn", System.currentTimeMillis() + 60_000L))
    }
}
