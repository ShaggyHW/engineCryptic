package com.projectx.traversal.pathing

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.projectx.script.api.localPlayer
import com.projectx.util.nonProfiledGaussian
import com.projectx.webwalker.WebArea
import com.projectx.webwalker.WebPath
import world.gregs.voidps.type.Tile
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ThreadLocalRandom

data class NavReply(val status: Int, val body: String)

/** Sends one request and returns the reply; throws [IOException] on a connection failure or a timeout. */
interface NavTransport {
    fun post(url: String, body: String, timeoutMs: Long): NavReply

    fun get(url: String, timeoutMs: Long): NavReply
}

/** navpath could not be reached at all: nothing listens, or it timed out. Every further request would fail the same. */
class NavPathUnavailableException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

data class SurgeOpt(val enabled: Boolean, val charges: Int, val cooldownMs: Long) {
    companion object {
        val DISABLED = SurgeOpt(false, 1, ABILITY_COOLDOWN_MS)
    }
}

/** [availableInMs]: how long until Dive is off cooldown, so the planner places the first one where it can be cast. */
data class DiveOpt(val enabled: Boolean, val cooldownMs: Long, val availableInMs: Long = 0) {
    companion object {
        val DISABLED = DiveOpt(false, ABILITY_COOLDOWN_MS)
    }
}

const val ABILITY_COOLDOWN_MS = 20_400L

/** Everything a `/route` request carries, gathered on the game thread so the planner thread reads no game state. */
data class NavRequest(
    val start: Tile,
    val goal: Tile,
    val requirements: List<Pair<String, Int>>,
    val surge: SurgeOpt = SurgeOpt.DISABLED,
    val dive: DiveOpt = DiveOpt.DISABLED,
    val seed: Int = 0,
    val exclude: Set<NavMacro> = emptySet(),
    val onlyActions: Boolean = true,
    val returnGeometry: Boolean = false,
) {
    fun toJson(): String = toJsonObject().toString()

    fun toJsonObject(): JsonObject {
        val body = JsonObject()
        body.add("start", jsonTile(start))
        body.add("goal", jsonTile(goal))
        body.add("profile", JsonObject().apply {
            add("requirements", JsonArray().apply {
                for ((key, value) in requirements) add(JsonObject().apply {
                    addProperty("key", key)
                    addProperty("value", value)
                })
            })
        })
        body.add("options", JsonObject().apply {
            addProperty("return_geometry", returnGeometry)
            if (onlyActions) addProperty("only_actions", true)
        })
        body.add("surge", JsonObject().apply {
            addProperty("enabled", surge.enabled)
            addProperty("charges", surge.charges)
            addProperty("cooldown_ms", surge.cooldownMs)
        })
        body.add("dive", JsonObject().apply {
            addProperty("enabled", dive.enabled)
            addProperty("available_in_ms", dive.availableInMs)
            addProperty("cooldown_ms", dive.cooldownMs)
        })
        body.addProperty("seed", if (seed > 0) seed else NavPathClient.generateSeed())
        if (exclude.isNotEmpty()) {
            body.add("exclude_macros", JsonArray().apply {
                for (macro in exclude.sortedWith(compareBy({ it.kind }, { it.id }))) add(JsonObject().apply {
                    addProperty("kind", macro.kind)
                    addProperty("id", macro.id)
                })
            })
        }
        return body
    }
}

/** The goal set of a `/route_area` request: an inclusive rectangle on one plane, explicit tiles, or both. */
class NavGoalSet(val area: WebArea?, val tiles: List<Tile>) {
    private val ids = tiles.mapTo(HashSet()) { it.id }

    fun contains(tile: Tile): Boolean = area?.contains(tile.x, tile.y, tile.plane) == true || tile.id in ids

    companion object {
        const val MAX_TILES = 4096
    }
}

/** A `/route_area` request: the `/route` body, with its goal as the tie-break, plus the goal set. */
data class NavAreaRequest(val route: NavRequest, val goals: NavGoalSet) {
    fun toJson(): String {
        val body = route.toJsonObject()
        goals.area?.let { area ->
            body.add("goal_area", JsonObject().apply {
                addProperty("min_wx", area.minX)
                addProperty("min_wy", area.minY)
                addProperty("max_wx", area.maxX)
                addProperty("max_wy", area.maxY)
                addProperty("plane", area.plane)
            })
        }
        if (goals.tiles.isNotEmpty()) body.add("goal_tiles", JsonArray().apply { for (tile in goals.tiles) add(jsonTile(tile)) })
        return body.toString()
    }
}

private fun jsonTile(tile: Tile) = JsonObject().apply {
    addProperty("wx", tile.x)
    addProperty("wy", tile.y)
    addProperty("plane", tile.plane)
}

/** A `/reachable` answer; [reason] is navpath's explanation of a no, when it gave one. */
data class ReachableResult(val reachable: Boolean, val reason: String? = null) {
    companion object {
        val YES = ReachableResult(true)
        const val UNPARSEABLE = "unparseable_response"
    }
}

object NavHttp : NavTransport {
    private val client: HttpClient by lazy {
        HttpClient.newBuilder().connectTimeout(Duration.ofMillis(NavConfig.CONNECT_TIMEOUT_MS)).build()
    }

