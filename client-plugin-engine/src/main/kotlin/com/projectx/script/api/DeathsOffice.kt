package com.projectx.script.api

import com.projectx.game.interfaces.IFSlot
import com.projectx.game.nxt.entity.location.SceneObject
import com.projectx.game.nxt.entity.npc.NPC
import com.projectx.script.Script
import com.projectx.util.FeatureIds
import com.projectx.util.componentIdFromHash
import com.projectx.util.gaussian
import com.projectx.util.interfaceIdFromHash
import world.gregs.voidps.gameval.Gameval
import java.util.Collections
import java.util.WeakHashMap

private class DeathIds(val death: Int, val reclaim: IFSlot, val confirmYes: IFSlot) {
    val window get() = reclaim.interfaceId
}

private val deathIds = FeatureIds("Death's Office") {
    DeathIds(Gameval.requireId(Gameval.NPC, "death_office_multi"), componentSlot("death:confirm_button"), componentSlot("death:popup_yes"))
}

private const val DEATH_RANGE = 30
private const val OFFICE_SETTLE_TICKS = 6
private const val RECLAIM_OPTION = "Reclaim items"

private val officeArrivalTick: MutableMap<Script, Long> = Collections.synchronizedMap(WeakHashMap())

private fun deathNpc(ids: DeathIds): NPC? = findClosestNPC(ids.death, DEATH_RANGE)

/** Whether the player is in Death's Office, after dying. */
val inDeathsOffice: Boolean
    get() = deathIds.get()?.let(::deathNpc) != null

/**
 * Reclaims the items lost on death from Death's Office and pays from the coffer, confirming the payment when the
 * game asks. Gives the office a few ticks to settle after arriving, as the game does not take the option at once.
 * Returns true once the reclaim window has closed after paying; false when not in the office or a step did not land,
 * so call it again on the next pass.
 */
suspend fun Script.reclaimFromDeath(): Boolean {
    val ids = deathIds.get() ?: return false
    val death = deathNpc(ids)
    if (death == null) {
        officeArrivalTick.remove(this)
        return false
    }
    val arrived = officeArrivalTick.getOrPut(this) { ServerTick.count }
    val settle = OFFICE_SETTLE_TICKS - (ServerTick.count - arrived)
    if (settle > 0) awaitServerTicks(settle.toInt())

    if (!interfaces.isOpen(ids.window)) {
        if (!death.interact(RECLAIM_OPTION)) return false
        delayUntil(gaussian(6000L, 1500L)) { interfaces.isOpen(ids.window) }
        if (!interfaces.isOpen(ids.window)) return false
        delay(gaussian(250, 80))
    }
    // The popup's Yes reads as visible even while the popup is hidden, so Confirm always goes first and Yes only
    // follows when the window is still up afterwards.
    if (!ids.reclaim.click(1)) return false
    delayUntil(gaussian(4500L, 1000L)) { !interfaces.isOpen(ids.window) }
    if (interfaces.isOpen(ids.window)) {
        delay(gaussian(250, 80))
        if (ids.confirmYes.click(1)) delayUntil(gaussian(4500L, 1000L)) { !interfaces.isOpen(ids.window) }
    }
    val reclaimed = !interfaces.isOpen(ids.window)
    if (reclaimed) officeArrivalTick.remove(this)
    return reclaimed
}

private class OfficeIds(
    val deaths: IntArray,
    val hourglasses: IntArray,
    val portal: Int,
    val reclaimButton: IFSlot,
    val confirmText: IFSlot,
    val confirmYes: IFSlot,
    val chatText: IFSlot,
    val itemsHeldVarbit: Int,
    val respawnInterface: Int,
) {
    val window get() = reclaimButton.interfaceId
}

private fun componentSlot(name: String): IFSlot {
    val hash = Gameval.requireComponentHash(name)
    return IFSlot(interfaceIdFromHash(hash), componentIdFromHash(hash))
}

