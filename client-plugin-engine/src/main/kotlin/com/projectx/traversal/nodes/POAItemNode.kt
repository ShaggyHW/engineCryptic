package com.projectx.traversal.nodes

import com.projectx.game.interfaces.IFSlot
import com.projectx.game.items.Item
import com.projectx.script.Script
import com.projectx.script.api.AttackMemory
import com.projectx.script.api.continueDialogueContaining
import com.projectx.script.api.dialogueOptionVisible
import com.projectx.script.api.equipment
import com.projectx.script.api.interfaces
import com.projectx.script.api.inventory
import com.projectx.traversal.TraversalNode
import com.projectx.util.FeatureIds
import com.projectx.webwalker.AbyssPassage
import world.gregs.voidps.cache.Cache
import world.gregs.voidps.gameval.Gameval
import world.gregs.voidps.type.Tile
import com.projectx.script.api.Area as WorldArea

/**
 * Teleports with an item that opens the choice menu, such as teleport jewellery or a Passage of the Abyss: click the
 * item with [action], pick [action2] in the menu, then [action3] on the destination page that replaces it. A null
 * stage is skipped, for one- or two-level menus.
 *
 * A Passage of the Abyss's first menu lists its configured slots, but some rows never expose their label in client
 * memory, so for one the [action2] row is picked by position from the item's own slot configuration.
 */
