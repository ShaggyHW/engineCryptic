package com.projectx.util.spec

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import java.time.Instant
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DiscordWebhookSpecTest {

    private class Received(val method: String, val path: String, val contentType: String?, val userAgent: String?, val body: String)

    private val home = IsolatedHome()
    private val scenarios = NotificationScenarios::class.java.name
    private val received = LinkedBlockingQueue<Received>()
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { exchange ->
            val body = exchange.requestBody.readBytes().toString(Charsets.UTF_8)
            received.add(
                Received(
                    exchange.requestMethod,
                    exchange.requestURI.path,
                    exchange.requestHeaders.getFirst("Content-Type"),
                    exchange.requestHeaders.getFirst("User-Agent"),
                    body,
                ),
            )
            exchange.sendResponseHeaders(204, -1)
            exchange.close()
        }
        start()
    }
    private val hook = "http://127.0.0.1:${server.address.port}/api/webhooks/1/abc"

    @AfterTest
    fun cleanUp() {
        server.stop(0)
        home.close()
    }

    private fun writeConfig(json: String) {
        home.projectx.mkdirs()
        File(home.projectx, "config.json").writeText(json)
    }

    private fun next(): Received? = received.poll(10, TimeUnit.SECONDS)

    @Test
    fun `posts one embed to the configured webhook`() {
        writeConfig("""{"discordWebhookUrl":"$hook","discordUsername":"Spec Bot","discordAvatarUrl":"https://img.example/a.png"}""")
        val before = Instant.now().minusSeconds(5)
        home.run(scenarios, "sendWebhook", "Level up", "Mining 99")

        val request = assertNotNull(next())
        assertEquals("POST", request.method)
        assertEquals("/api/webhooks/1/abc", request.path)
        assertEquals("application/json", request.contentType)
        assertEquals("Project X-Notifications/1.0", request.userAgent)

        val payload = JsonParser.parseString(request.body).asJsonObject
        assertEquals(setOf("username", "avatar_url", "embeds"), payload.keySet())
        assertEquals("Spec Bot", payload["username"].asString)
        assertEquals("https://img.example/a.png", payload["avatar_url"].asString)
        val embeds = payload["embeds"].asJsonArray
        assertEquals(1, embeds.size())
        val embed = embeds[0] as JsonObject
        assertEquals(setOf("title", "description", "color", "timestamp", "footer"), embed.keySet())
        assertEquals("Level up", embed["title"].asString)
        assertEquals("Mining 99", embed["description"].asString)
        assertEquals(0x3498DB, embed["color"].asInt)
        val stamp = Instant.parse(embed["timestamp"].asString)
        assertTrue(stamp.isAfter(before) && stamp.isBefore(Instant.now().plusSeconds(5)))
        assertEquals("Project X Notification", embed["footer"].asJsonObject["text"].asString)
    }

    @Test
    fun `a blank avatar is left out and the default username is used`() {
        writeConfig("""{"discordWebhookUrl":"$hook"}""")
        home.run(scenarios, "sendWebhook", "t", "m")
        val payload = JsonParser.parseString(assertNotNull(next()).body).asJsonObject
        assertEquals("Project X", payload["username"].asString)
        assertFalse(payload.has("avatar_url"))
    }

    @Test
    fun `nothing is sent when discord is off or no webhook is set`() {
        writeConfig("""{"discordWebhookUrl":"$hook","discordEnabled":false}""")
        home.run(scenarios, "sendWebhook", "t", "m")
        writeConfig("""{"discordWebhookUrl":"   "}""")
        home.run(scenarios, "sendWebhook", "t", "m")
        assertNull(received.poll(1500, TimeUnit.MILLISECONDS))
    }

    @Test
    fun `an unreachable webhook does not throw to the caller`() {
        writeConfig("""{"discordWebhookUrl":"http://127.0.0.1:1/nothing-listens"}""")
        home.run(scenarios, "sendWebhook", "t", "m")
    }

    @Test
    fun `a notification is forwarded to discord`() {
        writeConfig("""{"discordWebhookUrl":"$hook","systemTrayEnabled":false}""")
        home.run(scenarios, "notify", "Script stopped", "Out of food")
        val embed = JsonParser.parseString(assertNotNull(next()).body).asJsonObject["embeds"].asJsonArray[0].asJsonObject
        assertEquals("Script stopped", embed["title"].asString)
        assertEquals("Out of food", embed["description"].asString)
    }
}
