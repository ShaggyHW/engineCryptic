package com.projectx.quest.runtime

import com.projectx.game.bootstrap.Bootstrap
import com.projectx.game.nxt.entity.Entity
import com.projectx.game.nxt.entity.location.SceneObject
import com.projectx.game.nxt.entity.npc.NPC
import com.projectx.quest.data.QuestAction
import com.projectx.quest.data.QuestCondition
import com.projectx.quest.data.WorldLocation
import com.projectx.script.api.getAllObjectsWithinRange
import com.projectx.script.api.groundItems
import com.projectx.script.api.localPlayer
import world.gregs.voidps.type.Tile
import kotlin.math.abs
import kotlin.math.max

/**
 * Resolves guide targets to live scene entities by type id alone. NPC and loc types are never looked up during a
 * scan: resolving them while the scene is rebuilt on instance entry corrupted client memory.
 */
object QuestTargetMatcher {
    private const val LOC_RADIUS = 60
    private const val NO_ANIMATION = 0xFFFF

    /** What to look for: the ids to match, which sort of entity, and optionally the exact tile. */
    data class Spec(val typeId: Int = -1, val candidateNpcTypeIds: List<Int> = listOf(), val candidateObjectTypeIds: List<Int> = listOf(),
                    val displayName: String = "", val kind: String = "", val atLocation: WorldLocation? = null,
                    val instance: Boolean = false, val animated: Boolean = false)

    /** [name] is filled only when the match was made by name. */
    data class GroundItemMatch(
        val id: Int,
        val name: String,
        val tile: Tile,
    )

    fun specFor(action: QuestAction.ModelHighlight) = Spec(
        action.typeId, action.candidateNpcTypeIds, action.candidateObjectTypeIds, action.displayName, action.kind,
        action.atLocation, action.instance, animated = false,
    )

    fun specFor(condition: QuestCondition.ModelVisible) = Spec(
        condition.typeId, condition.candidateNpcTypeIds, condition.candidateObjectTypeIds, condition.displayName, condition.kind,
        condition.atLocation, condition.instance, condition.animated,
    )

    fun specFor(condition: QuestCondition.ModelNotVisible) = Spec(
        condition.typeId, condition.candidateNpcTypeIds, condition.candidateObjectTypeIds, condition.displayName, condition.kind,
        condition.atLocation, condition.instance, animated = false,
    )

    fun specFor(condition: QuestCondition.NpcNearTile) = Spec(
        typeId = condition.typeId,
        candidateNpcTypeIds = condition.candidateNpcTypeIds,
        displayName = condition.displayName,
        kind = "npc",
    )

    fun findFirst(action: QuestAction.ModelHighlight): Entity? = specFor(action).let(::findFirst)

    fun findAll(action: QuestAction.ModelHighlight): List<Entity> = specFor(action).let(::findAll)

    fun findGroundItems(action: QuestAction.ModelHighlight): List<GroundItemMatch> = specFor(action).let(::findGroundItems)

    /** For kind `model` a matching NPC always wins over a loc. */
    fun findFirst(spec: Spec): Entity? = findAll(spec).let { if (it.isEmpty()) null else it[0] }

    fun findAll(spec: Spec): List<Entity> = when (spec.kind) {
        "grounditem" -> listOf()
        "object" -> locs(spec)
        "npc" -> npcs(spec)
        else -> npcs(spec) + locs(spec)
    }

    fun isPresent(spec: Spec): Boolean = countAtLeast(spec, 1)

    fun countAtLeast(spec: Spec, n: Int): Boolean {
        val found = if (spec.kind == "grounditem") findGroundItems(spec).size else findAll(spec).size
        return found >= n
    }

    fun findGroundItems(spec: Spec): List<GroundItemMatch> = if (spec.kind == "grounditem") scanGroundItems(spec) else listOf()

