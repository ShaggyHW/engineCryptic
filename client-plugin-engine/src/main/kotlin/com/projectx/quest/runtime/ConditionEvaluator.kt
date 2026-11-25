package com.projectx.quest.runtime

import com.projectx.game.hooks.impl.InterfaceTextCapture
import com.projectx.quest.data.QuestCondition
import com.projectx.quest.data.QuestCondition as Check
import com.projectx.script.api.dialogueOptions
import com.projectx.script.api.inInstancedArea
import com.projectx.script.api.interfaces
import com.projectx.script.api.inventory
import com.projectx.script.api.isDialogOpen
import com.projectx.script.api.localPlayer
import com.projectx.script.api.varps
import world.gregs.voidps.gameval.Gameval
import kotlin.math.abs
import kotlin.math.max
import kotlin.reflect.KClass

/** Decides guide conditions against live game state, and keeps the dialogue capture the editor HUD shows. */
object ConditionEvaluator {
    private const val QUOTE_LIMIT = 60
    private const val HIGH_GROUND_FINE = 700.0
    private const val MISS_LOG_INTERVAL_MS = 2_000L
    private const val QUOTE = "\""
    private const val DOLLAR = "$"
    private const val TIMES = "\u00D7"

    /** The text one open dialogue interface showed at the last capture. */
    data class DialogCapture(
        val interfaceId: Int,
        val sample: String,
    )

    @Volatile var debugCaptureEnabled: Boolean = false

    @Volatile var lastDialogCapture: List<DialogCapture> = listOf()
        private set

    @Volatile private var logRequested = false
    @Volatile private var sweepRequested: IntRange? = null
    private var lastMissLogMs = 0L

    private val questJournal by lazy { Gameval.requireId(Gameval.INTERFACE, "quest_journal") }
    private val markup = Regex("<[^>]*>")
    private val whitespace = Regex("\\s+")

    fun isMet(condition: QuestCondition): Boolean = try {
        evaluate(condition)
    } catch (_: Throwable) {
        false
    }

    private fun evaluate(condition: QuestCondition): Boolean = when (val c = condition) {
        is Check.DistanceTo -> within(c.x, c.y, c.range, c.instance)
        is Check.DistanceFrom -> !within(c.x, c.y, c.range, c.instance)
        is Check.DistanceToWithHeight -> within(c.x, c.y, c.range, c.instance) && onHeight(c.heightFine)
        is Check.DistanceFromWithHeight -> !(within(c.x, c.y, c.range, c.instance) && onHeight(c.heightFine))
        is Check.InventoryContains -> carried(c.itemId, c.candidateItemTypeIds, c.displayName, exactName = false) >= c.quantity
        is Check.InventoryDoesNotContain -> carried(c.itemId, c.candidateItemTypeIds, c.displayName, exactName = true) == 0
        is Check.ModelVisible -> seen(QuestTargetMatcher.specFor(c), c.quantity)
        is Check.ModelNotVisible -> !seen(QuestTargetMatcher.specFor(c), 1)
        is Check.InterfaceOpen -> c.interfaceId >= 0 && interfaces.isOpen(c.interfaceId)
        is Check.NpcNearTile -> npcNear(c)
        is Check.ConversationText -> dialogueSays(c.text)
        is Check.ChatText -> RecentChat.containsRecent(c.text)
        else -> stateChecks[c]?.invoke() ?: false
    }

    private val stateChecks: Map<QuestCondition, () -> Boolean> = mapOf(
        Check.QuestInterfaceOpen to { interfaces.isOpen(questJournal) },
        Check.ConversationActive to { isDialogOpen() },
        Check.ConversationInactive to { !isDialogOpen() },
        Check.QuestStarted to { stageOfSelected()?.let { (stage, _) -> stage > 0 } == true },
        Check.QuestComplete to { stageOfSelected()?.let { (stage, complete) -> complete > 0 && stage >= complete } == true },
        Check.InInstance to { inInstancedArea },
        Check.NotInInstance to { !inInstancedArea },
        Check.Always to { true },
    )

    private val checkableKinds: Set<KClass<out QuestCondition>> = setOf(
        Check.DistanceTo::class, Check.DistanceFrom::class, Check.DistanceToWithHeight::class, Check.DistanceFromWithHeight::class,
        Check.InventoryContains::class, Check.InventoryDoesNotContain::class, Check.ModelVisible::class, Check.ModelNotVisible::class,
        Check.QuestInterfaceOpen::class, Check.InterfaceOpen::class, Check.NpcNearTile::class,
        Check.ConversationActive::class, Check.ConversationInactive::class, Check.ConversationText::class, Check.ChatText::class,
        Check.InInstance::class, Check.NotInInstance::class, Check.Always::class,
    )

