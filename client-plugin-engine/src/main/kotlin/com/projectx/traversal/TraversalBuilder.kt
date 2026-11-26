package com.projectx.traversal

import world.gregs.voidps.type.Tile
import com.projectx.game.interfaces.IFSlot
import com.projectx.script.State
import com.projectx.script.StateMachineScript
import com.projectx.script.api.Lodestone
import com.projectx.script.api.TeleportSpell
import com.projectx.script.api.localPlayer
import com.projectx.traversal.nodes.*
import com.projectx.game.nxt.entity.location.SceneObject
import com.projectx.util.Area
import com.projectx.webwalker.WebWalker
import com.projectx.script.api.Area as WorldArea

/**
 * Assembles nodes into a [Traversal]. Destinations take the script [WorldArea], which counts the plane; the older
 * plane-blind [Area] overloads stay. The methods added since return the builder so calls chain.
 */
class TraversalBuilder<T : StateMachineScript<T>> {
    private val nodes = arrayListOf<TraversalNode>()

    private fun append(node: TraversalNode) = apply { nodes.add(node) }

    private fun arrivedIn(area: WorldArea): () -> Boolean = { area.contains(localPlayer.tile) }

    fun interactObj(action: String, searchRadius: Int = 30) = nodes.add(ObjectNode(-1, action, objectSearchRadius = searchRadius))
    fun interactObj(action: String, searchRadius: Int = 30, reached: () -> Boolean) = nodes.add(ObjectNode(-1, action, objectSearchRadius = searchRadius, customReached = reached))
    fun interactObj(id: Int, action: String, searchRadius: Int = 30) = nodes.add(ObjectNode(id, action, objectSearchRadius = searchRadius))
    fun interactObj(name: String, action: String, searchRadius: Int = 30) = nodes.add(ObjectNode(name, action, objectSearchRadius = searchRadius))
    fun interactObj(id: Int, action: String, searchRadius: Int = 30, reached: () -> Boolean) = nodes.add(ObjectNode(id, action, objectSearchRadius = searchRadius, customReached = reached))
    fun interactObj(name: String, action: String, searchRadius: Int = 30, reached: () -> Boolean) = nodes.add(ObjectNode(name, action, objectSearchRadius = searchRadius, customReached = reached))

    /** Only an object at [requestTile] (the route step's own tile) is used: a same-id twin elsewhere is not it. */
    fun interactObj(action: String, searchRadius: Int, requestTile: Tile, reached: (() -> Boolean)? = null) =
        append(ObjectNode(ObjectIdentifier.IdRef(-1), action, null, searchRadius, reached, requestTile))

    fun interactObj(id: Int, action: String, searchRadius: Int, requestTile: Tile, reached: (() -> Boolean)? = null) =
        append(ObjectNode(ObjectIdentifier.IdRef(id), action, null, searchRadius, reached, requestTile))

    fun interactObj(name: String, action: String, searchRadius: Int, requestTile: Tile, reached: (() -> Boolean)? = null) =
        append(ObjectNode(ObjectIdentifier.NameRef(name), action, null, searchRadius, reached, requestTile))

    /** Of twins at [requestTile], the one [destination] lies behind; reached on landing in it, or by [reached]. */
    fun interactObj(id: Int, action: String, searchRadius: Int, destination: WorldArea?, requestTile: Tile?, reached: (() -> Boolean)?) =
        append(objectAt(ObjectIdentifier.IdRef(id), action, searchRadius, destination, requestTile, reached))

    fun interactObj(name: String, action: String, searchRadius: Int, destination: WorldArea?, requestTile: Tile?, reached: (() -> Boolean)?) =
        append(objectAt(ObjectIdentifier.NameRef(name), action, searchRadius, destination, requestTile, reached))

    /**
     * The route mapper's form: [searchRadius] is measured from the step's [origin] area. With [skipWhenPassable] an
     * obstacle whose far side is already a short walk away is walked past.
     */
    fun interactObj(
        identifier: ObjectIdentifier,
        action: String?,
        searchRadius: Int,
        destination: WorldArea?,
        origin: WorldArea.Rectangular?,
        reached: (() -> Boolean)?,
        skipWhenPassable: Boolean,
    ) = append(ObjectNode(identifier, action, destination, searchRadius, reached, origin, skipWhenPassable))

