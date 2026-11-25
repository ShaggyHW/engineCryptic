package com.projectx.traversal.pathing

import com.projectx.game.items.Item
import com.projectx.script.Script
import com.projectx.script.State
import com.projectx.script.StateMachineScript
import com.projectx.script.api.Equipment
import com.projectx.script.api.Perks
import com.projectx.script.api.actionbarAbilities
import com.projectx.script.api.getRealLevel
import com.projectx.script.api.interfaces
import com.projectx.script.api.varcs
import com.projectx.script.api.varps
import com.projectx.traversal.MasterFarmerHat
import com.projectx.traversal.Traversal
import com.projectx.traversal.TraversalBuilder
import com.projectx.traversal.TraversalNode
import com.projectx.traversal.TraversalNodeList
import com.projectx.traversal.nodes.ChebychevNode
import com.projectx.traversal.nodes.DramenStaff
import com.projectx.traversal.nodes.PathNode
import com.projectx.traversal.nodes.PouchItems
import com.projectx.traversal.nodes.TileExactNode
import com.projectx.traversal.nodes.TourStops
import com.projectx.traversal.nodes.TraversalSupport
import com.projectx.traversal.pathing.mappers.MoveMapper
import com.projectx.traversal.pathing.mappers.StepMapperRegistry
import com.projectx.webwalker.WalkGoal
import com.projectx.webwalker.MeasuredLinks
import kotlinx.coroutines.future.await
import org.projectx.core.game.combat.Ability
import org.projectx.core.game.combat.CombatIds
import org.projectx.core.game.invention.Perk
import org.projectx.core.game.skill.Skill
import world.gregs.voidps.gameval.Gameval
import world.gregs.voidps.type.Tile
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.math.roundToLong

/**
 * Builds a [Traversal] from a navpath route: the request (off the game thread), the parsed steps, and one node or more
 * per step. Every node carries the edge it came from ([TraversalNode.navMacro]), so a re-route to the same goal leaves
 * out the edges behind the nodes that gave up. With no navpath running this throws [NavPathUnavailableException]; it
 * never falls back to a client-side planner.
 */
object PathingTraversal {
    data class PathInfo(val start: Tile, val goal: Tile, val cost: Double)

    /** One routed leg, parsed once: it can price an ordering ([cost]) and later be walked as part of a tour. */
    data class Leg(val from: Tile, val to: Tile, val found: Boolean, val cost: Double, val steps: List<Step> = emptyList())

    /** navpath answered, but with no route this engine can walk. */
    class NoRouteException(message: String) : RuntimeException(message)

    /** A route mapped into nodes, with the answer it came from and the options it was asked with. */
    internal class Routed(val nodes: TraversalNodeList, val route: NavRoute, val options: NavOptions)

    /** How long a fairy ring that landed somewhere else stays out of every route. */
    const val MISROUTE_MEMORY_MS = MeasuredLinks.MISROUTE_MEMORY_MS

    private const val MAX_RING_REPLANS = 4

    /** Surge / Dive cooldown while a Powerburst of acceleration runs. */
    private const val BURST_COOLDOWN_MS = 1_200L
    private const val MOBILE_CHECK_MS = 5_000L
    private const val MOBILE_MIN_INVENTION = 2
    private const val DOUBLE_SURGE_VARBIT = "dino_agility_double_surge_unlocked"
    private const val END_VARC_SUFFIX = "_end_client"
    private const val START_VARC_SUFFIX = "_start_client"
    private const val MS_PER_TICK = 600.0
    private val MOBILE_SLOTS = listOf(Equipment.Slot.BODY, Equipment.Slot.LEGS, Equipment.Slot.WEAPON, Equipment.Slot.SHIELD)

    var log: (String) -> Unit = ::println

    @Volatile
    private var routeAreaMissing = false

    @Volatile
    private var mobileCheckedAt = 0L

    @Volatile
    private var mobile = false

    /** Leaves [macro] out of every route for [forMs]. */
    fun setAside(macro: NavMacro, forMs: Long = MISROUTE_MEMORY_MS) = MeasuredLinks.setAside(macro, forMs)

