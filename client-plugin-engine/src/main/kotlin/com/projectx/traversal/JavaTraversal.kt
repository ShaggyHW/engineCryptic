package com.projectx.traversal

import com.projectx.script.JavaScript
import com.projectx.script.JavaState
import com.projectx.script.JavaStateMachineScript
import com.projectx.script.Script
import com.projectx.script.Wait
import java.util.function.BooleanSupplier
import java.util.function.Consumer
import java.util.function.Supplier

/**
 * [Traversal] for a [JavaStateMachineScript]: a [JavaState] that drives a node list one pass at a time and moves on to
 * [next] once it stops. Return it from `checkNext`:
 *
 * ```java
 * TraversalNodeList nodes = JavaTraversal.nodes(b -> b.path(here, bank, null));
 * return new JavaTraversal<>(Phase.BANKING, nodes, () -> nearBank())
 *         .interruptWhen("under attack", () -> underAttack(), Phase.FLEE);
 * ```
 *
 * A plain [JavaScript] runs a node list to its end with [Wait.traversal].
 */
class JavaTraversal<S : JavaStateMachineScript<S>> @JvmOverloads constructor(
    private val next: JavaState<S>?,
    nodes: TraversalNodeList,
    finishedCondition: BooleanSupplier,
    interruptCondition: BooleanSupplier? = null,
    noMovementTimeoutMillis: Long = 0L,
) : JavaState<S> {
    internal val driver = TraversalDriver(finishedCondition.onAnyScript(), interruptCondition?.onAnyScript(), nodes, noMovementTimeoutMillis)
    private var interruptedNext: JavaState<S>? = null
    private var ended = false

    constructor(next: JavaState<S>?, nodes: TraversalNodeList, finishedCondition: BooleanSupplier, noMovementTimeoutMillis: Long) :
        this(next, nodes, finishedCondition, null, noMovementTimeoutMillis)

    /** Why the traversal stopped, or [TraversalOutcome.RUNNING] while it goes on. */
    val outcome: TraversalOutcome get() = driver.outcome

    val wasInterrupted: Boolean get() = driver.wasInterrupted

    val currentNode: TraversalNode get() = driver.currentNode

    fun interruptWhen(condition: BooleanSupplier): JavaTraversal<S> = setInterrupt(null, condition, null)

    fun interruptWhen(name: String, condition: BooleanSupplier): JavaTraversal<S> = setInterrupt(name, condition, null)

    fun interruptWhen(condition: BooleanSupplier, onInterrupt: JavaState<S>): JavaTraversal<S> = setInterrupt(null, condition, onInterrupt)

    fun interruptWhen(name: String, condition: BooleanSupplier, onInterrupt: JavaState<S>): JavaTraversal<S> =
        setInterrupt(name, condition, onInterrupt)

    fun onInterrupt(onInterrupt: JavaState<S>): JavaTraversal<S> = apply { interruptedNext = onInterrupt }

    private fun setInterrupt(name: String?, condition: BooleanSupplier, onInterrupt: JavaState<S>?): JavaTraversal<S> = apply {
        if (onInterrupt != null) interruptedNext = onInterrupt
        driver.interruptWhen(name, condition.onAnyScript())
    }

    /** See [Traversal.repathWith]. The supplier runs on the game thread, so it must not block. */
    fun repathWith(supplier: Supplier<TraversalNodeList?>): JavaTraversal<S> = apply { driver.repathWith { supplier.get() } }

    fun pollOnTick(enabled: Boolean): JavaTraversal<S> = apply { driver.pollOnTick = enabled }

    fun pollConditionsOnTick(): Boolean = driver.pollConditionsOnTick()

    fun pollConditionsNow(): Boolean = driver.pollConditionsNow()

    override fun checkNext(script: S): JavaState<S>? {
        if (!ended) return null
        return if (driver.wasInterrupted) interruptedNext ?: next else next
    }

    override fun onLoop(script: S): Wait = Wait.Call<Boolean>({ driver.step(this).also { ended = !it } }, null)

    override fun onTickLoop(script: S) = driver.tickLoop(script)

    internal suspend fun runInline(script: Script): TraversalOutcome {
        driver.run(script)
        ended = true
        return driver.outcome
    }

    companion object {
        /** The nodes [init] adds, for a [JavaTraversal] or [Wait.traversal]. */
        @JvmStatic
        fun nodes(init: Consumer<TraversalBuilder<*>>): TraversalNodeList = TraversalBuilder<Nothing>().also { init.accept(it) }.buildNodes()
    }
}

internal fun BooleanSupplier.onAnyScript(): (Script) -> Boolean = { asBoolean }
