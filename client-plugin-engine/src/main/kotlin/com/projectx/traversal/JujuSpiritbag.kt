package com.projectx.traversal

import com.projectx.game.interfaces.IFSlot
import com.projectx.game.nxt.entity.npc.NPC
import com.projectx.script.Script
import com.projectx.script.api.coinPouch
import com.projectx.script.api.findClosestNPC
import com.projectx.script.api.inCombat
import com.projectx.script.api.interfaces
import com.projectx.script.api.inventory
import com.projectx.script.api.localPlayer
import com.projectx.util.FeatureIds
import com.projectx.webwalker.WebLink
import com.projectx.webwalker.WebLinkKind
import world.gregs.voidps.gameval.Gameval

/**
 * Buys juju teleport spiritbags from Papa Mambo when a route's backpack spiritbag teleport used the last one: he stands
 * by the Herblore Habitat landing. Up to [RESTOCK_COUNT], fewer when coins or free backpack slots run short; never while
 * the master farmer hat is held, whose teleport spends banked bags rather than these.
 */
internal object JujuSpiritbag {
    const val RESTOCK_COUNT = 2
    private const val PRICE = 2_000
    private const val MAMBO_RANGE = 10
    private const val TRADE_ATTEMPTS = 2
    private const val BUY_OPTION = "Buy 1"
    private const val LANDING_TIMEOUT_MS = 10_000L

    // The landing is seen before the new region's NPCs load.
    private const val MAMBO_APPEAR_TIMEOUT_MS = 5_000L

    // The in-combat flag outlasts a fight the teleport left behind; a bank trip costs far more than waiting.
    private const val COMBAT_CLEAR_TIMEOUT_MS = 15_000L
    private const val SHOP_OPEN_TIMEOUT_MS = 5_000L

    // The shop reads open before its stock grid fills.
    private const val STOCK_GRID_TIMEOUT_MS = 1_800L
    private const val BUY_TIMEOUT_MS = 3_000L
    private const val SHOP_CLOSE_TIMEOUT_MS = 1_500L

    private val ids = FeatureIds("JujuSpiritbag") {
        Ids(
            bag = Gameval.requireId(Gameval.OBJ, "ecosystem_teleport_bag"),
            coins = Gameval.requireId(Gameval.OBJ, "coins"),
            mambo = Gameval.requireId(Gameval.NPC, "ecosystem_papa_mambo"),
            stock = slot("shop_main:list_background"),
            icons = slot("shop_main:list_icon"),
            close = slot("shop_main:mainmodal_window_close_button"),
        )
    }

    private class Ids(val bag: Int, val coins: Int, val mambo: Int, val stock: IFSlot, val icons: IFSlot, val close: IFSlot)

    private fun slot(name: String): IFSlot = Gameval.requireComponentHash(name).let { IFSlot(it ushr 16, it and 0xFFFF) }

    val itemId: Int? get() = ids.get()?.bag

    /** Spiritbags in the backpack; -1 when unreadable. */
    fun packCount(): Int {
        val bag = ids.get()?.bag ?: return -1
        return runCatching { inventory.filter { it.id == bag }.sumOf { it.amount } }.getOrDefault(-1)
    }

    /** Backpack spiritbags before a link, or -1 when [link] is not a spiritbag teleport. */
    fun before(link: WebLink): Int {
        val bag = ids.get()?.bag ?: return -1
        return if (link.kind == WebLinkKind.ITEM && link.target?.id == bag) packCount() else -1
    }

    suspend fun afterTeleport(script: Script, bagsBefore: Int) {
        restockIfLastUsed(script, bagsBefore)
    }

    /**
     * After a backpack spiritbag teleport that began with [packBefore] bags: when that was the last and it lands at the
     * Habitat, buys more. It runs after the route's stop may have latched, since the Habitat is often the goal, so its
     * waits are bounded rather than tied to the stop. True when the landing was seen; the Trade walk may then have left
     * the player off the route's landing tile.
     */
    suspend fun restockIfLastUsed(script: Script, packBefore: Int): Boolean {
        if (packBefore != 1 || MasterFarmerHat.held()) return false
        val known = ids.get() ?: return false
        // The landing and the backpack update share a tick, so the position can be seen first.
        val landed = { atLanding() && packCount() == 0 }
        script.delayUntil(LANDING_TIMEOUT_MS) { landed() }
        if (!landed()) {
            println("[JujuSpiritbag] Last spiritbag clicked; no Habitat landing with it gone (${packCount()} left)")
            return false
        }
        runCatching { restock(script, known) }.onFailure { println("[JujuSpiritbag] Restock failed: $it") }
        return true
    }