    suspend fun pathInfo(start: Tile, goal: Tile, options: NavOptions = NavOptions(start, goal)): PathInfo {
        if (options.start == null) options.start = start
        if (options.goal == null) options.goal = goal
        options.requirements = RequirementsBuilder.requirements(options.useItems)
        val route = requestRoute(options, goalsOf(options), MeasuredLinks.setAsideNow())
        return PathInfo(start, goal, route.cost)
    }

    suspend fun tileExists(x: Int, y: Int, plane: Int): Boolean = try {
        offThread(NavConfig.TILE_EXISTS_TIMEOUT_MS) { NavPathClient.local.tileExists(Tile.of(x, y, plane)) }
    } catch (e: NavPathUnavailableException) {
        log("[PathingTraversal] navpath unavailable - tileExists cannot proceed: ${e.message}")
        throw e
    }

    /**
     * Routes [options] and returns the traversal that walks it, re-routing to the same goal when a node asks. [append]
     * adds nodes after the route's own. [script] reads what the requirements need brought on screen and drives re-routes.
     */
    suspend fun <T : StateMachineScript<T>> build(
        script: T,
        finishedCondition: T.() -> Boolean,
        options: NavOptions,
        next: State<T>? = null,
        interruptCondition: (T.() -> Boolean)? = null,
        append: TraversalBuilder<T>.() -> Unit = {},
    ): Traversal<T> {
        if (options.start == null) options.start = playerTile()
        val goals = goalsOf(options)
        val exclude = HashSet<NavMacro>()
        val appendNodes: (TraversalBuilder<T>, NavOptions) -> Unit = { builder, _ -> builder.append() }
        val routed = routeNodes(script, options, goals, exclude, appendNodes)
        lateinit var traversal: Traversal<T>
        traversal = Traversal(next, finishedCondition, routed.nodes, interruptCondition, Traversal.ROUTE_NO_MOVEMENT_TIMEOUT_MILLIS)
            .repathSuspending { reroute(script, options, goals, exclude, traversal.currentNode, appendNodes)?.nodes }
        return traversal
    }

    /** Item teleports whose requirement reads UI state: the Habitat count off the hat, memory strands off an open pouch. */
    suspend fun prepareItemRequirements(script: Script) {
        MasterFarmerHat.refreshIfUnknown(script)
        PouchItems.prepare(script)
    }

    /** Options for a batch of [leg] requests, with the action bar and requirements sampled once. */
    suspend fun routingOptions(script: Script, assumeNotAttacked: Boolean = false): NavOptions {
        val options = NavOptions()
        applyAbilityToggles(options)
        if (options.useItems) prepareItemRequirements(script)
        options.requirements = RequirementsBuilder.requirements(options.useItems, assumeNotAttacked = assumeNotAttacked)
        return options
    }

    /** Options for legs on foot: no teleport of any kind and no Surge or Dive. */
    fun walkOnlyRoutingOptions(): NavOptions = NavOptions().apply {
        hops = false
        teleports = false
        useItems = false
        requirements = RequirementsBuilder.walkOnlyRequirements()
    }

    /** [routingOptions] for a fight that goes on between hits: no lodestone and no teleport that opens a menu. */
    suspend fun routingOptionsUnderAttack(script: Script): NavOptions {
        val options = NavOptions()
        applyAbilityToggles(options)
        if (options.useItems) prepareItemRequirements(script)
        options.requirements = RequirementsBuilder.requirements(options.useItems, assumeAttacked = true)
        return options
    }

    /** One `/route` request from [from] to [to] with [routing]'s sampled options. */
    suspend fun leg(routing: NavOptions, from: Tile, to: Tile): Leg {
        val options = routing.copy().also {
            it.start = from
            it.goal = to
        }
        val route = requestRoute(options, null, MeasuredLinks.setAsideNow())
        return Leg(from, to, route.found, route.cost, route.steps)
    }

