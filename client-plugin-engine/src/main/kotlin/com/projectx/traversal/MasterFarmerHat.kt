package com.projectx.traversal

import com.projectx.game.items.Item
import com.projectx.script.Script
import com.projectx.script.api.ServerTick
import com.projectx.script.api.bank
import com.projectx.script.api.bankOpen
import com.projectx.script.api.continueDialogueContaining
import com.projectx.script.api.dialogueOptionVisible
import com.projectx.script.api.dialogueOptions
import com.projectx.script.api.equipment
import com.projectx.script.api.inCombat
import com.projectx.script.api.interfaces
import com.projectx.script.api.inventory
import com.projectx.script.api.isDialogOpen
import com.projectx.script.api.isLoggedIn
import com.projectx.script.api.localPlayer
import com.projectx.util.FeatureIds
import world.gregs.voidps.gameval.Gameval
import world.gregs.voidps.type.Tile

/**
 * How many Herblore Habitat teleports the worn Master farmer hat has left, for the planner's `hasMasterFarmerHabitat`.
 *
 * Each teleport spends a juju teleport spiritbag from the bank, and the client keeps no var for that count. It shows in
 * two places only: the hat's Teleport chooser, whose line reads "Herblore Habitat (31)", and the bank grid while the
 * bank is open. Both are read every server tick, and a landed Habitat teleport takes one off in between; a bag used
 * from the backpack lands there too and leaves the banked count alone. Until a number has been seen the count is
 * [UNKNOWN] and the hat is left out of routes; [refreshIfUnknown] reads the chooser once before a route is planned.
 */
object MasterFarmerHat {
    const val UNKNOWN = -1
    const val UNLIMITED = Int.MAX_VALUE

    private const val CHOOSER_INTERFACE = 720
    private val HABITAT_LINE = Regex("Herblore Habitat \\((\\d+|unlimited)\\)", RegexOption.IGNORE_CASE)

    private const val LAND_X = 2953
    private const val LAND_Y = 2933
    private const val LAND_PLANE = 0
    private const val LAND_RADIUS = 5

    // The cast took seven ticks to land when measured.
    private const val LANDING_WINDOW_TICKS = 15L

    private const val CHOOSER_OPEN_MS = 3_000L
    private const val CHOOSER_CLOSE_MS = 1_500L

    private class Ids(val hat: Int, val bag: Int, val cast: Int)

    private val ids = FeatureIds("MasterFarmerHat") {
        Ids(
            hat = Gameval.requireId(Gameval.OBJ, "pof_rewards_trh_master_head"),
            bag = Gameval.requireId(Gameval.OBJ, "ecosystem_teleport_bag"),
            cast = Gameval.requireId(Gameval.SEQ, "pog_player_2014_spirit_tree_teleport"),
        )
    }

    private val lock = Any()

    @Volatile
    private var count = UNKNOWN
    private var account: String? = null
    private var castTick = -1L
    private var packBagsAtCast = -1
    private var lastObservedTick = -1L

    val spiritbagId: Int? get() = ids.get()?.bag

    /** True when the hat is worn and at least one Habitat teleport is known to be left. */
    fun habitatUsable(): Boolean = worn() && habitatCount() > 0

    /** Habitat teleports left: a count, [UNLIMITED], or [UNKNOWN]. */
    fun habitatCount(): Int = synchronized(lock) {
        syncAccount()
        count
    }

    /** Called every client frame on the game thread; observes once per server tick. */
    internal fun tick() {
        val tick = runCatching { ServerTick.count }.getOrDefault(-1L)
        if (tick < 0 || tick == lastObservedTick) return
        lastObservedTick = tick
        observeTick(tick)
    }

    /** One server tick's look at the chooser, the bank and a teleport in flight; never throws. */
    internal fun observeTick(tick: Long) {
        runCatching {
            if (!isLoggedIn()) return
            synchronized(lock) {
                syncAccount()
                readChooser()
                readBank()
                trackTeleport(tick)
            }
        }
    }

