package com.projectx.traversal

import com.projectx.game.collision.CollisionMap
import com.projectx.script.Script
import com.projectx.script.StateMachineScript
import com.projectx.script.api.ServerTick
import com.projectx.script.api.localPlayer
import com.projectx.traversal.local.LocalNav
import com.projectx.traversal.nodes.DramenStaff
import world.gregs.voidps.type.Tile
import java.util.concurrent.locks.ReentrantLock

/**
 * Drives a node list one [process] call at a time. The finished condition stops it having arrived, the interrupt
 * condition stops it cut short, and a node may fail it or ask for a fresh route; [outcome] says which. The conditions
 * are polled on every [process] and once per server tick from the script's tick hook, so a stop is latched on the tick
 * it becomes true even while a node blocks; the processor acts on it when [process] next runs.
 */
class TraversalProcessor<T : StateMachineScript<T>> internal constructor(internal val core: NodeListProcessor) {

    constructor(finishedCondition: T.() -> Boolean, nodes: TraversalNodeList) : this(finishedCondition, null, nodes, 0L)

    constructor(
        finishedCondition: T.() -> Boolean,
        interruptCondition: (T.() -> Boolean)?,
        nodes: TraversalNodeList,
        noMovementTimeoutMillis: Long,
    ) : this(NodeListProcessor(onScript(finishedCondition), interruptCondition?.let { onScript(it) }, nodes, noMovementTimeoutMillis))

    /** Why the last [process] returned false, or [TraversalOutcome.RUNNING] while it returns true. */
    val outcome: TraversalOutcome get() = core.outcome

    val wasInterrupted: Boolean get() = core.wasInterrupted

    val currentNode: TraversalNode get() = core.currentNode

    internal var playerTile: () -> Tile?
        get() = core.seams.playerTile
        set(value) {
            core.seams.playerTile = value
        }

    internal var repathSupported: Boolean
        get() = core.repathSupported
        set(value) {
            core.repathSupported = value
        }

    fun setInterruptCondition(interruptCondition: (T.() -> Boolean)?) = setInterruptCondition(null, interruptCondition)

    /** Sets the interrupt condition; [name] labels its stop in the log. */
    fun setInterruptCondition(name: String?, interruptCondition: (T.() -> Boolean)?) =
        core.setInterruptCondition(name, interruptCondition?.let { onScript(it) })

    /** True once the finished or interrupt condition has fired. */
    fun stopRequested(): Boolean = core.stopRequested()

    suspend fun process(script: T): Boolean = core.process(script)

    /** Polls the conditions at most once per server tick; true once one has fired. */
    fun pollConditionsOnTick(): Boolean = core.pollConditionsOnTick(null)

    /** Polls the conditions now, without the once-per-tick dedup; true once one has fired. */
    fun pollConditionsNow(): Boolean = core.pollConditionsNow(null)

    internal fun requestRepath(): Boolean = core.requestRepath()

    internal fun requestFail(reason: String) = core.requestFail(reason)

    internal fun cancelRepathRequest() = core.cancelRepathRequest()

    internal fun stopLatched(): Boolean = core.stopLatched()

}

/** The Dramen staff as the processor needs it; a seam so the wield ladder can be tested without a client. */
internal interface DramenStaffHands {
    fun carried(): Boolean
    fun worn(): Boolean
    val restorePending: Boolean
    suspend fun wield(script: Script): Boolean
    suspend fun restore(script: Script)
    suspend fun wield(script: Script, stop: () -> Boolean): Boolean = wield(script)
    suspend fun restore(script: Script, stop: () -> Boolean) = restore(script)
}

internal object LiveDramenStaff : DramenStaffHands {
    override fun carried() = DramenStaff.carried()
    override fun worn() = DramenStaff.worn()
    override val restorePending get() = DramenStaff.restorePending
    override suspend fun wield(script: Script) = DramenStaff.wield(script)
    override suspend fun restore(script: Script) = DramenStaff.restore(script)
    override suspend fun wield(script: Script, stop: () -> Boolean) = DramenStaff.wield(script, stop)
    override suspend fun restore(script: Script, stop: () -> Boolean) = DramenStaff.restore(script, stop)
}

