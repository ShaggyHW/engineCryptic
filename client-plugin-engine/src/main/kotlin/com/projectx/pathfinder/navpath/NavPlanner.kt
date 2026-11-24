package com.projectx.pathfinder.navpath

import com.projectx.util.nonProfiledGaussian
import com.projectx.webwalker.WebPath
import world.gregs.voidps.type.Tile
import java.io.IOException

/**
 * Plans on the navpath the launcher runs on this machine. A planner still warming up (503) is asked again once or
 * twice after a short pause; anything else that goes wrong is the answer, with the reason. Runs on the planner thread
 * only.
 */
internal class NavPlanner(
    private val transport: NavTransport,
    private val baseUrl: () -> String?,
    private val pause: (Long) -> Unit = { Thread.sleep(it) },
    private val clock: () -> Long = System::currentTimeMillis,
    private val log: (String) -> Unit = ::println,
) {
    sealed interface Outcome {
        data class Planned(val path: WebPath) : Outcome

        data class Failed(val reason: String) : Outcome
    }

    private var lastLogged: String? = null
    private var lastLoggedAt = 0L

    @Synchronized
    fun plan(request: NavRequest, acceptsTeleports: Boolean): Outcome =
        exchange(ROUTE_PATH, request.toJson(), request.start, acceptsTeleports, NavResultsParser::parse) { null }

    /** Plans to whichever tile of the request's goal set is cheapest to reach, on `/route_area`. */
    @Synchronized
    fun plan(request: NavAreaRequest, acceptsTeleports: Boolean): Outcome =
        exchange(AREA_PATH, request.toJson(), request.route.start, acceptsTeleports, { NavResultsParser.parseArea(it).route }) { path ->
            if (request.goals.contains(path.tile(path.lastIndex))) null else "the route ends outside the goal area"
        }

    /** Plans on `/route_area` when there are [goals], otherwise on `/route` to the request's goal. */
    fun plan(request: NavRequest, goals: NavGoalSet?, acceptsTeleports: Boolean): Outcome =
        if (goals == null) plan(request, acceptsTeleports) else plan(NavAreaRequest(request, goals), acceptsTeleports)

    private fun exchange(
        path: String,
        body: String,
        start: Tile,
        acceptsTeleports: Boolean,
        read: (String) -> NavRoute,
        refuse: (WebPath) -> String?,
    ): Outcome {
        val base = baseUrl()?.takeIf { it.isNotBlank() } ?: return failed(NOT_RUNNING)
        var attempt = 0
        while (true) {
            val reply = try {
                transport.post(base.trimEnd('/') + path, body)
            } catch (e: IOException) {
                return failed("$NOT_RUNNING (${e.javaClass.simpleName})")
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return failed("interrupted")
            } catch (e: RuntimeException) {
                return failed("the request failed (${e.javaClass.simpleName})")
            }
            when {
                reply.status == 503 && attempt < BUSY_RETRIES -> {
                    attempt++
                    pause(nonProfiledGaussian(BUSY_BACKOFF_MS, BUSY_BACKOFF_VARIANCE).toLong().coerceIn(BUSY_BACKOFF_MIN_MS, BUSY_BACKOFF_MAX_MS))
                    continue
                }
                reply.status == 400 -> return failed("navpath refused the request: ${reply.body.take(120)}")
                reply.status !in 200..299 -> return failed("navpath answered HTTP ${reply.status}")
            }
            val route = try {
                read(reply.body)
            } catch (e: NavParseException) {
                return failed("an answer that does not parse: ${e.message}")
            }
            return when (val mapped = NavRouteMapper.map(start, route)) {
                is NavRouteMapper.Mapped.Refused -> failed(mapped.reason)
                is NavRouteMapper.Mapped.Route -> when {
                    !acceptsTeleports && (1 until mapped.path.size).any { mapped.path.linkAt(it)?.isGlobal == true } ->
                        failed("the route starts with a teleport this walk may not use")
                    else -> refuse(mapped.path)?.let(::failed) ?: Outcome.Planned(mapped.path)
                }
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
    }
}
