package com.projectx.game.collision

import com.projectx.game.nxt.EntityType
import com.projectx.game.nxt.OClient
import com.projectx.game.nxt.OCombinedLocationSection
import com.projectx.game.nxt.OEntity
import com.projectx.game.nxt.OGraphNode
import com.projectx.game.nxt.OLocation
import com.projectx.game.nxt.OLocationContainer
import com.projectx.game.nxt.OLocationType
import com.projectx.game.nxt.OMapSquare
import com.projectx.game.nxt.OPlacement
import com.projectx.game.nxt.ORegionHeightContainer
import com.projectx.game.nxt.OSceneManager
import com.projectx.game.nxt.OWorld
import com.projectx.game.nxt.OffsetUnavailableException
import com.projectx.game.platform.PlatformMemory
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/**
 * The live world read through [PlatformMemory], which lets the kernel check every page: a pointer the client freed
 * a moment ago reads as a failure instead of faulting the game, so the worker thread may chase pointers the game
 * thread is changing. A read that tears is caught by the shape checks (sizes, counts, ids) and costs one pass.
 */
internal class MemoryLiveWorld(private val clientAddress: Long, private val localEntity: () -> Long) : LiveWorld {
    private val bytes = ByteArray(BUFFER_BYTES)
    private val view: ByteBuffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    private var vectorBegin = 0L
    private var vectorEnd = 0L

    private fun read(address: Long, length: Int): Boolean =
        address >= MIN_ADDRESS && length in 1..bytes.size && PlatformMemory.read(address, bytes, length)

    private fun pointer(address: Long): Long = if (read(address, 8)) view.getLong(0) else 0L

    private fun int(address: Long): Int? = if (read(address, 4)) view.getInt(0) else null

    private fun byte(address: Long): Int? = if (read(address, 1)) bytes[0].toInt() and 0xFF else null

    private fun float(address: Long): Float? = if (read(address, 4)) view.getFloat(0) else null

    /** Reads the begin and end of the vector at [address] into [vectorBegin] / [vectorEnd]; false when empty or unreadable. */
    private fun vector(address: Long): Boolean {
        if (!read(address, 16)) return false
        vectorBegin = view.getLong(0)
        vectorEnd = view.getLong(8)
        return vectorBegin != 0L && vectorEnd > vectorBegin
    }

    /** The pointer at [pointerOffset] of each of [count] entries [stride] apart from [begin]; null when unreadable. */
    private fun pointers(begin: Long, count: Int, stride: Long, pointerOffset: Long): LongArray? {
        val out = LongArray(count)
        val perBatch = (bytes.size / stride).toInt()
        var done = 0
        while (done < count) {
            val batch = minOf(perBatch, count - done)
            if (!read(begin + done * stride, (batch * stride).toInt())) return null
            for (k in 0 until batch) out[done + k] = view.getLong((k * stride + pointerOffset).toInt())
            done += batch
        }
        return out
    }

    private fun plausible(pointer: Long): Boolean = pointer > GRAPH_MIN_ADDRESS && pointer and 0x7L == 0L

    override fun localTile(): LocalTile? {
        val entity = localEntity()
        if (entity == 0L) return null
        val plane = int(entity + OEntity.ENTITY_PLANE) ?: return null
        val node = pointer(entity + OEntity.GRAPH_NODE)
        if (node == 0L) return LocalTile(0, 0, plane)
        val fineX = float(node + OGraphNode.SCENE_X) ?: return null
        val fineY = float(node + OGraphNode.SCENE_Y) ?: return null
        val size = byte(entity + OEntity.SIZE) ?: return null
        val x = ((fineX - 256f - (size shl 8).toFloat()) / 512f).roundToInt()
        val y = ((fineY - 256f - (size shl 8).toFloat()) / 512f).roundToInt()
        return LocalTile(x, y, plane)
    }

    private fun world(): Long {
        val sceneManager = pointer(clientAddress + OClient.SCENE_MANAGER)
        if (sceneManager == 0L) return 0L
        val index = int(sceneManager + OSceneManager.CURRENT_WORLD_INDEX) ?: return 0L
        if (index !in 0..WORLD_INDEX_MAX) return 0L
        val element = pointer(sceneManager + OSceneManager.WORLD_ARRAY + index * OSceneManager.WORLD_ARRAY_STRIDE)
        if (element == 0L) return 0L
        return pointer(element + SHARED_VALUE)
    }