    /**
     * One traversal over legs already routed, walked as one route: the legs' steps are joined end to end, so the walk
     * sees past every waypoint, while still walking to each routed one ([TourStops]). [repath] gives the legs still to
     * walk from where the player stands, for a node's re-route; null, or no legs, refuses it.
     */
    fun <T : StateMachineScript<T>> buildTour(
        next: State<T>?,
        finishedCondition: T.() -> Boolean,
        interruptCondition: (T.() -> Boolean)?,
        routing: NavOptions,
        legs: List<Leg>,
        repath: (suspend () -> List<Leg>?)? = null,
        append: TraversalBuilder<T>.() -> Unit = {},
    ): Traversal<T> {
        val traversal = Traversal(next, finishedCondition, tourNodes(routing, legs, append), interruptCondition, Traversal.ROUTE_NO_MOVEMENT_TIMEOUT_MILLIS)
        if (repath == null) return traversal
        return traversal.repathSuspending { repath()?.takeIf { it.isNotEmpty() }?.let { tourNodes(routing, it, append) } }
    }

    internal fun <T : StateMachineScript<T>> tourNodes(routing: NavOptions, legs: List<Leg>, append: TraversalBuilder<T>.() -> Unit = {}): TraversalNodeList {
        require(legs.isNotEmpty()) { "a tour needs at least one leg" }
        val steps = ArrayList<Step>()
        for (leg in legs) {
            val options = routing.copy().also {
                it.start = leg.from
                it.goal = leg.to
            }
            val legSteps = if (options.clientPlacedAbilities) MoveMapper.abilityStepsAsMoves(leg.steps) else leg.steps
            if (legSteps.isEmpty()) {
                steps += Step.Move(leg.from)
                steps += Step.Move(leg.to)
                continue
            }
            steps += legSteps
            val end = endTile(legSteps, options)
            if (end != null && end != leg.to) {
                if (legSteps.last() !is Step.Move) steps += Step.Move(end)
                steps += Step.Move(leg.to)
            }
        }
        val options = routing.copy().also {
            it.start = legs.first().from
            it.goal = legs.last().to
        }
        log("[PathingTraversal] Tour of ${legs.size} legs, ${steps.size} steps. Start: ${options.start}, Goal: ${options.goal}")
        val builder = TraversalBuilder<T>()
        mapSteps(steps, builder, options)
        builder.append()
        val nodes = builder.buildNodes()
        val stops = TourStops(legs.filter(Leg::found).map(Leg::to))
        var node: TraversalNode? = nodes.head
        while (node != null) {
            (node as? ChebychevNode)?.withStops(stops)
            node = node.next
        }
        return nodes
    }

    private fun endTile(steps: List<Step>, options: NavOptions): Tile? {
        var end: Tile? = null
        for (step in steps) end = if (step is Step.Move) step.tile else lastActionDestTile(step, options, end)
        return end
    }

    /**
     * Surge and Dive on as the action bar and Agility allow, Surge's charges, and each one's cooldown as
     * [abilityCooldownMs] reads it rather than assumed.
     */
    fun applyAbilityToggles(options: NavOptions) {
        RequirementsBuilder.applyAbilityToggles(options)
        if (!options.hops) return
        val dive = if (runCatching { actionbarAbilities[Ability.DIVE.type] != null }.getOrDefault(false)) Ability.DIVE else Ability.BLADED_DIVE
        options.surge = options.surge.copy(cooldownMs = abilityCooldownMs(Ability.SURGE, options.surge.cooldownMs))
        options.dive = options.dive.copy(cooldownMs = abilityCooldownMs(dive, options.dive.cooldownMs))
    }

