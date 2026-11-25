package com.projectx.util

import com.projectx.game.hooks.impl.defaultUiToggleKey
import org.projectx.packetlog.upload.PacketUploader
import java.io.File
import java.io.IOException

data class PersistentConfig(
    val discordWebhookUrl: String = "",
    val discordEnabled: Boolean = true,
    val systemTrayEnabled: Boolean = true,
    val soundEnabled: Boolean = true,
    val discordUsername: String = "Project X",
    val discordAvatarUrl: String = "",
    val uiToggleKey: Int = defaultUiToggleKey,
    val mcpEnabled: Boolean = false,
    val favoriteScripts: Set<String> = emptySet(),
    val libraryScripts: Set<String>? = null,
    val packetLogEnabled: Boolean = true,
    val packetLogUploadEnabled: Boolean = true,
    val packetLogKeepLocalAfterUpload: Boolean = false,
    val packetLogConsentVersion: Int = 1,
    val packetLogEndpoint: String = PacketUploader.DEFAULT_ENDPOINT,
    val packetLogTextDumpEnabled: Boolean = false,
    val overlayBounds: List<Int>? = null,
)

object Configuration {

    private val guard = Any()

    @Volatile
    private var cached: PersistentConfig? = null

    private val configFile: File get() = File(System.getProperty("user.home"), ".projectx/config.json")

    val config: PersistentConfig
        get() = cached ?: synchronized(guard) { cached ?: readFromDisk().also { cached = it } }

    fun updateConfig(newConfig: PersistentConfig) {
        synchronized(guard) {
            cached = newConfig
            writeToDisk(newConfig)
        }
    }

    fun saveFavoriteScripts(names: Set<String>) {
        synchronized(guard) { updateConfig(config.copy(favoriteScripts = names)) }
    }

    fun saveLibraryScripts(names: Set<String>) {
        synchronized(guard) { updateConfig(config.copy(libraryScripts = names)) }
    }

    fun reloadConfig() {
        synchronized(guard) { cached = readFromDisk() }
    }

    private fun readFromDisk(): PersistentConfig {
        val file = configFile
        if (!file.exists()) return PersistentConfig().also(::writeToDisk)
        return try {
            JsonFileManager.loadJsonFile<PersistentConfig?>(file, PersistentConfig::class.java)
                ?: defaultsFor(file, "it holds no settings")
        } catch (e: IOException) {
            println("Failed to load configuration: ${e.message}")
            PersistentConfig()
        } catch (e: RuntimeException) {
            defaultsFor(file, e.message?.lineSequence()?.first() ?: e.javaClass.simpleName)
        }
    }

    private fun defaultsFor(file: File, reason: String): PersistentConfig {
        println("Configuration ${file.absolutePath} could not be read ($reason); using defaults until the next save")
        return PersistentConfig()
    }

    private fun writeToDisk(config: PersistentConfig) {
        try {
            JsonFileManager.saveJsonFile(config, configFile)
        } catch (e: IOException) {
            println("Failed to save configuration: ${e.message}")
        }
    }
}