    private suspend fun restock(script: Script, known: Ids) {
        if (MasterFarmerHat.held()) {
            println("[JujuSpiritbag] Used the last spiritbag; master farmer hat held, not restocking (hat count ${MasterFarmerHat.habitatCount()})")
            return
        }
        val free = runCatching { inventory.freeSlots }.getOrDefault(-1)
        val coins = coinsCarried(known.coins)
        val want = buyCount(free, coins)
        if (want == 0) {
            println("[JujuSpiritbag] Used the last spiritbag; not restocking ($coins coins, $free free slots)")
            return
        }
        if (inCombat) {
            println("[JujuSpiritbag] Used the last spiritbag; waiting for the in-combat flag to clear")
            script.delayUntil(COMBAT_CLEAR_TIMEOUT_MS) { !inCombat }
            if (inCombat) {
                println("[JujuSpiritbag] Still in combat after $COMBAT_CLEAR_TIMEOUT_MS ms, not restocking")
                return
            }
        }
        var mambo: NPC? = null
        script.delayUntil(MAMBO_APPEAR_TIMEOUT_MS) {
            mambo = findClosestNPC(MAMBO_RANGE) { it.id == known.mambo }
            mambo != null
        }
        val shopkeeper = mambo
        if (shopkeeper == null) {
            println("[JujuSpiritbag] Used the last spiritbag; Papa Mambo not within $MAMBO_RANGE tiles after $MAMBO_APPEAR_TIMEOUT_MS ms")
            return
        }
        println("[JujuSpiritbag] Used the last spiritbag; buying $want from Papa Mambo")
        val shop = known.stock.interfaceId
        var attempt = 0
        while (attempt < TRADE_ATTEMPTS && !interfaces.isOpen(shop)) {
            attempt++
            if (shopkeeper.interact("Trade")) script.delayUntil(SHOP_OPEN_TIMEOUT_MS) { interfaces.isOpen(shop) }
        }
        if (!interfaces.isOpen(shop)) {
            println("[JujuSpiritbag] Papa Mambo's shop did not open after $TRADE_ATTEMPTS Trades")
            return
        }
        script.delayUntil(STOCK_GRID_TIMEOUT_MS) { stockSlot(known) >= 0 }
        val slot = stockSlot(known)
        if (slot < 0) {
            println("[JujuSpiritbag] Papa Mambo has no spiritbags in stock")
        } else {
            val buy = IFSlot(shop, known.stock.componentId, slot)
            for (i in 0 until want) {
                val held = packCount()
                if (held < 0 || !buy.click(BUY_OPTION)) break
                script.delayUntil(BUY_TIMEOUT_MS) { packCount() > held }
                if (packCount() <= held) break
            }
            println("[JujuSpiritbag] Restocked: ${packCount().coerceAtLeast(0)} in the backpack")
        }
        closeShop(script, known)
    }

    /** Bags to buy: [RESTOCK_COUNT], fewer when free slots (they do not stack) or coins run short. */
    fun buyCount(freeSlots: Int, coins: Long): Int {
        if (freeSlots <= 0 || coins < PRICE) return 0
        return minOf(RESTOCK_COUNT.toLong(), freeSlots.toLong(), coins / PRICE).toInt()
    }

    private fun coinsCarried(coins: Int): Long {
        val inPouch = runCatching { coinPouch.filter { it.id == coins }.sumOf { it.amount } }.getOrDefault(0)
        val inPack = runCatching { inventory.filter { it.id == coins }.sumOf { it.amount } }.getOrDefault(0)
        return inPouch.coerceAtLeast(0).toLong() + inPack.coerceAtLeast(0)
    }

    private fun stockSlot(known: Ids): Int =
        interfaces.getComponentRaw(known.icons.interfaceId, known.icons.componentId)?.createdChildren
            ?.firstOrNull { it.itemId == known.bag }?.slotId ?: -1

    private suspend fun closeShop(script: Script, known: Ids) {
        if (!interfaces.isOpen(known.close.interfaceId)) return
        known.close.click(1)
        script.delayUntil(SHOP_CLOSE_TIMEOUT_MS) { !interfaces.isOpen(known.close.interfaceId) }
    }

    private fun atLanding(): Boolean = runCatching { localPlayer.let { MasterFarmerHat.inLandingBox(it.serverTile ?: it.tile) } }.getOrDefault(false)
}
