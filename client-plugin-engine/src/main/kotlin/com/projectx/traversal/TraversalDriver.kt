package com.projectx.traversal

import com.projectx.script.Script
import com.projectx.script.StateMachineScript
import kotlinx.coroutines.CancellationException

/**
 * What [Traversal] and [JavaTraversal] share: the processor, the re-route that replaces it, and the tick poll. The
 * conditions outlive every processor a re-route swaps in.
 */
internal class TraversalDriver(
    private val finishedCondition: (Script) -> Boolean,
    private var interruptCondition: ((Script) -> Boolean)?,
    nodes: TraversalNodeList,
    private val noMovementTimeoutMillis: Long,
) {
    val seams = ProcessorSeams()

    @Volatile
    var processor = NodeListProcessor(finishedCondition, interruptCondition, nodes, noMovementTimeoutMillis, seams)
        private set

    private var interruptName: String? = null
    private var repathSupplier: (suspend () -> TraversalNodeList?)? = null
    private var repathCount = 0

    @Volatile
    var pollOnTick = true

    val outcome: TraversalOutcome get() = processor.outcome

    val wasInterrupted: Boolean get() = processor.wasInterrupted

    val currentNode: TraversalNode get() = processor.currentNode

    fun interruptWhen(name: String?, condition: ((Script) -> Boolean)?) {
        interruptName = name
        interruptCondition = condition
        processor.setInterruptCondition(name, condition)
    }

    fun repathWith(supplier: (suspend () -> TraversalNodeList?)?) {
        repathSupplier = supplier
        processor.repathSupported = supplier != null
    }

    /** One processor pass; false once the traversal has stopped for good. */
    suspend fun step(script: Script): Boolean {
        if (processor.process(script)) return true
        if (processor.outcome != TraversalOutcome.REPATH_REQUESTED) return false
        repath()
        return true
    }

    suspend fun run(script: Script) {
        while (true) {
            if (processor.process(script)) {
                script.delay(POLL_MS)
                continue
            }
            if (processor.outcome != TraversalOutcome.REPATH_REQUESTED) return
            repath()
            script.delay(POLL_MS)
        }
    }

    fun tickLoop(script: Script) {
        if (pollOnTick) processor.pollConditionsOnTick(script)
    }

    fun pollConditionsOnTick(): Boolean = processor.pollConditionsOnTick(null)

    fun pollConditionsNow(): Boolean = processor.pollConditionsNow(null)

    private suspend fun repath() {
        val retired = processor
        // A finished or interrupt condition that latched on the way here wins over the re-route.
        if (retired.stopLatched()) {
            retired.cancelRepathRequest()
            return
        }
        val supplier = repathSupplier
        if (supplier == null || ++repathCount > MAX_REPATHS) {
            println("[Traversal] repath refused (${if (supplier == null) "no route supplier" else "limit $MAX_REPATHS reached"}) - continuing on the existing path")
            retired.cancelRepathRequest()
            // Refused at the source from now on, so a node falls back to its own handling instead of asking again.
            retired.repathSupported = false
            return
        }
        val fresh = try {
            supplier()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: RuntimeException) {
            println("[Traversal] repath failed ($e) - continuing on the existing path")
            retired.cancelRepathRequest()
            return
        }
        if (fresh == null) {
            println("[Traversal] repath produced no nodes - continuing on the existing path")
            retired.cancelRepathRequest()
            return
        }
        if (retired.stopLatched()) {
            println("[Traversal] repath discarded - a stop condition fired while routing")
            retired.cancelRepathRequest()
            return
        }
        println("[Traversal] repath $repathCount/$MAX_REPATHS - driving the new path")
        processor = NodeListProcessor(finishedCondition, interruptCondition, fresh, noMovementTimeoutMillis, seams).also {
            it.setInterruptCondition(interruptName, interruptCondition)
            it.repathSupported = true
        }
    }

    companion object {
        const val MAX_REPATHS = 3
        private const val POLL_MS = 50
    }
}

/** A Kotlin condition on the state machine script, as the driver holds it: invoked with the script that drives it. */
@Suppress("UNCHECKED_CAST")
internal fun <T : StateMachineScript<T>> onScript(condition: T.() -> Boolean): (Script) -> Boolean = { (it as T).condition() }
