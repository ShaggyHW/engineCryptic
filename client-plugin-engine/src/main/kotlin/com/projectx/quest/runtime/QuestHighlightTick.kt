package com.projectx.quest.runtime

import com.projectx.game.highlight.EntityHighlight
import com.projectx.game.nxt.HeightMap
import com.projectx.game.nxt.entity.Entity
import com.projectx.game.nxt.entity.location.SceneObject
import com.projectx.quest.data.QuestAction
import com.projectx.quest.data.QuestStep
import com.projectx.quest.solver.QuestSolverRegistry
import com.projectx.ui.UIState
import world.gregs.voidps.type.Tile

/**
 * Outlines the current step's highlight targets on the game thread and publishes where they are, so the render
 * thread can label them without touching game memory.
 */
object QuestHighlightTick {
    private const val OUTLINE_COLOUR = 0x00FFFF
    private const val OUTLINE_SCALE = 8
    private const val FINE_PER_TILE = 512
    private const val SOLVER_STAMP_MS = 250L

    data class Label(
        val fineX: Float,
        val fineY: Float,
        val fineZ: Float,
        val name: String,
        val tiles: List<Tile> = listOf(),
    )

    @Volatile var labels: Map<QuestAction.ModelHighlight, List<Label>> = mapOf()
        private set

    @Volatile var solverOverlayActions: List<QuestAction> = listOf()
        private set

    private var seenOrigin: QuestInstanceTracker.Origin? = null
    private var seenSlug: String? = null
    private var seenStep = -1

    fun tick() {
        val origin = QuestInstanceTracker.origin
        val guided = ActiveQuestState.selectedQuest
        val index = ActiveQuestState.currentStepIndex
        // Entities from an earlier instance or step may already be freed, so they are forgotten, never written.
        if (origin != seenOrigin || guided?.slug != seenSlug || index != seenStep) {
            seenOrigin = origin
            seenSlug = guided?.slug
            seenStep = index
            labels = mapOf()
        }
        val showing = UIState.questHelperEnabled.value && UIState.questHelperShowOverlay.value
        if (!showing) {
            labels = mapOf()
            solverOverlayActions = listOf()
            return
        }
        val step = guided?.steps?.getOrNull(index)
        if (step == null) {
            solverOverlayActions = listOf()
            return
        }
        val extra = solverActions(step, index)
        solverOverlayActions = extra
        val found = LinkedHashMap<QuestAction.ModelHighlight, List<Label>>()
        for (action in (step.actions + extra).filterIsInstance<QuestAction.ModelHighlight>()) {
            val marks = when (action.kind) {
                "item" -> continue
                "grounditem" -> groundLabels(action)
                else -> entityLabels(action)
            }
            if (marks.isNotEmpty()) found[action] = marks
        }
        labels = found
    }

    private fun solverActions(step: QuestStep, index: Int): List<QuestAction> {
        val quest = ActiveQuestState.selectedQuest ?: return listOf()
        if (step.solverId == null) return listOf()
        return QuestSolverRegistry.overlayActionsFor(quest, step, index, System.currentTimeMillis() / SOLVER_STAMP_MS)
    }

    private fun groundLabels(action: QuestAction.ModelHighlight): List<Label> {
        val everyMatch = action.priority == "all"
        val matches = QuestTargetMatcher.findGroundItems(action).let { if (everyMatch) it else it.take(1) }
        return matches.map { item ->
            val tile = item.tile
            val height = HeightMap.fineHeight(tile) ?: 0
            Label(
                fineX = (tile.x * FINE_PER_TILE + FINE_PER_TILE / 2).toFloat(),
                fineY = (tile.y * FINE_PER_TILE + FINE_PER_TILE / 2).toFloat(),
                fineZ = height.toFloat(),
                name = item.name,
                tiles = listOf(tile),
            )
        }
    }

    private fun entityLabels(action: QuestAction.ModelHighlight): List<Label> {
        val targets = if (action.priority == "all") QuestTargetMatcher.findAll(action) else listOfNotNull(QuestTargetMatcher.findFirst(action))
        return targets.mapNotNull { entity ->
            EntityHighlight.apply(entity, color = OUTLINE_COLOUR, mode = EntityHighlight.Mode.OUTLINE, scale = OUTLINE_SCALE)
            labelOf(entity)
        }
    }

    private fun labelOf(entity: Entity): Label? = try {
        val at = entity.graphNode.tileFine
        val footprint = (entity as? SceneObject)?.occupiedTiles().orEmpty()
        Label(at.x, at.y, at.z, "", footprint)
    } catch (_: Throwable) {
        null
    }
}
