package com.projectx.script

import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ScriptConfigStoreTest {
    enum class Mode { SAFE, FAST }

    class Settings : ConfigurableScript {
        val section = ConfigSection("Main")
        val enabled = BooleanConfigItem("Enabled", "", false)
        val count = IntConfigItem("Count", "", 5, 0, 10)
        val pin = StringConfigItem("PIN", "", "")
        val mode = EnumConfigItem("Mode", "", Mode.entries.toTypedArray(), Mode.SAFE)
        val speed = OptionsConfigItem("Speed", "", arrayOf("slow", "quick"), "slow")
        val status = InfoDisplayConfigItem("Status", "", "idle")
    }

    class Untouched : ConfigurableScript {
        val enabled = BooleanConfigItem("Enabled", "", false)
        val count = IntConfigItem("Count", "", 5, 0, 10)
        val mode = EnumConfigItem("Mode", "", Mode.entries.toTypedArray(), Mode.SAFE)
    }

    class Held : ConfigHolder {
        val section = ConfigSection("Held")
        val enabled = BooleanConfigItem("Enabled", "", false)
        val count = IntConfigItem("Count", "", 5, 0, 10)
    }

    class Holding : ConfigurableScript {
        val own = BooleanConfigItem("Own", "", false)
        val settings = Held()
    }

    class Moved : ConfigurableScript {
        val settings = Held()
    }

    private lateinit var dir: File
    private lateinit var previous: File

    @BeforeTest
    fun useTempDirectory() {
        previous = ScriptConfigStore.directory
        dir = Files.createTempDirectory("script-settings").toFile()
        ScriptConfigStore.directory = dir
    }

    @AfterTest
    fun restoreDirectory() {
        ScriptConfigStore.directory = previous
        dir.deleteRecursively()
    }

    @Test
    fun `a change is written to disk under the script's class name`() {
        val script = Settings()
        script.enabled.value = true
        script.count.value = 8
        script.pin.value = "1234"
        script.mode.value = Mode.FAST
        script.speed.value = "quick"
        ScriptConfigStore.save(script)

        val text = File(dir, "${Settings::class.java.name}.json").readText()
        assertTrue("\"count\": 8" in text, text)
        assertTrue("\"mode\": \"FAST\"" in text, text)
        assertTrue("status" !in text && "section" !in text, text)

        val next = Settings()
        ScriptConfigStore.applyTo(next)
        assertEquals(true, next.enabled.value)
        assertEquals(8, next.count.value)
        assertEquals("1234", next.pin.value)
        assertEquals(Mode.FAST, next.mode.value)
        assertEquals("quick", next.speed.value)
    }

    @Test
    fun `a new session reads settings back from disk and keeps defaults for anything stale`() {
        File(dir, "${Untouched::class.java.name}.json").writeText(
            """{ "enabled": true, "count": 99, "mode": "GONE", "removed": "x" }""",
        )
        val script = Untouched()
        ScriptConfigStore.applyTo(script)
        assertEquals(true, script.enabled.value)
        assertEquals(10, script.count.value)
        assertEquals(Mode.SAFE, script.mode.value)
    }

    @Test
    fun `a holder's items are found, in declaration order, under its field name`() {
        val fields = configItems(Holding())
        assertEquals(listOf("own", "settings.section", "settings.enabled", "settings.count"), fields.map { it.key })
    }

    @Test
    fun `a holder's items are saved and restored`() {
        val script = Holding()
        script.settings.enabled.value = true
        script.settings.count.value = 9
        ScriptConfigStore.save(script)

        val text = File(dir, "${Holding::class.java.name}.json").readText()
        assertTrue("\"settings.count\": 9" in text, text)

        val restored = Holding()
        ScriptConfigStore.applyTo(restored)
        assertEquals(true, restored.settings.enabled.value)
        assertEquals(9, restored.settings.count.value)
    }

    @Test
    fun `settings saved before the items moved into a holder are still read back`() {
        File(dir, "${Moved::class.java.name}.json").writeText("""{ "enabled": true, "count": 7 }""")

        val script = Moved()
        ScriptConfigStore.applyTo(script)

        assertEquals(true, script.settings.enabled.value)
        assertEquals(7, script.settings.count.value)
    }

    @Test
    fun `a plan's values apply on top of the saved settings for that run only`() {
        val saved = Settings().apply { count.value = 7 }
        ScriptConfigStore.save(saved)
        val run = Settings()
        ScriptConfigStore.applyTo(run)
        ScriptConfigStore.applyValues(
            run,
            mapOf(
                "enabled" to JsonPrimitive(true),
                "mode" to JsonPrimitive("FAST"),
                "count" to JsonPrimitive(99),
                "gone" to JsonPrimitive("x"),
            ),
        )
        assertEquals(true, run.enabled.value)
        assertEquals(Mode.FAST, run.mode.value)
        assertEquals(10, run.count.value, "kept inside the item's range")
        val next = Settings()
        ScriptConfigStore.applyTo(next)
        assertEquals(7, next.count.value, "the saved settings are untouched")
        assertEquals(false, next.enabled.value)
    }

    @Test
    fun `settings are described with their kind, value and choices`() {
        val described = ScriptConfigStore.describe(Settings().apply { count.value = 3 }).map { it.toString() }
        assertTrue(described.any { "\"key\":\"section\"" in it && "\"kind\":\"section\"" in it })
        assertTrue(described.any { "\"key\":\"count\"" in it && "\"value\":3" in it && "\"max\":10" in it })
        assertTrue(described.any { "\"key\":\"mode\"" in it && "\"choices\":[\"SAFE\",\"FAST\"]" in it })
        assertTrue(described.none { "\"key\":\"status\"" in it }, "information-only items are left out")
    }
}
