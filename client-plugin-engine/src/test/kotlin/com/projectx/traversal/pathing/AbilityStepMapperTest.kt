package com.projectx.traversal.pathing

import com.projectx.traversal.TraversalBuilder
import com.projectx.traversal.TraversalNode
import com.projectx.traversal.WalkEquivalent
import com.projectx.traversal.nodes.DiveNode
import com.projectx.traversal.nodes.SurgeNode
import com.projectx.traversal.pathing.mappers.StepMapperRegistry
import world.gregs.voidps.type.Tile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Planner-placed Surge and Dive steps: a node of their own only when the client does not place its hops. */
class AbilityStepMapperTest {
    private val from = Tile.of(3200, 3200, 0)
    private val to = Tile.of(3210, 3200, 0)

    private fun mapped(step: Step, clientPlaced: Boolean): TraversalNode {
        val options = NavOptions().apply {
            clientPlacedAbilities = clientPlaced
            surge = SurgeOpt(true, 1, 10_200L)
            dive = DiveOpt(true, 10_200L)
        }
        val builder = TraversalBuilder<Nothing>()
        StepMapperRegistry.apply(step, builder, options)
        return builder.buildNodes().head
    }

    @Test
    fun `with the walker placing hops a planner's surge or dive is walked`() {
        assertFalse(mapped(Step.Surge(from, to, 10), clientPlaced = true) is SurgeNode)
        assertFalse(mapped(Step.Dive(from, to, 10), clientPlaced = true) is DiveNode)
    }

    @Test
    fun `without it the planner's hops become surge and dive nodes that are walked past and never aimed through`() {
        val surge = assertIs<SurgeNode>(mapped(Step.Surge(from, to, 10), clientPlaced = false))
        val dive = assertIs<DiveNode>(mapped(Step.Dive(from, to, 10), clientPlaced = false))
        for (node in listOf(surge, dive)) {
            assertEquals(listOf(to), (node as WalkEquivalent).routeTiles())
            assertTrue(node.passableNow())
            assertFalse(node.walkThrough())
        }
        assertIs<SurgeNode>(surge.copy())
        assertIs<DiveNode>(dive.copy())
    }
}