    /**
     * How long [ability]'s cooldown is, for the planner and the walker's schedule: the length the client last applied
     * when there is one (it already counts MOBILE and anything else), capped by the gear worn now; otherwise the cache's
     * length, halved by a worn MOBILE perk; otherwise [fallback].
     *
     * An observed length of a Powerburst's 2 ticks is the burst's, not the ability's, and with Double Surge the observed
     * Surge length is the gap the charges were used at, so both take the cache's length instead.
     */
    fun abilityCooldownMs(ability: Ability, fallback: Long): Long {
        val observed = observedCooldownMs(ability)
        val charges = ability == Ability.SURGE && varbitNamed(DOUBLE_SURGE_VARBIT) == 1
        val base = baseCooldownMs(ability)
        val geared = if (base <= 0L) 0L else if (hasMobilePerk()) base / 2L else base
        if (!charges && observed > 2 * BURST_COOLDOWN_MS) return if (geared > 0L) minOf(observed, geared) else observed
        return if (geared > 0L) geared else fallback
    }

    private fun baseCooldownMs(ability: Ability): Long = runCatching { ability.type.cooldownParamTicks * MS_PER_TICK.toLong() }.getOrDefault(0L)

    /** END - START over the ability's cooldown varc pair: 0 before it has been used this session, or when the pair reads wrong. */
    private fun observedCooldownMs(ability: Ability): Long = runCatching {
        val endVarc = ability.type.cooldownEndVarc.takeIf { it != -1 } ?: return 0L
        val endName = Gameval.varc(endVarc)?.takeIf { it.endsWith(END_VARC_SUFFIX) } ?: return 0L
        val startVarc = Gameval.id(Gameval.VAR_CLIENT, endName.removeSuffix(END_VARC_SUFFIX) + START_VARC_SUFFIX) ?: return 0L
        val start = varcs.getVar(startVarc)
        if (start <= 0) return 0L
        val end = varcs.getVar(endVarc)
        if (end <= start) return 0L
        val ticks = (end - start).toDouble() / CombatIds.CYCLES_PER_TICK
        val base = ability.type.cooldownParamTicks
        // A modifier only ever shortens a cooldown: far above the cache's length, the pair is not what it seems.
        if (base > 0 && ticks > base * 2.0) return 0L
        (ticks * MS_PER_TICK).roundToLong()
    }.getOrDefault(0L)

    private fun hasMobilePerk(): Boolean {
        val now = System.currentTimeMillis()
        if (now - mobileCheckedAt < MOBILE_CHECK_MS) return mobile
        mobile = runCatching { readMobilePerk() }.getOrDefault(false)
        mobileCheckedAt = now
        return mobile
    }

    private fun readMobilePerk(): Boolean {
        if (getRealLevel(Skill.INVENTION) <= MOBILE_MIN_INVENTION) return false
        return MOBILE_SLOTS.any { slot -> Equipment.Slot.getItem(slot)?.let(::isMobile) == true }
    }

    private fun isMobile(item: Item): Boolean = item.isAugmented && Perks.has(item, Perk.MOBILE)

    private fun varbitNamed(name: String): Int = runCatching { Gameval.id(Gameval.VARBIT, name)?.let(varps::getVarBit) }.getOrNull() ?: 0

    /** Samples the action bar and the requirements into [options], as each route request does. */
    private suspend fun sample(script: Script, options: NavOptions) {
        if (options.hops) {
            applyAbilityToggles(options)
        } else {
            options.surge = SurgeOpt.DISABLED
            options.dive = DiveOpt.DISABLED
        }
        log("[PathingTraversal] Surge enabled: ${options.surge.enabled}, charges: ${options.surge.charges}, cooldownMs: ${options.surge.cooldownMs}")
        log("[PathingTraversal] Dive enabled: ${options.dive.enabled}, cooldownMs: ${options.dive.cooldownMs}")
        options.requirements = if (!options.teleports) {
            RequirementsBuilder.walkOnlyRequirements()
        } else {
            if (options.useItems) prepareItemRequirements(script)
            RequirementsBuilder.requirements(options.useItems)
        }
    }

