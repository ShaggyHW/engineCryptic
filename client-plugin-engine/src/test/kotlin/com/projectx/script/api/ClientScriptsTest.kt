package com.projectx.script.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ClientScriptsTest {

    @Test
    fun `a clientscript is found by its full Jagex name`() {
        assertEquals(0, clientScriptId("[proc,quicksort]"))
        assertEquals(11, clientScriptId("[clientscript,magic_deltooltip]"))
    }

    @Test
    fun `a bare name finds the proc or clientscript of that name`() {
        assertEquals(0, clientScriptId("quicksort"))
        assertEquals(11, clientScriptId("magic_deltooltip"))
    }

    @Test
    fun `an unknown name finds nothing`() {
        assertNull(clientScriptId("no_such_script_anywhere"))
    }
}
