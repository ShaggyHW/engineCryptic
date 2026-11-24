package com.projectx.traversal.nodes

import com.projectx.game.items.Item
import com.projectx.script.api.equipment
import com.projectx.script.api.inventory
import com.projectx.util.FeatureIds
import world.gregs.voidps.gameval.Gameval

/**
 * A teleport device that is carried active or inactive, such as the portable fairy ring. The game refuses an
 * activation while an active one exists anywhere, the bank included; that refusal latches the device out of routes
 * ([activeOutOfReach]) until an active one is in hand again.
 */
internal class ActivatedDevice(feature: String, active: String, inactive: String) {
    private val ids = FeatureIds(feature) { Gameval.requireId(Gameval.OBJ, active) to Gameval.requireId(Gameval.OBJ, inactive) }

    val activeId: Int? get() = ids.get()?.first

    val inactiveId: Int? get() = ids.get()?.second

    @Volatile
    var activeOutOfReach = false

    /** An active one carried or worn, or an inactive one in the backpack to activate first. */
    fun usable(backpack: List<Item>, worn: List<Item>): Boolean {
        val (active, inactive) = ids.get() ?: return false
        if (backpack.any { it.id == active } || worn.any { it.id == active }) {
            activeOutOfReach = false
            return true
        }
        return !activeOutOfReach && backpack.any { it.id == inactive }
    }

    fun usable(): Boolean = runCatching { usable(inventory.toList(), equipment.toList()) }.getOrDefault(false)
}