    private fun objectAt(
        identifier: ObjectIdentifier,
        action: String,
        searchRadius: Int,
        destination: WorldArea?,
        requestTile: Tile?,
        reached: (() -> Boolean)?,
    ): ObjectNode = if (requestTile != null) {
        ObjectNode(identifier, action, destination, searchRadius, reached, requestTile)
    } else {
        ObjectNode(identifier, action, destination, searchRadius, reached)
    }

    fun interactNpc(action: String, searchRadius: Int = 30) =nodes.add(NpcNode(-1, action, npcSearchRadius = searchRadius))
    fun interactNpc(action: String, searchRadius: Int = 30, reached: () -> Boolean) = nodes.add(NpcNode(-1, action, npcSearchRadius = searchRadius, customReached = reached))
    fun interactNpc(id: Int, action: String, searchRadius: Int = 30) = nodes.add(NpcNode(id, action, npcSearchRadius = searchRadius))
    fun interactNpc(name: String, action: String, searchRadius: Int = 30) = nodes.add(NpcNode(name, action, npcSearchRadius = searchRadius))
    fun interactNpc(id: Int, action: String, searchRadius: Int = 30, reached: () -> Boolean) = nodes.add(NpcNode(id, action, npcSearchRadius = searchRadius, customReached = reached))
    fun interactNpc(name: String, action: String, searchRadius: Int = 30, reached: () -> Boolean) = nodes.add(NpcNode(name, action, npcSearchRadius = searchRadius, customReached = reached))

    fun clickIFSlot(ifSlot: IFSlot, optionNum: Int = 1, reached: (() -> Boolean)? = null) = nodes.add(IFSlotNode(ifSlot, optionNum, customReached = reached))
    fun clickIFSlot(ifSlot: IFSlot, optionNum: Int = 1, destination: Area) = nodes.add(IFSlotNode(ifSlot, optionNum, destination))

    fun clickIFSlot(ifSlot: IFSlot, optionNum: Int = 1, destination: WorldArea) =
        append(IFSlotNode(ifSlot, optionNum, customReached = arrivedIn(destination)))

    fun clickItem(itemName: String, option: String, reached: (() -> Boolean)? = null) =
        nodes.add(ItemNode(itemName, optionName = option, customReached = reached))

    fun clickItem(itemId: Int, option: String, reached: (() -> Boolean)? = null) =
        nodes.add(ItemNode(itemId, optionName = option, customReached = reached))

    fun clickItem(itemName: String, optionNum: Int, reached: (() -> Boolean)? = null) =
        nodes.add(ItemNode(itemName, optionNum = optionNum, customReached = reached))

    fun clickItem(itemId: Int, optionNum: Int, reached: (() -> Boolean)? = null) =
        nodes.add(ItemNode(itemId, optionNum = optionNum, customReached = reached))

    fun clickItem(itemName: String, option: String, destination: Area) =
        nodes.add(ItemNode(itemName, optionName = option, destination = destination))

    fun clickItem(itemId: Int, option: String, destination: Area) =
        nodes.add(ItemNode(itemId, optionName = option, destination = destination))

    fun clickItem(itemName: String, optionNum: Int, destination: Area) =
        nodes.add(ItemNode(itemName, optionNum = optionNum, destination = destination))

    fun clickItem(itemId: Int, optionNum: Int, destination: Area) =
        nodes.add(ItemNode(itemId, optionNum = optionNum, destination = destination))

    fun clickItem(itemName: String, option: String, destination: WorldArea) =
        append(ItemNode(itemName, optionName = option, customReached = arrivedIn(destination)))

    fun clickItem(itemId: Int, option: String, destination: WorldArea) =
        append(ItemNode(itemId, optionName = option, customReached = arrivedIn(destination)))

    fun clickItem(itemName: String, optionNum: Int, destination: WorldArea) =
        append(ItemNode(itemName, optionNum = optionNum, customReached = arrivedIn(destination)))

    fun clickItem(itemId: Int, optionNum: Int, destination: WorldArea) =
        append(ItemNode(itemId, optionNum = optionNum, customReached = arrivedIn(destination)))

    fun path(start: Tile, end: Tile, reached: (() -> Boolean)? = null) = nodes.add(PathNode(start, end, reached))
    fun chebychevPath(start: Tile, end: List<Tile>, fallback: (() -> Boolean)? = null, reached: (() -> Boolean)? = null) = nodes.add(ChebychevNode(start, end, fallback, reached))

