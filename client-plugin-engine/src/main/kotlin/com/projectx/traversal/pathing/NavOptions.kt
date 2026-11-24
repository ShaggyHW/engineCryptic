package com.projectx.traversal.pathing

import com.projectx.script.api.Area
import world.gregs.voidps.type.Tile

/** How one navpath request is made and its steps mapped. */
class NavOptions(
    var start: Tile? = null,
    var goal: Tile? = null,
) {
    /** The navpath to ask; null asks the launcher's ([NavConfig.baseUrl]). */
    var serviceBaseUrl: String? = null
    var timeoutMs: Long = NavConfig.ROUTE_TIMEOUT_MS
    var skipExecution: Boolean = false

    /** Route to the cheapest tile of this area on `/route_area`; [goal] then only breaks cost ties. */
    var goalArea: Area? = null
    var requirements: List<Pair<String, Int>> = emptyList()
    var jsonActionsOnly: Boolean = true
    var preferChebyshev: Boolean = true

    /** Item teleports and items used on the way; off leaves lodestones, spells and worn-item teleports. */
    var useItems: Boolean = true

    /** Walk past an object step on one plane when the live map shows its far side a short walk away. */
    var skipPassableObjects: Boolean = false

    /** The route is planned with Surge and Dive, but the walk places its own hops on the live map. */
    var clientPlacedAbilities: Boolean = true
    var useAccelerationPotion: Boolean = true
    var exploreHops: Boolean = true
    var scheduleHops: Boolean = true

    /** False plans and walks with no Surge or Dive at all. */
    var hops: Boolean = true

    /** False asks for a route on foot: no lodestone, spell, item, menu or worn-item teleport. */
    var teleports: Boolean = true
    var surge: SurgeOpt = SurgeOpt.DISABLED
    var dive: DiveOpt = DiveOpt.DISABLED

    /** 0 is the account's stable seed ([NavPathClient.generateSeed]). */
    var seed: Int = 0

    fun copy(): NavOptions = NavOptions(start, goal).also {
        it.serviceBaseUrl = serviceBaseUrl
        it.timeoutMs = timeoutMs
        it.skipExecution = skipExecution
        it.goalArea = goalArea
        it.requirements = requirements
        it.jsonActionsOnly = jsonActionsOnly
        it.preferChebyshev = preferChebyshev
        it.useItems = useItems
        it.skipPassableObjects = skipPassableObjects
        it.clientPlacedAbilities = clientPlacedAbilities
        it.useAccelerationPotion = useAccelerationPotion
        it.exploreHops = exploreHops
        it.scheduleHops = scheduleHops
        it.hops = hops
        it.teleports = teleports
        it.surge = surge
        it.dive = dive
        it.seed = seed
    }

    /** [area] as a rectangle, an unset plane taking the goal's. */
    fun toArea(area: NavArea?): Area? {
        area ?: return null
        val plane = area.plane ?: goal?.plane ?: 0
        return Area.Rectangular(area.minX, area.minY, area.maxX, area.maxY, plane)
    }

    /** Whether the route should come back with its geometry: the walk places its own hops along it. */
    val wantsHopWalks: Boolean get() = clientPlacedAbilities && (surge.enabled || dive.enabled)
}
