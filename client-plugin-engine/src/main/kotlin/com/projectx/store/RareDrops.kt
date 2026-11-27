package com.projectx.store

import com.google.gson.JsonObject
import com.projectx.game.bootstrap.Bootstrap
import com.projectx.game.items.Item
import com.projectx.game.nxt.MainState
import com.projectx.script.ScriptExecutor
import com.projectx.script.api.GrandExchangePrices
import com.projectx.script.api.inventory
import com.projectx.script.api.localPlayer
import com.projectx.script.api.npcs
import com.projectx.script.event.Event
import com.projectx.script.event.impl.Chat
import com.projectx.ui.RareDropsPreference
import world.gregs.voidps.cache.Cache
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.Executors

/**
 * Tells the site about rare drops a store script gets, for the drop feed on xclient.dev and its Discord channel: loot
 * from reward chests and boss containers, drops that fall where a fought NPC stood, and pets. Only while a store script
 * runs and "Share rare drops" is on, and only the item, how many and which script; never anything about the player.
 * Game memory is read only on the game thread, in [tick].
 */
internal object RareDrops {
    private const val GAME = "rs3"
    private const val POLL_MILLIS = 600L
    private const val VIEW_TILES = 20
    private const val SAME_DROP_MILLIS = 90_000L
    private const val PET_WAIT_POLLS = 6
    private const val PRICES_REFRESH_MILLIS = 3_600_000L
    private const val COINS = 995

    private val containers = ContainerDrops()
    private var ground = GroundDrops()
    private val reported = HashMap<String, Long>()
    private val announced = AnnouncedDrops()
    private var lastPoll = 0L
    private var lastPrices = 0L
    private var active = false

    @Volatile private var petPending: RareDropRules.PetMessage? = null
    private var petPolls = 0
    private var petBackpackBefore: Map<Int, Int> = emptyMap()

