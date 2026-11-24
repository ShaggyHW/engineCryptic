package com.projectx.traversal.pathing

import com.google.gson.JsonParser
import java.io.File

/** Where the navpath the launcher runs on this machine answers, and how long each kind of request may take. */
object NavConfig {
    const val URL_PROPERTY = "projectx.navpath.url"
    const val ROUTE_TIMEOUT_MS = 120_000L
    const val TILE_EXISTS_TIMEOUT_MS = 10_000L
    const val REACHABLE_TIMEOUT_MS = 10_000L
    const val CONNECT_TIMEOUT_MS = 5_000L

    private val endpointFile = File(System.getProperty("user.home"), ".projectx/navpath/endpoint.json")

    @Volatile
    private var cached: Pair<Long, String?> = -1L to null

    /** The `projectx.navpath.url` property, else the endpoint file the launcher writes; null when neither is there. */
    fun baseUrl(): String? {
        System.getProperty(URL_PROPERTY)?.takeIf { it.isNotBlank() }?.let { return it.trim().trimEnd('/') }
        val modified = endpointFile.lastModified()
        if (modified == 0L) return null
        val (readAt, url) = cached
        if (readAt == modified) return url
        val fresh = runCatching { JsonParser.parseString(endpointFile.readText()).asJsonObject.get("url")?.asString }
            .getOrNull()?.trim()?.trimEnd('/')
        cached = modified to fresh
        return fresh
    }
}