    private fun mapSquare(squareX: Int, squareY: Int): Long {
        val world = world()
        if (world == 0L) return 0L
        val baseX = int(world + OWorld.MAPSQUARE_X_OFFSET) ?: return 0L
        val baseY = int(world + OWorld.MAPSQUARE_Y_OFFSET) ?: return 0L
        if (!vector(world + OWorld.MAPSQUARES_VECTOR)) return 0L
        val columns = ((vectorEnd - vectorBegin) / VECTOR_STRIDE).toInt()
        val column = squareX - baseX
        if (columns !in 1..SQUARE_INDEX_MAX || column !in 0 until columns) return 0L
        if (!vector(vectorBegin + column * VECTOR_STRIDE)) return 0L
        val rows = ((vectorEnd - vectorBegin) / VECTOR_STRIDE).toInt()
        val row = squareY - baseY
        if (rows !in 1..SQUARE_INDEX_MAX || row !in 0 until rows) return 0L
        return pointer(vectorBegin + row * VECTOR_STRIDE + SHARED_VALUE)
    }

    private class ContainerGrid(val begin: Long, val columns: Int)

    /** The square's loc container grid; null for a stub square (terrain but no containers: its locs are unknown). */
    private fun containers(mapSquare: Long): ContainerGrid? {
        if (!vector(mapSquare + OMapSquare.LOCATION_CONTAINERS)) return null
        val columns = ((vectorEnd - vectorBegin) / VECTOR_STRIDE).toInt()
        if (columns !in 1..CONTAINER_COLUMNS_MAX) return null
        return ContainerGrid(vectorBegin, columns)
    }

    private fun loadedSquare(squareX: Int, squareY: Int): Pair<Long, ContainerGrid>? {
        val mapSquare = mapSquare(squareX, squareY)
        if (mapSquare == 0L) return null
        val grid = containers(mapSquare) ?: return null
        return mapSquare to grid
    }

    override fun placementStamp(squareX: Int, squareY: Int): Long {
        val (mapSquare, _) = loadedSquare(squareX, squareY) ?: return 0L
        if (!read(mapSquare + OMapSquare.PLACEMENTS, 16)) return 0L
        return stamp(mapSquare, view.getLong(0), view.getLong(8))
    }

    override fun placements(squareX: Int, squareY: Int): SquareWalk {
        val (mapSquare, _) = loadedSquare(squareX, squareY) ?: return SquareWalk(null, false)
        if (!read(mapSquare + OMapSquare.PLACEMENTS, 16)) return SquareWalk(null, false)
        val begin = view.getLong(0)
        val end = view.getLong(8)
        if (begin == 0L || end <= begin) return SquareWalk(emptyList(), false)
        val stride = OMapSquare.PLACEMENT_ENTRY_STRIDE
        var count = ((end - begin) / stride).toInt()
        var truncated = false
        if (count > PLACEMENTS_MAX) {
            count = PLACEMENTS_MAX
            truncated = true
        }
        val entries = pointers(begin, count, stride, OMapSquare.PLACEMENT_ENTRY_PTR_OFFSET)
            ?: return SquareWalk(emptyList(), true)
        val recordBytes = (OPlacement.LOCATION_TYPE + 8).toInt()
        val baseX = squareX * CollisionGrid.SQ
        val baseY = squareY * CollisionGrid.SQ
        val out = ArrayList<LiveLoc>(count)
        var budget = READ_BUDGET
        for (placement in entries) {
            if (placement == 0L) continue
            if (--budget < 0) return SquareWalk(out, true)
            if (!read(placement, recordBytes)) {
                truncated = true
                continue
            }
            val shape = u8(OPlacement.SHAPE)
            if (shape == SHAPE_WALL_SECOND_LEG) continue
            out += LiveLoc(
                x = baseX + u8(OPlacement.LOCAL_X),
                y = baseY + u8(OPlacement.LOCAL_Y),
                id = view.getInt(OPlacement.TYPE_ID.toInt()),
                locType = view.getLong(OPlacement.LOCATION_TYPE.toInt()),
                plane = u8(OPlacement.PLANE),
                shape = shape,
                rotation = u8(OPlacement.ROTATION),
                source = LiveLocSource.PLACEMENT,
            )
        }
        return SquareWalk(out, truncated)
    }

    private fun u8(offset: Long): Int = bytes[offset.toInt()].toInt() and 0xFF

    private class DynamicWalk(val out: ArrayList<LiveLoc> = ArrayList(), var truncated: Boolean = false, var budget: Int = READ_BUDGET) {
        fun add(loc: LiveLoc): Boolean {
            if (out.size >= LOCATIONS_MAX) {
                truncated = true
                budget = -1
                return false
            }
            out += loc
            return true
        }
    }

