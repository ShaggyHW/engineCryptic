package com.projectx.traversal

import com.projectx.script.State
import com.projectx.script.StateMachineScript

/**
 * A state that drives a node list until it stops, then moves on to [next]. The finished condition means it arrived;
 * an interrupt condition ([interruptWhen]) means it was cut short, and may route to a state of its own. Both are
 * polled on every pass and once per server tick while this is the script's state, so a stop is seen even while a node
 * blocks. A traversal built without a `next` is driven inline with [run].
 */
open class Traversal<T : StateMachineScript<T>>(
    private val next: State<T>?,
    finishedCondition: T.() -> Boolean,
    nodes: TraversalNodeList,
    interruptCondition: (T.() -> Boolean)?,
    noMovementTimeoutMillis: Long,
) : State<T>() {
    private val driver = TraversalDriver(onScript(finishedCondition), interruptCondition?.let { onScript(it) }, nodes, noMovementTimeoutMillis)
    private var interruptedNext: State<T>? = null

    constructor(next: State<T>?, finishedCondition: T.() -> Boolean, nodes: TraversalNodeList) :
        this(next, finishedCondition, nodes, null, 0L)

    /** A traversal that also ends, as [TraversalOutcome.TIMED_OUT], when the player stands still that long on one node. */
    constructor(next: State<T>?, finishedCondition: T.() -> Boolean, nodes: TraversalNodeList, noMovementTimeoutMillis: Long) :
        this(next, finishedCondition, nodes, null, noMovementTimeoutMillis)

    constructor(next: State<T>?, finishedCondition: T.() -> Boolean, nodes: TraversalNodeList, interruptCondition: (T.() -> Boolean)?) :
        this(next, finishedCondition, nodes, interruptCondition, 0L)

    /** Why the traversal stopped, or [TraversalOutcome.RUNNING] while it goes on. */
    val outcome: TraversalOutcome get() = driver.outcome

    val wasInterrupted: Boolean get() = driver.wasInterrupted

    val currentNode: TraversalNode get() = driver.currentNode

    internal val seams: ProcessorSeams get() = driver.seams

    /** Stops the traversal early, without arriving, once [condition] holds; it then moves on to [onInterrupt] if given. */
    @JvmOverloads
    fun interruptWhen(condition: T.() -> Boolean, onInterrupt: State<T>? = null): Traversal<T> =
        setInterrupt(null, condition, onInterrupt)

    /** [interruptWhen] with [name] labelling the stop in the log. */
    @JvmOverloads
    fun interruptWhen(name: String, condition: T.() -> Boolean, onInterrupt: State<T>? = null): Traversal<T> =
        setInterrupt(name, condition, onInterrupt)

    /** Routes an interrupt to [onInterrupt] instead of `next`, keeping the interrupt condition as it is. */
    fun onInterrupt(onInterrupt: State<T>): Traversal<T> = apply { interruptedNext = onInterrupt }

    private fun setInterrupt(name: String?, condition: T.() -> Boolean, onInterrupt: State<T>?): Traversal<T> = apply {
        if (onInterrupt != null) interruptedNext = onInterrupt
        driver.interruptWhen(name, onScript(condition))
    }

    /**
     * Lets nodes ask for a fresh route to the same goal when the rest of theirs no longer holds, such as after a
     * locked shortcut. [supplier] builds the replacement from where the player stands; null keeps the current one.
     * Without a supplier, or after [MAX_REPATHS] re-routes, requests are refused and nodes fall back on their own.
     */
    fun repathWith(supplier: () -> TraversalNodeList?): Traversal<T> = apply { driver.repathWith { supplier() } }

    /** [repathWith] for a supplier that suspends, such as one asking the route planner, so the game thread never blocks. */
    fun repathSuspending(supplier: suspend () -> TraversalNodeList?): Traversal<T> = apply { driver.repathWith(supplier) }

    /**
     * Turns the once-per-server-tick condition poll off, or back on (the default). Off, the conditions are evaluated
     * only on each pass, so not while a node blocks.
     */
    fun pollOnTick(enabled: Boolean): Traversal<T> = apply { driver.pollOnTick = enabled }

    /** Polls the conditions at most once per server tick; for a script driving [run] from its own tick hook. */
    fun pollConditionsOnTick(): Boolean = driver.pollConditionsOnTick()

    /** Polls the conditions now, for a script that just changed what they read; true once one has fired. */
    fun pollConditionsNow(): Boolean = driver.pollConditionsNow()

    /** Drives the traversal inline until it stops; [outcome] then says how. The tick poll does not run on this path. */
    suspend fun run(script: T) = driver.run(script)

    override suspend fun T.checkNext(): State<T>? {
        if (driver.step(this)) return null
        if (driver.wasInterrupted) interruptedNext?.let { return it }
        return next
    }

    override suspend fun T.stateLoop() { }

    /** An override must call super, or the conditions are no longer polled while a node blocks. */
    override fun T.stateTickLoop() = driver.tickLoop(this)

    companion object {
        const val MAX_REPATHS = TraversalDriver.MAX_REPATHS

        /** The no-movement timeout of a traversal built from a planned route; a hand-built one has none unless given. */
        const val ROUTE_NO_MOVEMENT_TIMEOUT_MILLIS = 15_000L

        fun <T : StateMachineScript<T>> traversal(next: State<T>, finishedCondition: T.() -> Boolean, init: TraversalBuilder<T>.() -> Unit): Traversal<T> =
            TraversalBuilder<T>().apply(init).build(next, finishedCondition)

        fun <T : StateMachineScript<T>> traversal(
            next: State<T>,
            finishedCondition: T.() -> Boolean,
            noMovementTimeoutMillis: Long,
            init: TraversalBuilder<T>.() -> Unit,
        ): Traversal<T> = TraversalBuilder<T>().apply(init).build(next, finishedCondition, null, noMovementTimeoutMillis)

        fun <T : StateMachineScript<T>> traversal(
            next: State<T>,
            finishedCondition: T.() -> Boolean,
            interruptCondition: (T.() -> Boolean)?,
            init: TraversalBuilder<T>.() -> Unit,
        ): Traversal<T> = TraversalBuilder<T>().apply(init).build(next, finishedCondition, interruptCondition, 0L)

        fun <T : StateMachineScript<T>> traversal(
            next: State<T>,
            finishedCondition: T.() -> Boolean,
            interruptCondition: (T.() -> Boolean)?,
            noMovementTimeoutMillis: Long,
            init: TraversalBuilder<T>.() -> Unit,
        ): Traversal<T> = TraversalBuilder<T>().apply(init).build(next, finishedCondition, interruptCondition, noMovementTimeoutMillis)

        /** A traversal with no `next`, driven inline with [run]. */
        fun <T : StateMachineScript<T>> traversal(finishedCondition: T.() -> Boolean, init: TraversalBuilder<T>.() -> Unit): Traversal<T> =
            TraversalBuilder<T>().apply(init).build(finishedCondition)

        fun <T : StateMachineScript<T>> traversal(
            finishedCondition: T.() -> Boolean,
            noMovementTimeoutMillis: Long,
            init: TraversalBuilder<T>.() -> Unit,
        ): Traversal<T> = TraversalBuilder<T>().apply(init).build(finishedCondition, null, noMovementTimeoutMillis)

        fun <T : StateMachineScript<T>> traversal(
            finishedCondition: T.() -> Boolean,
            interruptCondition: (T.() -> Boolean)?,
            init: TraversalBuilder<T>.() -> Unit,
        ): Traversal<T> = TraversalBuilder<T>().apply(init).build(finishedCondition, interruptCondition, 0L)

        fun <T : StateMachineScript<T>> traversal(
            finishedCondition: T.() -> Boolean,
            interruptCondition: (T.() -> Boolean)?,
            noMovementTimeoutMillis: Long,
            init: TraversalBuilder<T>.() -> Unit,
        ): Traversal<T> = TraversalBuilder<T>().apply(init).build(finishedCondition, interruptCondition, noMovementTimeoutMillis)
    }
}
