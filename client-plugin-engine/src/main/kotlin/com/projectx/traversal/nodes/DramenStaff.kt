package com.projectx.traversal.nodes

import com.projectx.game.items.Item
import com.projectx.script.Script
import com.projectx.script.api.Equipment
import com.projectx.script.api.equipment
import com.projectx.script.api.inventory
import com.projectx.script.api.varps
import com.projectx.util.FeatureIds
import world.gregs.voidps.gameval.Gameval

/**
 * The Dramen staff, or the Lunar staff the game accepts in its place. The planner routes with it carried, in the
 * backpack or worn, but the game only lets you through with it wielded, so the traversal wields it before a node that
 * needs it and puts back whatever it took off once no later node needs it, or the traversal ends.
 *
 * Two kinds of step need it: one whose planner row lists the staff requirement, such as the Zanaris shed door, and a
 * fairy ring (not the portable one) before A Fairy Tale III is done; the planner's fairy ring steps carry no
 * requirement ids, so the ring asks for itself.
 */
internal object DramenStaff {
    const val REQUIREMENT_ID = 379

    private const val FAIRY_TALE_III_DONE = 180
    private const val FAIRY_TALE_II_RING_PERMISSION = 40

    private const val WIELD_OPTION = "Wield"
    private val EQUIP_OPTIONS = listOf("Wield", "Wear", "Equip")
    private const val WIELD_WAIT_MS = 2_400L
    private const val RESTORE_TRIES = 2

    private val ids = FeatureIds("DramenStaff") {
        mapOf(
            "dramen" to Gameval.requireId(Gameval.OBJ, "dramen_staff"),
            "lunar" to Gameval.requireId(Gameval.OBJ, "lunar_moonclan_liminal_staff"),
            "fairyTale3" to Gameval.requireId(Gameval.VARBIT, "ft3_quest"),
            "fairyTale2" to Gameval.requireId(Gameval.VARBIT, "fairy2_queencure_quest"),
        )
    }

    // Process-wide: a re-route builds a new traversal, and the staff may still be needed on the new route.
    @Volatile
    private var swappedOut: IntArray? = null

    /** True when a planner step's requirement ids include the staff's. */
    fun requiredBy(requirementIds: Collection<Int>?): Boolean = requirementIds?.contains(REQUIREMENT_ID) == true

    /** True while fairy rings still need a staff wielded: A Fairy Tale III not done. */
    fun fairyRingsNeedIt(): Boolean = varbit("fairyTale3") < FAIRY_TALE_III_DONE

    /** A Fairy Tale II past the Fairy Godfather's permission to use the rings. */
    fun fairyRingPermission(): Boolean = varbit("fairyTale2") >= FAIRY_TALE_II_RING_PERMISSION

    /**
     * The planner's key for its staff copies of the fairy ring rows: A Fairy Tale III done, or, with items allowed, the
     * Fairy Godfather's permission and a staff carried. It counts Fairy Tale III on purpose: the planner keeps one row
     * per ring tile for the portable ring's check, the copy, so the copy must pass wherever the original does.
     */
    fun fairyRingStaffAccess(withItems: Boolean): Boolean = !fairyRingsNeedIt() || withItems && fairyRingPermission() && carried()

    fun carried(): Boolean = backpackStaff() != null || worn()

    fun worn(): Boolean = staffIds().let { staffs -> runCatching { equipment.any { it.id in staffs } }.getOrDefault(false) }

    val restorePending: Boolean get() = swappedOut != null

    suspend fun wield(script: Script): Boolean = wield(script) { false }

    /**
     * Wields the staff from the backpack and waits for it to show as worn, or for [stop]. True once it is worn; false
     * when there is none in the backpack, the click did not go, or it did not show in time.
     */
    suspend fun wield(script: Script, stop: () -> Boolean): Boolean {
        if (worn()) return true
        val staff = backpackStaff() ?: return false
        if (swappedOut == null) {
            val weapon = wornId(Equipment.Slot.WEAPON)
            val offHand = wornId(Equipment.Slot.SHIELD)
            if (weapon >= 0 || offHand >= 0) swappedOut = intArrayOf(weapon, offHand)
        }
        val putBack = swappedOut?.let { " (will re-equip ${describe(it)} after)" }.orEmpty()
        println("[DramenStaff] wielding ${staff.name} for the next step$putBack")
        if (!staff.click(WIELD_OPTION)) return false
        script.delayUntil(WIELD_WAIT_MS) { stop() || worn() }
        return worn()
    }

    suspend fun restore(script: Script) = restore(script) { false }

    /**
     * Puts back the weapon and off-hand the staff replaced, weapon first so a one-hander and its off-hand both go back
     * on, each from the backpack. The swap stays pending only when [stop] cut a wait short; otherwise it is forgotten,
     * back on or not, so a failing click never loops.
     */
    suspend fun restore(script: Script, stop: () -> Boolean) {
        val wanted = swappedOut ?: return
        for (id in wanted) {
            if (id < 0) continue
            var tries = 0
            while (tries < RESTORE_TRIES && !equipped(id)) {
                tries++
                val item = backpackItem(id)
                if (item == null) {
                    println("[DramenStaff] $id is not in the backpack - not re-equipped")
                    break
                }
                if (EQUIP_OPTIONS.none { item.click(it) }) {
                    println("[DramenStaff] ${item.name} has no Wield/Wear/Equip option - not re-equipped")
                    break
                }
                script.delayUntil(WIELD_WAIT_MS) { stop() || equipped(id) }
                if (stop() && !equipped(id)) return
            }
        }
        println("[DramenStaff] re-equipped ${describe(wanted)}")
        swappedOut = null
    }

    private fun equipped(id: Int): Boolean = runCatching { equipment.any { it.id == id } }.getOrDefault(false)

    private fun wornId(slot: Equipment.Slot): Int = runCatching { Equipment.Slot.getItem(slot)?.id }.getOrNull() ?: -1

    private fun backpackItem(id: Int): Item? = runCatching { inventory.firstOrNull { it.id == id } }.getOrNull()

    private fun backpackStaff(): Item? = staffIds().let { staffs -> runCatching { inventory.firstOrNull { it.id in staffs } }.getOrNull() }

    private fun staffIds(): Set<Int> = ids.get()?.let { setOf(it.getValue("dramen"), it.getValue("lunar")) }.orEmpty()

    private fun varbit(key: String): Int = ids.get()?.get(key)?.let { runCatching { varps.getVarBit(it) }.getOrDefault(0) } ?: 0

    private fun describe(ids: IntArray) = "weapon ${ids[0]}, off-hand ${ids[1]}"
}