    private val http: HttpClient by lazy { HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build() }
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "projectx-rare-drops").apply { isDaemon = true } }
    private val observer: (Event) -> Unit = ::onEvent

    fun start() {
        ScriptExecutor.addEventObserver(observer)
    }

    fun close() {
        ScriptExecutor.removeEventObserver(observer)
        worker.shutdownNow()
    }

    fun tick() {
        val now = System.currentTimeMillis()
        if (now - lastPoll < POLL_MILLIS) return
        lastPoll = now
        val sharing = RareDropsPreference.enabled.value && RunReports.storeScript() != null &&
            Bootstrap.client.mainState == MainState.LOGGED_IN && Entitlements.token() != null
        if (!sharing) {
            if (active) reset()
            return
        }
        active = true
        if (now - lastPrices > PRICES_REFRESH_MILLIS) {
            lastPrices = now
            GrandExchangePrices.refresh()
        }
        runCatching { containers.observe(containerContents()) }.getOrNull()?.forEach { (id, n) -> loot(id, n) }
        runCatching { groundDrops(now) }.getOrNull()?.forEach { loot(it.id, it.amount) }
        runCatching { namePendingPet() }
    }

    private fun reset() {
        active = false
        ground = GroundDrops()
        petPending = null
        containers.observe(LootContainers.ids.associateWith { null })
    }

    private fun containerContents(): Map<Int, Map<Int, Int>?> {
        val manager = Bootstrap.client.inventoryManager
        return LootContainers.ids.associateWith { id ->
            if (!manager.exists(id)) null else counts(manager.getWithInterface(id, -1, -1))
        }
    }

    private fun counts(items: Iterable<Item>): Map<Int, Int> {
        val out = HashMap<Int, Int>()
        for (item in items) if (item.id > 0) out.merge(item.id, item.amount.coerceAtLeast(1), Int::plus)
        return out
    }

    private fun groundDrops(now: Long): List<GroundDrops.Stack> {
        val me = localPlayer
        val here = me.tile
        val target = me.interactionSid
        for ((index, npc) in npcs) {
            if (index != target && npc.interactionSid != me.serverIndex) continue
            val at = npc.tile
            ground.fighting(index, at.x, at.y, npc.size, at.plane, now)
        }
        val stacks = Bootstrap.client.itemStackList.allGroundItems
            .filter { it.tile.plane == here.plane && it.tile.withinDistance(here, VIEW_TILES) }
            .map { GroundDrops.Stack(it.id, it.amount, it.tile.x, it.tile.y, it.tile.plane) }
        return ground.observe(stacks, now)
    }

    private fun onEvent(event: Event) {
        if (event !is Chat || !event.cleanSenderName.isNullOrBlank() || !active) return
        val pet = RareDropRules.petMessage(event.message)
        if (pet == null) {
            val playerName = runCatching { localPlayer.name }.getOrNull()
            RareDropRules.announcedDrop(event.message, playerName)?.let(::announcedLoot)
            return
        }
        if (pet == RareDropRules.PetMessage.DUPLICATE) {
            report("PET", 0, "Pet (already owned)", 1)
            return
        }
        petBackpackBefore = runCatching { counts(inventory) }.getOrDefault(emptyMap())
        petPolls = 0
        petPending = pet
    }

    /** A pet that went into the backpack is named by the item that arrived; one that follows goes unnamed. */
    private fun namePendingPet() {
        val pending = petPending ?: return
        if (pending == RareDropRules.PetMessage.BACKPACK) {
            val arrived = RareDropRules.gained(petBackpackBefore, counts(inventory)).keys.firstNotNullOfOrNull { id -> nameOf(id)?.let { id to it } }
            if (arrived != null) {
                petPending = null
                report("PET", arrived.first, arrived.second, 1)
                return
            }
        }
        if (++petPolls >= PET_WAIT_POLLS) {
            petPending = null
            report("PET", 0, "Pet", 1)
        }
    }

    private fun loot(rawId: Int, amount: Int) {
        val id = baseId(rawId)
        if (id <= 0 || id == COINS) return
        val name = nameOf(id) ?: return
        val fresh = announced.unannounced(name, amount, System.currentTimeMillis())
        if (fresh <= 0 || !RareDropRules.worthReporting(name, stackValue(id, fresh))) return
        report("VALUABLE", id, name, fresh)
    }

    /** The player's own News broadcast is only ever sent for a rare drop, so it counts whatever the price. */
    private fun announcedLoot(drop: RareDropRules.AnnouncedDrop) {
        val id = GrandExchangePrices.item(drop.itemName)?.id ?: 0
        val name = (if (id > 0) nameOf(id) else null) ?: drop.itemName
        val value = if (id > 0) stackValue(id, drop.quantity) else null
        if (id == COINS || !(drop.news || RareDropRules.worthReporting(name, value))) return
        if (report("VALUABLE", id, name, drop.quantity)) announced.announce(name, drop.quantity, System.currentTimeMillis())
    }

    private fun baseId(id: Int): Int {
        val type = Cache.obj(id) ?: return id
        return if (type.noted && type.notedId > 0) type.notedId else id
    }

    private fun nameOf(id: Int): String? = Cache.obj(id)?.name?.takeUnless { it.isBlank() || it.equals("null", ignoreCase = true) }

    private fun stackValue(id: Int, amount: Int): Long? {
        val price = GrandExchangePrices.price(id) ?: return null
        val each = when {
            price.high != null && price.low != null -> (price.high + price.low) / 2
            else -> price.high ?: price.low ?: return null
        }
        return each * amount
    }

    /** False when nothing was sent: the same item was reported moments ago, or there is no store script or token. */
    private fun report(kind: String, itemId: Int, itemName: String, quantity: Int): Boolean {
        val now = System.currentTimeMillis()
        reported.values.removeIf { now - it > SAME_DROP_MILLIS }
        if (reported.putIfAbsent(itemName.lowercase(), now) != null) return false
        val script = RunReports.storeScript() ?: return false
        val token = Entitlements.token() ?: return false
        val body = JsonObject().apply {
            addProperty("scriptInternalName", script)
            addProperty("kind", kind)
            if (itemId > 0) addProperty("itemId", itemId)
            addProperty("itemName", itemName)
            addProperty("quantity", quantity.coerceAtLeast(1))
        }
        println("[RareDrops] $itemName x$quantity ($kind) with $script")
        worker.execute { runCatching { post(body.toString(), token) } }
        return true
    }

    private fun post(body: String, token: String) {
        val request = HttpRequest.newBuilder(URI.create("${Entitlements.storeBase()}/api/v1/drops?game=$GAME"))
            .timeout(Duration.ofSeconds(10))
            .header("Authorization", "Bearer $token")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        val status = http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode()
        if (status !in 200..299) println("[RareDrops] the site refused the drop: HTTP $status")
    }
}
