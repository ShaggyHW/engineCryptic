package com.projectx.store

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.projectx.script.Script
import com.projectx.script.ScriptDescription
import com.projectx.script.ScriptExecutor
import com.projectx.store.StartRefusal.Reason
import com.projectx.ui.LiveDetailsPreference
import com.projectx.ui.compose.GameThread
import java.net.InetAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The instance this client uses while it runs scripts. An account runs scripts on as many clients at once as it has
 * instances - one free, more rented, bought or with a membership, shared with the OSRS client - and the site keeps
 * the count so it holds across machines. A client takes one when its first script starts and gives it back a little
 * after its last stops, so an idle game window costs nothing. A running script carries on through a failed
 * heartbeat; only a Stop pressed on the website stops it.
 *
 * A script sold per instance also needs one of the player's seats of it. Every claim and heartbeat names the
 * per-instance scripts this client runs or is about to start, and the site answers which it may: a refused one does
 * not start, and a running one whose seat the site takes back (its seats ran out) is stopped.
 */
internal object Instances {
    private const val HEARTBEAT_SECONDS = 30L
    private const val STOPPED = "stopped"
    private const val REMOTE_PROTOCOL = 1
    private const val GAME = "rs3"
    private val RELEASE_AFTER_IDLE: Duration = Duration.ofMinutes(2)
    private val SEAT_GRACE: Duration = Duration.ofSeconds(15)

    enum class State { IDLE, HELD, SIGNED_OUT, SIGN_IN_EXPIRED, FULL, UNREACHABLE, STOPPED }

    @Volatile
    var state = State.IDLE
        private set

    @Volatile
    var used = 0
        private set

    @Volatile
    var total = 0
        private set

    /** The last per-instance refusal: how many seats of it were in use elsewhere, and how many the player has. */
    @Volatile
    var seatsUsed = 0
        private set

    @Volatile
    var seatsTotal = 0
        private set

    @Volatile
    private var heldSeats: Set<String> = emptySet()
    private val grantedAt = ConcurrentHashMap<String, Long>()

    private val sessionId = UUID.randomUUID().toString().replace("-", "")
    private val http: HttpClient by lazy { HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build() }
    private val worker = Executors.newSingleThreadScheduledExecutor { Thread(it, "projectx-instances").apply { isDaemon = true } }
    private val waiting = ConcurrentLinkedQueue<Script>()

    @Volatile
    private var idleSince = 0L

    @Volatile
    private var started = false

    fun instancesUrl() = "${Entitlements.storeBase()}/instances"

    @Synchronized
    fun start() {
        if (started) return
        started = true
        worker.scheduleWithFixedDelay({ runCatching { tick() } }, HEARTBEAT_SECONDS, HEARTBEAT_SECONDS, TimeUnit.SECONDS)
    }

    /**
     * True with an instance in hand, and a seat of [script] when it is sold per instance; otherwise they are fetched in
     * the background and [script] starts once they are.
     */
    fun mayStart(script: Script): Boolean {
        val name = Entitlements.nameOf(script)
        if (state == State.HELD && (!Entitlements.isPerInstance(name) || name in heldSeats)) return true
        waiting += script
        StoreNotices.refused(titleOf(script), null, Reason.INSTANCE_CHECKING)
        worker.submit { runCatching { claimForWaiting() } }
        return false
    }

    fun release() {
        if (state != State.HELD) return
        Entitlements.token()?.let { token -> runCatching { post(token, "release", emptySet(), Duration.ofSeconds(3)) } }
        heldSeats = emptySet()
        idleSince = 0L
        change(State.IDLE)
    }

    /** A per-instance script stopped: give its seat back now rather than at the next heartbeat. */
    fun stopped(script: Script) {
        if (state != State.HELD || !Entitlements.isPerInstance(Entitlements.nameOf(script))) return
        worker.submit { runCatching { send("heartbeat") } }
    }

