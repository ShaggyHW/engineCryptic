package com.projectx.traversal.pathing

import com.projectx.traversal.TraversalBuilder
import com.projectx.traversal.TraversalNode
import com.projectx.traversal.nodes.ItemNode
import com.projectx.traversal.nodes.SpiritTreeRerooterNode
import com.projectx.traversal.pathing.mappers.StepMapperRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** An item step on the active re-rooter maps to a [SpiritTreeRerooterNode] and keeps its map click; others stay [ItemNode]s. */
class SpiritTreeRerooterTest {

    private val rerooter = """
        {"found":true,"cost":4950.0,"actions":[
         {"type":"item","from":{"min":[3200,3200,0],"max":[3200,3200,0]},
          "to":{"min":[2461,3443,0],"max":[2463,3445,0]},"cost_ms":4950.0,
          "metadata":{"steps":[
           {"kind":"item","db_row":{"item_id":41078,"action":"Teleport","dest_min_x":2461,"dest_max_x":2463,
            "dest_min_y":3443,"dest_max_y":3445,"dest_plane":0,"next_node_type":"ifslot","next_node_id":123}},
           {"kind":"ifslot","db_row":{"interface_id":1145,"component_id":13,"slot_id":-1,"click_id":1}}]}}]}
    """.trimIndent()

    private val seed = """
        {"found":true,"cost":3000.0,"actions":[
         {"type":"item","from":{"min":[3200,3200,0],"max":[3200,3200,0]},
          "to":{"min":[2330,3172,0],"max":[2330,3172,0]},"cost_ms":3000.0,
          "metadata":{"steps":[
           {"kind":"item","db_row":{"item_id":39784,"action":"Activate","dest_min_x":2330,"dest_max_x":2330,
            "dest_min_y":3172,"dest_max_y":3172,"dest_plane":0,"next_node_type":"ifslot","next_node_id":23}},
           {"kind":"ifslot","db_row":{"interface_id":720,"component_id":20,"slot_id":-1,"click_id":0}}]}}]}
    """.trimIndent()

    @Test
    fun `the active re-rooter maps to its own node`() {
        val steps = ResultsParser.parse(rerooter).steps
        assertEquals(1, steps.size)
        val click = assertIs<Step.ItemClick>(steps[0])
        assertEquals(SpiritTreeRerooterNode.activeId(), click.id)
        assertNotNull(click.id)
        assertTrue(click.chain.any { it is Step.IFSlot && it.interfaceId == 1145 && it.component == 13 })
        val node = mapped(click)
        assertIs<SpiritTreeRerooterNode>(node)
        assertIs<SpiritTreeRerooterNode>(node.copy())
    }

    @Test
    fun `another item still maps to an item node`() {
        assertIs<ItemNode>(mapped(ResultsParser.parse(seed).steps[0]))
    }

    private fun mapped(step: Step): TraversalNode {
        val builder = TraversalBuilder<Nothing>()
        StepMapperRegistry.apply(step, builder, NavOptions())
        return builder.buildNodes().head
    }
}
