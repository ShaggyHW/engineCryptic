package com.projectx.traversal.nodes

import com.projectx.game.items.Item
import com.projectx.script.api.Equipment
import com.projectx.script.api.equipmentItem
import com.projectx.script.api.varps
import com.projectx.util.FeatureIds
import world.gregs.voidps.gameval.Gameval

/**
 * Skillcape perks stored in the max cape's three perk slots. A stored perk shows as an op on whatever cape is worn
 * ("Slayer", "Dungeoneering", "Quest point") and opens the same list as the real cape's "Activate", so a cape row is
 * usable through the perk without the cape carried.
 */
object SkillcapePerks {
    /** Perk ids, the keys of the skillcape perk enum. */
    const val SLAYER = 20
    const val DUNGEONEERING = 25
    const val QUEST_POINT = 31

    private class Perk(val id: Int, val rowName: String, val op: String)

    private val PERKS = listOf(
        Perk(SLAYER, "slayer", "Slayer"),
        Perk(DUNGEONEERING, "dungeoneering", "Dungeoneering"),
        Perk(QUEST_POINT, "quest", "Quest point"),
    )

    private val CAPE_ROW_OPTIONS = setOf("activate", "teleport")

    private val storedVarbits = FeatureIds("SkillcapePerks") {
        (1..3).map { Gameval.requireId(Gameval.VARBIT, "skillcape_perks_stored$it") }
    }

    /** [perk] is stored and the worn cape shows its op: the same live read the item node clicks through. */
    fun usable(perk: Int): Boolean {
        if (!stored(perk)) return false
        val op = PERKS.firstOrNull { it.id == perk }?.op ?: return false
        val cape = wornCape() ?: return false
        return runCatching { cape.slot.optionIndexOf(op) > 0 }.getOrDefault(false)
    }

    fun stored(perk: Int): Boolean =
        storedVarbits.get()?.any { runCatching { varps.getVarBit(it) }.getOrDefault(0) == perk } == true

    fun wornCape(): Item? = runCatching { equipmentItem(Equipment.Slot.CAPE) }.getOrNull()

    /**
     * The worn-cape op standing in for a cape row: [rowName] is the row's item name ("slayer", "dungeoneering cape"),
     * [option] its teleport op. Null when the row is no cape row or that perk is not stored.
     */
    fun opFor(rowName: String?, option: String?): String? {
        if (rowName == null || option == null || option.lowercase() !in CAPE_ROW_OPTIONS) return null
        val name = rowName.trim().lowercase()
        return PERKS.firstOrNull { (name == it.rowName || name == "${it.rowName} cape") && stored(it.id) }?.op
    }
}
