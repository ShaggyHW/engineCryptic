package com.projectx.script.api

import com.projectx.game.nxt.entity.GroundItem
import com.projectx.script.Script
import com.projectx.util.gaussian
import world.gregs.voidps.gameval.Gameval

private val AREA_LOOT by lazy { Gameval.requireId(Gameval.INTERFACE, "toplevel_v2_loot") }

/** Whether the backpack can take item [id]: a free slot, or a stack of it already there when it is [stackable]. */
fun hasRoomFor(id: Int, stackable: Boolean): Boolean = !inventory.isFull || (stackable && inventory.hasItem(id))

/** The ground stacks within [radius] of ([x], [y], [plane]) whose name [wanted] accepts and that fit the backpack, nearest first. */
fun wantedGroundItems(x: Int, y: Int, plane: Int, radius: Int, wanted: (String) -> Boolean): List<GroundItem> {
    val here = localPlayer.tile
    return groundItems
        .filter { it.tile.within(x, y, plane, radius) && wanted(it.name) && hasRoomFor(it.id, it.getDef().isStackable()) }
        .sortedBy { it.tile.distanceTo(here) }
}

// Coins go to the money pouch, so a take is judged by the loot shrinking, never by the backpack growing.
private class LootLeft(val ground: Int, val window: Int) {
    fun shrankFrom(before: LootLeft) = ground < before.ground || window < before.window
}

private fun lootLeft(x: Int, y: Int, plane: Int, radius: Int, wanted: (String) -> Boolean) = LootLeft(
    ground = groundItems.filter { it.tile.within(x, y, plane, radius) && wanted(it.name) }.sumOf { it.amount },
    window = if (areaLootOpen) areaLoot.filter { wanted(it.name) }.sumOf { it.amount } else 0,
)

/**
 * One take of the loot [wanted] names within [radius] of ([x], [y], [plane]): an item from Area Loot when its window
 * opens (Loot All when [everything]), otherwise the nearest stack off the ground. True once the loot shrank. Call it
 * again for the next item, after a pause.
 */
@JvmSynthetic
suspend fun Script.takeLootOnce(x: Int, y: Int, plane: Int, radius: Int, everything: Boolean, wanted: (String) -> Boolean): Boolean {
    val nearest = wantedGroundItems(x, y, plane, radius, wanted).firstOrNull() ?: return false
    val before = lootLeft(x, y, plane, radius, wanted)
    val fired = when {
        !openAreaLootWindow() -> nearest.interact("Take")
        everything -> lootAllAreaLoot()
        else -> {
            val item = areaLoot.firstOrNull { wanted(it.name) && hasRoomFor(it.id, it.getDef().isStackable()) }
            if (item == null) nearest.interact("Take") else item.click("Take") || item.click(1)
        }
    }
    if (!fired) return false
    waitThenDelayUntil(gaussian(400L, 6_400L), gaussian(3000L, 360_000L)) { lootLeft(x, y, plane, radius, wanted).shrankFrom(before) }
    return lootLeft(x, y, plane, radius, wanted).shrankFrom(before)
}

private suspend fun Script.openAreaLootWindow(): Boolean {
    if (interfaces.isOpen(AREA_LOOT)) return true
    if (!openAreaLoot()) return false
    delayUntil(gaussian(1800L, 160_000L)) { interfaces.isOpen(AREA_LOOT) }
    if (!interfaces.isOpen(AREA_LOOT)) return false
    delay(320, 6_400)
    return true
}

private const val PICKUP_RANGE = 20
private const val MAX_PICKUPS = 28

internal object GroundPickup {
suspend fun Script.pickUpNamed(items: Array<out String>): Boolean {
    val wanted = { name: String -> items.any { it.equals(name, ignoreCase = true) } }
    var picked = false
    repeat(MAX_PICKUPS) {
        val here = localPlayer.tile
        val stack = wantedGroundItems(here.x, here.y, here.plane, PICKUP_RANGE, wanted).firstOrNull() ?: return picked
        val before = lootLeft(here.x, here.y, here.plane, PICKUP_RANGE, wanted)
        if (!stack.interact("Take")) return picked
        waitThenDelayUntil(gaussian(600L, 10_000L), gaussian(4200L, 640_000L)) {
            lootLeft(here.x, here.y, here.plane, PICKUP_RANGE, wanted).shrankFrom(before)
        }
        if (!lootLeft(here.x, here.y, here.plane, PICKUP_RANGE, wanted).shrankFrom(before)) return picked
        picked = true
        delay(220, 2_500)
    }
    return picked
}
}
