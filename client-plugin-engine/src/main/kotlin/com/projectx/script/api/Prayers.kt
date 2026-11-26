package com.projectx.script.api

import com.projectx.game.interfaces.IFSlot
import com.projectx.script.Script
import com.projectx.util.FeatureIds
import com.projectx.util.gaussian
import world.gregs.voidps.cache.Cache
import world.gregs.voidps.cache.type.data.StructType
import world.gregs.voidps.gameval.Gameval

enum class PrayerBook { NORMAL, CURSES }

/**
 * One prayer as the game defines it: its name and Prayer level, its book and its slot there. A tiered prayer such as
 * Thick Skin, Rock Skin and Steel Skin is one entry per tier, sharing the slot and the flag that says it is on.
 */
class PrayerInfo internal constructor(
    val name: String,
    val book: PrayerBook,
    val level: Int,
    val slot: Int,
    private val activeVarbit: Int,
) {
    val active: Boolean get() = varps.getVarBit(activeVarbit) == 1

    /** Whether its book is the one in use, which is the only time its button can be pressed. */
    val inCurrentBook: Boolean get() = book == Prayers.currentBook

    /** Presses its prayer button, which turns it on or off; false, sending nothing, when its book is not in use. */
    fun click(): Boolean = inCurrentBook && Prayers.button(slot)?.click(1) == true

    override fun toString(): String = name
}

/** Every prayer in both books, read from the game's own prayer lists. */
object Prayers {

    private class Ids(
        val normalList: Int,
        val curseList: Int,
        val name: Int,
        val level: Int,
        val tiered: Int,
        val tiers: List<Int>,
        val curseBook: Int,
        val buttons: Int,
    )

    private val ids = FeatureIds("Prayers") {
        fun param(name: String) = Gameval.requireId(Gameval.PARAM, name)
        Ids(
            normalList = Gameval.requireId(Gameval.ENUM, "combatv2_prayer_order_by_level"),
            curseList = Gameval.requireId(Gameval.ENUM, "combatv2_curse_order_by_level"),
            name = param("combatv2_ability_name"),
            level = param("combatv2_ability_level_req"),
            tiered = param("combatv2_tiered_prayer"),
            tiers = listOf(param("combatv2_tier1_struct"), param("combatv2_tier2_struct"), param("combatv2_tier3_struct")),
            curseBook = Gameval.requireId(Gameval.VAR_PLAYER, "topstat_curse"),
            buttons = requireNotNull(Gameval.componentHash("toplevel_v2_prayer:prayer_buttons")) { "no prayer_buttons" },
        )
    }

    // Kept only once it holds something: asked before the cache is up, it builds again next time rather than staying empty.
    @Volatile
    private var built: List<PrayerInfo> = emptyList()

    private val catalogue: List<PrayerInfo>
        get() = built.ifEmpty { build().also { built = it } }

    /** The book in use. */
    @JvmStatic
    val currentBook: PrayerBook
        get() = if (ids.get()?.let { varps.getVar(it.curseBook) } == 1) PrayerBook.CURSES else PrayerBook.NORMAL

    /** Every prayer in both books, normal first, each in level order. */
    @JvmStatic
    fun all(): List<PrayerInfo> = catalogue

    @JvmStatic
    fun inBook(book: PrayerBook): List<PrayerInfo> = catalogue.filter { it.book == book }

    /** The prayer called [name], ignoring case, from the book in use first; null when no book has it. */
    @JvmStatic
    fun named(name: String): PrayerInfo? {
        val matches = catalogue.filter { it.name.equals(name.trim(), ignoreCase = true) }
        return matches.firstOrNull { it.inCurrentBook } ?: matches.firstOrNull()
    }

    /** The prayers that are on. */
    @JvmStatic
    fun active(): List<PrayerInfo> = catalogue.filter { it.active }

    internal fun button(slot: Int): IFSlot? = ids.get()?.let { IFSlot(it.buttons ushr 16, it.buttons and 0xFFFF, slot) }

    private fun build(): List<PrayerInfo> {
        val known = ids.get() ?: return emptyList()
        return listOf(PrayerBook.NORMAL to known.normalList, PrayerBook.CURSES to known.curseList).flatMap { (book, list) ->
            val entries = Cache.enum(list)?.values ?: return@flatMap emptyList()
            entries.keys.sorted().flatMap { slot ->
                val struct = (entries[slot] as? Int)?.let(Cache::struct) ?: return@flatMap emptyList()
                val tiers = if (struct.getIntValue(known.tiered, 0) == 1) {
                    known.tiers.mapNotNull { struct.getIntValue(it, -1).takeIf { id -> id >= 0 }?.let(Cache::struct) }
                } else {
                    listOf(struct)
                }
                tiers.mapNotNull { prayer(it, book, slot, known) }
            }
        }
    }

    private fun prayer(struct: StructType, book: PrayerBook, slot: Int, known: Ids): PrayerInfo? {
        val name = struct.getStringValue(known.name) ?: return null
        val flag = PrayerFlags.varbitName(book, name) ?: return null
        val varbit = Gameval.id(Gameval.VARBIT, flag) ?: return null
        return PrayerInfo(name, book, struct.getIntValue(known.level, 1), slot, varbit)
    }
}

/**
 * Turns [prayer] on or off and waits for the game to agree; true once it has. Pressing a prayer whose book is not in use
 * does nothing, so that returns false at once.
 */
@JvmSynthetic
suspend fun Script.togglePrayer(prayer: PrayerInfo, shouldBeActive: Boolean): Boolean {
    if (prayer.active == shouldBeActive) return true
    if (!prayer.click()) return false
    delayUntil(gaussian(2000L, 1592L)) { prayer.active == shouldBeActive }
    return prayer.active == shouldBeActive
}

/** [togglePrayer] by the prayer's name, as the game spells it ("Piety", "Soul Split"); false when no book has it. */
@JvmSynthetic
suspend fun Script.togglePrayer(name: String, shouldBeActive: Boolean): Boolean =
    Prayers.named(name)?.let { togglePrayer(it, shouldBeActive) } ?: false
