package com.projectx.traversal.pathing.mappers

import com.projectx.traversal.TraversalBuilder
import com.projectx.traversal.pathing.MappingContext
import com.projectx.traversal.pathing.NavOptions
import com.projectx.traversal.pathing.Step

/** Turns one parsed step into builder calls. Mappers only translate; the nodes they add do the work. */
fun interface StepMapper {
    fun map(step: Step, builder: TraversalBuilder<*>, context: MappingContext)
}

/**
 * The mapper for each step type. A move cannot be mapped on its own: the route batches contiguous moves and hands
 * them to [MoveMapper.mapSequence].
 */
object StepMapperRegistry {
    private val mappers = LinkedHashMap<String, StepMapper>()

    /** Registers or replaces the mapper for [type], ignoring case. */
    fun register(type: String, mapper: StepMapper) {
        mappers[type.lowercase()] = mapper
    }

    fun get(type: String?): StepMapper? = type?.let { mappers[it.lowercase()] }

    val knownTypes: Set<String> get() = mappers.keys.toSortedSet()

    /** Maps [step], with [reached] as the route's own signal that the step is done. */
    fun apply(step: Step, builder: TraversalBuilder<*>, options: NavOptions, reached: (() -> Boolean)? = null) {
        val mapper = get(step.type)
            ?: throw IllegalStateException("No mapper registered for step type='${step.type}'. Known types: ${knownTypes.joinToString()}")
        mapper.map(step, builder, MappingContext(options, reached))
    }

    init {
        register("move", MoveMapper::map)
        register("surge", SurgeMapper::map)
        register("dive", DiveMapper::map)
        register("fairy_ring", FairyRingMapper::map)
        register("lodestone", LodestoneMapper::map)
        register("npc", NpcMapper::map)
        register("ifslot", IFSlotMapper::map)
        register("object", ObjectMapper::map)
        register("door", DoorMapper::map)
        register("item_click", ItemClickMapper::map)
        register("poa_item", POAItemMapper::map)
        register("use_on", ItemOnObjectMapper::map)
        register("tile_exact", TileExactMapper::map)
        register("spell", SpellMapper::map)
    }
}

internal inline fun <reified S : Step> Step.expect(mapper: String): S =
    this as? S ?: throw IllegalArgumentException("$mapper received incompatible step type='$type'")
