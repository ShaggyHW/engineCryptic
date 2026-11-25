package com.projectx.traversal.pathing

import com.projectx.script.State
import com.projectx.script.StateMachineScript
import com.projectx.traversal.TraversalBuilder
import com.projectx.traversal.TraversalNode
import com.projectx.traversal.TraversalNodeList
import com.projectx.traversal.WalkEquivalent
import com.projectx.traversal.nodes.ChebychevNode
import com.projectx.traversal.nodes.DoorNode
import com.projectx.traversal.nodes.ObjectNode
import com.projectx.traversal.nodes.PathNode
import com.projectx.traversal.pathing.mappers.MoveMapper
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import world.gregs.voidps.type.Tile
import java.net.InetSocketAddress
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Route to nodes: hops walked along their geometry, edges carried on nodes, the tail walk, and which nodes gave up. */
class PathingTraversalTest {

    class TestScript : StateMachineScript<TestScript>() {
        override fun getStartState(): State<TestScript> = Idle()
    }

    class Idle : State<TestScript>() {
        override suspend fun TestScript.checkNext(): State<TestScript>? = null
        override suspend fun TestScript.stateLoop() {}
    }

    private var server: HttpServer? = null

    @AfterTest
    fun stop() {
        server?.stop(0)
    }

    @Test
    fun `a hop is walked along the route's geometry, or the straight line without one`() {
        val from = Tile.of(3334, 3383, 0)
        val to = Tile.of(3337, 3383, 0)
        val around = listOf(Tile.of(3334, 3384, 0), Tile.of(3335, 3385, 0), Tile.of(3336, 3384, 0), to)
        val walked = MoveMapper.abilityStepsAsMoves(listOf(Step.Move(from), Step.Dive(from, to, 3, around))).map { (it as Step.Move).tile }
        assertEquals(listOf(from) + around, walked)
        val straight = MoveMapper.abilityStepsAsMoves(listOf(Step.Move(from), Step.Surge(from, to, 3))).map { (it as Step.Move).tile }
        assertEquals(listOf(from, Tile.of(3335, 3383, 0), Tile.of(3336, 3383, 0), to), straight)
        val elsewhere = MoveMapper.abilityStepsAsMoves(listOf(Step.Surge(null, Tile.of(3337, 3383, 1), null)))
        assertEquals(listOf<Step>(Step.Move(Tile.of(3337, 3383, 1))), elsewhere)
    }

    @Test
    fun `planned hops become one contiguous walk, a hop with no start from the step before it`() {
        val steps = ArrayList<Step>()
        for (x in 0..5) steps += Step.Move(Tile.of(3200 + x, 3200, 0))
        steps += Step.Surge(Tile.of(3205, 3200, 0), Tile.of(3213, 3204, 0), 10)
        steps += Step.Move(Tile.of(3214, 3204, 0))
        steps += Step.Dive(null, Tile.of(3220, 3210, 0), 8)
        val walked = MoveMapper.abilityStepsAsMoves(steps).map { assertIs<Step.Move>(it).tile }
        assertTrue(walked.zipWithNext().all { (a, b) -> a.getLongestDelta(b) == 1 }, walked.toString())
        assertEquals(Tile.of(3220, 3210, 0), walked.last())
    }

    @Test
    fun `keypoints keep both sides of a plane change and the last tile`() {
        val tiles = (0..20).map { Tile.of(3200 + it, 3200, 0) } + Tile.of(3220, 3200, 1) + Tile.of(3221, 3200, 1)
        val keys = MoveMapper.extractKeypoints(tiles, MoveMapper.DEFAULT_SAMPLE_DISTANCE)
        assertEquals(Tile.of(3200, 3200, 0), keys.first())
        assertTrue(Tile.of(3220, 3200, 0) in keys && Tile.of(3220, 3200, 1) in keys)
        assertEquals(Tile.of(3221, 3200, 1), keys.last())
    }

