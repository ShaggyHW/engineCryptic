package com.projectx.util.spec

import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test

class ConfigurationSpecTest {

    private val home = IsolatedHome()
    private val scenarios = ConfigurationScenarios::class.java.name
    private val configFile get() = File(home.projectx, "config.json")

    private val f12 = if (System.getProperty("os.name").lowercase().contains("win")) 0x7B else 0x40000045

    @AfterTest
    fun cleanUp() = home.close()

    @Test
    fun `default values`() = home.run(scenarios, "defaultValues", f12.toString())

    @Test
    fun `a missing config file is created holding the defaults`() {
        val expected = """
            {
              "discordWebhookUrl": "",
              "discordEnabled": true,
              "systemTrayEnabled": true,
              "soundEnabled": true,
              "discordUsername": "Project X",
              "discordAvatarUrl": "",
              "uiToggleKey": $f12,
              "mcpEnabled": false,
              "favoriteScripts": [],
              "packetLogEnabled": true,
              "packetLogUploadEnabled": true,
              "packetLogKeepLocalAfterUpload": false,
              "packetLogConsentVersion": 1,
              "packetLogEndpoint": "https://rs3-data.projectx.org",
              "packetLogTextDumpEnabled": false
            }
        """.trimIndent()
        home.run(scenarios, "defaultsAreWrittenWhenNoFileExists", expected)
    }

    @Test
    fun `fields missing from the file take their defaults and unknown fields are ignored`() {
        home.projectx.mkdirs()
        configFile.writeText("""{"discordUsername":"Bot","mcpEnabled":true,"favoriteScripts":["a.B"],"retiredField":5}""")
        home.run(scenarios, "partialFileFillsInDefaults")
    }

    @Test
    fun `an update is saved and read back by the next engine load`() {
        home.run(scenarios, "updateIsKeptAndSaved")
        home.run(scenarios, "updateIsReadBack")
    }

    @Test
    fun `favourites and an emptied library survive a reload`() {
        home.run(scenarios, "favouritesAndLibraryAreSaved")
        home.run(scenarios, "favouritesAndLibraryAreReadBack")
    }

    @Test
    fun `reload rereads the file`() = home.run(scenarios, "reloadPicksUpEditsMadeOnDisk")

    @Test
    fun `saving writes the whole config, keeping values loaded from the file`() {
        home.projectx.mkdirs()
        configFile.writeText("""{"discordUsername":"Kept"}""")
        home.run(scenarios, "savingKeepsFieldsTheEngineSet")
    }
}