    /** A walk through the keypoints [end] that also carries every route tile, and may place its own hops ([abilities]). */
    fun chebychevPath(start: Tile, end: List<Tile>, routeTiles: List<Tile>, abilities: ClientAbilities = ClientAbilities.NONE) =
        append(ChebychevNode(start, end, null, null, routeTiles, abilities))

    /** A walk over a known tile list, such as a navpath move batch: no route is asked for. */
    fun pathTiles(start: Tile, end: Tile, tiles: List<Tile>, abilities: ClientAbilities = ClientAbilities.NONE) =
        append(PathNode(start, end, null, tiles, abilities))

    /** A planner-placed Surge toward [tile], cast when [enabled] and the live map shows it helps, else walked. */
    fun surge(tile: Tile, enabled: Boolean, reached: (() -> Boolean)? = null) = append(SurgeNode(tile, enabled, reached))

    /** [surge] that also knows the tile the planner cast it from, so the node can step onto it first. */
    fun surge(from: Tile?, tile: Tile, enabled: Boolean, reached: (() -> Boolean)?) = append(SurgeNode(from, tile, enabled, reached))

    /** A planner-placed Dive toward [tile], cast when [enabled] and the live map shows it clear, else walked. */
    fun dive(tile: Tile, enabled: Boolean, reached: (() -> Boolean)? = null) = append(DiveNode(tile, enabled, reached))

    fun walkExact(tile: Tile, minimap: Boolean = false, reached: (() -> Boolean)?) = nodes.add(TileExactNode(tile, minimap, customReached = reached))

    fun webWalk(
        destination: Tile,
        arriveDistance: Int = WebWalker.DEFAULT_ARRIVE_DISTANCE,
        useLodestones: Boolean = true,
        reached: (() -> Boolean)? = null,
    ) = nodes.add(WebWalkNode(destination, arriveDistance, reached, useLodestones))

    // Coordinate forms of the Tile-taking steps above, for Java (JavaTraversal.nodes builds with this class): Tile is an
    // inline value class, so those compile to mangled JVM names Java cannot call. Each adds exactly its Tile form's step.

    @JvmOverloads
    fun interactObj(action: String, searchRadius: Int, requestX: Int, requestY: Int, requestPlane: Int, reached: (() -> Boolean)? = null) =
        interactObj(action, searchRadius, Tile.of(requestX, requestY, requestPlane), reached)

    @JvmOverloads
    fun interactObj(id: Int, action: String, searchRadius: Int, requestX: Int, requestY: Int, requestPlane: Int, reached: (() -> Boolean)? = null) =
        interactObj(id, action, searchRadius, Tile.of(requestX, requestY, requestPlane), reached)

    @JvmOverloads
    fun interactObj(name: String, action: String, searchRadius: Int, requestX: Int, requestY: Int, requestPlane: Int, reached: (() -> Boolean)? = null) =
        interactObj(name, action, searchRadius, Tile.of(requestX, requestY, requestPlane), reached)

    @JvmOverloads
    fun path(startX: Int, startY: Int, endX: Int, endY: Int, plane: Int, reached: (() -> Boolean)? = null) =
        path(Tile.of(startX, startY, plane), Tile.of(endX, endY, plane), reached)

    /** [chebychevPath] through the keypoints ([xs] `[i]`, [ys] `[i]`) on [plane]. */
    @JvmOverloads
    fun chebychevPath(
        startX: Int,
        startY: Int,
        plane: Int,
        xs: IntArray,
        ys: IntArray,
        fallback: (() -> Boolean)? = null,
        reached: (() -> Boolean)? = null,
    ) = chebychevPath(Tile.of(startX, startY, plane), xs.indices.map { Tile.of(xs[it], ys[it], plane) }, fallback, reached)

    @JvmOverloads
    fun surge(x: Int, y: Int, plane: Int, enabled: Boolean, reached: (() -> Boolean)? = null) = surge(Tile.of(x, y, plane), enabled, reached)

    @JvmOverloads
    fun dive(x: Int, y: Int, plane: Int, enabled: Boolean, reached: (() -> Boolean)? = null) = dive(Tile.of(x, y, plane), enabled, reached)

    @JvmOverloads
    fun walkExact(x: Int, y: Int, plane: Int, minimap: Boolean = false, reached: (() -> Boolean)? = null) =
        walkExact(Tile.of(x, y, plane), minimap, reached)

