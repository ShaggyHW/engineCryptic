package com.projectx.script.api

import kotlin.test.Test
import kotlin.test.assertEquals

class NpcScanTest {
    @Test
    fun `keeps named attackable npcs once each, sorted`() {
        val names = AttackableNames.of(
            listOf(
                "White knight" to true,
                "null" to true,
                null to true,
                "  " to true,
                "Squire" to false,
                "white knight" to true,
                " Chicken " to true,
                "Imp" to true,
            ),
        )
        assertEquals(listOf("Chicken", "Imp", "White knight"), names)
    }

    @Test
    fun `nothing attackable gives an empty list`() {
        assertEquals(emptyList(), AttackableNames.of(listOf("Squire" to false, "Banker" to false)))
    }
}