    /**
     * Samples, requests and maps [options]' route into nodes, then [append]s the caller's. A route through a fairy
     * ring this account cannot use is asked for again without it. Mutates [options]: toggles, requirements, and the
     * goal an area route ends on.
     */
    internal suspend fun <T : StateMachineScript<T>> routeNodes(
        script: Script,
        options: NavOptions,
        goals: NavGoalSet?,
        exclude: Set<NavMacro>,
        append: (TraversalBuilder<T>, NavOptions) -> Unit,
    ): Routed {
        sample(script, options)
        val permissions = RingPermissions.snapshot()
        val excluded = HashSet(exclude).apply { addAll(MeasuredLinks.setAsideNow()) }
        log("[PathingTraversal] Executing navpath request. Start: ${options.start}, Goal: ${options.goal}")
        var route = requestRoute(options, goals, excluded)
        var replans = 0
        while (true) {
            val ring = permissions.forbiddenRing(route.steps) ?: break
            val macro = ring.macro ?: throw NoRouteException("the route needs the fairy ring to ${ring.code}, which this account can't use")
            if (++replans > MAX_RING_REPLANS) throw NoRouteException("every route found needs a fairy ring this account can't use")
            log("[PathingTraversal] The route takes the fairy ring to ${ring.code}, which this account can't use - routing without it")
            excluded += macro
            route = requestRoute(options, goals, excluded)
        }
        if (!route.found) throw NoRouteException("the planner found no route")
        val steps = if (options.clientPlacedAbilities) MoveMapper.abilityStepsAsMoves(route.steps) else route.steps
        log("[PathingTraversal] Path found: ${route.found}, cost: ${route.cost}, durationMs: ${route.durationMs}, lengthTiles: ${route.lengthTiles}")
        val builder = TraversalBuilder<T>()
        val last = mapSteps(steps, builder, options)
        val goal = options.goal
        // No tail when the route already ends on the goal: it would be a zero-length walk.
        if (last != null && goal != null && last != goal) MoveMapper.mapSequence(listOf(Step.Move(last), Step.Move(goal)), builder, options)
        append(builder, options)
        if (builder.nodeCount() == 0) throw NoRouteException("the route has no step this engine can take")
        return Routed(builder.buildNodes(), route, options)
    }

    /**
     * A re-route to [options]' goal from the player's server tile. The edge behind [failed], when it is an action that
     * gave up rather than a walk, is left out from now on.
     */
    internal suspend fun <T : StateMachineScript<T>> reroute(
        script: Script,
        options: NavOptions,
        goals: NavGoalSet?,
        exclude: MutableSet<NavMacro>,
        failed: TraversalNode?,
        append: (TraversalBuilder<T>, NavOptions) -> Unit,
    ): Routed? {
        failed?.takeIf(::gaveUp)?.navMacro?.let { if (exclude.add(it)) log("[PathingTraversal] Leaving out $it, behind $failed") }
        val fresh = options.copy()
        playerTile()?.let { fresh.start = it }
        log("[PathingTraversal] Re-routing to the same goal. Start: ${fresh.start}, Goal: ${fresh.goal}")
        return try {
            routeNodes(script, fresh, goals, exclude, append)
        } catch (e: NoRouteException) {
            log("[PathingTraversal] Re-route found nothing: ${e.message}")
            null
        }
    }

    /** An action node, not a walk: its edge is what a re-route can do without. */
    internal fun gaveUp(node: TraversalNode): Boolean =
        node.navMacro != null && node !is ChebychevNode && node !is PathNode && node !is TileExactNode

    internal fun goalsOf(options: NavOptions): NavGoalSet? {
        val area = options.goalArea ?: return null
        val goal = WalkGoal.of(area)
        if (options.goal == null) options.goal = goal.centre
        return goal.goalSet(options.start ?: goal.centre)
    }

