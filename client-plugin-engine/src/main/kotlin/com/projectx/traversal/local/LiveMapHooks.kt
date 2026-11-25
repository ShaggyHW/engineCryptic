package com.projectx.traversal.local

import com.projectx.game.collision.CollisionFlags
import com.projectx.game.collision.CollisionMap
import com.projectx.pathfinder.StepRule
import com.projectx.traversal.hops.HopBlockers
import com.projectx.traversal.hops.HopMap
import world.gregs.voidps.type.Tile

/** The live collision map as hop learning reads it: its walk flags are already in the shared 11-bit layout. */
internal object CollisionHopMap : HopMap {
    override fun walk(): StepRule = LocalNav.rule()

    override fun contains(x: Int, y: Int, plane: Int): Boolean = CollisionMap.snapshot().contains(x, y, plane)

    override fun known(x: Int, y: Int, plane: Int): Boolean =
        LocalNav.trustedLive(Tile.of(x, y, plane)) && !CollisionMap.snapshot().isStatic(x, y, plane)

    override fun flags(x: Int, y: Int, plane: Int): Int = CollisionMap.snapshot().flags(x, y, plane) and CollisionFlags.WALK_MASK
}

/** Plugs the live collision map into what reads a live map: the worker, hop learning's map and its edge blockers. */
internal object LiveMapHooks {
    @Volatile
    private var installed = false

    val blockerSource = HopBlockers.Source { x, y, plane, dx, dy ->
        CollisionMap.blockers(x, y, plane, dx, dy)?.map {
            HopBlockers.Blocker(it.id, it.shape, it.rotation, it.clipType, it.blocksProjectile, it.bitsFrom, it.bitsTo, it.bitsSides)
        }
    }

    /** Game thread: where the client and the local player's entity are. */
    fun feed(clientAddress: Long, entityAddress: Long) {
        CollisionMap.feed(clientAddress, entityAddress)
        if (installed || !CollisionMap.supported) return
        installed = true
        HopMap.install(CollisionHopMap)
        HopBlockers.source = blockerSource
    }
}