    override fun dynamic(squareX: Int, squareY: Int): SquareWalk {
        val (_, grid) = loadedSquare(squareX, squareY) ?: return SquareWalk(null, false)
        val walk = DynamicWalk()
        for (column in 0 until grid.columns) {
            if (walk.budget < 0) break
            if (!vector(grid.begin + column * VECTOR_STRIDE)) continue
            val stride = OMapSquare.LOCATION_CONTAINER_ENTRY_STRIDE
            var rows = ((vectorEnd - vectorBegin) / stride).toInt()
            if (rows <= 0) continue
            if (rows > CONTAINER_ROWS_MAX) {
                rows = CONTAINER_ROWS_MAX
                walk.truncated = true
            }
            val containers = pointers(vectorBegin, rows, stride, OMapSquare.LOCATION_CONTAINER_ENTRY_PTR_OFFSET)
            if (containers == null) {
                walk.truncated = true
                continue
            }
            for (container in containers) {
                if (container == 0L || walk.budget < 0) continue
                if (--walk.budget < 0) break
                if (vector(container + OLocationContainer.SPAWNED_LOCATIONS_BEGIN)) locations(walk, vectorBegin, vectorEnd, spawned = true)
                for (offset in staticVectors) {
                    if (walk.budget < 0) break
                    if (vector(container + offset)) locations(walk, vectorBegin, vectorEnd, spawned = false)
                }
                if (walk.budget >= 0) {
                    val node = pointer(container + OEntity.GRAPH_NODE)
                    if (node != 0L) hiddenSections(walk, node, 0)
                }
            }
        }
        if (walk.budget < 0) walk.truncated = true
        return SquareWalk(walk.out, walk.truncated)
    }

    /** Spawned locations as spawns, or (not [spawned]) the hidden or deleted ones of a static vector as removal markers. */
    private fun locations(walk: DynamicWalk, begin: Long, end: Long, spawned: Boolean) {
        val stride = OLocationContainer.ENTRY_STRIDE
        var count = ((end - begin) / stride).toInt()
        if (count > LOCATIONS_MAX) {
            count = LOCATIONS_MAX
            walk.truncated = true
        }
        val locations = pointers(begin, count, stride, OLocationContainer.ENTRY_LOCATION_PTR_OFFSET)
        if (locations == null) {
            walk.truncated = true
            return
        }
        val flagsFrom = minOf(OLocation.IS_DELETED, OLocation.IS_HIDDEN)
        val flagsLength = (maxOf(OLocation.IS_DELETED, OLocation.IS_HIDDEN) + 1 - flagsFrom).toInt()
        for (location in locations) {
            if (location == 0L) continue
            if (--walk.budget < 0) return
            if (!spawned) {
                if (!read(location + flagsFrom, flagsLength)) continue
                val deleted = bytes[(OLocation.IS_DELETED - flagsFrom).toInt()].toInt() != 0
                val hidden = bytes[(OLocation.IS_HIDDEN - flagsFrom).toInt()].toInt() != 0
                if (!deleted && !hidden) continue
            }
            val (loc, removed) = location(location) ?: continue
            if (spawned == removed) continue
            if (!walk.add(loc.copy(source = if (spawned) LiveLocSource.SPAWN else LiveLocSource.REMOVED))) return
        }
    }

    /** A Location with whether it is hidden or deleted. */
    private fun location(location: Long): Pair<LiveLoc, Boolean>? {
        val from = locationFrom
        val length = locationLength
        if (!read(location + from, length)) return null
        fun at(offset: Long) = (offset - from).toInt()
        val removed = bytes[at(OLocation.IS_DELETED)].toInt() != 0 || bytes[at(OLocation.IS_HIDDEN)].toInt() != 0
        val ownType = view.getLong(at(OLocation.OWN_TYPE))
        val x = view.getInt(at(OLocation.POS_X))
        val y = view.getInt(at(OLocation.POS_Y))
        val plane = view.getInt(at(OEntity.ENTITY_PLANE)) and 0xFF
        val shape = bytes[at(OLocation.SHAPE)].toInt() and 0xFF
        val rotation = bytes[at(OLocation.ROTATION)].toInt() and 0xFF
        return LiveLoc(x, y, typeId(ownType), ownType, plane, shape, rotation, LiveLocSource.SPAWN) to removed
    }

