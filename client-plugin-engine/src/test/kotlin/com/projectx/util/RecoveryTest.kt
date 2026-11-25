package com.projectx.util

import com.projectx.util.spec.IsolatedHome
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RecoveryTest {

    private val home = IsolatedHome()
    private val scenarios = RecoveryScenarios::class.java.name
    private val configFile get() = File(home.projectx, "config.json")

    @AfterTest
    fun cleanUp() = home.close()

    private fun withConfig(text: String, method: String) {
        home.projectx.mkdirs()
        configFile.writeText(text)
        home.run(scenarios, method, text)
    }

    @Test
    fun `an empty config file falls back to defaults and is left alone`() = withConfig("", "brokenConfigFallsBack")

    @Test
    fun `a null config file falls back to defaults and is left alone`() = withConfig("null", "brokenConfigFallsBack")

    @Test
    fun `a malformed config file falls back to defaults and is left alone`() =
        withConfig("""{"discordUsername": """, "brokenConfigFallsBack")

    @Test
    fun `a config file of the wrong shape falls back to defaults`() = withConfig("[1, 2]", "brokenConfigFallsBack")

    @Test
    fun `the next save replaces a broken config file`() = withConfig("{oops", "brokenConfigIsReplacedOnSave")

    @Test
    fun `write to file creates every missing folder`() = home.run(scenarios, "nestedFoldersAreCreated")
}

class RecoveryScenarios {

    private val projectx = File(System.getProperty("user.home"), ".projectx")
    private val file = File(projectx, "config.json")

    private fun capturedOut(block: () -> Unit): String {
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

    fun brokenConfigFallsBack(original: String) {
        val out = capturedOut { assertEquals(PersistentConfig(), Configuration.config) }
        assertEquals(1, out.lines().count { it.isNotBlank() }, out)
        assertTrue(out.contains("using defaults"), out)
        assertEquals(original, file.readText())
    }

    fun brokenConfigIsReplacedOnSave(original: String) {
        capturedOut { Configuration.saveFavoriteScripts(setOf("a.B")) }
        Configuration.reloadConfig()
        assertEquals(PersistentConfig(favoriteScripts = setOf("a.B")), Configuration.config)
        assertTrue(file.readText() != original)
    }

    fun nestedFoldersAreCreated() {
        Logger.writeToFile("a/b/c.log", "nested")
        assertTrue(File(projectx, "a/b/c.log").readText().endsWith("]:  nested\r\n"))
    }
}
