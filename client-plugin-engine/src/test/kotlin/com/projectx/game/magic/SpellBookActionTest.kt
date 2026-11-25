package com.projectx.game.magic

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SpellBookActionTest {

    @Test
    fun `a slot naming only Cast has no alternative destination`() {
        assertTrue(SpellTeleports.onlyCast(listOf("Cast", "", "cast")))
        assertTrue(SpellTeleports.onlyCast(emptyList()))
        assertFalse(SpellTeleports.onlyCast(listOf("Cast", "Grand Exchange")))
    }
}