    /** Hidden combined-location sections under a container's scene graph: a static loc the server deleted. */
    private fun hiddenSections(walk: DynamicWalk, node: Long, depth: Int) {
        if (!plausible(node) || depth > GRAPH_DEPTH_MAX) return
        if (!vector(node + OGraphNode.CHILDREN)) return
        val count = ((vectorEnd - vectorBegin) / 8).toInt().coerceAtMost(GRAPH_CHILDREN_MAX)
        val children = pointers(vectorBegin, count, 8L, 0L) ?: return
        for (child in children) {
            if (--walk.budget < 0) return
            if (!plausible(child)) continue
            val entity = pointer(child + OGraphNode.ENTITY)
            if (!plausible(entity)) continue
            when (byte(entity + OEntity.ENTITY_TYPE)) {
                EntityType.COMBINED_LOCATION_SECTION.value -> hiddenSection(walk, entity)
                EntityType.COMBINED_LOCATION.value -> hiddenSections(walk, pointer(entity + OEntity.GRAPH_NODE), depth + 1)
            }
        }
    }

    private fun hiddenSection(walk: DynamicWalk, section: Long) {
        if (byte(section + OCombinedLocationSection.HIDDEN) == 0) return
        val type = pointer(section + OCombinedLocationSection.TYPE_SHAREDPTR + SHARED_VALUE)
        val x = int(section + OCombinedLocationSection.POS_X) ?: return
        val y = int(section + OCombinedLocationSection.POS_Y) ?: return
        val plane = int(section + OEntity.ENTITY_PLANE) ?: return
        val shape = byte(section + OCombinedLocationSection.SHAPE) ?: return
        val rotation = byte(section + OCombinedLocationSection.ROTATION) ?: return
        walk.add(LiveLoc(x, y, typeId(type), type, plane and 0xFF, shape, rotation, LiveLocSource.REMOVED))
    }

    /** A loc type's own id; -1 when unreadable or negative (0 is a real loc id). */
    private fun typeId(locType: Long): Int {
        if (locType == 0L) return -1
        val id = int(locType + OLocationType.ID) ?: return -1
        return if (id < 0) -1 else id
    }

    override fun terrain(squareX: Int, squareY: Int): SquareTerrain? {
        val mapSquare = mapSquare(squareX, squareY)
        if (mapSquare == 0L) return null
        val terrainObject = terrainObject(mapSquare)
        if (terrainObject == 0L) return null
        if (!vector(terrainObject + ORegionHeightContainer.SETTINGS_LEVELS)) return null
        val levels = ((vectorEnd - vectorBegin) / ORegionHeightContainer.PLANE_STRIDE).toInt().coerceAtMost(CollisionGrid.PLANES)
        val levelsBegin = vectorBegin
        val out = SquareTerrain()
        val rowBytes = GRID_DIM * VECTOR_STRIDE.toInt()
        for (plane in 0 until levels) {
            val grid = pointer(levelsBegin + plane * ORegionHeightContainer.PLANE_STRIDE + ORegionHeightContainer.PLANE_ELEM)
            if (grid == 0L) continue
            if (!read(grid, 16)) return null
            val rowsBegin = view.getLong(0)
            val rowsEnd = view.getLong(8)
            if (rowsBegin == 0L || rowsEnd <= rowsBegin) continue
            if ((rowsEnd - rowsBegin) / VECTOR_STRIDE != GRID_DIM.toLong()) return null
            if (!read(rowsBegin, rowBytes)) return null
            val rowBegins = LongArray(GRID_DIM) { view.getLong(it * VECTOR_STRIDE.toInt()) }
            val rowEnds = LongArray(GRID_DIM) { view.getLong(it * VECTOR_STRIDE.toInt() + 8) }
            for (localX in 0 until SquareTerrain.SQ) {
                val rowBegin = rowBegins[localX + GRID_BORDER]
                val rowEnd = rowEnds[localX + GRID_BORDER]
                if (rowBegin == 0L || rowEnd - rowBegin != GRID_DIM.toLong()) return null
                if (!read(rowBegin, GRID_DIM)) return null
                for (localY in 0 until SquareTerrain.SQ) out.set(plane, localX, localY, bytes[localY + GRID_BORDER].toInt() and TERRAIN_BITS)
            }
        }
        return out
    }

    /** The client's own pick: the primary terrain object when its two ready bytes agree, else the fallback. */
    private fun terrainObject(mapSquare: Long): Long {
        for (slot in longArrayOf(ORegionHeightContainer.PRIMARY, ORegionHeightContainer.FALLBACK)) {
            val terrain = pointer(mapSquare + slot)
            if (terrain == 0L) continue
            val low = byte(terrain + ORegionHeightContainer.READY_LO) ?: continue
            val high = byte(terrain + ORegionHeightContainer.READY_HI) ?: continue
            if (low == high) return terrain
        }
        return 0L
    }

