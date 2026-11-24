package com.projectx.traversal.nodes

import com.projectx.game.nxt.entity.location.SceneObject
import com.projectx.game.nxt.entity.npc.NPC
import com.projectx.pathfinder.WalkField
import com.projectx.script.api.allNpcsWithinRange
import com.projectx.script.api.getAllObjectsWithinRange
import com.projectx.traversal.local.LocalNav
import world.gregs.voidps.type.Tile
import kotlin.math.max
import com.projectx.script.api.Area as WorldArea

/**
 * Which object or NPC a node uses: the one the player reaches in the fewest steps on the live map, measured to the tiles
 * it can be used from (its footprint and the ring around it), so one across a wall never wins over one round the corner.
 * Every lookup answers null when the map is not trusted at the player; callers then pick without it.
 */
internal object LocalTargets {

    /** [distance] is [LocalNav.UNREACHABLE] when no candidate is reachable. */
    data class Choice<T>(val target: T, val distance: Int) {
        val reachable: Boolean get() = distance >= 0
    }

    /** With an anchor, objects this much farther from it than the closest match are other objects. */
    const val ANCHOR_SLACK = 2

    /** Twins this much farther from the step's destination than the nearest one lead elsewhere. */
    const val DEST_SLACK = 1

    /** A destination farther than this from every twin is a teleport's, so it says nothing about which twin is meant. */
    const val DEST_NEAR = 8

    /** The scene query pads by this so a large object's south-west tile is not cut off the search box. */
    const val FOOTPRINT_PAD = 4

    private const val FAR = Int.MAX_VALUE / 2

    /**
     * The matching object within [radius] of [anchor] the player reaches in the fewest steps. [anchored] means [anchor]
     * is the route step's own tile: only matches about as close to it as the closest one are candidates, and walking
     * only picks between twins there. A [destination] tells mirrored twins apart by which one it lies behind.
     */
    fun nearestObject(
        anchor: Tile?,
        radius: Int,
        predicate: (SceneObject) -> Boolean,
        anchored: Boolean = false,
        destination: WorldArea.Rectangular? = null,
    ): Choice<SceneObject>? {
        if (anchor == null) return null
        return nearestObject(anchor, radius, Rect.of(anchor), -1, predicate, anchored, destination)
    }

    /**
     * Anchored on a route step's origin area: distances are to the whole area, and an object whose footprint is more
     * than [radius] from it is never a candidate, even when nothing nearer is loaded (a twin across town wins while the
     * step's own object has not loaded after a teleport).
     */
    fun nearestObjectNear(
        origin: WorldArea.Rectangular?,
        radius: Int,
        predicate: (SceneObject) -> Boolean,
        destination: WorldArea.Rectangular?,
    ): Choice<SceneObject>? {
        if (origin == null) return null
        val rect = Rect.of(origin)
        val span = max(rect.width, rect.height)
        return nearestObject(origin.getBottomLeft(), radius + span + FOOTPRINT_PAD, rect, radius, predicate, true, destination)
    }

    /** True when [o]'s footprint is on [origin]'s plane and at most [radius] from it. */
    fun nearOrigin(o: SceneObject, origin: WorldArea.Rectangular, radius: Int): Boolean =
        nearOrigin(o.tileX, o.tileY, o.plane, o.footprintX, o.footprintY, origin, radius)

    fun nearOrigin(x: Int, y: Int, plane: Int, sizeX: Int, sizeY: Int, origin: WorldArea.Rectangular, radius: Int): Boolean {
        val rect = Rect.of(origin)
        return plane == rect.plane && rect.distanceTo(x, y, sizeX, sizeY) <= radius
    }

    /** A negative [cap] puts no cap on the distance to [origin]. */
    private fun nearestObject(
        from: Tile,
        range: Int,
        origin: Rect,
        cap: Int,
        predicate: (SceneObject) -> Boolean,
        anchored: Boolean,
        destination: WorldArea.Rectangular?,
    ): Choice<SceneObject>? {
        val field = LocalNav.field(TraversalSupport.serverTileOrNull() ?: return null) ?: return null
        val dest = destination?.let(Rect::of)
        val found = ArrayList<SceneObject>()
        val dist = ArrayList<IntArray>()
        for (o in getAllObjectsWithinRange(from, range)) {
            if (!o.exists || !predicate(o)) continue
            val sizeX = o.footprintX
            val sizeY = o.footprintY
            val straight = if (o.plane == origin.plane) origin.distanceTo(o.tileX, o.tileY, sizeX, sizeY) else FAR
            if (cap >= 0 && straight > cap) continue
            val walk = field.distanceToFootprint(o.tileX, o.tileY, o.plane, sizeX, sizeY)
            // x/y only: the landing is usually on another plane.
            val toDest = dest?.distanceTo(o.tileX, o.tileY, sizeX, sizeY) ?: -1
            found += o
            dist += intArrayOf(straight, walk, toDest)
        }
        val i = pick(dist, anchored)
        if (i < 0) return null
        val d = dist[i][1]
        return Choice(found[i], if (d >= 0) d else LocalNav.UNREACHABLE)
    }