private val officeIds = FeatureIds("Deaths") {
    OfficeIds(
        deaths = intArrayOf(
            Gameval.requireId(Gameval.NPC, "death_office_multi"),
            Gameval.requireId(Gameval.NPC, "death_office_multi_tutorial"),
            Gameval.requireId(Gameval.NPC, "advisor_grim_reaper_tutorial"),
        ),
        hourglasses = intArrayOf(
            Gameval.requireId(Gameval.LOC, "bslay_2014_portal_timer_op_exit"),
            Gameval.requireId(Gameval.LOC, "bslay_2014_portal_timer_inv_op_exit"),
        ),
        portal = Gameval.requireId(Gameval.LOC, "pvm_hub_exit"),
        reclaimButton = componentSlot("death:confirm_button"),
        confirmText = componentSlot("death:confirm_text"),
        confirmYes = componentSlot("death:popup_yes"),
        chatText = componentSlot("chat_v2_left:chat_text"),
        itemsHeldVarbit = Gameval.requireId(Gameval.VARBIT, "gravestone_timer_inv_slots"),
        respawnInterface = Gameval.requireId(Gameval.INTERFACE, "modal_choice"),
    )
}

/**
 * Getting the gear back after dying: reach Death (through the War's Retreat portal, teleporting there first), reclaim,
 * confirm the fee, then leave the office through the hourglass and pick a respawn point. An account's first death
 * opens with Death's tutorial, walked through on the way. [step] is one non-blocking pass for a tick worker;
 * [recoverFromDeath] is the blocking form.
 */
object Deaths {
    const val DEATH_NPC_NAME = "Death"
    const val RECLAIM_OPTION = "Reclaim items"

    private const val TALK_OPTION = "Talk to"
    private const val OFFICE_RANGE = 60
    private const val HOURGLASS_NAME = "Death's Hourglass"
    private const val HOURGLASS_OPTION = "Exit"
    private val TUTORIAL_OPTIONS = listOf("Great! How do I get back?", "Am I... dead?", "Goodbye!")
    private const val COIN_PROMPT_TEXT = "you need at least"
    private const val PORTAL_OPTION = "Exit to Death's office"
    private const val PORTAL_OPTION_SLOT = 2
    private const val PORTAL_RANGE = 30

    private const val DIALOGUE_COOLDOWN_MS = 1200L
    private const val CONFIRM_COOLDOWN_MS = 2500L
    private const val RECLAIM_COOLDOWN_MS = 1800L
    private const val TALK_PENDING_MS = 2_500L
    private const val PORTAL_PENDING_MS = 12_000L
    private const val TELEPORT_PENDING_MS = 8_000L
    private const val EXIT_PENDING_MS = 3_000L
    internal const val EXIT_WALK_MS = 25_000L
    internal const val WALK_STOP_MS = 15_000L

    enum class Result {
        /** Death holds nothing and we are not in his office. */
        NOTHING_TO_RECLAIM,
        /** Everything collected and out of the office. */
        RECLAIMED,
        /** A pass did something or waits on something it did: call again. */
        IN_PROGRESS,
        /** The fee is unaffordable; retrying cannot fix it. */
        NEEDS_COINS,
        /** Death can't be found or travelled to (no portal in range, no War's Retreat Teleport on the bar). */
        NO_DEATH_FOUND,
        TIMED_OUT,
    }

    internal enum class Fired { NONE, CONFIRM, RECLAIM, TALK, PORTAL, TELEPORT, EXIT, DIALOGUE }

    internal class Pass(val result: Result, val fired: Fired)

    private const val NEVER = Long.MIN_VALUE

    @Volatile private var lastConfirmAt = NEVER
    @Volatile private var lastReclaimAt = NEVER
    @Volatile private var lastDialogueAt = NEVER
    @Volatile private var lastExit = Fired.NONE
    @Volatile private var inFlight = Fired.NONE
    @Volatile private var inFlightSince = 0L

    /** Items Death holds; it survives logout, so it is safe to check on script start. */
    @JvmStatic
    val itemsHeld: Int
        get() = officeIds.get()?.let { varps.getVarBit(it.itemsHeldVarbit) } ?: 0