    @Test
    fun `moves become one walk node, a PathNode over the same tiles without Chebyshev`() {
        val moves = listOf(Step.Move(Tile.of(3200, 3200, 0)), Step.Move(Tile.of(3201, 3200, 0)), Step.Move(Tile.of(3202, 3200, 0)))
        val chebyshev = TraversalBuilder<Nothing>().also { PathingTraversal.mapSteps(moves, it, NavOptions()) }.buildNodes()
        assertIs<ChebychevNode>(chebyshev.head)
        assertEquals(moves.map { it.tile }, (chebyshev.head as WalkEquivalent).routeTiles())
        val plain = TraversalBuilder<Nothing>().also { PathingTraversal.mapSteps(moves, it, NavOptions().apply { preferChebyshev = false }) }.buildNodes()
        assertIs<PathNode>(plain.head)
        assertEquals(moves.map { it.tile }, (plain.head as WalkEquivalent).routeTiles())
    }

    @Test
    fun `action nodes carry their edge, walks none, and only actions give up their edge`() {
        val macro = NavMacro("object", 7)
        val steps = listOf(
            Step.Move(Tile.of(3200, 3200, 0)),
            Step.Loc(1, null, "Climb-up", 10, null, NavArea(3201, 3200, 3201, 3200, 0), macro = macro),
        )
        val nodes = all(TraversalBuilder<Nothing>().also { PathingTraversal.mapSteps(steps, it, NavOptions()) }.buildNodes())
        val walk = nodes.first()
        val obj = assertIs<ObjectNode>(nodes.last())
        assertNull(walk.navMacro)
        assertEquals(macro, obj.navMacro)
        assertEquals(macro, TraversalNodeList(obj).copy().head.navMacro)
        assertTrue(PathingTraversal.gaveUp(obj))
        assertFalse(PathingTraversal.gaveUp(walk))
    }

    @Test
    fun `a door node gives up its edge though it walks`() {
        val door = Step.Door("IN", DoorInfo(realIdClosed = 3), Tile.of(3200, 3201, 0), Tile.of(3200, 3200, 0), macro = NavMacro("door", 1))
        val node = TraversalBuilder<Nothing>().also { PathingTraversal.mapSteps(listOf(door), it, NavOptions()) }.buildNodes().head
        assertIs<DoorNode>(node)
        assertTrue(PathingTraversal.gaveUp(node))
    }

    @Test
    fun `the tail walks on to the goal only when the route stops short of it`() = runBlocking {
        val goal = Tile.of(3205, 3200, 0)
        val short = routed(goal, """{"found":true,"cost":1.0,"actions":[{"type":"move","to":[3201,3200,0]},{"type":"move","to":[3203,3200,0]}]}""")
        val walks = all(short.nodes)
        assertEquals(2, walks.size)
        assertEquals(goal, (walks.last() as WalkEquivalent).routeTiles().last())
        val onGoal = routed(goal, """{"found":true,"cost":1.0,"actions":[{"type":"move","to":[3201,3200,0]},{"type":"move","to":[3205,3200,0]}]}""")
        assertEquals(1, all(onGoal.nodes).size)
    }

    @Test
    fun `no route is a failure of its own`() = runBlocking {
        val failure = runCatching { routed(Tile.of(3205, 3200, 0), """{"found":false,"actions":[]}""") }.exceptionOrNull()
        assertIs<PathingTraversal.NoRouteException>(failure)
    }

    private suspend fun routed(goal: Tile, answer: String): PathingTraversal.Routed {
        server?.stop(0)
        val http = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/route") { exchange ->
                val bytes = answer.toByteArray()
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            start()
        }
        server = http
        val options = NavOptions(Tile.of(3200, 3200, 0), goal).apply {
            serviceBaseUrl = "http://127.0.0.1:${http.address.port}"
            hops = false
            useItems = false
            seed = 1
        }
        return PathingTraversal.routeNodes<Nothing>(TestScript(), options, null, emptySet()) { _, _ -> }
    }

    private fun all(list: TraversalNodeList): List<TraversalNode> = generateSequence(list.head) { it.next }.toList()
}
