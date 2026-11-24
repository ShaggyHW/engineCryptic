package com.projectx.script.api

import world.gregs.voidps.type.Tile
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AttackLedgerTest {
    private val here = Tile.of(2900, 9800, 0)

    @Test
    fun `an attack is remembered for the window and then forgotten`() {
        val ledger = AttackLedger()
        ledger.remember(listOf(7), 1_000L, here)

        assertTrue(ledger.seenWithin(3_999L, AttackMemory.MEMORY_MS, here) { true })
        assertFalse(ledger.seenWithin(4_000L, AttackMemory.MEMORY_MS, here) { true })
    }

    @Test
    fun `a dead or departed attacker ends the memory`() {
        val ledger = AttackLedger()
        ledger.remember(listOf(7), 1_000L, here)

        assertFalse(ledger.seenWithin(1_500L, AttackMemory.MEMORY_MS, here) { false })
    }

    @Test
    fun `teleporting away ends the memory and walking with the attacker does not`() {
        val ledger = AttackLedger()
        ledger.remember(listOf(7), 1_000L, here)

        assertTrue(ledger.seenWithin(1_500L, AttackMemory.MEMORY_MS, Tile.of(2950, 9800, 0)) { true })
        assertFalse(ledger.seenWithin(1_500L, AttackMemory.MEMORY_MS, Tile.of(2951, 9800, 0)) { true })
        assertFalse(ledger.seenWithin(1_500L, AttackMemory.MEMORY_MS, Tile.of(2900, 9800, 1)) { true })
    }

    @Test
    fun `any one live attacker keeps the memory`() {
        val ledger = AttackLedger()
        ledger.remember(listOf(7, 9), 1_000L, here)

        assertTrue(ledger.seenWithin(1_500L, AttackMemory.MEMORY_MS, here) { it == 9 })
    }

    @Test
    fun `a longer window still sees an older attack`() {
        val ledger = AttackLedger()
        ledger.remember(listOf(7), 1_000L, here)

        assertTrue(ledger.seenWithin(10_000L, 15_000L, here) { true })
        assertFalse(ledger.seenWithin(61_000L, 120_000L, here) { true })
    }
}
