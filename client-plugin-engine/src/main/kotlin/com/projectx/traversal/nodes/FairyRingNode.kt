package com.projectx.traversal.nodes

import com.projectx.game.input.Key
import com.projectx.game.interfaces.IFSlot
import com.projectx.game.items.Item
import com.projectx.game.nxt.entity.location.SceneObject
import com.projectx.game.nxt.interfaces.InterfaceComponent
import com.projectx.quest.runtime.RecentChat
import com.projectx.script.Script
import com.projectx.script.api.AttackMemory
import com.projectx.script.api.equipment
import com.projectx.script.api.findClosestObject
import com.projectx.script.api.interfaces
import com.projectx.script.api.inventory
import com.projectx.script.api.pressKey
import com.projectx.script.api.varcs
import com.projectx.traversal.TraversalNode
import com.projectx.util.FeatureIds
import world.gregs.voidps.gameval.Gameval
import world.gregs.voidps.type.Tile
import kotlin.math.abs
import kotlin.math.max

/**
 * Dials a fairy ring by its three-letter code and waits for the teleport to land. A ring the account has used before
 * is dialled with one click on its Travel Log row; any other is turned by hand, all three dials together. A portable
 * node opens the dial from the portable fairy ring instead of a ring object, activating an inactive one first.
 */