class POAItemNode(
    private val itemId: Int,
    private val action: String?,
    private val action2: String?,
    private val action3: String?,
    private val destination: WorldArea? = null,
    private val customReached: (() -> Boolean)? = null,
) : TraversalNode() {
    private enum class Stage { CLICK_ITEM, OPTION_2, OPTION_3, COMPLETE }

    private var nextClick = 0L
    private var stage = Stage.CLICK_ITEM

    /** Where the item was clicked; a teleport this far from it has landed. */
    private var takeOff: Tile? = null

    override suspend fun process(script: Script): Boolean {
        if (reached(script)) return false
        if (System.currentTimeMillis() < nextClick) return true

        when (stage) {
            Stage.CLICK_ITEM -> {
                if (action == null) return false
                val item = resolveItem()
                if (item == null) {
                    // The item may still be arriving, after banking.
                    script.delayBetween(150, 250)
                    return true
                }
                if (firstOptionStage() != Stage.COMPLETE && AttackMemory.isUnderAttack() &&
                    MenuGuard.reroute(this, "under attack: a hit closes its menu", ::requestTraversalRepath)
                ) {
                    return true
                }
                if (!item.click(action)) {
                    script.delayBetween(150, 250)
                    return true
                }
                takeOff = TraversalSupport.serverTileOrNull()
                val next = firstOptionStage()
                if (next == Stage.COMPLETE) {
                    awaitTeleport(script)
                } else if (!TraversalSupport.delayUntil(MENU_OPEN_MS) { traversalStopRequested() || menuOpen() }) {
                    return true
                }
                stage = next
            }
            Stage.OPTION_2 -> {
                if (action2 == null) {
                    stage = if (action3 != null) Stage.OPTION_3 else Stage.COMPLETE
                    return true
                }
                if (menuOpen() && selectFirstMenuOption(action2)) {
                    if (action3 != null) {
                        // The menu stays open; its destination page replaces this one.
                        TraversalSupport.delayUntil(MENU_OPEN_MS) { traversalStopRequested() || dialogueOptionVisible(action3) }
                        stage = Stage.OPTION_3
                    } else {
                        awaitTeleport(script)
                        stage = Stage.COMPLETE
                    }
                } else {
                    script.delayBetween(100, 200)
                }
            }
            Stage.OPTION_3 -> {
                if (action3 == null) {
                    stage = Stage.COMPLETE
                    return true
                }
                if (menuOpen() && continueDialogueContaining(action3)) {
                    awaitTeleport(script)
                    stage = Stage.COMPLETE
                } else {
                    script.delayBetween(100, 200)
                }
            }
            Stage.COMPLETE -> {
                // A landing just outside the destination box would otherwise stand out the no-movement timeout; it is
                // taken once the player stands still far from where the item was clicked.
                val here = TraversalSupport.serverTileOrNull()
                val from = takeOff
                if (from != null && here != null && (here.plane != from.plane || here.getLongestDelta(from) > LANDED_TILES) &&
                    !TraversalSupport.isMoving() && !TraversalSupport.isAnimating()
                ) {
                    println("[POAItemNode] $this landed at $here, outside its destination area $destination - taking it")
                    return false
                }
                script.delayBetween(100, 150)
            }
        }
        return true
    }

    private fun firstOptionStage(): Stage = when {
        action2 != null -> Stage.OPTION_2
        action3 != null -> Stage.OPTION_3
        else -> Stage.COMPLETE
    }

    private fun resolveItem(): Item? = backpackItem() ?: wornItem()

    private fun backpackItem(): Item? = runCatching { inventory.firstOrNull { it.id == itemId } }.getOrNull()

    private fun wornItem(): Item? = runCatching { equipment.firstOrNull { it.id == itemId } }.getOrNull()

    private fun selectFirstMenuOption(target: String): Boolean {
        if (AbyssPassage.isPassage(itemId)) selectAbyssPassageSlot(target)?.let { return it }
        return continueDialogueContaining(target)
    }

    /**
     * The first menu lists the passage's configured slots in slot order, so the target's position among them is its
     * row. Null when the item or a matching slot cannot be resolved; the caller then matches by text.
     */
    private fun selectAbyssPassageSlot(target: String): Boolean? {
        val item = backpackItem() ?: wornItem() ?: return null
        val configured = AbyssPassage.slotValues(item).filter { it != 0 }.map(::jewelleryName)
        val position = configured.indexOfFirst { nameMatches(it, target) }
        val rows = optionRows.get() ?: return null
        if (position < 0 || position >= rows.size) return null
        return rows[position].dialogueContinue()
    }

    private suspend fun awaitTeleport(script: Script) {
        TraversalSupport.awaitTeleport(TraversalSupport.serverTileOrNull()) { traversalStopRequested() || reached(script) }
        nextClick = System.currentTimeMillis() + TraversalSupport.clickPacingJitter()
    }

    override fun reached(script: Script): Boolean {
        customReached?.let { return it() }
        return TraversalSupport.inside(destination, TraversalSupport.serverTileOrNull())
    }

    override fun copy(): TraversalNode = POAItemNode(itemId, action, action2, action3, destination, customReached)

    override fun toString(): String = "[POAItem $itemId action=\"$action\" action2=\"$action2\" action3=\"$action3\"]"

    companion object {
        internal const val LANDED_TILES = 20
        private const val MENU_OPEN_MS = 5_000L
        private const val EMPTY_SLOT = "Empty slot"

        private val FALLBACK_JEWELLERY = mapOf(
            1 to "Ferocious ring",
            2 to "Games necklace",
            3 to "Ring of duelling",
            4 to "Ring of wealth",
            5 to "Skills necklace",
            6 to "Ring of slaying",
            7 to "Amulet of glory",
            8 to "Combat bracelet",
            9 to "Dig Site pendant",
            10 to "Ring of respawn",
            11 to "Enlightened amulet",
            12 to "Traveller's necklace",
            13 to "Delver's anklet",
        )

        private val menu = FeatureIds("POAItemNode") { Gameval.requireId(Gameval.INTERFACE, "modal_choice") }

        private val optionRows = FeatureIds("POAItemNode rows") {
            (1..6).map { n -> Gameval.requireComponentHash("modal_choice:option_click_$n").let { IFSlot(it ushr 16, it and 0xFFFF) } }
        }

        private val jewelleryEnum = FeatureIds("POAItemNode jewellery") { Gameval.requireId(Gameval.ENUM, "alch_onyx_teleport_item_names") }

        private fun menuOpen(): Boolean = menu.get()?.let { interfaces.isOpen(it) } == true

        /** A passage slot's raw value as its jewellery name, from the cache's name enum, else a mirror of it. */
        internal fun jewelleryName(slotValue: Int): String {
            val names = jewelleryEnum.get()?.let { id -> runCatching { Cache.enum(id) }.getOrNull() }
            names?.getString(slotValue)?.takeIf { it != "null" }?.let { return it }
            return FALLBACK_JEWELLERY[slotValue] ?: EMPTY_SLOT
        }

        /** Jewellery names compared ignoring case and straight or curly apostrophes. */
        internal fun nameMatches(name: String, target: String): Boolean = norm(name).contains(norm(target))

        private fun norm(s: String): String = s.replace('’', '\'').lowercase().trim()
    }
}