    override fun locType(locType: Long, expectId: Int): LocTypeRead {
        if (locType == 0L) return LocTypeRead.Unreadable
        if (expectId >= 0 && typeId(locType) != expectId) return LocTypeRead.OtherId
        val from = OLocationType.SIZE_X
        val length = (OLocationType.BLOCKS_PROJECTILE + 1 - from).toInt()
        if (!read(locType + from, length)) return LocTypeRead.Unreadable
        val sizeX = view.getInt((OLocationType.SIZE_X - from).toInt())
        val sizeY = view.getInt((OLocationType.SIZE_Y - from).toInt())
        val clipType = view.getInt((OLocationType.CLIP_TYPE - from).toInt())
        val blocksProjectile = bytes[(OLocationType.BLOCKS_PROJECTILE - from).toInt()].toInt() and 0xFF
        if (sizeX !in 1..64 || sizeY !in 1..64 || clipType !in 0..2 || blocksProjectile > 1) return LocTypeRead.Unreadable
        return LocTypeRead.Read(LocProps(sizeX, sizeY, clipType, blocksProjectile == 1))
    }

    private fun stamp(mapSquare: Long, begin: Long, end: Long): Long {
        val h = mix(mix(mix(0L, mapSquare), begin), end)
        return if (h == 0L) 1L else h
    }

    private fun mix(h: Long, v: Long): Long {
        val x = h xor (v + -0x61c8864680b583ebL + (h shl 6) + (h ushr 2))
        return x * -0xae502812aa7333L
    }

    companion object {
        private const val BUFFER_BYTES = 8192
        private const val MIN_ADDRESS = 0x1000L
        private const val GRAPH_MIN_ADDRESS = 0x10000L
        private const val SHARED_VALUE = 0x8L
        private const val VECTOR_STRIDE = 0x18L
        private const val WORLD_INDEX_MAX = 16
        private const val SQUARE_INDEX_MAX = 256
        private const val CONTAINER_COLUMNS_MAX = 4096
        private const val CONTAINER_ROWS_MAX = 256
        private const val LOCATIONS_MAX = 4096
        private const val PLACEMENTS_MAX = 32_768
        private const val READ_BUDGET = 60_000
        private const val GRAPH_DEPTH_MAX = 4
        private const val GRAPH_CHILDREN_MAX = 512
        private const val SHAPE_WALL_SECOND_LEG = 23
        private const val GRID_DIM = 66
        private const val GRID_BORDER = 1
        private const val TERRAIN_BITS = SquareTerrain.BLOCKED or SquareTerrain.BRIDGE

        private val locationFields by lazy {
            longArrayOf(
                OEntity.ENTITY_PLANE, OLocation.OWN_TYPE, OLocation.POS_X, OLocation.POS_Y, OLocation.SHAPE,
                OLocation.ROTATION, OLocation.IS_DELETED, OLocation.IS_HIDDEN,
            )
        }
        private val locationFrom by lazy { locationFields.min() }
        private val locationLength by lazy { (locationFields.max() + 8 - locationFrom).toInt() }

        // The span vector is only in the tables that located it; the Windows container has none at that place.
        private val staticVectors: LongArray by lazy {
            val span = try {
                OLocationContainer.SPAN_LOCATIONS_BEGIN
            } catch (_: OffsetUnavailableException) {
                null
            }
            listOfNotNull(OLocationContainer.PRIMARY_LOCATIONS_BEGIN, span, OLocationContainer.TERTIARY_LOCATIONS_BEGIN).toLongArray()
        }

        /** Whether this client's offset table carries everything the reader needs. */
        val supported: Boolean by lazy {
            try {
                listOf(
                    OMapSquare.PLACEMENTS, OMapSquare.PLACEMENT_ENTRY_STRIDE, OMapSquare.PLACEMENT_ENTRY_PTR_OFFSET,
                    OPlacement.TYPE_ID, OPlacement.PLANE, OPlacement.LOCAL_X, OPlacement.LOCAL_Y, OPlacement.SHAPE,
                    OPlacement.ROTATION, OPlacement.LOCATION_TYPE, OLocationType.CLIP_TYPE, OLocationType.BLOCKS_PROJECTILE,
                    OLocation.OWN_TYPE, OLocationContainer.SPAWNED_LOCATIONS_BEGIN, ORegionHeightContainer.SETTINGS_LEVELS,
                )
                true
            } catch (_: OffsetUnavailableException) {
                false
            }
        }
    }
}
