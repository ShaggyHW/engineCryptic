package com.projectx.script

import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MultiSelectConfigItemTest {

    class Targets : ConfigurableScript {
        val targets = MultiSelectConfigItem("Targets", "What to attack")
            .withScan("Scan nearby") { listOf("Guard", " White knight ", "Guard", "", "chicken") }
    }

    private lateinit var dir: File
    private lateinit var previous: File

    @BeforeTest
    fun useTempDirectory() {
        previous = ScriptConfigStore.directory
        dir = Files.createTempDirectory("multi-select").toFile()
        ScriptConfigStore.directory = dir
    }

    @AfterTest
    fun restoreDirectory() {
        ScriptConfigStore.directory = previous
        dir.deleteRecursively()
    }

    @Test
    fun `a scan lists what it found once each, sorted, without blanks`() {
        val item = Targets().targets
        item.rescan()
        assertEquals(listOf("chicken", "Guard", "White knight"), item.options)
    }

    @Test
    fun `a ticked choice stays listed when a later scan no longer finds it`() {
        val item = MultiSelectConfigItem("Targets", "", listOf("Guard"), setOf("Hill giant"))
        assertEquals(listOf("Guard", "Hill giant"), item.shownOptions)
        assertTrue(item.isSelected("hill GIANT"))
        assertFalse(item.isSelected("Guard"))
    }

    @Test
    fun `the selection is saved and read back in a new session`() {
        val script = Targets()
        script.targets.value = setOf("White knight", "Guard")
        ScriptConfigStore.save(script)

        val text = File(dir, "${Targets::class.java.name}.json").readText()
        assertTrue("\"targets\": \"White knight, Guard\"" in text, text)

        val next = Targets()
        ScriptConfigStore.applyTo(next)
        assertEquals(setOf("White knight", "Guard"), next.targets.value)
    }

    @Test
    fun `the launcher sees and sets the selection as comma-separated text`() {
        val script = Targets().apply { targets.value = setOf("Guard") }
        val described = ScriptConfigStore.describe(script).map { it.toString() }
        assertTrue(described.any { "\"key\":\"targets\"" in it && "\"kind\":\"text\"" in it && "\"value\":\"Guard\"" in it }, described.toString())

        ScriptConfigStore.applyValues(script, mapOf("targets" to JsonPrimitive("Hill giant,  Moss giant\nGuard")))
        assertEquals(setOf("Hill giant", "Moss giant", "Guard"), script.targets.value)
    }
}
