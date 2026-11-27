package com.projectx.store

import com.projectx.store.RareDropRules.AnnouncedDrop
import com.projectx.store.RareDropRules.PetMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RareDropsTest {
    @Test
    fun `a drop is worth reporting at the site's minimum or as a clue from hard up`() {
        assertTrue(RareDropRules.worthReporting("Codex", RareDropRules.MIN_VALUE_GP))
        assertFalse(RareDropRules.worthReporting("Codex", RareDropRules.MIN_VALUE_GP - 1))
        assertFalse(RareDropRules.worthReporting("Untradeable thing", null))
        for (name in listOf("Sealed clue scroll (elite)", "Clue scroll (master)", "Clue scroll (hard)", "Reward casket (elite)")) {
            assertTrue(RareDropRules.worthReporting(name, null), name)
        }
        for (name in listOf("Clue scroll (easy)", "Sealed clue scroll (medium)", "Clue scroll (elite) extra")) {
            assertFalse(RareDropRules.isNotable(name), name)
        }
    }

    @Test
    fun `pet messages are told apart by how they start`() {
        assertEquals(PetMessage.FOLLOWING, RareDropRules.petMessage("You have a funny feeling like you're being followed."))
        assertEquals(PetMessage.BACKPACK, RareDropRules.petMessage("You feel something weird sneaking into your backpack."))
        assertEquals(PetMessage.DUPLICATE, RareDropRules.petMessage("You have a funny feeling like you would have been followed..."))
        assertNull(RareDropRules.petMessage("Oh dear, you are dead!"))
    }

    @Test
    fun `a container's loot is what it gained, afresh each time it opens`() {
        val drops = ContainerDrops()
        assertEquals(emptyMap(), drops.observe(mapOf(1 to mapOf(10 to 1))), "what is open on the first look is not loot")
        assertEquals(mapOf(11 to 2), drops.observe(mapOf(1 to mapOf(10 to 1, 11 to 2))))
        assertEquals(emptyMap(), drops.observe(mapOf(1 to mapOf(10 to 1, 11 to 2))))
        assertEquals(emptyMap(), drops.observe(mapOf(1 to null)))
        assertEquals(mapOf(12 to 1), drops.observe(mapOf(1 to mapOf(12 to 1))), "a chest opened again shows its loot")
    }

    @Test
    fun `a ground drop counts only where a fought npc stood`() {
        val drops = GroundDrops()
        val old = GroundDrops.Stack(1, 1, 3200, 3200, 0)
        assertEquals(emptyList(), drops.observe(listOf(old), 0L), "the first look only learns")
        drops.fighting(npc = 7, x = 3210, y = 3210, size = 2, plane = 0, now = 1_000L)
        val atCorpse = GroundDrops.Stack(2, 1, 3211, 3211, 0)
        val elsewhere = GroundDrops.Stack(3, 1, 3230, 3230, 0)
        assertEquals(listOf(atCorpse), drops.observe(listOf(old, atCorpse, elsewhere), 1_600L))
        assertEquals(emptyList(), drops.observe(listOf(old, atCorpse, elsewhere), 2_200L), "a stack is reported once")
    }

    @Test
    fun `a stack that comes back into view is not a new drop`() {
        val drops = GroundDrops()
        drops.fighting(npc = 7, x = 3210, y = 3210, size = 1, plane = 0, now = 0L)
        val stack = GroundDrops.Stack(2, 1, 3210, 3210, 0)
        drops.observe(listOf(stack), 0L)
        drops.observe(emptyList(), 600L)
        assertEquals(emptyList(), drops.observe(listOf(stack), 1_200L))
    }

    @Test
    fun `a fight long over no longer claims new stacks`() {
        val drops = GroundDrops(fightMillis = 30_000L)
        drops.observe(emptyList(), 0L)
        drops.fighting(npc = 7, x = 3210, y = 3210, size = 1, plane = 0, now = 0L)
        assertEquals(emptyList(), drops.observe(listOf(GroundDrops.Stack(2, 1, 3210, 3210, 0)), 31_000L))
    }

    @Test
    fun `the luck of the dwarves line names the item and how many`() {
        val line = "<col=ff8000>Your Luck of the Dwarves ring shines brightly. You receive: 1 x Memory dowser.</col>"
        assertEquals(AnnouncedDrop("Memory dowser", 1, news = false), RareDropRules.announcedDrop(line, "Test Player"))
        assertEquals(AnnouncedDrop("Onyx", 1_200, news = false), RareDropRules.announcedDrop("You receive: 1,200 x Onyx.", null))
    }

    @Test
    fun `a news broadcast counts only when it names this player`() {
        val news = "<img=12><col=ff0000>News: Test Player has received a Memory Dowser drop!</col>"
        assertEquals(AnnouncedDrop("Memory Dowser", 1, news = true), RareDropRules.announcedDrop(news, "Test Player"))
        assertEquals(AnnouncedDrop("Memory Dowser", 1, news = true), RareDropRules.announcedDrop(news, "test player"))
        assertEquals(
            AnnouncedDrop("elder rune bar", 1, news = true),
            RareDropRules.announcedDrop("News: Big\u00A0Name has received an elder rune bar drop!", "Big Name"),
        )
        assertNull(RareDropRules.announcedDrop(news, "Someone Else"))
        assertNull(RareDropRules.announcedDrop(news, null))
    }

    @Test
    fun `other game messages are not drops`() {
        for (line in listOf("You have killed 2,057 The Gate of Elidinis.", "Oh dear, you are dead!", "You receive 5 coins.")) {
            assertNull(RareDropRules.announcedDrop(line, "Test Player"), line)
        }
    }

    @Test
    fun `an announced drop is not reported again from the chest`() {
        val announced = AnnouncedDrops(holdMillis = 60_000L)
        announced.announce("Memory dowser", 1, now = 0L)
        assertEquals(0, announced.unannounced("memory dowser", 1, now = 30_000L), "the chest shows the same dowser")
        assertEquals(1, announced.unannounced("Memory dowser", 1, now = 31_000L), "a second dowser is a new drop")
        assertEquals(2, announced.unannounced("Onyx", 2, now = 31_000L))
    }

    @Test
    fun `an announcement only covers what it named, and not forever`() {
        val announced = AnnouncedDrops(holdMillis = 60_000L)
        announced.announce("Onyx", 1, now = 0L)
        assertEquals(2, announced.unannounced("Onyx", 3, now = 1_000L))
        announced.announce("Memory dowser", 1, now = 0L)
        assertEquals(1, announced.unannounced("Memory dowser", 1, now = 61_000L))
    }

    @Test
    fun `every watched container exists in this cache`() {
        assertEquals(30, LootContainers.ids.size)
    }
}