/** What a processor reads from the game, replaceable in tests and carried over to the processor a re-route builds. */
internal class ProcessorSeams {
    var playerTile: () -> Tile? = { localPlayer.let { it.serverTile ?: it.tile } }
    var serverTick: () -> Long = { ServerTick.count }
    var dramenStaff: DramenStaffHands = LiveDramenStaff
    var prewarm: (Int, Int) -> Unit = CollisionMap::prewarm
}

/** The processor itself, with conditions on any [Script] so Kotlin and Java traversals share it. */
internal class NodeListProcessor(
    private val finishedCondition: (Script) -> Boolean,
    private var interruptCondition: ((Script) -> Boolean)?,
    nodes: TraversalNodeList,
    private val noMovementTimeoutMillis: Long,
    val seams: ProcessorSeams = ProcessorSeams(),
) {
    private val nodes: TraversalNodeList = nodes.copy()
    private var curr: TraversalNode = this.nodes.head
    private var interruptName: String? = null

    var outcome: TraversalOutcome = TraversalOutcome.RUNNING
        private set

    val wasInterrupted: Boolean get() = outcome == TraversalOutcome.INTERRUPTED

    val currentNode: TraversalNode get() = curr

    // Conditions may be polled off the game thread through pollConditionsNow; the lock keeps two evaluations of a
    // side-effecting pair from overlapping, and the tick hook only ever tries it, never waits.
    private val conditionLock = ReentrantLock()

    @Volatile
    private var latchedStop: TraversalOutcome? = null

    @Volatile
    private var lastPolledServerTick = -1L

    @Volatile
    private var polledAt = 0L

    @Volatile
    private var lastScript: Script? = null

    @Volatile
    var repathSupported = false

    @Volatile
    private var repathRequested = false

    @Volatile
    private var failReason: String? = null

    private var stopNotified = false

    private var movementNode: TraversalNode? = null
    private var lastTile: Tile? = null
    private var lastMovedAt = 0L

    init {
        var node: TraversalNode? = this.nodes.head
        while (node != null) {
            node.stopSignal = ::stopRequested
            node.repathRequester = ::requestRepath
            node.failer = ::requestFail
            node = node.next
        }
    }

    fun setInterruptCondition(name: String?, condition: ((Script) -> Boolean)?) {
        interruptName = name
        interruptCondition = condition
    }

    /**
     * True once a stop has latched. Also polls the conditions when the server tick moved on or a few hundred
     * milliseconds passed since the last poll, so a traversal driven without the tick hook still sees a stop mid-wait.
     */
    fun stopRequested(): Boolean {
        if (latchedStop != null) return true
        val script = lastScript ?: return false
        val sameTick = seams.serverTick() == lastPolledServerTick
        if (sameTick && System.currentTimeMillis() - polledAt < CONDITION_POLL_MS) return false
        return pollConditions(script, waitForLock = false) != null
    }

    fun stopLatched(): Boolean = latchedStop != null

    fun pollConditionsOnTick(script: Script?): Boolean {
        if (latchedStop != null) return true
        val polled = script?.also { lastScript = it } ?: lastScript ?: return false
        if (seams.serverTick() == lastPolledServerTick) return false
        return pollConditions(polled, waitForLock = false) != null
    }

    fun pollConditionsNow(script: Script?): Boolean {
        val polled = script ?: lastScript ?: return latchedStop != null
        return pollConditions(polled, waitForLock = true) != null
    }

    private fun pollConditions(script: Script, waitForLock: Boolean): TraversalOutcome? {
        latchedStop?.let { return it }
        if (waitForLock) conditionLock.lock() else if (!conditionLock.tryLock()) return null
        try {
            latchedStop?.let { return it }
            polledAt = System.currentTimeMillis()
            if (finishedCondition(script)) {
                println("[Traversal] FINISHED - finished condition met on $curr")
                latchedStop = TraversalOutcome.FINISHED
            } else if (interruptCondition?.invoke(script) == true) {
                println("[Traversal] INTERRUPTED - ${interruptName ?: "interrupt condition"} met on $curr")
                latchedStop = TraversalOutcome.INTERRUPTED
            }
            return latchedStop
        } finally {
            lastPolledServerTick = seams.serverTick()
            conditionLock.unlock()
        }
    }

    fun requestRepath(): Boolean {
        if (!repathSupported || latchedStop != null) return false
        if (!repathRequested) println("[Traversal] repath requested on $curr")
        repathRequested = true
        return true
    }

    fun requestFail(reason: String) {
        if (failReason == null) {
            failReason = reason
            println("[Traversal] failed on $curr: $reason")
        }
    }

    fun cancelRepathRequest() {
        repathRequested = false
        if (outcome == TraversalOutcome.REPATH_REQUESTED) outcome = TraversalOutcome.RUNNING
    }

    private var prewarmedFor: TraversalNode? = null

    /** Before a node that moves the player far, loads the static squares where the next walk starts, once per node. */
    private fun prewarmLanding(player: Tile?) {
        if (curr === prewarmedFor || curr is WalkEquivalent || player == null) return
        prewarmedFor = curr
        var next = curr.next
        var looked = 0
        while (next != null && looked < PREWARM_LOOKAHEAD) {
            if (next is WalkEquivalent) {
                val start = next.routeTiles().firstOrNull() ?: return
                if (start.x >= INSTANCE_MIN_X) return
                if (start.plane == player.plane && start.getLongestDelta(player) < PREWARM_MIN_TILES) return
                seams.prewarm(start.x, start.y)
                return
            }
            next = next.next
            looked++
        }
    }

    suspend fun process(script: Script): Boolean {
        lastScript = script
        val now = System.currentTimeMillis()
        pollConditions(script, waitForLock = true)?.let { return stop(it, script) }
        if (repathRequested) return stop(TraversalOutcome.REPATH_REQUESTED, script)
        if (failReason != null) return stop(TraversalOutcome.FAILED, script)
        val player = runCatching { seams.playerTile() }.getOrNull()
        if (timedOut(player, now)) {
            println("[Traversal] TIMEOUT - no movement for ${noMovementTimeoutMillis}ms on $curr")
            return stop(TraversalOutcome.TIMED_OUT, script)
        }
        if (player != null) {
            LocalNav.observe(player)
            advanceByProgress(player)
        }

        var hops = 0
        while (curr.reached(script)) {
            val next = curr.next ?: return stop(TraversalOutcome.COMPLETED, script)
            curr = next
            outcome = TraversalOutcome.RUNNING
            if (++hops >= MAX_ADVANCE_PER_PASS) return true
        }

        val staff = seams.dramenStaff
        if (staff.restorePending && !dramenStaffNeededFrom(curr)) {
            staff.restore(script, ::stopRequested)
            outcome = TraversalOutcome.RUNNING
            return true
        }
        if (curr.needsDramenStaff() && !staff.worn() && wieldDramenStaff(script, staff)) return afterNodeWork(script)

        prewarmLanding(player)
        if (curr.process(script)) return afterNodeWork(script)
        if (repathRequested) return stop(TraversalOutcome.REPATH_REQUESTED, script)
        if (failReason != null) return stop(TraversalOutcome.FAILED, script)
        curr.next?.let {
            curr = it
            outcome = TraversalOutcome.RUNNING
            return true
        }
        return stop(if (curr.reached(script)) TraversalOutcome.COMPLETED else TraversalOutcome.STALLED, script)
    }

    private suspend fun afterNodeWork(script: Script): Boolean {
        if (repathRequested) return stop(TraversalOutcome.REPATH_REQUESTED, script)
        if (failReason != null) return stop(TraversalOutcome.FAILED, script)
        outcome = TraversalOutcome.RUNNING
        return true
    }

    /**
     * Wields the staff from the backpack before a node planned with it carried, since the game wants it worn. With none
     * left the route is planned again, which no longer counts on it. True when this pass was spent on it.
     */
    private suspend fun wieldDramenStaff(script: Script, staff: DramenStaffHands): Boolean {
        val node = curr
        if (!staff.carried()) {
            println("[Traversal] $node needs the Dramen staff and none is carried - re-routing")
            if (!requestRepath()) requestFail("Dramen staff needed for $node but none is carried")
            return true
        }
        if (staff.wield(script, ::stopRequested)) {
            node.dramenStaffWieldAttempts = 0
            return false
        }
        if (++node.dramenStaffWieldAttempts >= MAX_DRAMEN_STAFF_WIELDS) {
            requestFail("could not wield the Dramen staff for $node after ${node.dramenStaffWieldAttempts} tries")
        }
        return true
    }

    private fun dramenStaffNeededFrom(node: TraversalNode): Boolean {
        var n: TraversalNode? = node
        while (n != null) {
            if (n.needsDramenStaff()) return true
            n = n.next
        }
        return false
    }

    private suspend fun stop(reason: TraversalOutcome, script: Script): Boolean {
        outcome = reason
        if (!stopNotified) {
            stopNotified = true
            var node: TraversalNode? = nodes.head
            while (node != null) {
                runCatching { node.notifyTraversalStopped(reason) }.onFailure { it.printStackTrace() }
                node = node.next
            }
            // Not on a re-route: the new route may need the staff again, and its processor restores once it does not.
            val staff = seams.dramenStaff
            if (reason != TraversalOutcome.REPATH_REQUESTED && staff.restorePending) staff.restore(script) { false }
        }
        return false
    }

    private fun timedOut(player: Tile?, now: Long): Boolean {
        if (noMovementTimeoutMillis <= 0L || player == null) return false
        if (movementNode !== curr || lastTile != player) {
            movementNode = curr
            lastTile = player
            lastMovedAt = now
            return false
        }
        return now - lastMovedAt >= noMovementTimeoutMillis
    }

    /**
     * When the player already stands on a later node's tiles and every node up to it can be walked past now, jumps to
     * it: otherwise a walk whose end the player ran past - through an open door, after a long hop - clicks back to it.
     * A node holding a tour stop is never skipped.
     */
    private fun advanceByProgress(player: Tile) {
        val here = curr as? WalkEquivalent ?: return
        if (!here.passableNow() || here.holdsStop()) return
        var target: TraversalNode? = null
        var node = curr.next
        var looked = 0
        while (node != null && looked++ < PROGRESS_LOOKAHEAD) {
            val walk = node as? WalkEquivalent ?: break
            if (onTiles(player, walk.routeTiles())) target = node
            if (!walk.passableNow() || walk.holdsStop()) break
            node = node.next
        }
        target?.let {
            println("[Traversal] PROGRESS - $player is already on $it, skipping from $curr")
            curr = it
            outcome = TraversalOutcome.RUNNING
        }
    }

    private fun onTiles(player: Tile, tiles: List<Tile>): Boolean {
        if (tiles.isEmpty()) return false
        val trusted = LocalNav.trusted(player)
        return tiles.any { t ->
            t.plane == player.plane && if (trusted) {
                player.getLongestDelta(t) <= 2 && LocalNav.distance(player, t, 2) >= 0
            } else {
                player.x == t.x && player.y == t.y
            }
        }
    }

    private companion object {
        private const val MAX_ADVANCE_PER_PASS = 8
        private const val PROGRESS_LOOKAHEAD = 8
        private const val CONDITION_POLL_MS = 300L
        private const val MAX_DRAMEN_STAFF_WIELDS = 3
        private const val PREWARM_LOOKAHEAD = 4
        private const val PREWARM_MIN_TILES = 48
        private const val INSTANCE_MIN_X = 6400
    }
}