    @JvmOverloads
    fun webWalk(
        x: Int,
        y: Int,
        plane: Int,
        arriveDistance: Int = WebWalker.DEFAULT_ARRIVE_DISTANCE,
        useLodestones: Boolean = true,
        reached: (() -> Boolean)? = null,
    ) = webWalk(Tile.of(x, y, plane), arriveDistance, useLodestones, reached)

    fun door(doorInfo: DoorInfo, direction: DoorDirection, reached: (() -> Boolean)? = null) = nodes.add(DoorNode(doorInfo, direction, reached))
    fun doorIn(doorInfo: DoorInfo, reached: (() -> Boolean)? = null) = nodes.add(DoorNode(doorInfo, DoorDirection.IN, reached))
    fun doorOut(doorInfo: DoorInfo, reached: (() -> Boolean)? = null) = nodes.add(DoorNode(doorInfo, DoorDirection.OUT, reached))

    fun useLodestone(lodestone: Lodestone, reached: (() -> Boolean)? = null) = nodes.add(LodestoneNode(lodestone, reached))

    /** Casts [spell], swapping book first when it is on another; reached on landing in [destination], or by [reached] without one. */
    fun castSpell(spell: TeleportSpell, destination: Area? = null, reached: (() -> Boolean)? = null) = nodes.add(SpellNode(spell, destination, reached))

    fun castSpell(spell: TeleportSpell, destination: WorldArea, reached: (() -> Boolean)? = null) =
        append(SpellNode(spell, null, destination, reached))

    /** [action] is the book slot op to cast, such as an alternate destination; null or "Cast" casts normally. */
    fun castSpell(spell: TeleportSpell, action: String?, destination: WorldArea, reached: (() -> Boolean)? = null) =
        append(SpellNode(spell, action, destination, reached))

    fun castSpell(spell: TeleportSpell, action: String?, destination: Area?, reached: (() -> Boolean)?) =
        append(SpellNode(spell, action, destination, reached))

    fun useItemOnObj(itemName: String, obj: SceneObject, searchRadius: Int = 20, reached: (() -> Boolean)? = null) = nodes.add(ItemOnObjectNode(itemName, obj, objectSearchRadius = searchRadius, customReached = reached))
    fun useItemOnObj(itemId: Int, obj: SceneObject, searchRadius: Int = 20, reached: (() -> Boolean)? = null) = nodes.add(ItemOnObjectNode(itemId, obj, objectSearchRadius = searchRadius, customReached = reached))
    fun useItemOnObj(itemName: String, objectName: String, searchRadius: Int = 20, reached: (() -> Boolean)? = null) = nodes.add(ItemOnObjectNode(itemName, objectName, objectSearchRadius = searchRadius, customReached = reached))
    fun useItemOnObj(itemId: Int, objectName: String, searchRadius: Int = 20, reached: (() -> Boolean)? = null) = nodes.add(ItemOnObjectNode(itemId, objectName, objectSearchRadius = searchRadius, customReached = reached))
    fun useItemOnObj(itemName: String, objectId: Int, searchRadius: Int = 20, reached: (() -> Boolean)? = null) = nodes.add(ItemOnObjectNode(itemName, objectId, objectSearchRadius = searchRadius, customReached = reached))
    fun useItemOnObj(itemId: Int, objectId: Int, searchRadius: Int = 20, reached: (() -> Boolean)? = null) = nodes.add(ItemOnObjectNode(itemId, objectId, objectSearchRadius = searchRadius, customReached = reached))

    /** Reached by [reached] when given, otherwise once the player stands in [destination]. */
    fun useItemOnObj(itemName: String, objectName: String, searchRadius: Int, destination: WorldArea, reached: (() -> Boolean)? = null) =
        append(ItemOnObjectNode(itemName, objectName, objectSearchRadius = searchRadius, customReached = reached ?: arrivedIn(destination)))

    fun useItemOnObj(itemId: Int, objectName: String, searchRadius: Int, destination: WorldArea, reached: (() -> Boolean)? = null) =
        append(ItemOnObjectNode(itemId, objectName, objectSearchRadius = searchRadius, customReached = reached ?: arrivedIn(destination)))

    fun useItemOnObj(itemName: String, objectId: Int, searchRadius: Int, destination: WorldArea, reached: (() -> Boolean)? = null) =
        append(ItemOnObjectNode(itemName, objectId, objectSearchRadius = searchRadius, customReached = reached ?: arrivedIn(destination)))

    fun useItemOnObj(itemId: Int, objectId: Int, searchRadius: Int, destination: WorldArea, reached: (() -> Boolean)? = null) =
        append(ItemOnObjectNode(itemId, objectId, objectSearchRadius = searchRadius, customReached = reached ?: arrivedIn(destination)))