    /** Chebyshev gap between two rectangles given as south-west corner and size; 0 when they touch or overlap. */
    fun rectDistance(ax: Int, ay: Int, aw: Int, ah: Int, bx: Int, by: Int, bw: Int, bh: Int): Int {
        val dx = max(0, max(bx - (ax + aw - 1), ax - (bx + bw - 1)))
        val dy = max(0, max(by - (ay + ah - 1), ay - (by + bh - 1)))
        return max(dx, dy)
    }

    /**
     * Index of the pick among candidates' {to the anchor, by walking (negative: unreachable), to the destination
     * (optional; negative: none)}: the nearest reachable by walking, else the closest to the anchor; with [anchored],
     * only among those within [ANCHOR_SLACK] of the closest to it. A destination at most [DEST_NEAR] away narrows the
     * reachable ones to those within [DEST_SLACK] of the reachable one nearest to it: an unreachable match nearer it is
     * the far piece of the same crossing. A walking tie goes to the one nearer the destination. -1 when none.
     */
    fun pick(dist: List<IntArray>, anchored: Boolean): Int {
        var min = Int.MAX_VALUE
        if (anchored) for (d in dist) min = minOf(min, d[0])
        var minDest = Int.MAX_VALUE
        if (anchored) {
            for (d in dist) {
                if (d[0] <= min + ANCHOR_SLACK && d[1] >= 0 && d.size > 2 && d[2] >= 0) minDest = minOf(minDest, d[2])
            }
            if (minDest > DEST_NEAR) minDest = Int.MAX_VALUE
        }
        val destSays = minDest != Int.MAX_VALUE
        var best = -1
        var bestD = Int.MAX_VALUE
        var closest = -1
        var closestD = Int.MAX_VALUE
        for ((i, d) in dist.withIndex()) {
            if (anchored && d[0] > min + ANCHOR_SLACK) continue
            if (d[0] < closestD) {
                closestD = d[0]
                closest = i
            }
            val behindDest = !destSays || d.size > 2 && d[2] >= 0 && d[2] <= minDest + DEST_SLACK
            val nearerDest = destSays && best >= 0 && d[1] == bestD && d[2] < dist[best][2]
            if (d[1] >= 0 && behindDest && (d[1] < bestD || nearerDest)) {
                bestD = d[1]
                best = i
            }
        }
        return if (best >= 0) best else closest
    }

    fun nearestNpc(radius: Int, predicate: (NPC) -> Boolean): Choice<NPC>? {
        val field = LocalNav.field(TraversalSupport.serverTileOrNull() ?: return null) ?: return null
        var best: NPC? = null
        var closest: NPC? = null
        var bestD = Int.MAX_VALUE
        for (npc in allNpcsWithinRange(radius, predicate)) {
            if (closest == null) closest = npc
            val d = npcDistance(field, npc)
            if (d in 0 until bestD) {
                bestD = d
                best = npc
            }
        }
        best?.let { return Choice(it, bestD) }
        return closest?.let { Choice(it, LocalNav.UNREACHABLE) }
    }

    /** Steps to an object's interaction tiles, [LocalNav.UNKNOWN] when untrusted. */
    fun objectDistance(o: SceneObject?): Int {
        val from = TraversalSupport.serverTileOrNull() ?: return LocalNav.UNKNOWN
        val field = LocalNav.field(from) ?: return LocalNav.UNKNOWN
        if (o == null) return LocalNav.UNKNOWN
        return field.distanceToFootprint(o.tileX, o.tileY, o.plane, o.footprintX, o.footprintY)
    }

    /** Steps to an NPC's interaction tiles, [LocalNav.UNKNOWN] when untrusted. */
    fun npcDistance(npc: NPC?): Int {
        val from = TraversalSupport.serverTileOrNull() ?: return LocalNav.UNKNOWN
        val field = LocalNav.field(from) ?: return LocalNav.UNKNOWN
        return if (npc == null) LocalNav.UNKNOWN else npcDistance(field, npc)
    }

    /** An NPC's tile is not reliably its south-west corner when it is larger than one, so the square is centred on it. */
    private fun npcDistance(field: WalkField, npc: NPC): Int {
        val tile = npc.tile
        val span = max(1, npc.size) - 1
        val side = 2 * span + 1
        return field.distanceToFootprint(tile.x - span, tile.y - span, tile.plane, side, side)
    }

    private class Rect(val x: Int, val y: Int, val width: Int, val height: Int, val plane: Int) {
        fun distanceTo(ox: Int, oy: Int, sizeX: Int, sizeY: Int): Int = rectDistance(ox, oy, sizeX, sizeY, x, y, width, height)

        companion object {
            fun of(tile: Tile) = Rect(tile.x, tile.y, 1, 1, tile.plane)

            fun of(area: WorldArea.Rectangular): Rect {
                val bl = area.getBottomLeft()
                val tr = area.getTopRight()
                return Rect(bl.x, bl.y, tr.x - bl.x + 1, tr.y - bl.y + 1, bl.plane)
            }
        }
    }
}