    /**
     * Reads the count off the hat's chooser when it is not known yet: Teleport, read the Habitat line, Cancel. One
     * server round trip, nothing spent. Skipped unless the hat is worn, the player is out of combat and no dialogue or
     * bank is up. Script thread only.
     */
    @JvmSynthetic
    suspend fun refreshIfUnknown(script: Script): Int {
        if (habitatCount() != UNKNOWN) return habitatCount()
        runCatching {
            if (inCombat || isDialogOpen() || bankOpen) return UNKNOWN
            val hat = wornHat() ?: return UNKNOWN
            if (!hat.click("Teleport")) return UNKNOWN
            script.delayUntil(CHOOSER_OPEN_MS) { chooserShowsHabitat() }
            if (!chooserShowsHabitat()) return UNKNOWN
            synchronized(lock) { readChooser() }
            continueDialogueContaining("Cancel")
            script.delayUntil(CHOOSER_CLOSE_MS) { !interfaces.isOpen(CHOOSER_INTERFACE) }
        }
        return habitatCount()
    }

    /** The count on a chooser line such as "3. Herblore Habitat (31)": the number, [UNLIMITED], or [UNKNOWN]. */
    internal fun parseHabitatLine(line: String?): Int {
        val value = line?.let { HABITAT_LINE.find(it) }?.groupValues?.get(1) ?: return UNKNOWN
        if (value.equals("unlimited", ignoreCase = true)) return UNLIMITED
        return value.toIntOrNull() ?: UNKNOWN
    }

    internal fun inLandingBox(tile: Tile): Boolean = inLandingBox(tile.x, tile.y, tile.plane)

    internal fun inLandingBox(x: Int, y: Int, plane: Int): Boolean =
        plane == LAND_PLANE && x in LAND_X - LAND_RADIUS..LAND_X + LAND_RADIUS && y in LAND_Y - LAND_RADIUS..LAND_Y + LAND_RADIUS

    /** The hat worn or in the backpack: its teleport spends banked bags, so backpack bags never recharge it. */
    fun held(): Boolean {
        val hat = ids.get()?.hat ?: return false
        return worn() || runCatching { inventory.any { it.id == hat } }.getOrDefault(false)
    }

    private fun worn(): Boolean = wornHat() != null

    private fun wornHat(): Item? {
        val hat = ids.get()?.hat ?: return null
        return runCatching { equipment.firstOrNull { it.id == hat } }.getOrNull()
    }

    private fun chooserShowsHabitat(): Boolean = interfaces.isOpen(CHOOSER_INTERFACE) && dialogueOptionVisible("Herblore Habitat")

    private fun syncAccount() {
        val name = runCatching { localPlayer.name }.getOrNull()
        if (name.isNullOrEmpty() || name == account) return
        account = name
        count = UNKNOWN
        castTick = -1L
    }

    private fun readChooser() {
        if (!interfaces.isOpen(CHOOSER_INTERFACE)) return
        for (line in dialogueOptions.keys) {
            val parsed = parseHabitatLine(line)
            if (parsed != UNKNOWN) {
                count = parsed
                return
            }
        }
    }

    private fun readBank() {
        if (!bankOpen || count == UNLIMITED) return
        val bag = ids.get()?.bag ?: return
        val items = bank.toList()
        // An empty grid is a bank still filling, not one with no spiritbags.
        if (items.isEmpty()) return
        count = items.filter { it.id == bag }.sumOf { it.amount }
    }

    private fun trackTeleport(tick: Long) {
        val known = ids.get() ?: return
        val at = localPlayer.let { it.serverTile ?: it.tile }
        if (castTick < 0) {
            if (localPlayer.animationId == known.cast && !inLandingBox(at)) {
                castTick = tick
                packBagsAtCast = packBags(known.bag)
            }
            return
        }
        if (tick - castTick > LANDING_WINDOW_TICKS) {
            castTick = -1L
            return
        }
        if (!inLandingBox(at)) return
        castTick = -1L
        val packNow = packBags(known.bag)
        val fromBackpack = packBagsAtCast > 0 && packNow >= 0 && packNow < packBagsAtCast
        if (!fromBackpack && count > 0 && count != UNLIMITED) count--
    }

    private fun packBags(bag: Int): Int = runCatching { inventory.filter { it.id == bag }.sumOf { it.amount } }.getOrDefault(-1)
}
