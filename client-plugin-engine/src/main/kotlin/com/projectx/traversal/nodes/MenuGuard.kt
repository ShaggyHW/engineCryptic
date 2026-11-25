package com.projectx.traversal.nodes

import com.projectx.script.api.AttackMemory
import com.projectx.traversal.TraversalNode
import java.util.concurrent.ConcurrentHashMap

/**
 * Teleports and transports that open a menu: the node that opens it (one whose next node is an [IFSlotNode]) and the
 * menu clicks after it. A hit closes the menu, so under attack the click goes nowhere and the walk after the chain waits
 * for a teleport that never comes. A route planned before the fight still holds one, so the opener asks for a re-route
 * when it sees the attack, and a menu click whose menu is shut asks too. Each opener (by node and take-off tile) asks at
 * most once per [SAME_OPENER_MS]: a menu teleport without a requirement key routes the same way under attack.
 */
internal object MenuGuard {
    const val SAME_OPENER_MS = 30_000L

    private const val MAX_MENU_CLICKS = 8

    private val asked = ConcurrentHashMap<String, Long>()

    internal var underAttack: () -> Boolean = AttackMemory::isUnderAttack
    internal var clock: () -> Long = System::currentTimeMillis

    fun opensMenu(node: TraversalNode?): Boolean = node?.next is IFSlotNode

    /** The node that opened the menu [node] clicks in: the first node before the run of menu clicks. */
    fun opener(node: TraversalNode): TraversalNode {
        var k = node
        var n = 0
        while (k is IFSlotNode && n++ < MAX_MENU_CLICKS) k = k.prev ?: break
        return k
    }

    /** Before [node] opens its menu: under attack, asks for a re-route; true when taken, and the node does no more. */
    fun beforeOpening(node: TraversalNode, requestRepath: () -> Boolean): Boolean {
        if (!opensMenu(node) || !underAttack()) return false
        return reroute(node, "under attack: a hit closes its menu", requestRepath)
    }

    /** Asks once for a re-route around the menu [node] belongs to; true when the traversal took it. */
    fun reroute(node: TraversalNode, why: String, requestRepath: () -> Boolean): Boolean {
        val opener = opener(node)
        val here = TraversalSupport.serverTileOrNull()
        val key = "$opener@$here"
        val now = clock()
        asked.values.removeIf { now - it > SAME_OPENER_MS }
        if (asked.putIfAbsent(key, now) != null) return false
        val taken = requestRepath()
        println("[MenuGuard] $opener at $here $why" + if (taken) " - re-routing" else " - no re-route possible, carrying on")
        return taken
    }

    internal fun resetForTests() {
        asked.clear()
        underAttack = AttackMemory::isUnderAttack
        clock = System::currentTimeMillis
    }
}
