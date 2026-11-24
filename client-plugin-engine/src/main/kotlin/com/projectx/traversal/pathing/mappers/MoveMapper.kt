package com.projectx.traversal.pathing.mappers

import com.projectx.traversal.TraversalBuilder
import com.projectx.traversal.nodes.ClientAbilities
import com.projectx.traversal.pathing.MappingContext
import com.projectx.traversal.pathing.NavOptions
import com.projectx.traversal.pathing.Step
import world.gregs.voidps.type.Tile
import kotlin.math.abs
import kotlin.math.sign

/**
 * Condenses contiguous moves into one walk node: keypoints sampled every [DEFAULT_SAMPLE_DISTANCE] tiles, always the
 * first and last tile and both sides of a plane change, with every tile of the batch carried for the live-map walk.
 */
object MoveMapper {
    const val DEFAULT_SAMPLE_DISTANCE = 8

    @Suppress("UNUSED_PARAMETER")
    fun map(step: Step, builder: TraversalBuilder<*>, context: MappingContext): Unit =
        throw UnsupportedOperationException("MoveMapper requires a contiguous sequence. Use MoveMapper.mapSequence(moves, builder, options).")

    fun mapSequence(moves: List<Step.Move>, builder: TraversalBuilder<*>, options: NavOptions) {
        if (moves.isEmpty()) return
        val tiles = ArrayList<Tile>(moves.size)
        for (move in moves) {
            // A walk-up endpoint often repeats the batch's last move (a door's near side).
            if (tiles.lastOrNull() != move.tile) tiles += move.tile
        }
        val start = tiles.first()
        val abilities = if (options.clientPlacedAbilities) {
            val hops = options.surge.enabled || options.dive.enabled
            ClientAbilities(options.surge.enabled, options.dive.enabled, options.useAccelerationPotion && hops, options.exploreHops, options.scheduleHops)
        } else {
            ClientAbilities.NONE
        }
        if (options.preferChebyshev) {
            builder.chebychevPath(start, extractKeypoints(tiles, DEFAULT_SAMPLE_DISTANCE), tiles, abilities)
        } else {
            builder.pathTiles(start, tiles.last(), tiles, abilities)
        }
    }

    /**
     * The route with every planned Surge and Dive turned into the walk it covers, so the walk places its own hops on
     * the live map. The walk is the hop's [Step.Surge.walked] geometry, since the planner lets a hop clear a fence a
     * walker cannot pass; only a hop without one is walked as the straight line. A hop with no `from` starts at the
     * previous step's tile; with neither it becomes one move to its landing.
     */
    fun abilityStepsAsMoves(steps: List<Step>): List<Step> {
        val out = ArrayList<Step>(steps.size)
        var prev: Tile? = null
        for (step in steps) {
            val (from, to, walked) = when (step) {
                is Step.Surge -> Triple(step.from, step.to, step.walked)
                is Step.Dive -> Triple(step.from, step.to, step.walked)
                else -> {
                    out += step
                    if (step is Step.Move) prev = step.tile
                    continue
                }
            }
            val start = from ?: prev
            if (start != null && start.plane == to.plane) {
                if (prev != start) out += Step.Move(start)
                val walk = if (walked.isNotEmpty() && start == from) walked else line(start, to)
                walk.mapTo(out) { Step.Move(it) }
            } else {
                out += Step.Move(to)
            }
            prev = to
        }
        return out
    }

    /** Bresenham tiles from [a] (exclusive) to [b] (inclusive), on [a]'s plane. */
    internal fun line(a: Tile, b: Tile): List<Tile> {
        val out = ArrayList<Tile>()
        var x = a.x
        var y = a.y
        val dx = abs(b.x - x)
        val dy = abs(b.y - y)
        val sx = (b.x - x).sign
        val sy = (b.y - y).sign
        var err = dx - dy
        while (x != b.x || y != b.y) {
            val e2 = 2 * err
            if (e2 > -dy) {
                err -= dy
                x += sx
            }
            if (e2 < dx) {
                err += dx
                y += sy
            }
            out += Tile.of(x, y, a.plane)
        }
        return out
    }

    fun extractKeypoints(all: List<Tile>, sampleDistance: Int): List<Tile> {
        if (all.size <= 1) return all.toList()
        val out = ArrayList<Tile>(all.size)
        out += all.first()
        var lastSelected = all.first()
        var prev = all.first()
        for (i in 1 until all.size) {
            val curr = all[i]
            if (curr.plane != prev.plane) {
                if (lastSelected != prev) out += prev
                out += curr
                lastSelected = curr
                prev = curr
                continue
            }
            if (lastSelected.getLongestDelta(curr) >= sampleDistance) {
                out += curr
                lastSelected = curr
            }
            prev = curr
        }
        if (out.last() != all.last()) out += all.last()
        return out
    }
}
