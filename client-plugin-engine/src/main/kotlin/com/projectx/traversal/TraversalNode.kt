package com.projectx.traversal

import com.projectx.script.Script
import com.projectx.traversal.pathing.NavMacro

abstract class TraversalNode {
    var prev: TraversalNode? = null
    var next: TraversalNode? = null

    internal var stopSignal: () -> Boolean = { false }
    internal var repathRequester: () -> Boolean = { false }
    internal var failer: (String) -> Unit = {}

    private var dramenStaffRequired = false

    /** The navpath edge this node was mapped from, so a re-route can leave it out; null for a hand-built node. */
    var navMacro: NavMacro? = null
        internal set

    /** Failed Wield clicks before this node, counted by the processor. */
    internal var dramenStaffWieldAttempts = 0

    abstract suspend fun process(script: Script): Boolean
    abstract fun reached(script: Script): Boolean
    abstract fun copy(): TraversalNode

    /** Marks this node as one whose route step was planned with the Dramen staff carried; it is wielded before it runs. */
    fun requireDramenStaff() {
        dramenStaffRequired = true
    }

    /** True when the staff must be wielded before [process] runs. A node may also ask from its own state. */
    open fun needsDramenStaff(): Boolean = dramenStaffRequired

    internal fun copyRouteFlagsFrom(original: TraversalNode) {
        dramenStaffRequired = original.dramenStaffRequired
        navMacro = original.navMacro
    }

    /** Called once on every node when the traversal stops, whatever the outcome, a re-route included. */
    protected open fun traversalStopped(outcome: TraversalOutcome) {}

    internal fun notifyTraversalStopped(outcome: TraversalOutcome) = traversalStopped(outcome)

    /** True once the traversal's finished or interrupt condition has fired; long waits in [process] should end on it. */
    protected open fun traversalStopRequested(): Boolean = stopSignal()

    /** Stops the traversal with [TraversalOutcome.FAILED]: this node can never complete. */
    protected open fun failTraversal(reason: String) = failer(reason)

    /**
     * Asks the traversal to replace the rest of its nodes with a fresh route to the same goal, from where the player
     * now is. False when the traversal cannot re-route, which a hand-built chain cannot.
     */
    protected open fun requestTraversalRepath(): Boolean = repathRequester()
}
