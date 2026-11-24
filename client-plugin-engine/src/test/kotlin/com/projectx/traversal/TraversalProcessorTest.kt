package com.projectx.traversal

import com.projectx.script.JavaState
import com.projectx.script.JavaStateMachineScript
import com.projectx.script.Script
import com.projectx.script.State
import com.projectx.script.Wait
import com.projectx.script.StateMachineScript
import kotlinx.coroutines.runBlocking
import world.gregs.voidps.type.Tile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class TraversalProcessorTest {

    class TestScript : StateMachineScript<TestScript>() {
        var done = false
        var interrupted = false

        override fun getStartState(): State<TestScript> = Idle()
    }

    class Idle : State<TestScript>() {
        override suspend fun TestScript.checkNext(): State<TestScript>? = null
        override suspend fun TestScript.stateLoop() {}
    }

    class Other : State<TestScript>() {
        override suspend fun TestScript.checkNext(): State<TestScript>? = null
        override suspend fun TestScript.stateLoop() {}
    }

    private open class Step(
        val name: String,
        var isReached: Boolean = false,
        var working: Boolean = true,
        val onProcess: Step.() -> Unit = {},
    ) : TraversalNode() {
        var processed = 0
        val stops = mutableListOf<TraversalOutcome>()

        override fun traversalStopped(outcome: TraversalOutcome) {
            stops += outcome
        }

        override suspend fun process(script: Script): Boolean {
            processed++
            onProcess()
            return working
        }

        override fun reached(script: Script): Boolean = isReached

        override fun copy(): TraversalNode = this

        fun fail(reason: String) = failTraversal(reason)

        fun repath() = requestTraversalRepath()

        override fun toString() = name
    }

    private class Walk(name: String, val tiles: List<Tile>, val stop: Boolean = false) : Step(name), WalkEquivalent {
        override fun routeTiles(): List<Tile> = tiles
        override fun passableNow(): Boolean = true
        override fun holdsStop(): Boolean = stop
    }

    private class FakeStaff(var carried: Boolean = true, var wieldWorks: Boolean = true) : DramenStaffHands {
        var worn = false
        var wields = 0
        var restores = 0
        override var restorePending = false

        override fun carried() = carried
        override fun worn() = worn

        override suspend fun wield(script: Script): Boolean {
            wields++
            if (!wieldWorks) return false
            worn = true
            restorePending = true
            return true
        }

        override suspend fun restore(script: Script) {
            restores++
            worn = false
            restorePending = false
        }
    }

    private val player = Tile.of(3200, 3200, 0)

    private fun list(vararg nodes: TraversalNode): TraversalNodeList =
        TraversalNodeList(nodes.first()).also { list -> nodes.drop(1).forEach { list.add(it) } }

    private fun processor(
        nodes: TraversalNodeList,
        interrupt: (TestScript.() -> Boolean)? = null,
        timeout: Long = 0,
        staff: DramenStaffHands = FakeStaff(carried = false),
    ) = TraversalProcessor<TestScript>({ done }, interrupt, nodes, timeout).also {
        it.playerTile = { player }
        it.core.seams.dramenStaff = staff
    }

    private fun Traversal<TestScript>.offline(staff: DramenStaffHands = FakeStaff(carried = false)) = apply {
        seams.playerTile = { player }
        seams.dramenStaff = staff
    }

    @Test
    fun `the finished condition stops it having arrived`() = runBlocking {
        val script = TestScript()
        val p = processor(list(Step("a")))
        assertTrue(p.process(script))
        script.done = true
        assertFalse(p.process(script))
        assertEquals(TraversalOutcome.FINISHED, p.outcome)
        assertTrue(p.outcome.isArrival)
    }

    @Test
    fun `the interrupt condition stops it cut short`() = runBlocking {
        val script = TestScript()
        val p = processor(list(Step("a")), interrupt = { interrupted })
        script.interrupted = true
        assertFalse(p.process(script))
        assertTrue(p.wasInterrupted)
        assertFalse(p.outcome.isArrival)
    }

    @Test
    fun `several reached nodes are passed in one go`() = runBlocking {
        val last = Step("c")
        val p = processor(list(Step("a", isReached = true), Step("b", isReached = true), last))
        assertTrue(p.process(TestScript()))
        assertSame(last, p.currentNode)
        assertEquals(1, last.processed)
    }

    @Test
    fun `a last node that is reached completes and one that gives up stalls`() = runBlocking {
        val done = processor(list(Step("a", isReached = true)))
        assertFalse(done.process(TestScript()))
        assertEquals(TraversalOutcome.COMPLETED, done.outcome)

        val stuck = processor(list(Step("a", working = false)))
        assertFalse(stuck.process(TestScript()))
        assertEquals(TraversalOutcome.STALLED, stuck.outcome)
    }

    @Test
    fun `a node that cannot complete fails the traversal`() = runBlocking {
        val p = processor(list(Step("door") { fail("locked") }, Step("after")))
        assertFalse(p.process(TestScript()))
        assertEquals(TraversalOutcome.FAILED, p.outcome)
    }

    @Test
    fun `a repath is refused without a supplier and taken with one`() = runBlocking {
        var accepted = false
        val p = processor(list(Step("hop") { accepted = repath() }))
        assertTrue(p.process(TestScript()))
        assertFalse(accepted)

        val supported = processor(list(Step("hop") { accepted = repath() })).also { it.repathSupported = true }
        assertFalse(supported.process(TestScript()))
        assertTrue(accepted)
        assertEquals(TraversalOutcome.REPATH_REQUESTED, supported.outcome)
    }

    @Test
    fun `standing still past the timeout times out`() = runBlocking {
        val p = processor(list(Step("a")), timeout = 1)
        assertTrue(p.process(TestScript()))
        Thread.sleep(5)
        assertFalse(p.process(TestScript()))
        assertEquals(TraversalOutcome.TIMED_OUT, p.outcome)
    }

    @Test
    fun `nodes see a stop that has fired`() = runBlocking {
        val script = TestScript()
        var seen = false
        val node = object : TraversalNode() {
            override suspend fun process(script: Script): Boolean {
                (script as TestScript).done = true
                Thread.sleep(350)
                seen = traversalStopRequested()
                return true
            }

            override fun reached(script: Script) = false
            override fun copy(): TraversalNode = this
        }
        val p = processor(list(node))
        p.process(script)
        assertTrue(seen)
    }

    @Test
    fun `the staff is wielded before a node that needs it and the node runs on the same pass`() = runBlocking {
        val staff = FakeStaff()
        val ring = Step("ring").also { it.requireDramenStaff() }
        val p = processor(list(ring), staff = staff)
        assertTrue(p.process(TestScript()))
        assertEquals(1, staff.wields)
        assertEquals(1, ring.processed)
    }

    @Test
    fun `a staff that will not go on fails the node after three tries`() = runBlocking {
        val staff = FakeStaff(wieldWorks = false)
        val ring = Step("ring").also { it.requireDramenStaff() }
        val p = processor(list(ring), staff = staff)
        assertTrue(p.process(TestScript()))
        assertTrue(p.process(TestScript()))
        assertFalse(p.process(TestScript()))
        assertEquals(TraversalOutcome.FAILED, p.outcome)
        assertEquals(3, staff.wields)
        assertEquals(0, ring.processed)
    }

    @Test
    fun `no staff carried re-routes or fails`() = runBlocking {
        val failing = processor(list(Step("ring").also { it.requireDramenStaff() }))
        assertFalse(failing.process(TestScript()))
        assertEquals(TraversalOutcome.FAILED, failing.outcome)

        val rerouting = processor(list(Step("ring").also { it.requireDramenStaff() })).also { it.repathSupported = true }
        assertFalse(rerouting.process(TestScript()))
        assertEquals(TraversalOutcome.REPATH_REQUESTED, rerouting.outcome)
    }

    @Test
    fun `the staff swap is put back once no later node needs it`() = runBlocking {
        val staff = FakeStaff()
        val ring = Step("ring").also { it.requireDramenStaff() }
        val after = Step("after")
        val p = processor(list(ring, after), staff = staff)
        assertTrue(p.process(TestScript()))
        ring.isReached = true
        assertTrue(p.process(TestScript()))
        assertEquals(1, staff.restores)
        assertEquals(0, after.processed)
        assertTrue(p.process(TestScript()))
        assertEquals(1, after.processed)
    }

    @Test
    fun `a stop puts the staff swap back, a re-route does not`() = runBlocking {
        val staff = FakeStaff().also { it.restorePending = true }
        val script = TestScript().also { it.done = true }
        val p = processor(list(Step("a").also { it.requireDramenStaff() }), staff = staff)
        assertFalse(p.process(script))
        assertEquals(1, staff.restores)

        val kept = FakeStaff().also { it.restorePending = true }
        val repath = processor(list(Step("hop") { repath() }.also { it.requireDramenStaff() }), staff = kept).also { it.repathSupported = true }
        kept.worn = true
        assertFalse(repath.process(TestScript()))
        assertEquals(TraversalOutcome.REPATH_REQUESTED, repath.outcome)
        assertEquals(0, kept.restores)
    }

    @Test
    fun `route flags survive the copy the processor takes`() {
        val original = Step("ring").also { it.requireDramenStaff() }
        val copy = object : TraversalNode() {
            override suspend fun process(script: Script) = true
            override fun reached(script: Script) = false
            override fun copy(): TraversalNode = this
        }
        val node = object : TraversalNode() {
            override suspend fun process(script: Script) = true
            override fun reached(script: Script) = false
            override fun copy(): TraversalNode = copy
        }
        node.requireDramenStaff()
        val copied = TraversalNodeList(original).add(node).copy()
        assertTrue(copied.head.needsDramenStaff())
        assertSame(copy, copied.head.next)
        assertTrue(copy.needsDramenStaff())
    }

    @Test
    fun `the tick poll evaluates the conditions at most once per server tick`() = runBlocking {
        var tick = 10L
        var finishedPolls = 0
        var interruptPolls = 0
        val script = TestScript()
        val p = TraversalProcessor<TestScript>({ finishedPolls++; done }, { interruptPolls++; interrupted }, list(Step("a")), 0).also {
            it.playerTile = { player }
            it.core.seams.dramenStaff = FakeStaff(carried = false)
            it.core.seams.serverTick = { tick }
        }
        assertTrue(p.process(script))
        assertEquals(1, finishedPolls)
        assertEquals(1, interruptPolls)

        assertFalse(p.pollConditionsOnTick())
        assertEquals(1, finishedPolls)

        tick++
        assertFalse(p.pollConditionsOnTick())
        assertEquals(2, finishedPolls)
        assertEquals(2, interruptPolls)

        script.done = true
        assertFalse(p.pollConditionsOnTick())
        tick++
        assertTrue(p.pollConditionsOnTick())
        assertEquals(3, finishedPolls)
        assertEquals(2, interruptPolls)

        tick++
        assertTrue(p.pollConditionsOnTick())
        assertEquals(3, finishedPolls)
        assertFalse(p.process(script))
        assertEquals(TraversalOutcome.FINISHED, p.outcome)
    }

    @Test
    fun `the state tick hook polls the conditions while the state waits`() = runBlocking {
        var tick = 0L
        var polls = 0
        val script = TestScript()
        val t = Traversal(Other(), { polls++; done }, list(Step("a"))).offline()
        t.seams.serverTick = { tick }
        assertNull(t.checkNextState(script))
        assertEquals(1, polls)
        script.done = true
        tick++
        t.tickLoop(script)
        assertEquals(2, polls)
        assertTrue(t.checkNextState(script) is Other)
        assertEquals(2, polls)
        assertEquals(TraversalOutcome.FINISHED, t.outcome)

        var offPolls = 0
        val off = Traversal(Other(), { offPolls++; false }, list(Step("a"))).offline().pollOnTick(false)
        off.seams.serverTick = { tick }
        assertNull(off.checkNextState(script))
        tick++
        off.tickLoop(script)
        assertEquals(1, offPolls)
    }

    @Test
    fun `a node holding a tour stop is never skipped`() = runBlocking {
        val here = listOf(player)
        val start = Walk("start", emptyList())
        val ahead = Walk("ahead", here)
        val skipped = processor(list(start, Walk("between", emptyList()), ahead))
        skipped.process(TestScript())
        assertSame(ahead, skipped.currentNode)

        val held = Walk("stop", emptyList(), stop = true)
        val kept = processor(list(Walk("start", emptyList()), held, Walk("ahead", here)))
        kept.process(TestScript())
        assertEquals("start", (kept.currentNode as Walk).name)

        val holding = processor(list(Walk("holding", emptyList(), stop = true), Walk("ahead", here)))
        holding.process(TestScript())
        assertEquals("holding", (holding.currentNode as Walk).name)
    }

    @Test
    fun `every node hears the stop once`() = runBlocking {
        val a = Step("a")
        val b = Step("b")
        val script = TestScript()
        val p = processor(list(a, b))
        assertTrue(p.process(script))
        script.done = true
        assertFalse(p.process(script))
        assertFalse(p.process(script))
        assertEquals(listOf(TraversalOutcome.FINISHED), a.stops)
        assertEquals(listOf(TraversalOutcome.FINISHED), b.stops)
    }

    @Test
    fun `a labelled interrupt routes to its own state and a finish to next`() = runBlocking {
        val script = TestScript()
        val flee = Other()
        val next = Idle()
        val t = Traversal(next, { done }, list(Step("a"))).offline().interruptWhen("under attack", { interrupted }, flee)
        script.interrupted = true
        assertSame(flee, t.checkNextState(script))
        assertTrue(t.wasInterrupted)

        val finished = Traversal(next, { done }, list(Step("a")), { interrupted }).offline().onInterrupt(flee)
        script.done = true
        assertSame(next, finished.checkNextState(script))
        assertEquals(TraversalOutcome.FINISHED, finished.outcome)
    }

    @Test
    fun `a hand-built traversal has no movement timeout unless given one`() = runBlocking {
        assertEquals(15_000L, Traversal.ROUTE_NO_MOVEMENT_TIMEOUT_MILLIS)
        val script = TestScript()
        val untimed = Traversal(Idle(), { done }, list(Step("a")), { false }).offline()
        assertNull(untimed.checkNextState(script))
        Thread.sleep(5)
        assertNull(untimed.checkNextState(script))
        assertEquals(TraversalOutcome.RUNNING, untimed.outcome)

        val timed = Traversal(Idle(), { done }, list(Step("a")), 1L).offline()
        assertNull(timed.checkNextState(script))
        Thread.sleep(5)
        assertTrue(timed.checkNextState(script) is Idle)
        assertEquals(TraversalOutcome.TIMED_OUT, timed.outcome)
    }

    @Test
    fun `re-routes stop at the limit and are then refused at the source`() = runBlocking {
        var accepted = 0
        val script = TestScript()
        fun hopList() = list(Step("hop") { if (repath()) accepted++ })
        val t = Traversal(Idle(), { done }, hopList()).offline().repathWith { hopList() }
        repeat(Traversal.MAX_REPATHS + 1) { assertNull(t.checkNextState(script)) }
        assertEquals(Traversal.MAX_REPATHS + 1, accepted)
        assertNull(t.checkNextState(script))
        assertEquals(Traversal.MAX_REPATHS + 1, accepted)
    }

    @Test
    fun `a legacy traversal without next runs inline to its outcome`() = runBlocking {
        assertFailsWith<IllegalArgumentException> { TraversalBuilder<TestScript>().build { done } }
        val inline = Traversal.traversal<TestScript>({ done }) { walkExact(player) { true } }.offline()
        inline.run(TestScript())
        assertEquals(TraversalOutcome.COMPLETED, inline.outcome)
    }

    @Test
    fun `the builder marks route nodes from an index and counts them`() {
        val builder = TraversalBuilder<TestScript>().apply {
            walkExact(player) { true }
            walkExact(player) { true }
            walkExact(player) { true }
        }
        assertEquals(3, builder.nodeCount())
        val nodes = builder.requireDramenStaffFrom(1).buildNodes()
        assertFalse(nodes.head.needsDramenStaff())
        assertTrue(nodes.head.next!!.needsDramenStaff())
        assertTrue(nodes.head.next!!.next!!.needsDramenStaff())
    }

    class JavaScriptUnderTest : JavaStateMachineScript<JavaScriptUnderTest>() {
        var done = false
        override fun getStartState(): JavaState<JavaScriptUnderTest> = Waiting
    }

    object Waiting : JavaState<JavaScriptUnderTest> {
        override fun checkNext(script: JavaScriptUnderTest): JavaState<JavaScriptUnderTest>? = null
        override fun onLoop(script: JavaScriptUnderTest): Wait = Wait.ms(0)
    }

    @Test
    fun `a java state machine drives a traversal and moves on`() = runBlocking {
        val script = JavaScriptUnderTest()
        val t = JavaTraversal(Waiting, list(Step("a")), { script.done })
        t.driver.seams.playerTile = { player }
        t.driver.seams.dramenStaff = FakeStaff(carried = false)
        assertNull(t.checkNext(script))
        (t.onLoop(script) as Wait.Call<*>).action(script)
        assertNull(t.checkNext(script))
        script.done = true
        (t.onLoop(script) as Wait.Call<*>).action(script)
        assertSame(Waiting, t.checkNext(script))
        assertEquals(TraversalOutcome.FINISHED, t.outcome)
    }
}
