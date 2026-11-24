package com.projectx.traversal.nodes

import com.projectx.game.interfaces.IFSlot
import com.projectx.script.Script
import com.projectx.script.api.AttackMemory
import com.projectx.script.api.interfaces
import com.projectx.traversal.TraversalNode
import com.projectx.script.api.Area as WorldArea
import com.projectx.util.Area as FlatArea

/** Clicks an interface slot: an option index, or dialogue-continue when [optionNum] is 0. */
class IFSlotNode private constructor(
    private val ifSlot: IFSlot,
    private val optionNum: Int,
    private val destination: NodeDestination?,
    private val customReached: (() -> Boolean)?,
) : TraversalNode() {

    constructor(
        ifSlot: IFSlot,
        optionNum: Int = 1,
        destination: FlatArea? = null,
        customReached: (() -> Boolean)? = null
    ) : this(ifSlot, optionNum, NodeDestination.of(destination), customReached)

    constructor(ifSlot: IFSlot, optionNum: Int, destination: WorldArea?, customReached: (() -> Boolean)? = null) :
        this(ifSlot, optionNum, NodeDestination.of(destination), customReached)

    private var nextClick = 0L

    /** When this node first ran: its menu may open a moment after the click before it. */
    private var firstProcessAt = 0L

    override suspend fun process(script: Script): Boolean {
        val now = System.currentTimeMillis()
        if (firstProcessAt == 0L) firstProcessAt = now
        if (now < nextClick) return true
        val menu = ifSlot.interfaceId
        // Under attack a shut menu is one a hit closed: re-route without the menu teleports. Only under attack, since a
        // message box after a transport may never open while the transport plays.
        if (!interfaces.isOpen(menu) && AttackMemory.attackedWithin(ATTACKED_WITHIN_MS)) {
            if (now - firstProcessAt < MENU_OPEN_GRACE_MS) {
                TraversalSupport.delayUntil(TraversalSupport.TICK_MS) { traversalStopRequested() || interfaces.isOpen(menu) }
                return true
            }
            if (MenuGuard.reroute(this, "under attack: a hit closed interface $menu before $this was clicked", ::requestTraversalRepath)) return true
        }
        val clicked = if (optionNum != 0) ifSlot.click(optionNum) else ifSlot.dialogueContinue()
        if (!clicked) return true
        // A paged dialogue keeps its interface open; the bound covers the server's reply.
        TraversalSupport.delayUntil(2 * TraversalSupport.TICK_MS) { traversalStopRequested() || reached(script) || !interfaces.isOpen(menu) }
        nextClick = System.currentTimeMillis() + TraversalSupport.clickPacingJitter()
        return false
    }

    override fun reached(script: Script): Boolean {
        customReached?.let { return it() }
        return destination?.contains(TraversalSupport.serverTileOrNull()) == true
    }

    override fun copy(): TraversalNode = IFSlotNode(ifSlot, optionNum, destination, customReached)

    override fun toString() = "[${ifSlot.interfaceId} - ${ifSlot.componentId} - ${ifSlot.slotId} - $optionNum]"

    companion object {
        /** How long a menu may take to open after the node before it; still shut after this under attack, a hit shut it. */
        const val MENU_OPEN_GRACE_MS = 3 * TraversalSupport.TICK_MS
        private const val ATTACKED_WITHIN_MS = 5_000L
    }
}