    override fun post(url: String, body: String, timeoutMs: Long): NavReply = send(
        HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMillis(timeoutMs))
            .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(),
    )

    override fun get(url: String, timeoutMs: Long): NavReply =
        send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMillis(timeoutMs)).GET().build())

    private fun send(request: HttpRequest): NavReply {
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        return NavReply(response.statusCode(), response.body().orEmpty())
    }
}

/**
 * The client of the navpath the launcher runs on this machine. A planner still warming up (503) is asked again once or
 * twice after a short pause. Every call blocks: run it off the game thread ([executor]).
 */
class NavPathClient(
    private val transport: NavTransport,
    private val baseUrl: () -> String?,
    private val pause: (Long) -> Unit = { Thread.sleep(it) },
    private val clock: () -> Long = System::currentTimeMillis,
    private val log: (String) -> Unit = ::println,
) {
    sealed interface Outcome {
        data class Planned(val path: WebPath, val route: NavRoute) : Outcome

        data class Failed(val reason: String) : Outcome
    }

    private sealed interface Reply {
        data class Ok(val body: String) : Reply

        data object NotFound : Reply

        data class Rejected(val reason: String) : Reply

        data class Unavailable(val reason: String) : Reply
    }

    /** The service answered 404 to `/route_area` once: area routes go to their goal tile until a restart. */
    @Volatile
    var routeAreaMissing = false
        private set

    private var lastLogged: String? = null
    private var lastLoggedAt = 0L

    /** The raw `/route` answer. */
    fun route(request: NavRequest): String = body(exchange(ROUTE_PATH, request.toJson(), NavConfig.ROUTE_TIMEOUT_MS))

    /** The raw `/route_area` answer, or null when this navpath has no `/route_area`. */
    fun routeArea(request: NavAreaRequest): String? = when (val reply = exchange(AREA_PATH, request.toJson(), NavConfig.ROUTE_TIMEOUT_MS)) {
        Reply.NotFound -> null
        else -> body(reply)
    }

    /** Whether [tile] is a node of navpath's walk graph: `GET /tile/exists`. */
    fun tileExists(tile: Tile): Boolean {
        val reply = exchange("/tile/exists?x=${tile.x}&y=${tile.y}&plane=${tile.plane}", null, NavConfig.TILE_EXISTS_TIMEOUT_MS)
        val text = body(reply).trim()
        if (text.equals("true", ignoreCase = true)) return true
        if (text.equals("false", ignoreCase = true)) return false
        return runCatching { JsonParser.parseString(text).asJsonObject.get("exists")?.asBoolean == true }.getOrDefault(false)
    }

    /** Whether [goal] is connected to [start] by walking alone: `GET /reachable`. No doors, no shortcuts, no teleports. */
    fun reachable(start: Tile, goal: Tile): ReachableResult {
        val query = "/reachable?sx=${start.x}&sy=${start.y}&splane=${start.plane}&gx=${goal.x}&gy=${goal.y}&gplane=${goal.plane}"
        return parseReachable(body(exchange(query, null, NavConfig.REACHABLE_TIMEOUT_MS)))
    }

    /**
     * Plans to [request]'s goal, or with [goals] to whichever of them is cheapest on `/route_area`; a navpath without
     * `/route_area` is asked for the goal tile instead. The answer is mapped onto a [WebPath].
     */
    @Synchronized
    fun plan(request: NavRequest, goals: NavGoalSet?, acceptsTeleports: Boolean): Outcome {
        if (goals != null && !routeAreaMissing) {
            val reply = exchange(AREA_PATH, NavAreaRequest(request, goals).toJson(), NavConfig.ROUTE_TIMEOUT_MS)
            if (reply != Reply.NotFound) {
                return finish(reply, request.start, acceptsTeleports, { ResultsParser.parseArea(it).route }) { path ->
                    if (goals.contains(path.tile(path.lastIndex))) null else "the route ends outside the goal area"
                }
            }
            routeAreaMissing = true
            log("[WebWalk] navpath has no /route_area; routing to ${request.goal} instead")
        }
        val reply = exchange(ROUTE_PATH, request.toJson(), NavConfig.ROUTE_TIMEOUT_MS)
        return finish(reply, request.start, acceptsTeleports, ResultsParser::parse) { null }
    }

    private fun finish(
        reply: Reply,
        start: Tile,
        acceptsTeleports: Boolean,
        read: (String) -> NavRoute,
        refuse: (WebPath) -> String?,
    ): Outcome {
        val text = when (reply) {
            is Reply.Ok -> reply.body
            Reply.NotFound -> return failed("navpath answered HTTP 404")
            is Reply.Rejected -> return failed(reply.reason)
            is Reply.Unavailable -> return failed(reply.reason)
        }
        val route = try {
            read(text)
        } catch (e: ResultsParseException) {
            return failed("an answer that does not parse: ${e.message}")
        }
        return when (val mapped = NavRouteMapper.map(start, route)) {
            is NavRouteMapper.Mapped.Refused -> failed(mapped.reason)
            is NavRouteMapper.Mapped.Route -> when {
                !acceptsTeleports && (1 until mapped.path.size).any { mapped.path.linkAt(it)?.isGlobal == true } ->
                    failed("the route starts with a teleport this walk may not use")
                else -> refuse(mapped.path)?.let(::failed) ?: Outcome.Planned(mapped.path, route)
            }
        }
    }

    private fun body(reply: Reply): String = when (reply) {
        is Reply.Ok -> reply.body
        Reply.NotFound -> throw RuntimeException("navpath answered HTTP 404")
        is Reply.Rejected -> throw RuntimeException(reply.reason)
        is Reply.Unavailable -> throw NavPathUnavailableException(reply.reason)
    }

    private fun exchange(path: String, body: String?, timeoutMs: Long): Reply {
        val base = baseUrl()?.takeIf { it.isNotBlank() } ?: return Reply.Unavailable(NOT_RUNNING)
        val url = base.trimEnd('/') + path
        var attempt = 0
        while (true) {
            val reply = try {
                if (body == null) transport.get(url, timeoutMs) else transport.post(url, body, timeoutMs)
            } catch (e: IOException) {
                return Reply.Unavailable("$NOT_RUNNING (${e.javaClass.simpleName})")
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return Reply.Rejected("interrupted")
            } catch (e: RuntimeException) {
                return Reply.Rejected("the request failed (${e.javaClass.simpleName})")
            }
            when {
                reply.status == 503 && attempt < BUSY_RETRIES -> {
                    attempt++
                    pause(nonProfiledGaussian(BUSY_BACKOFF_MS, BUSY_BACKOFF_VARIANCE).toLong().coerceIn(BUSY_BACKOFF_MIN_MS, BUSY_BACKOFF_MAX_MS))
                }
                reply.status == 404 -> return Reply.NotFound
                reply.status == 400 -> return Reply.Rejected("navpath refused the request: ${reply.body.take(120)}")
                reply.status !in 200..299 -> return Reply.Rejected("navpath answered HTTP ${reply.status}")
                reply.body.isBlank() -> return Reply.Rejected("navpath answered with nothing")
                else -> return Reply.Ok(reply.body)
            }
        }
    }

    private fun failed(reason: String): Outcome {
        val now = clock()
        if (reason != lastLogged || now - lastLoggedAt > LOG_REPEAT_MS) {
            log("[WebWalk] navpath: $reason")
            lastLogged = reason
            lastLoggedAt = now
        }
        return Outcome.Failed(reason)
    }

    companion object {
        const val ROUTE_PATH = "/route"
        const val AREA_PATH = "/route_area"
        const val BUSY_RETRIES = 2
        const val NOT_RUNNING = "navpath isn't running on this machine; start the Project X launcher"
        private const val BUSY_BACKOFF_MS = 250
        private const val BUSY_BACKOFF_VARIANCE = 80 * 80
        private const val BUSY_BACKOFF_MIN_MS = 100L
        private const val BUSY_BACKOFF_MAX_MS = 600L
        private const val LOG_REPEAT_MS = 30_000L

        /** The launcher's navpath on this machine. */
        val local = NavPathClient(NavHttp, NavConfig::baseUrl)

        /** Where blocking navpath calls run, so none holds up the game thread. */
        val executor: ExecutorService = Executors.newCachedThreadPool { runnable ->
            Thread(runnable, "projectx-navpath").apply { isDaemon = true }
        }

        fun parseReachable(body: String?): ReachableResult {
            val trimmed = body?.trim().orEmpty()
            if (trimmed.equals("true", ignoreCase = true)) return ReachableResult.YES
            if (trimmed.equals("false", ignoreCase = true)) return ReachableResult(false)
            return runCatching {
                val json = JsonParser.parseString(trimmed).asJsonObject
                val reachable = json.get("reachable")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean
                when (reachable) {
                    null -> ReachableResult(false, ReachableResult.UNPARSEABLE)
                    true -> ReachableResult.YES
                    false -> ReachableResult(false, json.get("reason")?.takeIf { it.isJsonPrimitive }?.asString)
                }
            }.getOrDefault(ReachableResult(false, ReachableResult.UNPARSEABLE))
        }

        /** Stable for an account across calls and sessions; random only when no name can be read. */
        fun generateSeed(): Int {
            val name = runCatching { localPlayer.name }.getOrNull()?.takeIf { it.isNotBlank() }
            if (name != null) return seedFromName(name)
            val entropy = System.nanoTime() xor System.currentTimeMillis() xor ThreadLocalRandom.current().nextLong()
            return (entropy and 0x7fffffffL).toInt().coerceAtLeast(1)
        }

        /** FNV-1a over the name's UTF-8 bytes, folded to 31 bits; never 0. */
        fun seedFromName(name: String): Int {
            var hash = -0x340d631b7bdddcdbL
            for (b in name.toByteArray()) {
                hash = hash xor (b.toLong() and 0xff)
                hash *= 0x100000001b3L
            }
            val seed = ((hash xor (hash ushr 32)) and 0x7fffffffL).toInt()
            return if (seed == 0) 1 else seed
        }
    }
}