    @JvmStatic
    val hasItemsToReclaim: Boolean
        get() = itemsHeld > 0

    /** Gear owed, or still standing in the office: a death that cost nothing never raises [itemsHeld]. */
    @JvmStatic
    val needsRecovery: Boolean
        get() = hasItemsToReclaim || atDeathsOffice

    /**
     * In Death's Office, told by its "Exit" hourglass: NPCs named Death stand elsewhere too, and the hourglasses outside
     * the office offer "Enter".
     */
    @JvmStatic
    val atDeathsOffice: Boolean
        get() = hourglass() != null

    @JvmStatic
    val isReclaimOpen: Boolean
        get() = officeIds.get()?.let { interfaces.isOpen(it.window) } == true

    /** The "you need at least N coins" refusal is on screen. */
    @JvmStatic
    val needsCoins: Boolean
        get() {
            val ids = officeIds.get() ?: return false
            if (!interfaces.isOpen(ids.chatText.interfaceId)) return false
            return componentText(ids.chatText)?.lowercase()?.contains(COIN_PROMPT_TEXT) == true
        }

    /** Death himself; by name only inside the office, as other NPCs named Death stand outside it. */
    @JvmStatic
    fun death(): NPC? {
        val ids = officeIds.get() ?: return null
        return findClosestNPC(OFFICE_RANGE) { it.id in ids.deaths }
            ?: if (hourglass() != null) findClosestNPC(DEATH_NPC_NAME, OFFICE_RANGE) else null
    }

    private fun hourglass(): SceneObject? {
        val ids = officeIds.get() ?: return null
        return findClosestObject(OFFICE_RANGE) { o ->
            ids.hourglasses.any { o.isType(it) } || o.name() == HOURGLASS_NAME && o.hasOption(HOURGLASS_OPTION)
        }
    }

    internal fun officePortal(): SceneObject? {
        val ids = officeIds.get() ?: return null
        return findClosestObject(PORTAL_RANGE) { it.isType(ids.portal) || it.hasOption(PORTAL_OPTION) }
    }

    /** One non-blocking pass: fires at most one action, and nothing while an earlier one is still in flight. */
    @JvmStatic
    fun step(): Result = pass().result

    internal fun pass(): Pass {
        val ids = officeIds.get() ?: return Pass(Result.NO_DEATH_FOUND, Fired.NONE)
        val now = System.currentTimeMillis()
        if (!hasItemsToReclaim) {
            if (atDeathsOffice) return exitPass(now)
            inFlight = Fired.NONE
            lastExit = Fired.NONE
            return Pass(Result.RECLAIMED, Fired.NONE)
        }
        if (needsCoins) {
            println("[Deaths] the reclaim fee is unaffordable - bring coins")
            return Pass(Result.NEEDS_COINS, Fired.NONE)
        }
        if (interfaces.isOpen(ids.window)) {
            inFlight = Fired.NONE
            if (confirmationShowing(ids)) {
                if (cooling(lastConfirmAt, now, CONFIRM_COOLDOWN_MS)) return Pass(Result.IN_PROGRESS, Fired.NONE)
                lastConfirmAt = now
                println("[Deaths] confirming the reclaim")
                ids.confirmYes.dialogueContinue()
                return Pass(Result.IN_PROGRESS, Fired.CONFIRM)
            }
            if (cooling(lastReclaimAt, now, RECLAIM_COOLDOWN_MS)) return Pass(Result.IN_PROGRESS, Fired.NONE)
            lastReclaimAt = now
            println("[Deaths] reclaiming ($itemsHeld item(s) held)")
            ids.reclaimButton.click(1)
            return Pass(Result.IN_PROGRESS, Fired.RECLAIM)
        }
        dialoguePass(now)?.let { return it }
        if (stillInFlight(now)) return Pass(Result.IN_PROGRESS, Fired.NONE)
        val death = death()
        if (death != null) {
            println("[Deaths] asking Death for the items back")
            if (death.interact(RECLAIM_OPTION) || death.interact(TALK_OPTION)) markInFlight(Fired.TALK, now)
            return Pass(Result.IN_PROGRESS, Fired.TALK)
        }
        val travel = travelStep(now)
        return if (travel == Fired.NONE) Pass(Result.NO_DEATH_FOUND, Fired.NONE) else Pass(Result.IN_PROGRESS, travel)
    }