    private fun scanGroundItems(spec: Spec): List<GroundItemMatch> {
        val wanted = spec.displayName.trim()
        if (spec.typeId < 0 && wanted.isEmpty()) return listOf()
        val me = playerTile() ?: return listOf()
        val spot = anchorTile(spec)
        val matches = mutableListOf<GroundItemMatch>()
        for (item in groundItems) {
            try {
                if (item.tile.level != me.level) continue
                if (spot != null && (item.tile.x != spot.first || item.tile.y != spot.second)) continue
                when {
                    spec.typeId >= 0 -> if (item.id == spec.typeId) matches += GroundItemMatch(item.id, "", item.tile)
                    else -> item.name.takeIf { it.contains(wanted, ignoreCase = true) }?.let { matches += GroundItemMatch(item.id, it, item.tile) }
                }
            } catch (_: Throwable) {
            }
        }
        return matches.sortedBy { squaredDistance(me, it.tile.x, it.tile.y) }
    }

    fun anyNpcNearTile(spec: Spec, x: Int, y: Int, plane: Int, distance: Int): Boolean {
        val wanted = npcIds(spec).ifEmpty { return false }
        return liveNpcs().any { npc ->
            try {
                val tile = npc.tile
                tile.level == plane && npcType(npc) in wanted && max(abs(tile.x - x), abs(tile.y - y)) <= distance
            } catch (_: Throwable) {
                false
            }
        }
    }

    private fun npcIds(spec: Spec): Set<Int> = buildSet {
        if (spec.typeId >= 0) add(spec.typeId)
        addAll(spec.candidateNpcTypeIds)
    }

    private fun locIds(spec: Spec): Set<Int> = buildSet {
        if (spec.typeId >= 0) add(spec.typeId)
        addAll(spec.candidateObjectTypeIds)
    }

    private fun npcs(spec: Spec): List<Entity> {
        val wanted = npcIds(spec)
        if (wanted.isEmpty()) return listOf()
        val me = playerTile() ?: return listOf()
        val spot = anchorTile(spec)
        val hits = mutableListOf<Pair<NPC, Long>>()
        for (npc in liveNpcs()) {
            try {
                if (npcType(npc) !in wanted) continue
                val tile = npc.tile
                if (tile.level != me.level) continue
                if (spot != null && (tile.x != spot.first || tile.y != spot.second)) continue
                if (spec.animated && !npc.isPlayingAnimation()) continue
                hits += npc to squaredDistance(me, tile.x, tile.y)
            } catch (_: Throwable) {
            }
        }
        return hits.sortedBy { it.second }.map { it.first }
    }

    private fun locs(spec: Spec): List<Entity> {
        val wanted = locIds(spec)
        if (wanted.isEmpty()) return listOf()
        val me = playerTile() ?: return listOf()
        val spot = anchorTile(spec)
        val hits = mutableListOf<Pair<Entity, Long>>()
        for (loc: SceneObject in getAllObjectsWithinRange(LOC_RADIUS)) {
            try {
                val entity = loc as? Entity ?: continue
                if (loc.visibleTypeId !in wanted) continue
                val tile = loc.tile
                if (tile.level != me.level) continue
                if (spot != null && (tile.x != spot.first || tile.y != spot.second)) continue
                hits += entity to squaredDistance(me, tile.x, tile.y)
            } catch (_: Throwable) {
            }
        }
        return hits.sortedBy { it.second }.map { it.first }
    }

    private fun liveNpcs(): List<NPC> = with(Bootstrap.client.npcManager) {
        indices.filter { it > 0 }.mapNotNull { key ->
            try {
                get(key)?.takeIf { it.address() != 0L }?.let(::NPC)?.takeIf { it.exists() }
            } catch (_: Throwable) {
                null
            }
        }
    }

    private fun npcType(npc: NPC): Int = npc.typeId.let { if (it == -1) npc.id else it }

    private fun NPC.isPlayingAnimation(): Boolean = animationId.let { it >= 0 && it != NO_ANIMATION }

    /** The exact tile a located target must stand on; with no instance origin the location is ignored. */
    private fun anchorTile(spec: Spec): Pair<Int, Int>? {
        val at = spec.atLocation ?: return null
        if (!spec.instance) return at.x.toInt() to at.y.toInt()
        val base = QuestInstanceTracker.origin ?: return null
        return base.x + at.x.toInt() to base.y + at.y.toInt()
    }

    private fun playerTile(): Tile? = try {
        localPlayer.tile
    } catch (_: Throwable) {
        null
    }

    private fun squaredDistance(from: Tile, x: Int, y: Int): Long {
        val east = (x - from.x).toLong()
        val north = (y - from.y).toLong()
        return east * east + north * north
    }
}