    private suspend fun requestRoute(options: NavOptions, goals: NavGoalSet?, exclude: Set<NavMacro>): NavRoute {
        val start = options.start ?: throw IllegalArgumentException("NavOptions.start is not set")
        val goal = options.goal ?: throw IllegalArgumentException("NavOptions.goal is not set")
        val seed = options.seed.takeIf { it > 0 } ?: NavPathClient.generateSeed()
        val request = NavRequest(start, goal, options.requirements, options.surge, options.dive, seed, exclude, options.jsonActionsOnly, options.wantsHopWalks)
        val client = clientFor(options)
        try {
            if (goals != null && !routeAreaMissing) {
                val raw = offThread(options.timeoutMs) { client.routeArea(NavAreaRequest(request, goals)) }
                if (raw != null) {
                    val area = ResultsParser.parseArea(raw)
                    area.goalReached?.let { options.goal = it }
                    log("[PathingTraversal] Area route ends on ${area.goalReached}")
                    return area.route
                }
                routeAreaMissing = true
                log("[PathingTraversal] navpath has no /route_area - routing to $goal instead")
            }
            return ResultsParser.parse(offThread(options.timeoutMs) { client.route(request) })
        } catch (e: NavPathUnavailableException) {
            log("[PathingTraversal] navpath unavailable - routing cannot proceed: ${e.message}")
            throw e
        }
    }

    private fun clientFor(options: NavOptions): NavPathClient =
        options.serviceBaseUrl?.takeIf { it.isNotBlank() }?.let { url -> NavPathClient(NavHttp, { url }) } ?: NavPathClient.local