    /** Quest progress checks need a stage varbit on the selected quest; every other kind is fixed. */
    fun implementable(condition: QuestCondition): Boolean = when (condition) {
        Check.QuestStarted, Check.QuestComplete -> (ActiveQuestState.selectedQuest?.stageVarbit ?: -1) >= 0
        else -> condition::class in checkableKinds
    }

    fun describe(condition: QuestCondition): String = when (val c = condition) {
        is Check.DistanceTo -> "Within ${c.range} tiles of ${at(c.x, c.y)}"
        is Check.DistanceFrom -> "More than ${c.range} tiles from ${at(c.x, c.y)}"
        is Check.DistanceToWithHeight -> "Within ${c.range} tiles of ${at(c.x, c.y)}${heightSuffix(c.heightFine)}"
        is Check.DistanceFromWithHeight -> "More than ${c.range} tiles from ${at(c.x, c.y)}${heightSuffix(c.heightFine)}"
        is Check.CaptureConversationState -> "Capture conversation state: $DOLLAR${c.key}"
        is Check.StateEquals -> "State ${c.key} == ${c.value}"
        is Check.InventoryContains -> "Inventory contains ${c.quantity}$TIMES ${c.displayName}"
        is Check.InventoryDoesNotContain -> "Inventory does NOT contain ${c.displayName}"
        is Check.ModelVisible -> c.displayName + " visible"
        is Check.ModelNotVisible -> c.displayName + " not visible"
        is Check.InterfaceOpen -> if (c.interfaceId >= 0) "Interface ${c.interfaceId} open" else "Interface open (set id)"
        is Check.NpcNearTile -> c.displayName.ifBlank { "NPC #${c.typeId}" } +
            " within ${c.distance} of (${c.tileX}, ${c.tileY})" + (if (c.instance) " [instance-local]" else "")
        is Check.ConversationText -> "Dialog says: " + quoted(c.text)
        is Check.ChatText -> "Chat says: " + quoted(c.text)
        is Check.InCombatWith -> "In combat with " + when {
            c.displayName.isNotBlank() -> c.displayName
            c.npcId >= 0 -> "NPC #${c.npcId}"
            else -> "(unknown enemy)"
        }
        is Check.ItemClicked -> "Item #${c.itemId} clicked"
        is Check.Unknown -> "(unsupported: ${c.name})"
        else -> fixedText.getValue(c)
    }

    private val fixedText: Map<QuestCondition, String> = mapOf(
        Check.NotInInstance to "Not in an instance",
        Check.ChangedInstance to "Changed instance",
        Check.Manual to "(manual advance)",
        Check.Always to "(always true)",
        Check.QuestStarted to "Quest started (manual advance)",
        Check.QuestComplete to "Quest complete (manual advance)",
        Check.QuestInterfaceOpen to "Quest dialog open",
        Check.ConversationActive to "Conversation active",
        Check.ConversationInactive to "Conversation inactive",
        Check.InInstance to "Player in instance",
        Check.Generic to "(generic - manual advance)",
    )

    private fun heightSuffix(heightFine: Double) = " at height ${heightFine.toInt()}"

    private fun quoted(text: String) = QUOTE + text.take(QUOTE_LIMIT) + QUOTE

    private fun seen(spec: QuestTargetMatcher.Spec, atLeast: Int): Boolean =
        if (atLeast > 0) QuestTargetMatcher.countAtLeast(spec, atLeast) else QuestTargetMatcher.isPresent(spec)

    /** Refreshes the dialogue snapshot and runs pending log or sweep requests; interfaces are only read here. */
    fun tickSnapshot() {
        val wantLog = logRequested
        val sweep = sweepRequested
        if (!debugCaptureEnabled && !wantLog && sweep == null) {
            if (lastDialogCapture.isNotEmpty()) lastDialogCapture = listOf()
            return
        }
        val samples = InterfaceTextCapture.samplesByInterface(InterfaceTextCapture.DIALOG_INTERFACES)
        lastDialogCapture = samples.map { (id, text) -> DialogCapture(id, text) }
        if (wantLog) {
            logRequested = false
            println("[QuestDialog] ${samples.size} dialogue interfaces with text")
            samples.forEach { (id, text) -> println("[QuestDialog]   $id: ${text.take(300)}") }
        }
        if (sweep != null) {
            sweepRequested = null
            val found = InterfaceTextCapture.samplesByInterface(sweep.toSet())
            println("[QuestDialog] sweep of $sweep found ${found.size} interfaces with text")
            found.toSortedMap().forEach { (id, text) ->
                val tag = if (id in InterfaceTextCapture.DIALOG_INTERFACES) "" else " (not a dialogue interface)"
                println("[QuestDialog]   $id$tag: ${text.take(250)}")
            }
        }
    }

