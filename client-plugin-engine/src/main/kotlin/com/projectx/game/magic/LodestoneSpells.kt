package com.projectx.game.magic

import com.projectx.game.interfaces.IFSlot
import com.projectx.script.api.Lodestone
import com.projectx.script.api.interfaces
import com.projectx.script.api.varps
import com.projectx.util.FeatureIds
import org.projectx.core.game.combat.CombatIds
import world.gregs.voidps.cache.Cache
import world.gregs.voidps.gameval.Gameval
import java.util.EnumMap
import kotlin.math.abs
import kotlin.math.max

/**
 * Lodestone teleports cast straight from the Magic ability book, skipping the lodestone network. The book lists them
 * while its lodestone filter varbit is 0. A spell's book slot is its ability id; lodestone spells are matched to a
 * [Lodestone] by their landing tile, which sits a tile or so off the lodestone's own.
 */
internal object LodestoneSpells {
    private const val MATCH_RADIUS = 5
    private const val QUICK_TELEPORT_OPTION = 1

    private class Ids(val hiddenVarbit: Int, val lodestoneParam: Int, val destinationParam: Int, val bookInterface: Int, val bookComponent: Int)

    private val ids = FeatureIds("LodestoneSpells") {
        val book = Gameval.requireComponentHash("toplevel_v2_window_ability_book_magic:abilities_layer")
        Ids(
            Gameval.requireId(Gameval.VARBIT, "ability_book_filter_lodestone_teleports"),
            Gameval.requireId(Gameval.PARAM, "combatv2_lodestone_teleport"),
            Gameval.requireId(Gameval.PARAM, "combatv2_teleport_destination"),
            book ushr 16,
            book and 0xFFFF,
        )
    }

    @Volatile
    private var slots: Map<Lodestone, Int>? = null

    /** The book slot of [lodestone]'s spell, or -1 when the cache has none for it or is not ready. */
    fun slot(lodestone: Lodestone): Int {
        val known = slots ?: build()?.also { slots = it } ?: return -1
        return known[lodestone] ?: -1
    }

    /** Whether [lodestone] can be cast from the book now: the book lists lodestones and shows the spell's slot. */
    fun available(lodestone: Lodestone): Boolean {
        val known = ids.get() ?: return false
        if (varps.getVarBit(known.hiddenVarbit) != 0) return false
        val slot = slot(lodestone)
        if (slot < 0) return false
        val grid = interfaces.getComponent(known.bookInterface, known.bookComponent) ?: return false
        return grid.createdChildren.firstOrNull { it.slotId == slot }?.let { it.opCount > 0 } == true
    }

    /** Fires "Quick teleport" on [lodestone]'s book slot; false, sending nothing, when it is not [available]. */
    fun cast(lodestone: Lodestone): Boolean {
        if (!available(lodestone)) return false
        val known = ids.get() ?: return false
        return IFSlot(known.bookInterface, known.bookComponent, slot(lodestone)).click(QUICK_TELEPORT_OPTION)
    }

    private fun build(): Map<Lodestone, Int>? {
        val known = ids.get() ?: return null
        val spells = Cache.enum(CombatIds.ENUM_MAGIC) ?: return null
        val out = EnumMap<Lodestone, Int>(Lodestone::class.java)
        val bestDistance = EnumMap<Lodestone, Int>(Lodestone::class.java)
        for ((key, value) in spells.values) {
            val struct = (value as? Int)?.let(Cache::struct) ?: continue
            if (struct.getIntValue(known.lodestoneParam, 0) != 1) continue
            val packed = struct.getIntValue(known.destinationParam, -1)
            if (packed == -1) continue
            val (plane, x, y) = SpellChecks.unpackCoord(packed)
            val slot = struct.getIntValue(CombatIds.ABILITY_ID, key)
            for (lodestone in Lodestone.entries) {
                if (lodestone.tile.plane != plane) continue
                val distance = max(abs(lodestone.tile.x - x), abs(lodestone.tile.y - y))
                if (distance > MATCH_RADIUS || distance >= (bestDistance[lodestone] ?: Int.MAX_VALUE)) continue
                bestDistance[lodestone] = distance
                out[lodestone] = slot
            }
        }
        return out.takeIf { it.isNotEmpty() }
    }
}
