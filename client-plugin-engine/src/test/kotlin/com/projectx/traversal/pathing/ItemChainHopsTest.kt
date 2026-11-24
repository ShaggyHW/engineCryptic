package com.projectx.traversal.pathing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * An item step keeps the item hops later in its chain: `metadata.steps[0]` is the parent item itself and is left out,
 * a later item is a real hop.
 */
class ItemChainHopsTest {

    private val modify = """
        {"found":true,"cost":5750.0,"actions":[
         {"type":"item","from":{"min":[3284,3378,0],"max":[3284,3378,0]},
          "to":{"min":[3340,3004,0],"max":[3340,3004,0]},"cost_ms":5750.0,
          "metadata":{"db_row":{"action":"Modify","item_id":18809,"dest_min_x":3338,"dest_max_x":3342,
            "dest_min_y":3003,"dest_max_y":3006,"dest_plane":0,"next_node_type":"ifslot","next_node_id":137},
           "steps":[
           {"kind":"item","id":264,"db_row":{"item_id":18809,"action":"Modify","dest_min_x":3338,"dest_max_x":3342,
            "dest_min_y":3003,"dest_max_y":3006,"dest_plane":0,"next_node_type":"ifslot","next_node_id":137}},
           {"kind":"ifslot","id":137,"db_row":{"interface_id":720,"component_id":23,"slot_id":-1,"click_id":0,
            "next_node_type":"ifslot","next_node_id":136}},
           {"kind":"ifslot","id":136,"db_row":{"interface_id":1188,"component_id":18,"slot_id":-1,"click_id":0,
            "next_node_type":"item","next_node_id":255}},
           {"kind":"item","id":255,"db_row":{"item_id":18811,"action":"Break","dest_min_x":3338,"dest_max_x":3342,
            "dest_min_y":3003,"dest_max_y":3006,"dest_plane":0}}]}}]}
    """.trimIndent()

    private val bigBook = """
        {"found":true,"cost":11100.0,"actions":[
         {"type":"item","from":{"min":[3200,3200,0],"max":[3200,3200,0]},
          "to":{"min":[3684,2958,0],"max":[3684,2958,0]},"cost_ms":11100.0,
          "metadata":{"steps":[
           {"kind":"item","id":363,"db_row":{"item_id":37729,"action":"Teleport","dest_min_x":3684,"dest_max_x":3684,
            "dest_min_y":2958,"dest_max_y":2958,"dest_plane":0,"next_node_type":"ifslot","next_node_id":155}},
           {"kind":"ifslot","id":155,"db_row":{"interface_id":720,"component_id":1,"slot_id":-1,"click_id":0,
            "next_node_type":"item","next_node_id":362}},
           {"kind":"item","id":362,"db_row":{"item_id":42379,"action":"Teleport","dest_min_x":3684,"dest_max_x":3684,
            "dest_min_y":2958,"dest_max_y":2958,"dest_plane":0,"next_node_type":"ifslot","next_node_id":154}},
           {"kind":"ifslot","id":154,"db_row":{"interface_id":720,"component_id":1,"slot_id":-1,"click_id":0,
            "dest_min_x":3684,"dest_max_x":3684,"dest_min_y":2958,"dest_max_y":2958,"dest_plane":0}}]}}]}
    """.trimIndent()

    private val journal = """
        {"found":true,"cost":3350.0,"actions":[
         {"type":"item","from":{"min":[3200,3200,0],"max":[3200,3200,0]},
          "to":{"min":[3050,3245,0],"max":[3053,3248,0]},"cost_ms":3350.0,
          "metadata":{"steps":[
           {"kind":"item","id":119,"db_row":{"item_id":37729,"action":"Teleport","dest_min_x":3050,"dest_max_x":3053,
            "dest_min_y":3245,"dest_max_y":3248,"dest_plane":0,"next_node_type":"ifslot","next_node_id":23}},
           {"kind":"ifslot","id":23,"db_row":{"interface_id":720,"component_id":1,"slot_id":-1,"click_id":0}}]}}]}
    """.trimIndent()

    private fun chainOf(json: String, parentId: Int, parentOp: String): List<Step> {
        val item = assertIs<Step.ItemClick>(ResultsParser.parse(json).steps.single())
        assertEquals(parentId, item.id)
        assertEquals(parentOp, item.option)
        return item.chain
    }

    @Test
    fun `a house tablet's Modify chain ends on the new tablet's Break`() {
        val chain = chainOf(modify, 18809, "Modify")
        assertEquals(3, chain.size)
        val tablet = assertIs<Step.ItemClick>(chain[2])
        assertEquals(18811, tablet.id)
        assertEquals("Break", tablet.option)
    }

    @Test
    fun `the Big Book o' Piracy stays in the middle of the Arc journal chain`() {
        val chain = chainOf(bigBook, 37729, "Teleport")
        assertEquals(3, chain.size)
        assertIs<Step.IFSlot>(chain[0])
        assertEquals(42379, assertIs<Step.ItemClick>(chain[1]).id)
        assertIs<Step.IFSlot>(chain[2])
    }

    @Test
    fun `a plain journal chain is just its menu option`() {
        assertIs<Step.IFSlot>(chainOf(journal, 37729, "Teleport").single())
    }
}