    private suspend fun <R> offThread(timeoutMs: Long, block: () -> R): R = try {
        CompletableFuture.supplyAsync(block, NavPathClient.executor)
            .orTimeout(timeoutMs + NavConfig.CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .await()
    } catch (e: TimeoutException) {
        throw NavPathUnavailableException("navpath did not answer within ${timeoutMs / 1000} s", e)
    }

    /**
     * Maps [steps] in order. Contiguous moves become one walk, ending on the tile an action right after them is taken
     * from; every other step is mapped with a "reached" signal from up to four sources, its chain right after it.
     * Each node is tagged with its step's edge. Returns where the last action leaves the player.
     */
    internal fun mapSteps(steps: List<Step>, builder: TraversalBuilder<*>, options: NavOptions): Tile? {
        var lastActionDestTile: Tile? = null
        var i = 0
        while (i < steps.size) {
            val step = steps[i]
            if (step is Step.Move) {
                val batch = ArrayList<Step.Move>()
                var j = i
                while (j < steps.size) {
                    val next = steps[j]
                    if (next is Step.Move) {
                        batch += next
                        j++
                        continue
                    }
                    walkUpTo(next, step.tile.plane)?.let { batch += Step.Move(it) }
                    break
                }
                MoveMapper.mapSequence(batch, builder, options)
                lastActionDestTile = batch.last().tile
                i = j
                continue
            }

            val fromCurrent = reachedFromCurrent(step)
            val reached = anyOf(fromCurrent, reachedFromEmbeddedChain(step), reachedFromNpcFirst(step), reachedFromLookahead(steps, i))
            val firstNode = builder.nodeCount()
            StepMapperRegistry.apply(step, builder, options, reached)
            if (needsDramenStaff(step)) builder.requireDramenStaffFrom(firstNode)

            if (step is Step.HasChain) {
                val chain = step.chain
                for ((c, child) in chain.withIndex()) {
                    // A child that opens a menu the next child clicks is done once that menu is open.
                    val nextIf = chain.getOrNull(c + 1) as? Step.IFSlot
                    val childReached = if (child !is Step.IFSlot && nextIf != null) anyOf(fromCurrent, { interfaces.isOpen(nextIf.interfaceId) }) else fromCurrent
                    val firstChildNode = builder.nodeCount()
                    StepMapperRegistry.apply(child, builder, options, childReached)
                    if (needsDramenStaff(child)) builder.requireDramenStaffFrom(firstChildNode)
                }
            }
            builder.nodesFrom(firstNode).forEach { it.navMacro = step.macro }
            lastActionDestTile = lastActionDestTile(step, options, lastActionDestTile)
            i++
        }
        return lastActionDestTile
    }

    /** The tile a walk stops on before [next]: a door's near side, or the origin area an action is taken from. */
    private fun walkUpTo(next: Step, plane: Int): Tile? = when (next) {
        is Step.Door -> (if (next.direction.equals("OUT", ignoreCase = true)) next.tileInside else next.tileOutside)?.let { Tile.of(it.x, it.y, plane) }
        is Step.Npc -> next.originArea?.let { Tile.of(it.minX, it.minY, plane) }
        is Step.Loc -> next.originArea?.let { Tile.of(it.minX, it.minY, plane) }
        is Step.ItemOnObject -> next.originArea?.let { Tile.of(it.minX, it.minY, plane) }
        else -> null
    }

    /** The route row lists the Dramen staff: the route counted it carried, so its nodes get it wielded first. */
    private fun needsDramenStaff(step: Step): Boolean = step is Step.HasChain && DramenStaff.requiredBy(step.requirements)

    private fun reachedFromCurrent(step: Step): (() -> Boolean)? = when (step) {
        is Step.Loc -> step.destinationArea?.let { area -> { playerTile()?.let(area::inside) == true && !isMoving() } }
        is Step.Npc, is Step.ItemClick, is Step.POAItem, is Step.ItemOnObject, is Step.IFSlot, is Step.Spell -> insideArea(step.destinationArea)
        is Step.Door -> doorReached(step)
        is Step.Surge -> { -> withinDistance(step.to, 2) }
        is Step.Dive -> { -> withinDistance(step.to, 2) }
        is Step.FairyRing -> { -> playerTile() == step.to }
        else -> null
    }

    private fun isMoving(): Boolean = TraversalSupport.isMoving()

    private fun doorReached(step: Step.Door): () -> Boolean {
        val chainArea = step.chain.mapNotNull { it.destinationArea }.lastOrNull()
        if (chainArea != null) return { playerTile()?.let { chainArea.isNear(it, 3) } == true }
        val target = if (step.direction.equals("OUT", ignoreCase = true)) step.tileOutside else step.tileInside
        return { target != null && playerTile() == target }
    }

    /** The first chained menu is open, or the player stands in the last chained click's destination. */
    private fun reachedFromEmbeddedChain(step: Step): (() -> Boolean)? {
        if (step !is Step.HasChain) return null
        val clicks = step.chain.filterIsInstance<Step.IFSlot>()
        val first = clicks.firstOrNull() ?: return null
        val area = clicks.last().destinationArea
        return { interfaces.isOpen(first.interfaceId) || area != null && playerTile()?.let(area::inside) == true }
    }

    private fun reachedFromNpcFirst(step: Step): (() -> Boolean)? {
        val id = (step as? Step.Npc)?.firstInterfaceId ?: return null
        return { interfaces.isOpen(id) }
    }

    /** The next step, and any run of them after it, is an interface click: its menu open counts. */
    private fun reachedFromLookahead(steps: List<Step>, i: Int): (() -> Boolean)? {
        val nextIf = steps.getOrNull(i + 1) as? Step.IFSlot ?: return null
        var last = nextIf
        var j = i + 1
        while (true) {
            last = steps.getOrNull(j + 1) as? Step.IFSlot ?: break
            j++
        }
        val area = last.destinationArea
        return { interfaces.isOpen(nextIf.interfaceId) || area != null && playerTile()?.let(area::inside) == true }
    }

    /**
     * Where [step] leaves the player. A chained step with neither a chained click's area nor its own lands nowhere
     * known: null, not [previous].
     */
    private fun lastActionDestTile(step: Step, options: NavOptions, previous: Tile?): Tile? = when (step) {
        is Step.Door -> if (step.direction.equals("OUT", ignoreCase = true)) step.tileOutside else step.tileInside
        is Step.Surge -> step.to
        is Step.Dive -> step.to
        is Step.FairyRing -> step.to
        is Step.HasChain -> {
            val area = step.chain.lastOrNull { it is Step.IFSlot && it.destinationArea != null }?.destinationArea ?: step.destinationArea
            val goal = options.goal
            if (area != null && goal != null) Tile.of(area.minX, area.minY, goal.plane) else null
        }
        else -> previous
    }

    private fun withinDistance(to: Tile, maxDistance: Int): Boolean {
        val here = playerTile() ?: return false
        return here.plane == to.plane && here.getDistance(to) <= maxDistance
    }
}
