package com.projectx.util.spec

import com.projectx.util.Configuration
import com.projectx.util.PersistentConfig
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Run only through [IsolatedHome], which gives every call its own Configuration and user home. */
class ConfigurationScenarios {

    private val file = File(System.getProperty("user.home"), ".projectx/config.json")

    fun defaultsAreWrittenWhenNoFileExists(expectedJson: String) {
        assertTrue(!file.exists())
        assertEquals(PersistentConfig(), Configuration.config)
        assertTrue(file.isFile)
        assertEquals(expectedJson, file.readText(Charsets.UTF_8))
    }

    fun defaultValues(uiToggleKey: String) {
        val config = PersistentConfig()
        assertEquals("", config.discordWebhookUrl)
        assertEquals(true, config.discordEnabled)
        assertEquals(true, config.systemTrayEnabled)
        assertEquals(true, config.soundEnabled)
        assertEquals("Project X", config.discordUsername)
        assertEquals("", config.discordAvatarUrl)
        assertEquals(uiToggleKey.toInt(), config.uiToggleKey)
        assertEquals(false, config.mcpEnabled)
        assertEquals(emptySet(), config.favoriteScripts)
        assertNull(config.libraryScripts)
        assertEquals(true, config.packetLogEnabled)
        assertEquals(true, config.packetLogUploadEnabled)
        assertEquals(false, config.packetLogKeepLocalAfterUpload)
        assertEquals(1, config.packetLogConsentVersion)
        assertEquals("https://rs3-data.projectx.org", config.packetLogEndpoint)
        assertEquals(false, config.packetLogTextDumpEnabled)
        assertNull(config.overlayBounds)
    }

    fun partialFileFillsInDefaults() {
        val original = file.readText()
        val config = Configuration.config
        assertEquals("Bot", config.discordUsername)
        assertEquals(true, config.mcpEnabled)
        assertEquals(setOf("a.B"), config.favoriteScripts)
        assertEquals(PersistentConfig(discordUsername = "Bot", mcpEnabled = true, favoriteScripts = setOf("a.B")), config)
        assertEquals(original, file.readText())
    }

    fun updateIsKeptAndSaved() {
        val updated = PersistentConfig(
            discordWebhookUrl = "https://discord.example/hook",
            discordEnabled = false,
            soundEnabled = false,
            uiToggleKey = 0x70,
            libraryScripts = setOf("x.One", "x.Two"),
            packetLogConsentVersion = 3,
            overlayBounds = listOf(10, 20, 640, 480),
        )
        Configuration.updateConfig(updated)
        assertSame(updated, Configuration.config)
        assertTrue(file.isFile)
    }

    fun updateIsReadBack() {
        val config = Configuration.config
        assertEquals("https://discord.example/hook", config.discordWebhookUrl)
        assertEquals(false, config.discordEnabled)
        assertEquals(false, config.soundEnabled)
        assertEquals(0x70, config.uiToggleKey)
        assertEquals(setOf("x.One", "x.Two"), config.libraryScripts)
        assertEquals(3, config.packetLogConsentVersion)
        assertEquals(listOf(10, 20, 640, 480), config.overlayBounds)
        assertEquals("Project X", config.discordUsername)
    }

    fun favouritesAndLibraryAreSaved() {
        Configuration.saveFavoriteScripts(setOf("fav.One"))
        Configuration.saveLibraryScripts(emptySet())
        assertEquals(setOf("fav.One"), Configuration.config.favoriteScripts)
        assertEquals(emptySet(), Configuration.config.libraryScripts)
    }

    fun favouritesAndLibraryAreReadBack() {
        assertEquals(setOf("fav.One"), Configuration.config.favoriteScripts)
        assertEquals(emptySet(), Configuration.config.libraryScripts)
    }

    fun reloadPicksUpEditsMadeOnDisk() {
        assertEquals("Project X", Configuration.config.discordUsername)
        file.writeText("""{"discordUsername": "Edited"}""")
        assertEquals("Project X", Configuration.config.discordUsername)
        Configuration.reloadConfig()
        assertEquals("Edited", Configuration.config.discordUsername)
    }

    fun savingKeepsFieldsTheEngineSet() {
        Configuration.saveFavoriteScripts(setOf("a"))
        val text = file.readText()
        assertTrue(text.contains("\"discordUsername\": \"Kept\""), text)
        assertTrue(text.contains("\"favoriteScripts\": [\n    \"a\"\n  ]"), text)
    }
}
