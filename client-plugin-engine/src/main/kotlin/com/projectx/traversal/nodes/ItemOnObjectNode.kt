package com.projectx.traversal.nodes

import com.projectx.game.nxt.entity.location.SceneObject
import com.projectx.script.Script
import com.projectx.script.api.findClosestObjectToTile
import com.projectx.script.api.findClosestReachableObject
import com.projectx.script.api.inventory
import com.projectx.script.api.isType
import com.projectx.traversal.EarlyStart
import com.projectx.traversal.TraversalNode
import world.gregs.voidps.type.Tile
import com.projectx.script.api.Area as WorldArea
import com.projectx.util.Area as FlatArea

class ItemOnObjectNode private constructor(
    private val itemIdentifier: ItemIdentifier,
    private val objIdentifier: ObjectIdentifier,
    private var destination: NodeDestination?,
    private var objectSearchRadius: Int,
    private var customReached: (() -> Boolean)?,
) : TraversalNode(), EarlyStart {
    private var nextClick: Long = 0

    private constructor(itemIdentifier: ItemIdentifier, objIdentifier: ObjectIdentifier) : this(itemIdentifier, objIdentifier, null as NodeDestination?, 20, null)

    constructor(
        itemIdentifier: ItemIdentifier,
        objIdentifier: ObjectIdentifier,
        destination: WorldArea?,
        objectSearchRadius: Int = 20,
        customReached: (() -> Boolean)? = null,
    ) : this(itemIdentifier, objIdentifier, NodeDestination.of(destination), objectSearchRadius, customReached)

    constructor(
        itemName: String,
        obj: SceneObject,
        destination: FlatArea? = null,
        objectSearchRadius: Int = 20,
        customReached: (() -> Boolean)? = null
    ) : this(
        ItemIdentifier.NameRef(itemName),
        ObjectIdentifier.ObjectRef(obj)
    ) {
        this.destination = NodeDestination.of(destination)
        this.customReached = customReached
        this.objectSearchRadius = objectSearchRadius
    }

    constructor(
        itemId: Int,
        obj: SceneObject,
        destination: FlatArea? = null,
        objectSearchRadius: Int = 20,
        customReached: (() -> Boolean)? = null
    ) : this(
        ItemIdentifier.IdRef(itemId),
        ObjectIdentifier.ObjectRef(obj)
    ) {
        this.destination = NodeDestination.of(destination)
        this.customReached = customReached
        this.objectSearchRadius = objectSearchRadius
    }

    constructor(
        itemName: String,
        objectName: String,
        destination: FlatArea? = null,
        objectSearchRadius: Int = 20,
        customReached: (() -> Boolean)? = null
    ) : this(
        ItemIdentifier.NameRef(itemName),
        ObjectIdentifier.NameRef(objectName)
    ) {
        this.destination = NodeDestination.of(destination)
        this.customReached = customReached
        this.objectSearchRadius = objectSearchRadius
    }

    constructor(
        itemId: Int,
        objectName: String,
        destination: FlatArea? = null,
        objectSearchRadius: Int = 20,
        customReached: (() -> Boolean)? = null
    ) : this(
        ItemIdentifier.IdRef(itemId),
        ObjectIdentifier.NameRef(objectName)
    ) {
        this.destination = NodeDestination.of(destination)
        this.customReached = customReached
        this.objectSearchRadius = objectSearchRadius
    }

    constructor(
        itemName: String,
        objectId: Int,
        destination: FlatArea? = null,
        objectSearchRadius: Int = 20,
        customReached: (() -> Boolean)? = null
    ) : this(
        ItemIdentifier.NameRef(itemName),
        ObjectIdentifier.IdRef(objectId)
    ) {
        this.destination = NodeDestination.of(destination)
        this.customReached = customReached
        this.objectSearchRadius = objectSearchRadius
    }

    constructor(
        itemId: Int,
        objectId: Int,
        destination: FlatArea? = null,
        objectSearchRadius: Int = 20,
        customReached: (() -> Boolean)? = null
    ) : this(
        ItemIdentifier.IdRef(itemId),
        ObjectIdentifier.IdRef(objectId)
    ) {
        this.destination = NodeDestination.of(destination)
        this.customReached = customReached
        this.objectSearchRadius = objectSearchRadius
    }

    private fun getItem() = when (itemIdentifier) {
        is ItemIdentifier.NameRef -> inventory.find { it.id != -1 && it.name == itemIdentifier.name }
        is ItemIdentifier.IdRef -> inventory.find { it.id == itemIdentifier.id }
    }

    private fun getTarget(): SceneObject? {
        val predicate: (SceneObject) -> Boolean = when (objIdentifier) {
            is ObjectIdentifier.ObjectRef -> return objIdentifier.obj
            is ObjectIdentifier.NameRef -> { obj -> obj.name().contains(objIdentifier.name) }
            is ObjectIdentifier.IdRef -> { obj -> objIdentifier.id == -1 || obj.isType(objIdentifier.id) }
        }
        LocalTargets.nearestObject(TraversalSupport.serverTileOrNull(), objectSearchRadius, predicate)?.let { return it.target }
        findClosestReachableObject(objectSearchRadius, predicate)?.let { return it }
        val me = TraversalSupport.serverTileOrNull() ?: return null
        return findClosestObjectToTile(me, SCENE_RANGE, false, predicate)
    }

    private fun busy(): Boolean = TraversalSupport.isMoving() || TraversalSupport.isAnimating()

    override suspend fun process(script: Script): Boolean {
        if (reached(script)) return false
        if (System.currentTimeMillis() < nextClick) return true

        val item = getItem()
        if (item == null) {
            script.delay(100, 100)
            return true
        }

        val target = getTarget()
        if (target == null) {
            script.delay(100, 100)
            return true
        }

        if (!item.useOn(target)) return false
        val done = { traversalStopRequested() || reached(script) }
        if (TraversalSupport.delayUntil(START_MS) { done() || busy() }) TraversalSupport.delayUntil(BUSY_MAX_MS) { done() || !busy() }
        nextClick = System.currentTimeMillis() + TraversalSupport.clickPacingJitter()
        return true
    }

    override fun reached(script: Script): Boolean {
        customReached?.let { return it() }
        return destination?.contains(TraversalSupport.serverTileOrNull()) == true
    }

    override fun readyFrom(serverTile: Tile): Boolean {
        if (getItem() == null) return false
        val target = getTarget() ?: return false
        if (target.plane != serverTile.plane) return false
        return LocalTargets.objectDistance(target) in 0..EARLY_START_STEPS
    }

    override fun copy(): TraversalNode = ItemOnObjectNode(itemIdentifier, objIdentifier, destination, objectSearchRadius, customReached)

    override fun toString() = "[${this.itemIdentifier} on ${this.objIdentifier}]"

    companion object {
        const val EARLY_START_STEPS = EarlyStart.MAX_STEPS
        private const val START_MS = 2_000L
        private const val BUSY_MAX_MS = 15_000L
        private const val SCENE_RANGE = 104
    }
}

sealed class ItemIdentifier {
    data class NameRef(val name: String) : ItemIdentifier()
    data class IdRef(val id: Int) : ItemIdentifier()
}