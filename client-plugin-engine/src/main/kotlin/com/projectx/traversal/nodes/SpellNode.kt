package com.projectx.traversal.nodes

import com.projectx.game.magic.CastResult
import com.projectx.game.magic.SpellTeleports
import com.projectx.script.Script
import com.projectx.script.api.TeleportSpell
import com.projectx.traversal.TraversalNode
import com.projectx.script.api.Area as WorldArea
import com.projectx.util.Area as FlatArea

/**
 * Casts a teleport spell, a Spellbook Swap first when it is on another book, then waits to land in its destination and
 * for the arrival animation. A spell that can no longer be cast, or that fails [MAX_ATTEMPTS] times, asks the
 * traversal to re-route.
 */
class SpellNode private constructor(
    private val spell: TeleportSpell,
    action: String?,
    private val destination: NodeDestination?,
    private val customReached: (() -> Boolean)?,
) : TraversalNode() {
    /** The book slot op this node casts, such as an alternate destination; null casts with "Cast". */
    val action: String? = action?.trim()?.takeUnless { it.isEmpty() || it.equals("Cast", ignoreCase = true) }

    private var attempts = 0
    private var nextTry = 0L

    @JvmOverloads
    constructor(spell: TeleportSpell, destination: FlatArea? = null, customReached: (() -> Boolean)? = null) :
        this(spell, null, NodeDestination.of(destination), customReached)

    constructor(spell: TeleportSpell, action: String?, destination: FlatArea?, customReached: (() -> Boolean)?) :
        this(spell, action, NodeDestination.of(destination), customReached)

    constructor(spell: TeleportSpell, action: String?, destination: WorldArea, customReached: (() -> Boolean)?) :
        this(spell, action, NodeDestination.of(destination), customReached)

    override suspend fun process(script: Script): Boolean {
        if (reached(script)) return false
        if (System.currentTimeMillis() < nextTry) return true
        if (inDestination()) {
            awaitArrivalAnimation()
            return true
        }
        val result = SpellTeleports.cast(script, spell, action) { traversalStopRequested() }
        when (result) {
            CastResult.STOPPED -> return false
            CastResult.NOT_USABLE -> {
                giveUp("$spell is no longer castable: ${SpellTeleports.check(spell).reason}")
                return false
            }
            CastResult.STARTED -> {
                val landed = TraversalSupport.delayUntil(LANDING_MS) { traversalStopRequested() || inDestination() }
                if (landed && !traversalStopRequested()) {
                    awaitArrivalAnimation()
                    return true
                }
                if (destination == null) return true
            }
            else -> {}
        }
        if (++attempts >= MAX_ATTEMPTS) {
            giveUp("$this failed $attempts times (last: $result)")
            return false
        }
        println("[Spell] $spell: $result, retrying ($attempts/$MAX_ATTEMPTS)")
        nextTry = System.currentTimeMillis() + RETRY_PACING_MS
        return true
    }

    private suspend fun awaitArrivalAnimation() {
        TraversalSupport.delayUntil(ARRIVAL_ANIMATION_MS) { traversalStopRequested() || !TraversalSupport.isAnimating() }
    }

    private fun giveUp(why: String) {
        println("[Spell] $why - re-routing")
        if (!requestTraversalRepath()) failTraversal(why)
    }

    private fun inDestination(): Boolean = destination?.contains(TraversalSupport.serverTileOrNull()) == true

    override fun reached(script: Script): Boolean {
        if (destination != null) return inDestination() && !TraversalSupport.isAnimating()
        return customReached?.invoke() == true
    }

    override fun copy(): TraversalNode = SpellNode(spell, action, destination, customReached)

    override fun toString(): String = "[Spell: $spell${action?.let { " [$it]" }.orEmpty()}]"

    private companion object {
        private const val MAX_ATTEMPTS = 3
        private const val LANDING_MS = 8_000L
        private const val ARRIVAL_ANIMATION_MS = 5_000L
        private const val RETRY_PACING_MS = 1_200L
    }
}