    private fun claimForWaiting() {
        if (waiting.isEmpty()) return
        val needsSeat = waiting.any { Entitlements.isPerInstance(Entitlements.nameOf(it)) }
        if (state != State.HELD) send("claim") else if (needsSeat) send("heartbeat")
        val scripts = generateSequence { waiting.poll() }.toList()
        if (state != State.HELD) {
            scripts.lastOrNull()?.let { StoreNotices.refused(titleOf(it), null, refusalFor(state)) }
            return
        }
        val (ready, unseated) = scripts.partition { val n = Entitlements.nameOf(it); !Entitlements.isPerInstance(n) || n in heldSeats }
        GameThread.post {
            if (unseated.isEmpty()) StoreNotices.dismiss()
            ready.filter { !ScriptExecutor.isScriptRunning(it.javaClass) }.forEach { ScriptExecutor.activate(it) }
        }
        unseated.lastOrNull()?.let { refuseSeat(it) }
    }

    private fun refuseSeat(script: Script) {
        val name = Entitlements.nameOf(script)
        val reason = if (state == State.HELD && answeredSeats) Reason.NO_SEAT else Reason.INSTANCE_UNREACHABLE
        StoreNotices.refused(titleOf(script), Entitlements.accessOf(name), reason)
    }

    // Holding: heartbeat, or give the instance back once nothing has run for a while. Not holding but still running,
    // having lost the slot mid-run: keep trying to take one back.
    @Synchronized
    private fun tick() {
        val running = ScriptExecutor.activeScripts.isNotEmpty() || waiting.isNotEmpty()
        if (state != State.HELD) {
            if (running) send("claim")
            return
        }
        val now = System.currentTimeMillis()
        idleSince = if (running) 0L else idleSince.takeIf { it != 0L } ?: now
        if (idleLongEnough(idleSince, now)) release() else send("heartbeat")
    }

    internal fun idleLongEnough(idleSince: Long, now: Long) = idleSince != 0L && now - idleSince >= RELEASE_AFTER_IDLE.toMillis()

    @Volatile
    private var answeredSeats = false

    @Synchronized
    private fun send(action: String) {
        val token = Entitlements.token() ?: return change(State.SIGNED_OUT)
        val asked = seatsWanted()
        val response = runCatching { post(token, action, asked, Duration.ofSeconds(15)) }.getOrNull()
        val answer = response?.let { runCatching { JsonParser.parseString(it.body()).asJsonObject }.getOrNull() }
        answer?.getAsJsonObject("allowance")?.let {
            used = it.get("used")?.asInt ?: used
            total = it.get("total")?.asInt ?: total
        }
        val seats = answer?.getAsJsonObject("seats")
        answeredSeats = seats != null
        if (seats != null) takeSeats(asked, seats)
        val error = answer?.get("error")?.takeIf { it.isJsonPrimitive }?.asString
        when (val next = next(state, response?.statusCode(), error, local())) {
            State.STOPPED -> {
                println("[instances] this client was stopped from the website; stopping its scripts.")
                GameThread.post {
                    RunReports.markAll(RunEnd.MANUAL)
                    ScriptExecutor.stopAll()
                }
                change(State.IDLE)
            }
            else -> change(next)
        }
    }

    /**
     * What a claim or heartbeat answer means. [status] is null when the site could not be reached: a heartbeat keeps
     * its slot through that, while a claim fails closed, except against a store running on this machine.
     */
    internal fun next(current: State, status: Int?, error: String?, localStore: Boolean): State {
        val beat = current == State.HELD
        return when {
            status == null -> if (beat || localStore) State.HELD else State.UNREACHABLE
            status in 200..299 -> State.HELD
            status == 401 -> State.SIGN_IN_EXPIRED
            status == 409 && error == STOPPED -> State.STOPPED
            status == 409 -> State.FULL
            beat -> State.HELD
            else -> State.UNREACHABLE
        }
    }

    private fun change(next: State) {
        if (next != state) println("[instances] ${describe(next)}")
        state = next
    }

    // Running, about to start, or granted a moment ago and not yet started by the game thread.
    private fun seatsWanted(): Set<String> {
        val now = System.currentTimeMillis()
        grantedAt.entries.removeIf { now - it.value > SEAT_GRACE.toMillis() }
        return (ScriptExecutor.activeScripts.asSequence() + waiting.asSequence())
            .map { Entitlements.nameOf(it) }
            .filter { Entitlements.isPerInstance(it) }
            .toSet() + grantedAt.keys
    }

    internal fun grantedIn(answer: JsonObject): Set<String> =
        answer.getAsJsonArray("granted")?.mapNotNull { runCatching { it.asString }.getOrNull() }?.toSet().orEmpty()