    /** Nothing owed but still in the office: the hourglass, alternating with Death when it does not let us out. */
    private fun exitPass(now: Long): Pass {
        dialoguePass(now)?.let { return it }
        if (stillInFlight(now)) return Pass(Result.IN_PROGRESS, Fired.NONE)
        val exit = hourglass()
        val death = death()
        if (exit != null && (lastExit != Fired.EXIT || death == null)) {
            println("[Deaths] leaving Death's Office through the hourglass")
            lastExit = Fired.EXIT
            exit.interact(HOURGLASS_OPTION)
            markInFlight(Fired.EXIT, now)
            return Pass(Result.IN_PROGRESS, Fired.EXIT)
        }
        if (death != null) {
            println("[Deaths] the hourglass did not let us out - talking to Death")
            lastExit = Fired.TALK
            death.interact(TALK_OPTION)
            markInFlight(Fired.TALK, now)
            return Pass(Result.IN_PROGRESS, Fired.TALK)
        }
        return Pass(Result.NO_DEATH_FOUND, Fired.NONE)
    }

    /** The respawn picker takes its first point, Death's tutorial the choices that lead out, anything else continues. */
    private fun dialoguePass(now: Long): Pass? {
        if (!isDialogOpen() || !atDeathsOffice) return null
        if (cooling(lastDialogueAt, now, DIALOGUE_COOLDOWN_MS)) return Pass(Result.IN_PROGRESS, Fired.NONE)
        lastDialogueAt = now
        inFlight = Fired.NONE
        val options = dialogueOptions
        if (options.isNotEmpty()) {
            val respawning = officeIds.get()?.let { interfaces.isOpen(it.respawnInterface) } == true
            val wanted = if (respawning) null else TUTORIAL_OPTIONS.firstOrNull(::dialogueOptionVisible)
            if (wanted != null) {
                continueDialogueContaining(wanted)
            } else {
                val (text, slot) = options.entries.first()
                if (respawning) println("[Deaths] respawning at $text")
                slot.dialogueContinue()
            }
            return Pass(Result.IN_PROGRESS, Fired.DIALOGUE)
        }
        continueDialogue()
        return Pass(Result.IN_PROGRESS, Fired.DIALOGUE)
    }

    /** Takes the War's Retreat portal when it is in range, otherwise teleports to War's Retreat. */
    private fun travelStep(now: Long): Fired {
        val portal = officePortal()
        if (portal != null) {
            println("[Deaths] taking the portal to Death's Office")
            val fired = if (portal.hasOption(PORTAL_OPTION)) portal.interact(PORTAL_OPTION) else portal.interact(PORTAL_OPTION_SLOT)
            if (!fired) return Fired.NONE
            markInFlight(Fired.PORTAL, now)
            return Fired.PORTAL
        }
        if (actionBarAbility(WarsRetreat.TELEPORT) != null) {
            println("[Deaths] teleporting to War's Retreat to reach Death's Office")
            if (castAbility(WarsRetreat.TELEPORT)) {
                markInFlight(Fired.TELEPORT, now)
                return Fired.TELEPORT
            }
        }
        return Fired.NONE
    }

    /**
     * One step towards Death's Office: the portal when in range, otherwise the War's Retreat Teleport. True when an
     * action was fired or one is still in flight.
     */
    @JvmStatic
    fun travelToDeath(): Boolean {
        val now = System.currentTimeMillis()
        return stillInFlight(now) || travelStep(now) != Fired.NONE
    }

    private fun markInFlight(what: Fired, now: Long) {
        inFlightSince = now
        inFlight = what
    }