    fun fairyRing(code: String, reached: (() -> Boolean)? = null) = nodes.add(FairyRingNode(code, false, reached))
    fun portableFairyRing(code: String, reached: (() -> Boolean)? = null) = nodes.add(FairyRingNode(code, true, reached))

    /** Dials [code] at the ring object [fairyRingObjectId] within [searchRadius], opened with [action]. */
    fun fairyRing(fairyRingObjectId: Int, code: String, action: String, searchRadius: Int = 30, reached: (() -> Boolean)? = null) =
        append(FairyRingNode(fairyRingObjectId, code, action, searchRadius, reached))

    fun fairyRing(fairyRingObjectId: Int, code: String, action: String, reached: () -> Boolean) =
        fairyRing(fairyRingObjectId, code, action, 30, reached)

    /** Opens the spirit tree map from the re-rooter, activating one first; the next node picks the destination. */
    fun spiritTreeRerooter(destination: WorldArea?, reached: (() -> Boolean)? = null) =
        append(SpiritTreeRerooterNode(destination, reached))

    /** Clicks [itemId] with [action], then picks [action2] and [action3] in the menus it opens; null stages are skipped. */
    fun poaItem(itemId: Int, action: String, action2: String?, action3: String?, reached: (() -> Boolean)? = null) =
        append(POAItemNode(itemId, action, action2, action3, null, reached))

    fun poaItem(itemId: Int, action: String, action2: String?, action3: String?, destination: WorldArea) =
        append(POAItemNode(itemId, action, action2, action3, destination, null))

    /** Nodes added so far. */
    fun nodeCount(): Int = nodes.size

    internal fun add(node: TraversalNode) = append(node)

    internal fun nodesFrom(from: Int): List<TraversalNode> = nodes.subList(from.coerceIn(0, nodes.size), nodes.size)

    /** Marks every node added from index [from] on as needing the Dramen staff wielded before it runs. */
    fun requireDramenStaffFrom(from: Int) = apply {
        for (k in from.coerceAtLeast(0) until nodes.size) nodes[k].requireDramenStaff()
    }

    /** The nodes added so far as a list, for a caller that owns the traversal already, such as a re-route. */
    fun buildNodes(): TraversalNodeList {
        require(nodes.isNotEmpty()) { "Traversal must contain at least one node" }
        return TraversalNodeList(nodes.first()).also { list -> nodes.drop(1).forEach { list.add(it) } }
    }

    fun build(next: State<T>, finishedCondition: T.() -> Boolean): Traversal<T> = build(next, finishedCondition, null, 0L)

    /** A traversal that also ends as [TraversalOutcome.TIMED_OUT] once the player stands still that long on one node. */
    fun build(next: State<T>, finishedCondition: T.() -> Boolean, noMovementTimeoutMillis: Long): Traversal<T> =
        build(next, finishedCondition, null, noMovementTimeoutMillis)

    /** [interruptCondition] stops it early without arriving: [TraversalOutcome.INTERRUPTED]. */
    fun build(next: State<T>, finishedCondition: T.() -> Boolean, interruptCondition: (T.() -> Boolean)?): Traversal<T> =
        build(next, finishedCondition, interruptCondition, 0L)

    fun build(next: State<T>, finishedCondition: T.() -> Boolean, interruptCondition: (T.() -> Boolean)?, noMovementTimeoutMillis: Long): Traversal<T> =
        Traversal(next, finishedCondition, buildNodes(), interruptCondition, noMovementTimeoutMillis)

    /** A traversal with no `next`, driven inline with [Traversal.run]. */
    fun build(finishedCondition: T.() -> Boolean): Traversal<T> = build(finishedCondition, null, 0L)

    fun build(finishedCondition: T.() -> Boolean, noMovementTimeoutMillis: Long): Traversal<T> =
        build(finishedCondition, null, noMovementTimeoutMillis)

    fun build(finishedCondition: T.() -> Boolean, interruptCondition: (T.() -> Boolean)?): Traversal<T> =
        build(finishedCondition, interruptCondition, 0L)

    fun build(finishedCondition: T.() -> Boolean, interruptCondition: (T.() -> Boolean)?, noMovementTimeoutMillis: Long): Traversal<T> =
        Traversal(null, finishedCondition, buildNodes(), interruptCondition, noMovementTimeoutMillis)
}
