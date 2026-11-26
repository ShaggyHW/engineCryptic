package com.projectx.script.api

import com.projectx.game.items.Item
import com.projectx.script.Script
import com.projectx.util.gaussian

/**
 * Drops every backpack item [which] picks, one at a time, waiting for each slot to empty before the next, and returns
 * how many were dropped. Java: `Wait.dropItems`.
 */
@JvmSynthetic
suspend fun Script.dropItems(which: (Item) -> Boolean): Int {
    var dropped = 0
    for (item in inventory.filter { it.amount > 0 && which(it) }) {
        val slot = item.slot.slotId
        if (inventory[slot]?.id != item.id) continue
        if (!item.click("Drop")) {
            delay(280, 9_000)
            continue
        }
        delayUntil(gaussian(1_500L, 120_000L)) { inventory[slot]?.id != item.id }
        if (inventory[slot]?.id != item.id) dropped++
        delay(160, 2_500)
    }
    return dropped
}
