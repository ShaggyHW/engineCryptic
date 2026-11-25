package com.projectx.quest.solver

import com.projectx.game.bootstrap.Bootstrap
import com.projectx.game.memory.NativeAccess
import com.projectx.game.nxt.entity.npc.NPC
import com.projectx.script.api.getAllObjectsWithinRange
import com.projectx.script.api.groundItems
import com.projectx.script.api.interfaces
import com.projectx.script.api.inventory
import com.projectx.script.api.localPlayer
import com.projectx.script.api.varcs
import com.projectx.script.api.varps
import world.gregs.voidps.type.Tile
import kotlin.math.abs
import kotlin.math.max

/** An NPC as the solvers see it; [type] is its type id, or its id when the type is unset. */
internal class NpcSighting(val id: Int, val type: Int, val tile: Tile, val animation: Int, val renderAnim: Int)

/** A loc as the solvers see it; [type] is its raw type id (or id when unset), [visible] the multi-loc result. */
internal class LocSighting(val type: Int, val visible: Int, val tile: Tile, val footprint: () -> List<Tile>)

internal class GroundSighting(val name: String, val amount: Int, val tile: Tile)

/**
 * The solvers' window on the game. Whatever cannot be read (no client, interface shut, list mid-rebuild) comes
 * back as absent, never as an error.
 */
internal object SolverWorld {
    private inline fun <T> read(block: () -> T): T? = if (!NativeAccess.isAttached) null else try {
        block()
    } catch (_: Throwable) {
        null
    }

    fun isOpen(interfaceId: Int): Boolean = read { interfaces.isOpen(interfaceId) } ?: false

    fun text(interfaceId: Int, componentId: Int): String? = read { interfaces.getComponent(interfaceId, componentId)?.text }

    fun varbit(id: Int): Int? = read { varps.getVarBit(id) }

    fun varp(id: Int): Int? = read { varps.getVar(id) }

    fun varc(id: Int): Int? = read { varcs.getVar(id) }

    fun playerTile(): Tile? = read { localPlayer.tile }

    fun carried(): Map<Int, Int> = read {
        val tally = HashMap<Int, Int>()
        for (item in inventory) if (item.amount > 0) tally.merge(item.id, item.amount, Int::plus)
        tally
    } ?: emptyMap()

    fun npcs(): List<NpcSighting> = read {
        val roster = Bootstrap.client.npcManager
        roster.indices.filter { it > 0 }.mapNotNull { index ->
            read {
                val address = roster[index]?.takeIf { it.address() != 0L } ?: return@read null
                val npc = NPC(address).takeIf { it.exists() } ?: return@read null
                val type = npc.typeId.let { if (it == -1) npc.id else it }
                NpcSighting(npc.id, type, npc.tile, npc.animationId, npc.renderAnim)
            }
        }
    } ?: emptyList()

    fun locs(range: Int): List<LocSighting> = read {
        getAllObjectsWithinRange(range).mapNotNull { loc ->
            read {
                val raw = loc.typeId.let { if (it == -1) loc.id else it }
                LocSighting(raw, loc.visibleTypeId, loc.tile) { loc.occupiedTiles() }
            }
        }
    } ?: emptyList()

    fun groundNear(centre: Tile, range: Int): List<GroundSighting> = read {
        groundItems.mapNotNull { item ->
            read {
                val tile = item.tile
                val near = tile.plane == centre.plane && chebyshev(tile, centre) <= range
                if (near) GroundSighting(item.name, item.amount, tile) else null
            }
        }
    } ?: emptyList()
}

internal fun chebyshev(a: Tile, b: Tile): Int = max(abs(a.x - b.x), abs(a.y - b.y))

/** One value remembered with where the player stood; it only counts while they stay within [reach] tiles. */
internal class RoomMemory<T : Any>(private val reach: Int) {
    private var value: T? = null
    private var takenAt: Tile? = null

    fun remember(found: T, at: Tile) {
        value = found
        takenAt = at
    }

    fun recall(here: Tile?): T? {
        val spot = takenAt ?: return null
        if (here == null || here.plane != spot.plane || chebyshev(here, spot) > reach) return null
        return value
    }
}
