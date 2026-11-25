package com.projectx.store

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
    fun `every watched container exists in this cache`() {
        assertEquals(30, LootContainers.ids.size)
    }
}
