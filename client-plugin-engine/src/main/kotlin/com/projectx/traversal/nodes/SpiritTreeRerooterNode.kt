package com.projectx.traversal.nodes

import com.projectx.game.items.Item
import com.projectx.quest.runtime.RecentChat
import com.projectx.script.Script
import com.projectx.script.api.equipment
import com.projectx.script.api.interfaces
import com.projectx.script.api.inventory
import com.projectx.traversal.TraversalNode
import com.projectx.util.FeatureIds
import world.gregs.voidps.gameval.Gameval
import com.projectx.script.api.Area as WorldArea

/**
 * Opens the spirit tree map from the Spirit tree re-rooter, backpack or worn; the next node, a click on the map, picks
 * the destination. With only inactive re-rooters held one is activated first. The game refuses an activation while an
 * active one exists anywhere, the bank included; that refusal latches the re-rooter out of routes until an active one
 * is in hand again. Charges need no tracking: an empty re-rooter disappears.
 */
class SpiritTreeRerooterNode(
    private val destination: WorldArea?,
    private val customReached: (() -> Boolean)?,
) : TraversalNode() {
    private var opens = 0
    private var activations = 0
    private var nextAttempt = 0L

    override suspend fun process(script: Script): Boolean {
        if (System.currentTimeMillis() < nextAttempt) return true
        if (mapOpen()) return true
        val device = activeDevice() ?: return activate()
        if (opens >= MAX_OPENS) {
            failTraversal("spirit tree re-rooter did not open the map after $opens clicks $this")
            return false
        }
        if (MenuGuard.beforeOpening(this, ::requestTraversalRepath)) return true
        opens++
        if (device.click(TELEPORT_OPTION)) {
            TraversalSupport.delayUntil(OPEN_WAIT_MS) { traversalStopRequested() || mapOpen() }
        }
        nextAttempt = System.currentTimeMillis() + 250
        return true
    }

    private suspend fun activate(): Boolean {
        val inactive = inactiveId()?.let(::backpackItem)
        if (inactive == null || activations >= MAX_OPENS) {
            if (!requestTraversalRepath()) {
                failTraversal("no spirit tree re-rooter to use ${if (inactive == null) "or activate " else "(activation failed) "}$this")
            }
            return false
        }
        activations++
        val clickMs = System.currentTimeMillis()
        if (inactive.click(ACTIVATE_OPTION)) {
            TraversalSupport.delayUntil(ACTIVATE_WAIT_MS) {
                traversalStopRequested() || activeDevice() != null || RecentChat.containsSince(ACTIVATE_REFUSED, clickMs)
            }
        }
        if (activeDevice() == null && RecentChat.containsSince(ACTIVATE_REFUSED, clickMs)) {
            device.activeOutOfReach = true
            println("[SpiritTreeRerooter] Activate refused (active re-rooter out of reach, banked?) - re-routing without it")
            if (!requestTraversalRepath()) {
                failTraversal("spirit tree re-rooter: the active one is out of reach and an inactive one cannot be activated $this")
            }
            return false
        }
        nextAttempt = System.currentTimeMillis() + 250
        return true
    }

    override fun reached(script: Script): Boolean {
        customReached?.let { return it() }
        if (mapOpen()) return true
        return TraversalSupport.inside(destination, TraversalSupport.serverTileOrNull())
    }

    override fun copy(): TraversalNode = SpiritTreeRerooterNode(destination, customReached)

    override fun toString(): String = "[SpiritTreeRerooter ${activeId() ?: "?"} op=\"$TELEPORT_OPTION\"]"

    companion object {
        private const val TELEPORT_OPTION = "Teleport"
        private const val ACTIVATE_OPTION = "Activate"
        private const val ACTIVATE_REFUSED = "already have an active version"
        private const val MAX_OPENS = 3

        // The map opened 300 ms after the click live; a few ticks covers a slow one.
        private const val OPEN_WAIT_MS = 2_400L
        private const val ACTIVATE_WAIT_MS = 3_000L

        private val mapInterface = FeatureIds("SpiritTreeRerooterNode") { Gameval.requireId(Gameval.INTERFACE, "spirittree2") }

        internal val device = ActivatedDevice("SpiritTreeRerooterNode", "invent_spirit_tree", "invent_spirit_tree_inactive")

        /** The active re-rooter's item id, or null when its name did not resolve. */
        @JvmStatic
        fun activeId(): Int? = device.activeId

        /**
         * Whether a route may use the re-rooter: an active one carried or worn, or an inactive one in the backpack to
         * activate, unless an activation was refused because the active one is banked.
         */
        @JvmStatic
        fun usable(): Boolean = device.usable()

        private fun inactiveId(): Int? = device.inactiveId

        private fun mapOpen(): Boolean = mapInterface.get()?.let { interfaces.isOpen(it) } == true

        private fun activeDevice(): Item? {
            val active = activeId() ?: return null
            return backpackItem(active) ?: runCatching { equipment.firstOrNull { it.id == active } }.getOrNull()
        }

        private fun backpackItem(id: Int): Item? = runCatching { inventory.firstOrNull { it.id == id } }.getOrNull()
    }
}