    private fun stillInFlight(now: Long): Boolean {
        val age = now - inFlightSince
        val pending = when (inFlight) {
            Fired.TALK -> age < TALK_PENDING_MS || age < WALK_STOP_MS && localPlayer.isMoving
            Fired.PORTAL -> age < PORTAL_PENDING_MS && !atDeathsOffice
            Fired.TELEPORT -> age < TELEPORT_PENDING_MS && officePortal() == null
            Fired.EXIT -> age < EXIT_PENDING_MS || age < EXIT_WALK_MS && localPlayer.isMoving
            else -> false
        }
        if (!pending) inFlight = Fired.NONE
        return pending
    }

    private fun cooling(last: Long, now: Long, cooldownMs: Long) = last != NEVER && now - last < cooldownMs

    /** The blurb turns into the multi-line "This will cost you..." prompt when the reclaim needs confirming. */
    private fun confirmationShowing(ids: OfficeIds): Boolean {
        val text = componentText(ids.confirmText)
        return !text.isNullOrEmpty() && ("<br>" in text || "This" in text)
    }

    private fun componentText(slot: IFSlot): String? = interfaces.getComponent(slot.interfaceId, slot.componentId)?.text
}

private const val WALK_START_TIMEOUT_MS = 1_800L

/**
 * Collects everything Death holds and leaves the office (also when nothing is owed but we stand in it), pacing
 * [Deaths.step] until done or [timeoutMillis] runs out. Never answers [Deaths.Result.IN_PROGRESS].
 */
suspend fun Script.recoverFromDeath(timeoutMillis: Long = 90_000L): Deaths.Result {
    if (!Deaths.needsRecovery) return Deaths.Result.NOTHING_TO_RECLAIM
    println("[Deaths] recovering ${Deaths.itemsHeld} item(s) from Death's Office")
    val start = System.currentTimeMillis()
    var noDeathPasses = 0
    while (System.currentTimeMillis() - start < timeoutMillis) {
        val pass = Deaths.pass()
        when (pass.result) {
            Deaths.Result.RECLAIMED -> {
                println("[Deaths] everything reclaimed, out of Death's Office")
                return Deaths.Result.RECLAIMED
            }
            Deaths.Result.NEEDS_COINS -> return Deaths.Result.NEEDS_COINS
            Deaths.Result.NO_DEATH_FOUND -> {
                if (++noDeathPasses >= 10) {
                    println("[Deaths] cannot reach Death's Office")
                    return Deaths.Result.NO_DEATH_FOUND
                }
                delayBetween(1200, 1800)
            }
            else -> {
                noDeathPasses = 0
                awaitFired(pass.fired)
                delayBetween(900, 1400)
            }
        }
    }
    println("[Deaths] recovery timed out with ${Deaths.itemsHeld} item(s) still held")
    return Deaths.Result.TIMED_OUT
}

private suspend fun Script.awaitFired(fired: Deaths.Fired) {
    when (fired) {
        Deaths.Fired.TALK -> awaitWalk(Deaths.WALK_STOP_MS) { Deaths.isReclaimOpen || isDialogOpen() }
        Deaths.Fired.EXIT -> awaitWalk(Deaths.EXIT_WALK_MS) { isDialogOpen() || !Deaths.atDeathsOffice }
        Deaths.Fired.PORTAL -> {
            awaitWalk(Deaths.WALK_STOP_MS) { Deaths.atDeathsOffice }
            delayUntil(gaussian(4000L, 250_000L)) { Deaths.atDeathsOffice }
        }
        Deaths.Fired.TELEPORT -> delayUntil(8_000L) { Deaths.officePortal() != null || Deaths.atDeathsOffice }
        else -> {}
    }
}

/** Waits for a walk to start, then for it to stop or [done], at most [stopMillis]. */
private suspend fun Script.awaitWalk(stopMillis: Long, done: () -> Boolean) {
    delayUntil(WALK_START_TIMEOUT_MS) { done() || localPlayer.isMoving }
    if (done()) return
    delayUntil(stopMillis) { done() || !localPlayer.isMoving }
}
