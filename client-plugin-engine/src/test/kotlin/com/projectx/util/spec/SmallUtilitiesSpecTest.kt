package com.projectx.util.spec

import com.projectx.util.Browser
import com.projectx.util.EngineBuild
import com.projectx.util.FeatureIds
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SmallUtilitiesSpecTest {

    private fun captureOut(block: () -> Unit): String {
        val saved = System.out
        val buffer = ByteArrayOutputStream()
        System.setOut(PrintStream(buffer, true, Charsets.UTF_8))
        try {
            block()
        } finally {
            System.setOut(saved)
        }
        return buffer.toString(Charsets.UTF_8)
    }

    @Test
    fun `feature ids resolve once, eagerly, and hand back the same value`() {
        var calls = 0
        val ids = FeatureIds("Spec") { calls++; listOf(1, 2, 3) }
        assertEquals(1, calls)
        val first = ids.get()
        assertEquals(listOf(1, 2, 3), first)
        assertTrue(first === ids.get())
        assertEquals(1, calls)
        assertFalse(ids.disabled)
    }

    @Test
    fun `a failing resolve disables the feature without throwing`() {
        val ids = FeatureIds<Int>("SpecFeature") { error("no such gameval: spec_missing") }
        assertTrue(ids.disabled)
        val log = captureOut {
            assertNull(ids.get())
            assertNull(ids.get())
        }
        val lines = log.lines().filter { it.isNotBlank() }
        assertEquals(3, lines.size, log)
        assertEquals("[SpecFeature] DISABLED: no such gameval: spec_missing", lines[0].trim())
        assertTrue(lines[1].contains("[SpecFeature]   gameval names came from "), lines[1])
        assertTrue(lines[2].contains("[SpecFeature]   the feature stays off for this session; re-dump the gamevals and restart"), lines[2])
    }

    @Test
    fun `errors thrown by the resolver are contained too`() {
        val ids = FeatureIds<String>("SpecError") { throw NoSuchFieldError("gone") }
        assertTrue(ids.disabled)
        captureOut { assertNull(ids.get()) }
    }

    @Test
    fun `browser refuses anything but http and https`() {
        assertFalse(Browser.open("file:///C:/Windows/System32/calc.exe"))
        assertFalse(Browser.open("javascript:alert(1)"))
        assertFalse(Browser.open("ftp://example.com/file"))
        assertFalse(Browser.open("mailto:someone@example.com"))
        assertFalse(Browser.open("example.com"))
        assertFalse(Browser.open("not a url"))
        assertFalse(Browser.open(""))
    }

    @Test
    fun `engine build describes its commit and build time`() {
        assertTrue(EngineBuild.commit.isNotBlank())
        assertTrue(EngineBuild.builtAt.isNotBlank())
        assertEquals("commit ${EngineBuild.commit} built ${EngineBuild.builtAt}", EngineBuild.describe())
    }
}
