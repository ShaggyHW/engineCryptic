package com.projectx.script.api

import com.projectx.game.items.Item
import com.projectx.script.Script
import com.projectx.util.gaussian

const val MAGIC_NOTEPAPER = "Magic notepaper"

/** An unnoted item that has a noted form, which is what magic notepaper turns it into. */
val Item.canBeNoted: Boolean
    get() = getDef().let { !it.noted && it.noteId != -1 }

/** Uses magic notepaper from the backpack on [item]; true once fewer unnoted copies of it are left. */
@JvmSynthetic
suspend fun Script.noteWithMagicNotepaper(item: Item): Boolean {
    val paper = inventory.getItem(MAGIC_NOTEPAPER) ?: return false
    val before = inventory.count(item.id)
    if (!paper.useOn(item)) return false
    waitThenDelayUntil(gaussian(350L, 6_400L), gaussian(2400L, 250_000L)) { inventory.count(item.id) < before }
    return inventory.count(item.id) < before
}