    /** Running per-instance scripts this client asked to keep a seat of and was refused: they must stop. */
    internal fun revoked(asked: Set<String>, granted: Set<String>, running: Collection<String>): Set<String> =
        running.filter { it in asked && it !in granted }.toSet()

    private fun takeSeats(asked: Set<String>, answer: JsonObject) {
        val granted = grantedIn(answer)
        answer.getAsJsonArray("refused")?.lastOrNull()?.asJsonObject?.let {
            seatsUsed = it.get("used")?.asInt ?: 0
            seatsTotal = it.get("total")?.asInt ?: 0
        }
        val now = System.currentTimeMillis()
        (granted - heldSeats).forEach { grantedAt[it] = now }
        heldSeats = granted intersect asked
        val stopping = revoked(asked, granted, ScriptExecutor.activeScripts.map { Entitlements.nameOf(it) })
        val revoked = ScriptExecutor.activeScripts.filter { Entitlements.nameOf(it) in stopping }
        if (revoked.isEmpty()) return
        GameThread.post {
            revoked.forEach { script ->
                println("[instances] ${titleOf(script)} has no instance free for this client any more ($seatsUsed of $seatsTotal in use elsewhere); stopping it.")
                RunReports.markStop(script.javaClass, RunEnd.ACCESS_ENDED)
                runCatching { script.stop() }
            }
            revoked.lastOrNull()?.let { StoreNotices.refused(titleOf(it), Entitlements.accessOf(Entitlements.nameOf(it)), Reason.NO_SEAT) }
        }
    }

    private fun post(token: String, action: String, seats: Set<String>, timeout: Duration): HttpResponse<String> {
        val body = JsonObject().apply {
            addProperty("action", action)
            addProperty("sessionId", sessionId)
            addProperty("label", label())
            if (action != "release") {
                addLiveDetails(this)
                addProperty("game", GAME)
                add("seats", JsonArray().apply { seats.forEach { add(it) } })
            }
        }
        val request = HttpRequest.newBuilder(URI.create("${Entitlements.storeBase()}/api/v1/instances"))
            .timeout(timeout)
            .header("Authorization", "Bearer $token")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
            .build()
        return http.send(request, HttpResponse.BodyHandlers.ofString())
    }

    // Without remote control the site sends no commands; a Stop pressed on the website still arrives as the 409 above.
    private fun addLiveDetails(body: JsonObject) {
        val monitor = LiveDetailsPreference.enabled.value
        body.add("remote", JsonObject().apply {
            addProperty("v", REMOTE_PROTOCOL)
            addProperty("control", false)
            addProperty("monitor", monitor)
        })
        if (monitor) runCatching { RunReports.liveState() }.getOrNull()?.let { body.add("state", it) }
    }

    private fun titleOf(script: Script) = script.javaClass.getAnnotation(ScriptDescription::class.java)?.name ?: script.javaClass.simpleName

    private fun label(): String {
        val machine = System.getenv("COMPUTERNAME") ?: runCatching { InetAddress.getLocalHost().hostName }.getOrDefault("this computer")
        return "RS3 on $machine".take(60)
    }

    private fun local(): Boolean = runCatching { URI.create(Entitlements.storeBase()).host in setOf("localhost", "127.0.0.1") }.getOrDefault(false)

    private fun refusalFor(state: State) = when (state) {
        State.SIGNED_OUT -> Reason.INSTANCE_SIGNED_OUT
        State.SIGN_IN_EXPIRED -> Reason.SIGN_IN_EXPIRED
        State.FULL -> Reason.NO_INSTANCE
        State.STOPPED -> Reason.INSTANCE_STOPPED
        State.IDLE, State.UNREACHABLE, State.HELD -> Reason.INSTANCE_UNREACHABLE
    }

    private fun describe(state: State) = when (state) {
        State.HELD -> "this client is using an instance ($used of $total in use)."
        State.IDLE -> "this client gave its instance back; no scripts are running."
        State.SIGNED_OUT -> "sign in with Discord in the launcher's Store tab to run scripts."
        State.SIGN_IN_EXPIRED -> "your store sign-in is no longer valid; sign in again on the launcher's Store tab."
        State.FULL -> "no instance free: $used of $total in use. Stop scripts on another client or add an instance at ${instancesUrl()}"
        State.UNREACHABLE -> "couldn't reach the store to check your instances."
        State.STOPPED -> "this client was stopped from the website."
    }
}