class FairyRingNode private constructor(
    private val fairyRingObjectId: Int,
    code: String,
    action: String?,
    private val objectSearchRadius: Int,
    private val customReached: (() -> Boolean)?,
    private val portable: Boolean,
) : TraversalNode() {
    private val code = code.lowercase()
    private val action = action.orEmpty()
    private val selectedTile: Tile? = FairyRing.forCode(this.code)?.tile

    private enum class Phase { OPEN_INTERFACE, SELECT_DESTINATION, WAIT_FOR_TELEPORT }

    private var phase = Phase.OPEN_INTERFACE
    private var nextAttempt = 0L
    private var portableOpens = 0
    private var portableActivations = 0
    private var arrived = false
    private var teleportPressedMs = 0L
    private var attackRerouteRefused = false

    /** Dials [code] at the nearest fairy ring, or from the portable ring when [portable]. */
    @JvmOverloads
    constructor(code: String, portable: Boolean = false, customReached: (() -> Boolean)? = null) :
        this(-1, code, "", DEFAULT_SEARCH_RADIUS, customReached, portable)

    @JvmOverloads
    constructor(
        fairyRingObjectId: Int,
        code: String,
        action: String?,
        objectSearchRadius: Int = DEFAULT_SEARCH_RADIUS,
        customReached: (() -> Boolean)? = null,
    ) : this(fairyRingObjectId, code, action, objectSearchRadius, customReached, false)

    /** A ring, not the portable one, takes a Dramen or Lunar staff wielded until A Fairy Tale III is done. */
    override fun needsDramenStaff(): Boolean = super.needsDramenStaff() || !portable && DramenStaff.fairyRingsNeedIt()

    override suspend fun process(script: Script): Boolean {
        if (reached(script)) {
            phase = Phase.OPEN_INTERFACE
            arrived = false
            return false
        }
        if (System.currentTimeMillis() < nextAttempt) return true
        if (phase != Phase.WAIT_FOR_TELEPORT && rerouteUnderAttack(script)) return false

        return when (phase) {
            Phase.OPEN_INTERFACE -> {
                if (isInterfaceOpen()) {
                    phase = Phase.SELECT_DESTINATION
                    nextAttempt = System.currentTimeMillis() + 250
                    return true
                }
                if (portable) return openFromItem(script)
                val ring = ringObject()
                if (ring == null) {
                    script.delayBetween(150, 350)
                    nextAttempt = System.currentTimeMillis() + 250
                    return true
                }
                val interacted = ring.action.isNotBlank() && ring.obj.interact(ring.action)
                if (!interacted) ring.obj.interact(0)
                script.delayBetween(450, 750)
                nextAttempt = System.currentTimeMillis() + 450
                return true
            }
            Phase.SELECT_DESTINATION -> {
                if (!isInterfaceOpen()) {
                    phase = Phase.OPEN_INTERFACE
                    nextAttempt = System.currentTimeMillis() + 250
                    return true
                }
                val selected = selectFairyRing(script)
                nextAttempt = System.currentTimeMillis() + 600
                if (selected) {
                    phase = Phase.WAIT_FOR_TELEPORT
                } else if (traversalStopRequested()) {
                    closeDial(script, "stop requested mid-dial")
                }
                return true
            }
            Phase.WAIT_FOR_TELEPORT -> return awaitLanding(script)
        }
    }

    private suspend fun awaitLanding(script: Script): Boolean {
        if (portable) {
            val window = (teleportPressedMs + CHARGE_USED_MS - System.currentTimeMillis()).coerceAtLeast(0L)
            if (!TraversalSupport.delayUntil(window) { traversalStopRequested() || landed() || chargeUsed() }) {
                println("[FairyRingNode] no charge used $CHARGE_USED_MS ms after the teleport press to $code - it did not go (dial closed?)")
                if (rerouteUnderAttack(script)) return false
                return dialAgain(250L)
            }
        }
        if (traversalStopRequested()) return true
        val landed = TraversalSupport.delayUntil(LANDING_TIMEOUT_MS) { traversalStopRequested() || landed() }
        if (traversalStopRequested()) return true
        if (!landed) {
            println("[FairyRingNode] no arrival at $code after ${LANDING_TIMEOUT_MS / 1000} s - dialing again")
            return dialAgain(450L)
        }
        awaitArrivalAnimation()
        arrived = true
        return true
    }

    private fun dialAgain(pauseMs: Long): Boolean {
        phase = Phase.OPEN_INTERFACE
        nextAttempt = System.currentTimeMillis() + pauseMs
        return true
    }

    private class Ring(val obj: SceneObject, val action: String)

    /** The ring object to click: by the node's id, or the nearest fairy ring when it has none. */
    private fun ringObject(): Ring? {
        if (fairyRingObjectId >= 0) {
            return findClosestObject(objectSearchRadius) { it.id == fairyRingObjectId }?.let { Ring(it, action) }
        }
        return findClosestObject(RING_NAME, objectSearchRadius)?.let { Ring(it, action.ifBlank { DEFAULT_ACTION }) }
    }

    private suspend fun openFromItem(script: Script): Boolean {
        val ring = portableRing() ?: return activateRing()
        if (portableOpens >= PORTABLE_MAX_OPENS) {
            failTraversal("portable fairy ring did not open the dial after $portableOpens clicks (out of charges?) $this")
            return false
        }
        portableOpens++
        if (ring.click(PORTABLE_OPTION)) {
            TraversalSupport.delayUntil(PORTABLE_OPEN_MS) { traversalStopRequested() || isInterfaceOpen() || AttackMemory.isUnderAttack() }
        }
        nextAttempt = System.currentTimeMillis() + 250
        return true
    }

    /**
     * A portable dial under attack: a hit closes the dial, and the planner leaves the portable ring out under attack,
     * so ask for that route now. True when the re-route was accepted; the dial is closed then.
     */
    private suspend fun rerouteUnderAttack(script: Script): Boolean {
        if (!portable || attackRerouteRefused || traversalStopRequested() || !AttackMemory.isUnderAttack()) return false
        if (!requestTraversalRepath()) {
            attackRerouteRefused = true
            println("[FairyRingNode] under attack at $this, no re-route possible - dialing anyway")
            return false
        }
        println("[FairyRingNode] under attack at $this: a hit closes the dial - re-routing without the portable ring")
        closeDial(script, "re-routing under attack")
        return true
    }

    private fun chargeUsed(): Boolean =
        RecentChat.containsSince(CHARGE_USED, teleportPressedMs) || RecentChat.containsSince(CHARGES_GONE, teleportPressedMs)

    private suspend fun activateRing(): Boolean {
        val inactive = portableDevice.inactiveId?.let(::backpack)
        if (inactive == null || portableActivations >= PORTABLE_MAX_OPENS) {
            if (!requestTraversalRepath()) {
                failTraversal("no portable fairy ring to use ${if (inactive == null) "or activate " else "(activation failed) "}$this")
            }
            return false
        }
        portableActivations++
        val clickMs = System.currentTimeMillis()
        if (inactive.click(ACTIVATE_OPTION)) {
            TraversalSupport.delayUntil(ACTIVATE_WAIT_MS) {
                traversalStopRequested() || portableRing() != null || RecentChat.containsSince(ACTIVATE_REFUSED, clickMs)
            }
        }
        if (portableRing() == null && RecentChat.containsSince(ACTIVATE_REFUSED, clickMs)) {
            portableDevice.activeOutOfReach = true
            println("[FairyRingNode] Activate refused (active portable fairy ring out of reach, banked?) - re-routing without it")
            if (!requestTraversalRepath()) {
                failTraversal("portable fairy ring: the active one is out of reach and an inactive one cannot be activated $this")
            }
            return false
        }
        nextAttempt = System.currentTimeMillis() + 250
        return true
    }

    /** Once dialled, reached only after the arrival animation: movement is blocked until it ends. */
    override fun reached(script: Script): Boolean {
        if (phase == Phase.WAIT_FOR_TELEPORT && !arrived) return false
        return landed()
    }

    private fun landed(): Boolean {
        customReached?.let { return it() }
        val here = TraversalSupport.serverTileOrNull() ?: return false
        if (TraversalSupport.isAnimating()) return false
        return selectedTile == here
    }

    private suspend fun awaitArrivalAnimation() {
        if (TraversalSupport.delayUntil(ARRIVAL_START_MS) { traversalStopRequested() || TraversalSupport.isAnimating() }) {
            TraversalSupport.delayUntil(ARRIVAL_END_MS) { traversalStopRequested() || !TraversalSupport.isAnimating() }
        }
    }

    /** Dials the code and presses teleport; gives up before any click once the traversal's stop has latched. */
    private suspend fun selectFairyRing(script: Script): Boolean {
        val dial = dials.get() ?: return false
        if (FairyRing.forCode(code) == null) {
            println("[FairyRingNode] Invalid fairy ring code: $code")
            return false
        }
        if (!dialMatches(code, dial) && selectFromTravelLog(code, dial)) {
            script.delayBetween(150, 300)
        }
        if (!isInterfaceOpen()) return false
        if (!dialAll(script, code, dial)) return false
        if (traversalStopRequested()) return false
        dial.teleport.click(1)
        teleportPressedMs = System.currentTimeMillis()
        script.delayBetween(300, 500)
        return true
    }

    /**
     * Turns all three dials at once: each round clicks every dial still short of its letter, then waits once. At most
     * three clicks per round stays under the click rate that crashes the client.
     */
    private suspend fun dialAll(script: Script, code: String, dial: DialIds): Boolean {
        val remaining = IntArray(3) { clicksFor(code, it, dial) }
        var clicked = true
        while (clicked) {
            clicked = false
            for (ring in remaining.indices) {
                if (remaining[ring] == 0) continue
                if (traversalStopRequested()) return false
                dial.turn[ring].click(1)
                remaining[ring]--
                clicked = true
            }
            if (clicked) script.delayBetween(300, 500)
        }
        return true
    }

    private fun dialMatches(code: String, dial: DialIds): Boolean = (0..2).all { clicksFor(code, it, dial) == 0 }

    /**
     * Turns every dial to [code] with one click on its Travel Log row's "Select". False when the ring is not in the log
     * (never used) or the dials did not follow; the hand dial then turns them from wherever they stand.
     */
    private suspend fun selectFromTravelLog(code: String, dial: DialIds): Boolean {
        val slot = travelLogSlot(code, dial)
        if (slot < 0 || traversalStopRequested()) return false
        IFSlot(dial.travelLogSelect.interfaceId, dial.travelLogSelect.componentId, slot).click(1)
        TraversalSupport.delayUntil(TRAVEL_LOG_SELECT_MS) { traversalStopRequested() || dialMatches(code, dial) || !isInterfaceOpen() }
        val set = dialMatches(code, dial)
        if (!set && !traversalStopRequested()) {
            println("[FairyRingNode] Travel Log select of $code (slot $slot) did not turn the dials - dialing by hand")
        }
        return set
    }

    /** Escape closes the dial after a stop or a re-route, so the next route's first click is not eaten by it. */
    private suspend fun closeDial(script: Script, why: String) {
        if (!isInterfaceOpen()) return
        println("[FairyRingNode] $why - closing the fairy ring interface")
        script.pressKey(Key.ESCAPE)
        TraversalSupport.delayUntil(CLOSE_WAIT_MS) { !isInterfaceOpen() }
    }

    override fun copy(): TraversalNode = FairyRingNode(fairyRingObjectId, code, action, objectSearchRadius, customReached, portable)

    override fun toString(): String = "[FairyRing ${if (portable) "portable" else fairyRingObjectId.toString()} -> $code]"

    private class DialIds(
        val offsets: IntArray,
        val turn: List<IFSlot>,
        val teleport: IFSlot,
        val travelLogCode: IFSlot,
        val travelLogSelect: IFSlot,
    )

    companion object {
        private const val DEFAULT_SEARCH_RADIUS = 30
        private const val RING_NAME = "Fairy ring"
        private const val DEFAULT_ACTION = "Configure"
        private const val PORTABLE_OPTION = "Teleport"
        private const val ACTIVATE_OPTION = "Activate"
        private const val ACTIVATE_REFUSED = "already have an active version"
        private const val PORTABLE_MAX_OPENS = 3
        private const val PORTABLE_OPEN_MS = 2_400L
        private const val ACTIVATE_WAIT_MS = 3_000L
        private const val LANDING_TIMEOUT_MS = 15_000L
        private const val CLOSE_WAIT_MS = 1_200L

        // The arrival animation starts on the landing tick or the next one.
        private const val ARRIVAL_START_MS = 1_200L
        private const val ARRIVAL_END_MS = 5_000L

        // The dials turn on the select click's frame.
        private const val TRAVEL_LOG_SELECT_MS = 1_200L
        private const val TRAVEL_LOG_SELECT_OPTION = "Select"

        // The portable ring's charge message comes on the landing tick, about 2-2.6 s after the press; none by then
        // means the press never teleported, as when a hit closed the dial.
        internal const val CHARGE_USED_MS = 3_600L
        private const val CHARGE_USED = "use a charge of your portable fairy ring"
        private const val CHARGES_GONE = "portable fairy ring runs out of charges"

        private val RING_CODE = Regex("[a-d][i-l][p-s]")
        private val TAG = Regex("<[^>]*>")
        private val WHITESPACE = Regex("\\s+")

        private val dials = FeatureIds("FairyRingNode") {
            DialIds(
                offsets = intArrayOf(
                    Gameval.requireId(Gameval.VAR_CLIENT, "fairyrings_left_offset"),
                    Gameval.requireId(Gameval.VAR_CLIENT, "fairyrings_middle_offset"),
                    Gameval.requireId(Gameval.VAR_CLIENT, "fairyrings_right_offset"),
                ),
                turn = listOf(slot("fairyrings:left_acw"), slot("fairyrings:mid_acw"), slot("fairyrings:right_acw")),
                teleport = slot("fairyrings:teleport_button"),
                travelLogCode = slot("fairyrings:code_text_build"),
                travelLogSelect = slot("fairyrings:text_click"),
            )
        }

        private fun slot(name: String): IFSlot = Gameval.requireComponentHash(name).let { IFSlot(it ushr 16, it and 0xFFFF) }

        /** Dials [code] from the portable fairy ring, backpack first, then worn. */
        @JvmStatic
        fun portable(code: String, customReached: (() -> Boolean)? = null): FairyRingNode =
            FairyRingNode(-1, code, "", 0, customReached, true)

        /**
         * Whether a route may use the portable fairy ring: an active ring carried or worn, or an inactive one in the
         * backpack to activate, unless an Activate was refused because the active ring is banked.
         */
        @JvmStatic
        fun portableRingUsable(): Boolean = portableDevice.usable()

        internal val portableDevice = ActivatedDevice("FairyRingNode", "invent_fairy_ring", "invent_fairy_ring_inactive")

        /** The single ring code a Travel Log row's text names ("A I P" is "aip"); null for a multi-hop route. */
        @JvmStatic
        fun travelLogCode(text: String?): String? {
            if (text == null) return null
            val letters = text.replace(TAG, "").replace(WHITESPACE, "").lowercase()
            return letters.takeIf { RING_CODE.matches(it) }
        }

        /** The dial is up while its teleport button is drawn: one closed mid-turn stays attached with all hidden. */
        private fun isInterfaceOpen(): Boolean {
            val teleport = dials.get()?.teleport ?: return false
            return interfaces.isOpen(teleport.interfaceId) &&
                interfaces.getComponent(teleport.interfaceId, teleport.componentId)?.onScreen == true
        }

        /** Anticlockwise clicks to turn dial [ring] (0 left, 1 middle, 2 right) to its letter of [code]. */
        private fun clicksFor(code: String, ring: Int, dial: DialIds): Int {
            val desired = rotationFor(ring, code[ring])
            val current = runCatching { varcs.getVar(dial.offsets[ring]) }.getOrDefault(0)
            return Math.floorMod(desired - current, 360) / 90
        }

        private fun rotationFor(ring: Int, letter: Char): Int {
            val letters = when (ring) {
                0 -> "abcd"
                1 -> "ijkl"
                else -> "pqrs"
            }
            return letters.indexOf(letter).coerceAtLeast(0) * 90
        }

        /**
         * The slot of the Travel Log "Select" button whose row shows [code], or -1 when no shown row does. Rows are
         * matched by geometry (the code text's vertical centre inside the button), so the sort order does not matter;
         * zero-height children are rings not yet used.
         */
        private fun travelLogSlot(code: String, dial: DialIds): Int {
            val codes = component(dial.travelLogCode) ?: return -1
            val selects = component(dial.travelLogSelect) ?: return -1
            if (!selects.onScreen) return -1
            val row = travelLogEntries(codes).firstOrNull { it.screenHeight > 0 && travelLogCode(it.text) == code } ?: return -1
            val centreY = row.parentRelY + row.screenHeight / 2
            travelLogEntries(selects).forEachIndexed { index, button ->
                if (button.screenHeight <= 0 || centreY < button.parentRelY || centreY >= button.parentRelY + button.screenHeight) return@forEachIndexed
                if (TRAVEL_LOG_SELECT_OPTION !in button.ops) return@forEachIndexed
                val slot = button.slotId
                return if (slot in 0 until 0xFFFF) slot else index
            }
            return -1
        }

        private fun travelLogEntries(component: InterfaceComponent): List<InterfaceComponent> =
            component.createdChildren.ifEmpty { component.dynamicChildren.ifEmpty { component.staticChildren } }

        private fun component(slot: IFSlot): InterfaceComponent? =
            interfaces.getComponent(slot.interfaceId, slot.componentId) ?: interfaces.getComponentRaw(slot.interfaceId, slot.componentId)

        private fun portableRing(): Item? {
            val active = portableDevice.activeId ?: return null
            return backpack(active) ?: runCatching { equipment.firstOrNull { it.id == active } }.getOrNull()
        }

        private fun backpack(id: Int): Item? = runCatching { inventory.firstOrNull { it.id == id } }.getOrNull()
    }
}