    fun captureAllDialogText(): List<DialogCapture> = lastDialogCapture

    fun requestSweep(range: IntRange = 0..2000) {
        sweepRequested = range
    }

    fun requestLogState() {
        logRequested = true
    }

    fun logDialogState() = requestLogState()

    fun sweepAllInterfaces(range: IntRange = 0..2000) = requestSweep(range)

    private fun at(x: Double, y: Double) = "(${x.toInt()}, ${y.toInt()})"

    /** The world tile a guide coordinate names, or null for an instance offset with no instance origin. */
    private fun worldTile(x: Double, y: Double, instance: Boolean): Pair<Int, Int>? {
        if (!instance) return x.toInt() to y.toInt()
        val base = QuestInstanceTracker.origin ?: return null
        return base.x + x.toInt() to base.y + y.toInt()
    }

    private fun within(x: Double, y: Double, range: Int, instance: Boolean): Boolean {
        val (tx, ty) = worldTile(x, y, instance) ?: return false
        val standing = localPlayer.tile
        return max(abs(standing.x - tx), abs(standing.y - ty)) <= range.coerceAtLeast(0)
    }

    private fun onHeight(heightFine: Double): Boolean {
        val plane = localPlayer.tile.level
        return if (heightFine > HIGH_GROUND_FINE) plane > 0 else plane == 0
    }

    private fun npcNear(c: QuestCondition.NpcNearTile): Boolean {
        if (c.typeId < 0 && c.candidateNpcTypeIds.isEmpty()) return false
        val (tx, ty) = worldTile(c.tileX.toDouble(), c.tileY.toDouble(), c.instance) ?: return false
        return QuestTargetMatcher.anyNpcNearTile(QuestTargetMatcher.specFor(c), tx, ty, c.plane, c.distance)
    }

    private fun stageOfSelected(): Pair<Int, Int>? {
        val quest = ActiveQuestState.selectedQuest ?: return null
        if (quest.stageVarbit < 0) return null
        return varps.getVarBit(quest.stageVarbit) to quest.stageVarbitCompleteValue
    }

    private fun carried(itemId: Int, candidates: List<Int>, displayName: String, exactName: Boolean): Int {
        var total = 0
        if (itemId >= 0) total += countOrZero { inventory.count(itemId) }
        if (candidates.isNotEmpty()) total += countOrZero { inventory.count(*candidates.toIntArray()) }
        val wanted = displayName.trim().lowercase()
        if (total > 0 || wanted.isEmpty()) return total
        return inventory.filter { it.amount > 0 }.filter { item ->
            val name = item.name.lowercase()
            if (exactName) name == wanted else name.contains(wanted)
        }.sumOf { it.amount }
    }

    private inline fun countOrZero(read: () -> Int): Int = try {
        read()
    } catch (_: Throwable) {
        0
    }

    private fun dialogueSays(needle: String): Boolean {
        val wanted = normalise(needle).ifEmpty { return false }
        val inOptions = dialogueOptions.keys.any { normalise(it).contains(wanted) }
        val found = inOptions || normalise(InterfaceTextCapture.textFor(InterfaceTextCapture.DIALOG_INTERFACES)).contains(wanted)
        if (!debugCaptureEnabled) return found
        val samples = publishCapture()
        if (!found) logMiss(needle, samples)
        return found
    }

    private fun publishCapture(): Map<Int, String> {
        val samples = InterfaceTextCapture.samplesByInterface(InterfaceTextCapture.DIALOG_INTERFACES)
        lastDialogCapture = samples.map { (id, text) -> DialogCapture(id, text) }
        return samples
    }

    private fun logMiss(needle: String, samples: Map<Int, String>) {
        val loggedAt = System.currentTimeMillis()
        if (loggedAt - lastMissLogMs < MISS_LOG_INTERVAL_MS) return
        lastMissLogMs = loggedAt
        println("[QuestDialog] no match for \"${needle.take(80)}\"")
        samples.forEach { (id, text) -> println("[QuestDialog]   $id: $text") }
    }

    private fun normalise(text: String): String = text.replace(markup, "").replace(whitespace, "").lowercase()
}
