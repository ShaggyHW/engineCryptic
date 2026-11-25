package com.projectx.traversal.nodes

import com.projectx.script.api.TeleportSpell
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TeleportNodesTest {

    @Test
    fun `a spell's Cast action is the default cast`() {
        val spell = TeleportSpell.entries.first()
        assertNull(SpellNode(spell, "Cast", null, null).action)
        assertNull(SpellNode(spell, "  ", null, null).action)
        assertEquals("Marigold Farm", SpellNode(spell, " Marigold Farm ", null, null).action)
        assertEquals("Marigold Farm", (SpellNode(spell, "Marigold Farm", null, null).copy() as SpellNode).action)
    }

    @Test
    fun `passage jewellery names match ignoring case and apostrophes`() {
        assertTrue(POAItemNode.nameMatches("Traveller’s necklace", "traveller's necklace"))
        assertTrue(POAItemNode.nameMatches("Ring of duelling", "duelling"))
        assertFalse(POAItemNode.nameMatches("Games necklace", "Ring of duelling"))
    }

    @Test
    fun `a skillcape perk only stands in for cape rows' teleport ops`() {
        assertNull(SkillcapePerks.opFor("slayer", "Wear"))
        assertNull(SkillcapePerks.opFor("amulet of glory", "Teleport"))
        assertNull(SkillcapePerks.opFor(null, "Teleport"))
    }

    @Test
    fun `fairy rings ask for the staff only when not portable`() {
        assertFalse(FairyRingNode.portable("aip").needsDramenStaff())
        assertEquals("[FairyRing portable -> aip]", FairyRingNode.portable("AIP").toString())
        assertEquals("[FairyRing 7 -> cks]", FairyRingNode(7, "CKS", "Use").toString())
    }
}
