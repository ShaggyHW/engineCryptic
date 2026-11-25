package com.projectx.util

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.net.HttpURLConnection
import java.net.URI
import java.time.Instant
import kotlin.concurrent.thread

object DiscordWebhook {

    private const val EMBED_COLOUR = 0x3498DB

    fun sendDiscordNotification(title: String, message: String) {
        val settings = Configuration.config
        if (!settings.discordEnabled || settings.discordWebhookUrl.isBlank()) return
        val body = payload(settings, title, message)
        thread(isDaemon = true, name = "discord-webhook") { post(settings.discordWebhookUrl, body) }
    }

    private fun payload(settings: PersistentConfig, title: String, message: String): String {
        val embed = JsonObject().apply {
            addProperty("title", title)
            addProperty("description", message)
            addProperty("color", EMBED_COLOUR)
            addProperty("timestamp", Instant.now().toString())
            add("footer", JsonObject().apply { addProperty("text", "Project X Notification") })
        }
        return JsonObject().apply {
            addProperty("username", settings.discordUsername)
            if (settings.discordAvatarUrl.isNotBlank()) addProperty("avatar_url", settings.discordAvatarUrl)
            add("embeds", JsonArray().apply { add(embed) })
        }.toString()
    }

    private fun post(url: String, body: String) {
        var connection: HttpURLConnection? = null
        try {
            connection = (URI(url).toURL().openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("User-Agent", "Project X-Notifications/1.0")
            }
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            if (status !in 200..299) {
                val reason = connection.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: "Unknown error"
                println("Discord webhook failed with code $status: $reason")
            }
        } catch (e: Exception) {
            println("Failed to send Discord notification: ${e.message}")
        } finally {
            connection?.disconnect()
        }
    }
}
