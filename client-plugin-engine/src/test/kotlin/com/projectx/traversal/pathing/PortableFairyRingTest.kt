package com.projectx.traversal.pathing

import com.projectx.traversal.TraversalBuilder
import com.projectx.traversal.TraversalNode
import com.projectx.traversal.nodes.FairyRingNode
import com.projectx.traversal.pathing.mappers.StepMapperRegistry
import world.gregs.voidps.type.Tile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** A portable fairy ring hop (no ring object) parses and maps to a portable [FairyRingNode]; a ring hop is unchanged. */
class PortableFairyRingTest {

    private val portable = """
        {"found":true,"cost":8650.0,"actions":[
         {"type":"fairy_ring","from":{"min":[2954,3224,0],"max":[2954,3224,0]},
          "to":{"min":[2801,3003,0],"max":[2801,3003,0]},"code":"ckr","cost_ms":8050.0,
          "metadata":{"action":"Configure","destination_code":"ckr","portable":true}},
         {"type":"move","to":[2802,3003,0],"cost_ms":300.0}]}
    """.trimIndent()

    private val ring = """
        {"found":true,"cost":8700.0,"actions":[
         {"type":"fairy_ring","from":{"min":[2996,3114,0],"max":[2996,3114,0],"object_id":14064},
          "to":{"min":[2801,3003,0],"max":[2801,3003,0]},"code":"ckr","cost_ms":7500.0,
          "metadata":{"action":"Configure","destination_code":"ckr","source_code":"aiq"}}]}
    """.trimIndent()

    @Test
    fun `a portable ring hop maps to a portable node`() {
        val steps = ResultsParser.parse(portable).steps
        assertEquals(2, steps.size)
        val hop = assertIs<Step.FairyRing>(steps[0])
        assertTrue(hop.portable)
        assertEquals(-1, hop.objectId)
        assertEquals("ckr", hop.code)
        assertEquals(Tile.of(2801, 3003, 0), hop.to)
        val node = mapped(hop)
        assertIs<FairyRingNode>(node)
        assertTrue(node.toString().contains("portable"))
    }

    @Test
    fun `a ring hop keeps its ring object`() {
        val hop = assertIs<Step.FairyRing>(ResultsParser.parse(ring).steps[0])
        assertFalse(hop.portable)
        assertEquals(14064, hop.objectId)
        assertTrue(mapped(hop).toString().contains("14064"))
    }

    private fun mapped(step: Step): TraversalNode {
        val builder = TraversalBuilder<Nothing>()
        StepMapperRegistry.apply(step, builder, NavOptions())
        return builder.buildNodes().head
    }
}
