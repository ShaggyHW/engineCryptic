# Traversal

Movement and interaction sequences for scripts: a linked list of `TraversalNode`s run one step at a time by a
`TraversalProcessor`, built by hand with the `TraversalBuilder` DSL or from a navpath route by `PathingTraversal`. Web
walking (`Script.webWalk*`, `WebWalker`, `Wait.webWalk*`) is a traversal built from a route.

```
traversal/            Traversal, TraversalBuilder, TraversalProcessor, TraversalDriver, TraversalNode(List),
                      TraversalOutcome, WalkEquivalent, EarlyStart, WalkOutlook, JavaTraversal,
                      JujuSpiritbag, MasterFarmerHat
traversal/nodes/      every node, RouteWalker and its pieces (RoutePicks, HopRules, HopSchedule, ScheduleLearning,
                      AccelerationPlanner, MovePrediction, ArrivalWatch, HopFlight), TraversalSupport, Interactions,
                      LocalTargets, MenuGuard, PouchItems, SkillcapePerks, DramenStaff, TourStops, ClientAbilities
traversal/pathing/    PathingTraversal, NavPathClient, NavConfig, NavOptions, ResultsParser, Step, RequirementsBuilder,
                      RingPermissions, Reachability, MappingContext, mappers/ (one per step type)
traversal/hops/       hop learning: HopModel, HopEdges, Evidence, HopStore, HopKnowledge, HopObservation, HopExplainer,
                      HopRecorder, HopExplorer, HopBlockers, ScheduleStore, HopLearning
traversal/local/      LocalNav (the trusted view of the live map), GridSearch, ServerWalk
webwalker/            the WebWalker façade, WalkGoal, WebWalkResult, WebPath, and links.json (WebLinks, MeasuredLinks)
game/collision/       the live collision map the walk decisions read
```

## Quick start

`Traversal.traversal(next, finishedCondition) { ... }` builds a traversal state; return it from `checkNext()`.

```kotlin
class GoToVarrock : State<MyScript>() {
    override suspend fun MyScript.checkNext(): State<MyScript>? {
        val target = Tile.of(3210, 3424, 0)
        return Traversal.traversal(next = Arrived(), finishedCondition = { localPlayer.tile.getDistance(target) <= 5 }) {
            useLodestone(Lodestone.VARROCK) { localPlayer.tile.getDistance(target) <= 25 }
            path(localPlayer.tile, target)
        }
    }

    override suspend fun MyScript.stateLoop() { }
}
```

- `Traversal` is a `State<T>`: returning it runs the traversal until it stops, then moves to `next` (or to the
  interrupt's state).
- It finishes when `finishedCondition` holds (checked first, every pass), or when its last node is reached.
- Without `next`, drive it inline: `Traversal.traversal(finished) { ... }.run(script)`.
- For a whole walk across the map, skip the builder: `webWalk(destination)` or the builder's own `webWalk(...)` node.

```kotlin
val result = webWalk(Tile.of(3185, 3436, 0))
if (!result.isSuccess) println(result)
```

## Builder reference

All in `TraversalBuilder.kt`. `Area` is `util.Area` (x/y only); `WorldArea` is the script `Area` (with
`Rectangular`), which counts the plane. Prefer the `WorldArea` forms.

**Objects**
- `interactObj(action, searchRadius = 30)`, `interactObj(action, searchRadius = 30, reached)`
- `interactObj(id, action, searchRadius = 30)`, `interactObj(name, action, searchRadius = 30)`, each also with `reached`
- `interactObj(action | id | name, …, searchRadius, requestTile, reached = null)`: candidates are judged from
  `requestTile` instead of the player.
- `interactObj(id | name, action, searchRadius, destination: WorldArea?, requestTile: Tile?, reached)`
- `interactObj(identifier: ObjectIdentifier, action, searchRadius, destination, origin: WorldArea.Rectangular?, reached,
  skipWhenPassable)`: the route mapper's form. The radius is measured from `origin`; with `skipWhenPassable` an obstacle
  whose far side is already a short walk away is walked past.

**NPCs**
- `interactNpc(action, searchRadius = 30)`, `interactNpc(id, …)`, `interactNpc(name, …)`, each also with `reached`.

**Interfaces and items**
- `clickIFSlot(ifSlot, optionNum = 1, reached = null)`, `clickIFSlot(ifSlot, optionNum = 1, destination: Area)`,
  `clickIFSlot(ifSlot, optionNum = 1, destination: WorldArea)`
- `clickItem(itemName | itemId, option: String | optionNum: Int, reached = null)` and the same with
  `destination: Area` or `destination: WorldArea`.
- `useItemOnObj(itemName | itemId, obj: SceneObject | objectName | objectId, searchRadius = 20, reached = null)`, and
  `useItemOnObj(itemName | itemId, objectName | objectId, searchRadius, destination: WorldArea, reached = null)`.

**Walking**
- `path(start, end, reached = null)`: a walk node over `end`'s route. Reached within 5 tiles of `end` by default.
- `chebychevPath(start, end: List<Tile>, fallback = null, reached = null)`: walks through key tiles, densified.
- `chebychevPath(start, end, routeTiles, abilities = ClientAbilities.NONE)` and
  `pathTiles(start, end, tiles, abilities = ClientAbilities.NONE)`: walks over a route's own tiles, with the hops
  `abilities` allows.
- `walkExact(tile, minimap = false, reached)`: one click on an exact tile; arrival is the server tile on it unless
  `reached` says otherwise.
- `surge(tile, enabled, reached = null)`, `surge(from, tile, enabled, reached)`, `dive(tile, enabled, reached = null)`:
  planner-placed hops (see [Surge and Dive](#surge-and-dive)).
- `webWalk(destination, arriveDistance = WebWalker.DEFAULT_ARRIVE_DISTANCE, useLodestones = true, reached = null)`: a
  whole web walk as one node; if the walk cannot get there the node hands on to the next.

**Doors**
- `door(doorInfo, direction, reached = null)`, `doorIn(doorInfo, reached = null)`, `doorOut(doorInfo, reached = null)`.

**Teleports**
- `useLodestone(lodestone, reached = null)`
- `castSpell(spell, destination: Area? = null, reached = null)`, `castSpell(spell, destination: WorldArea, reached = null)`,
  `castSpell(spell, action: String?, destination: WorldArea, reached = null)`,
  `castSpell(spell, action: String?, destination: Area?, reached)`: `action` picks a book action, such as an alternate
  destination. A spell on another book is reached with a Spellbook Swap from the action bar.
- `fairyRing(code, reached = null)`, `portableFairyRing(code, reached = null)`: the ring is found from our measured
  rings.
- `fairyRing(fairyRingObjectId, code, action, searchRadius = 30, reached = null)`,
  `fairyRing(fairyRingObjectId, code, action, reached)`
- `spiritTreeRerooter(destination: WorldArea?, reached = null)`: opens the spirit tree map; the next node picks.
- `poaItem(itemId, action, action2, action3, reached = null)`, `poaItem(itemId, action, action2, action3, destination)`:
  the Passage of the Abyss, then the jewellery, then the place; a null stage is skipped.

**Building**
- `build(next, finishedCondition[, interruptCondition][, noMovementTimeoutMillis])`
- `build(finishedCondition[, interruptCondition][, noMovementTimeoutMillis])`: no `next`, for `run(script)`.
- `buildNodes()`: the node list alone, for a re-route supplier. `nodeCount()`.
- `requireDramenStaffFrom(index)`: every node from `index` on needs the staff wielded.
- `build` throws when no node was added.

## Execution model

`TraversalProcessor.process(script)` runs one pass. In order:

1. **Conditions.** The finished condition, then the interrupt (`interruptWhen`). A condition is evaluated at most once
   per server tick; a stop latched by the tick poll is acted on here.
2. **Re-route.** A node that called `requestTraversalRepath()` gets the rest of the list replaced by the supplier's
   (`repathWith` / `repathSuspending`), at most `Traversal.MAX_REPATHS` times. With no supplier, or past the limit, the
   request is refused at the source and the node falls back on its own.
3. **Failure.** `failTraversal(reason)` stops it with `FAILED`.
4. **No movement.** With a timeout, a player who stood still that long on one node stops it with `TIMED_OUT`.
5. **Progress.** When the player already stands on a later `WalkEquivalent` node's tiles and every node between is
   passable, the processor jumps to it. A node whose `holdsStop()` is true is never jumped past.
6. **Reached.** Up to 8 reached nodes are passed in one pass; the last one reached stops with `COMPLETED`.
7. **Dramen staff** (below), then **prewarm**: before a node that will move the player far, the collision worker is
   asked to load the landing.
8. **Work.** `curr.process(script)`: `true` stays on the node, `false` moves to the next, or `STALLED` with none.

Tiles are read from the **server tile**, falling back to the client tile. `Traversal.outcome` says how it stopped;
every node hears `traversalStopped(outcome)` once.

**Conditions and interrupts.** `interruptWhen(name, condition, onInterrupt)` labels the interrupt in the log and sends
it to its own state; `onInterrupt(state)` only sets the state. The conditions are also polled once per server tick
from the script's tick hook (`Script.onTickLoop` → `State.stateTickLoop`), so a stop latches on the tick it holds even
while a node blocks in a wait; nodes end long waits early on `traversalStopRequested()`. `pollOnTick(false)` turns the
tick poll off; `pollConditionsOnTick()` and `pollConditionsNow()` are for a script driving `run` itself. A subclass
overriding `stateTickLoop` or `onTickLoop` must call super.

**Timeouts.** A hand-built traversal has no no-movement timeout unless given one. A traversal built from a route
(`PathingTraversal`, web walks) uses `Traversal.ROUTE_NO_MOVEMENT_TIMEOUT_MILLIS`, 15 s.

**Dramen staff.** A node marked `requireDramenStaff()`, or answering `needsDramenStaff()` (a fairy ring before
A Fairy Tale III is done, or a route step whose row needs the staff), gets the staff wielded from the backpack first.
Three failed wields fail the traversal; with no staff carried it re-routes or fails. What the staff replaced goes back on
once no later node needs it, and whenever the traversal stops other than for a re-route.

**Node contract.**
- `process(script)` does one small step; `true` to keep working on this node.
- `reached(script)` is fast and side-effect free; it is polled often.
- `copy()` copies every field the node mutates. The processor runs a copy of the list, so one builder can back many
  traversals.
- A node finishing early on its own returns `false` from `process`.

**Java scripts.** A `JavaStateMachineScript` returns a `JavaTraversal` from `checkNext`, with `BooleanSupplier`
conditions and the same interrupt, re-route and tick-poll calls; `JavaTraversal.nodes(b -> ...)` builds the list. A plain
`JavaScript` runs a list to its end with `Wait.traversal(nodes, finished, interrupt, timeout, onResult)`, and walks with
`Wait.webWalk*` / `Wait.findWebPath*`. Builder methods taking a `Tile` are not reachable from Java (`Tile` is a value
class).

## Web walking and PathingTraversal

Routes come only from the **navpath the launcher runs on this machine**, found through
`~/.projectx/navpath/endpoint.json`, or the `-Dprojectx.navpath.url` property when set. There is no client-side
planner: with navpath down a walk fails with "start the Project X launcher" and never hangs.

`PathingTraversal` turns a route into a traversal:

1. **Sample** on the game thread: the action bar's Surge and Dive with their real cooldowns (the observed cooldown,
   halved by the Mobile perk, shortened during a Powerburst, Double Surge and Bladed Dive), then
   `prepareItemRequirements` (refresh the master farmer hat's count, open the currency pouch for memory strands), then
   the requirement keys (`RequirementsBuilder`).
2. **Request** off the game thread (`NavPathClient`): `/route`, or `/route_area` for a goal area (a navpath without it
   is asked for the goal tile). A planner still warming up (503) is asked again after a pause. The seed is stable per
   account. Edges to avoid go as `exclude_macros`, and a Dive still cooling down as `dive.available_in_ms`.
3. **Parse** (`ResultsParser` → `Step`s). An unknown, `teleport` or `global_teleport` step is logged and dropped; the
   rest of the route is walked.
4. **Ring check.** A route through a fairy ring this account cannot use (`RingPermissions`, from our measured rings) is
   asked for again without it.
5. **Map** each step through its mapper into nodes, each node tagged with the edge (`NavMacro`) it came from. Planned
   Surges and Dives become the walk they cover (`MoveMapper.abilityStepsAsMoves`) unless
   `clientPlacedAbilities = false`. No tail walk is added when the route already ends on the goal.

A **re-route** goes to the same goal from the server tile, leaving out the edge behind the node that gave up (an action,
never a walk): a link given up on, a locked door, a ring the account cannot use. `buildTour`/`leg` route several legs
and stitch them; `TourStops` keeps walks and hops from running past a tour's stops.

`NavOptions` carries every switch: `useItems`, `teleports`, `hops`, `clientPlacedAbilities`, `useAccelerationPotion`,
`exploreHops`, `scheduleHops`, `skipPassableObjects`, `preferChebyshev`, `goalArea`, `timeoutMs`, the seed.
`routingOptions`, `walkOnlyRoutingOptions` and `routingOptionsUnderAttack` give the usual sets.

**Requirements.** Base and spell keys always go; item keys with `useItems`. Lodestones, interface clicks, menu teleports
and the Passage of the Abyss go only while nothing has attacked the player recently (attack memory: hitsplats and NPCs
targeting the player nearby), since a hit cancels them. Every var and item is read by gameval name. Beyond navpath's
own keys we send `hasPontifexRing`, `hasGhostspeakWorn` and the var keys the links.json rows are gated on.

**`WebWalker`** is a façade over this. `walk` routes, drives the traversal in a `TraversalDriver`, and routes again from
where the player stands after `FAILED`, `TIMED_OUT` or `STALLED`, leaving the failed edge out. It watches fairy rings:
one that sets the player down nowhere near its landing is set aside from every route for 30 minutes
(`MeasuredLinks.setAside`): a ring the account has not unlocked lands in Zanaris, and dialling it again only loops. It
puts back what a Dramen staff replaced. `findPath` / `findPathAsync` plan without walking and return the route as a
`WebPath`. Its switches (`useItemTeleports`, `useMovementAbilities`, `useAccelerationPotion`) are views onto
`WebWalker.options`.

**links.json** (`WebLinks`) is merged into navpath's map at release. At runtime it supplies what the map lacks
(`MeasuredLinks`): the answer to a prompt an object puts up (Kharid-et's "Choose destination."), the action a ring
object takes, ring landings and ring permissions.

## The live collision map

`game/collision` keeps a collision map of the client's loaded window on a worker thread and publishes immutable
snapshots; squares the client has not loaded yet are served from our cache in their default state. Collision blockers
say which loc blocks an edge (doors, hop learning).

`LocalNav` is the traversal's view of it, and every answer first asks `trusted`:
- the tile is inside the window and the map status is OK;
- the player is not arriving: after a teleport nothing is trusted until the window has re-centred and every square has
  loaded, or the count still unloaded has held still for a while, or a few seconds have passed. The landing time is the
  worker's own jump time.

An untrusted question answers `UNKNOWN` or null, and the caller does what it would without a map. Walk and hop
planning opt into static squares with `allowStatic`, so hops can start right after a teleport before the window is live;
door and object reachability never do, because an open door reads shut in static data. A client whose offset table
lacks the reader falls back to `CacheWalkMap` (cache collision plus the scene's door changes).

`GridSearch` is the BFS under it; `GridSearch.serverPath` reproduces the server's own choice among equally short
walks (neighbour order, the parent chain back from the click, an unreachable click walking to the nearest reached tile).
`ServerWalk` predicts the server walk of the last click: which tiles the player walks next, which way each step faces.
Any other synthetic action or a hop drops the prediction; a walk clicked mid-hop is predicted from the hop's landing.

What reads it:
- **Walk nodes** (`RouteWalker`, below).
- **`DoorNode`**: open or shut from the collision edge (its two side tiles join by a short walk), which is right for
  doors whose leaf shows open while the edge stays solid; object ids decide elsewhere. An open door is walked through,
  a shut one opened from a few steps away, a locked one fails the traversal, one the game says cannot be reached asks
  for a re-route. Closed variants (a gate with two closed ids) are recognised.
- **`ObjectNode` / `NpcNode` / `ItemOnObjectNode`**: the target nearest by walking within the search radius; with an
  origin area only those at it, and of twins there the one the destination lies behind (`LocalTargets`). Unreachable,
  or answered "You can't reach that.", they ask for a re-route. An `EarlyStart` node is handed over to before the walk
  reaches its tile.
- **Reached checks** use the server tile and require the same plane.

## RouteWalker

The live-map walking step shared by `ChebychevNode` and `PathNode`.

- **Clicks** go to the furthest route tile within the look-ahead whose live walking distance matches its route distance
  (`RoutePicks.choose`): never a blocked tile, never a U-turn tile across a wall. Re-clicks go off the server tile and
  never repeat a tile; the aim runs on through open doors and other passable nodes after the walk. A walk click sent
  with a hop is planned from the hop's landing.
- **Stuck** with nothing reachable ahead: a closed door just ahead takes over, else the server's own pathing gets one
  click past the spot, else the route is re-planned. The live map can be wrong where the route is not.
- **Landings.** A teleport, boat or charter landing holds the player a while; a walk or hop sent meanwhile is dropped.
  `ArrivalWatch` learns that hold per what sent the player and re-clicks a walk the landing dropped.
- Untrusted map: the node walks its keypoints as it would without one.

### Surge and Dive

By default (`clientPlacedAbilities`) the route is requested with the abilities, so its shape and teleport choices
assume them, but the hops themselves are placed by the client on the live map:

- **Surge** goes one of the 8 ways, the way the player's last step faced. While walking that comes from the predicted
  server walk; a surge fires from the server tile at the cast or up to a tick on, so it is cast only when every such tile
  faces the same way and each landing gains enough walking steps toward the route ahead (less near the walk's end). No
  walk click is sent to line it up unless `lineUpSurge` finds one that runs straight.
- **Dive** aims at the furthest route tile in reach, so it uses its full range while the player keeps walking, and it
  must land well from every tile it may fire from. The line to the landing must be walkable: the game refuses a dive
  through anything you cannot walk through.
- **The schedule** (`HopSchedule`) decides whether to cast the ready ability now or keep it for a better spot ahead,
  the cooldown turned into running steps; past the live map's edge a ready ability is worth an ordinary hop per cycle.
  Hops are also planned past interactions (a climb, a door) onto the walks after them, with each interaction's dead time
  learned per action (`Interactions`).
- **Combos.** With both ready, a Surge and a Dive can go in one server tick (`surgeThenDive` / `diveThenSurge`, the
  follow-up click paced just after the first at tick start); the joint plan is weighed first, so a probe never takes a
  hop a combo needs.
- **Flight state** (`HopFlight`) is shared across walkers: no walker casts or clicks around another's hop in the air.
- **`SurgeNode` / `DiveNode`** run only with `clientPlacedAbilities = false`: they predict and pre-check the planned
  hop, face first, and read a blocked cast ("You can't dive through", "Destination unreachable") from chat on a later
  pass. A blocked cast sets a cooldown, walks instead, and re-routes once that walk has finished, since the route was
  wrong about the terrain. A cooldown or an unslotted ability just walks: the route already planned around it.

`ClientAbilities` carries the per-node switches (surge, dive, potion, explore, schedule); `ClientAbilities.NONE` walks
only.

### Powerburst of acceleration

With `useAccelerationPotion` (on by default, inert without the potion), `AccelerationPlanner` replays the burst on the
live map on a clock with the walker's own rules (the predicted server walk, Surge before Dive, chained hops, the recast
delay, no dive in the burst's last tick) and the walker drinks where the projected saving, net of the running each hop
costs and of abilities that would come back inside the burst anyway, clears the threshold and no point a little further
on would save clearly more. The drink log lists the projected hops so they can be checked against the ones that follow.
During the burst the walker hops as fast as the flights allow and casts no probe.

## Hop learning

The map knows walls, but not every edge a hop can or cannot cross. Hop learning records what hops actually did and feeds
it back into the hop rule; walking never reads it.

- **`HopKnowledge`** is the overlay every client reads, in layers summed per key: the shared contributor data (a
  checkout folder from `RS3_HOP_KNOWLEDGE_DIR` / `-Dprojectx.hopKnowledgeDir`, else the copy shipped in the engine's
  `hops/` resources), the machine-local store, and evidence not flushed yet. Lines from an older model are dropped.
- **`HopStore`** lives at `~/.projectx/hops`, shared by every client on the machine: `knowledge.jsonl` (a model header,
  one evidence line per key, merged under a lock and replaced atomically; a file that does not parse is never
  overwritten) and `observations/` (append-only per client per day, kept a bounded time and size so a new model can
  re-derive the evidence). Data from a model too old to re-derive is moved aside on first start.
- **`HopObservation` / `HopExplainer` / `HopRecorder`** turn a hop's prediction and outcome into evidence, attributing a
  short hop to the blocker that stopped it. Manual evidence outranks learned evidence.
- **`HopExplorer`** casts probes: hops the map says are blocked or short, tried because one crossable edge would make
  them worth it. Only on a learning client, only when the ability would otherwise sit idle, never through a door, water
  or void, never in an instance, the Wilderness, a fight or at low health. A refuted edge is never probed again.
- **`ScheduleStore`** keeps what the schedule learns (hold margins, ordinary hop gains, combo trust, landing holds,
  interaction times) as weighted sums, layered the same way, with the old constants as priors.

Switches, read once at start:
- `-Dprojectx.hopLearning=true` or `RS3_HOP_LEARNING=1` turns writing on; the contributor id comes from
  `-Dprojectx.hopContributor` or `RS3_HOP_CONTRIBUTOR`, else is derived from the user name. Without both the client
  only reads.

## Kill switches

| Switch | Effect |
|---|---|
| `-Dprojectx.hopSchedule=false` / `RS3_HOP_SCHEDULE=0` | The schedule never keeps a hop: every ready ability is cast at the first spot that clears its need. |
| `-Dprojectx.sameTickPairs=false` / `RS3_SAME_TICK_PAIRS=0` | No one-tick Surge/Dive combos. |
| `NavOptions.exploreHops` / `scheduleHops` / `hops` | Per route: no probes, no schedule, no hops at all. |
| `WebWalker.useMovementAbilities` / `useAccelerationPotion` / `useItemTeleports` | The same for every web walk. |

## Nodes in brief

- **`ObjectNode`**: anchoring and the twin pick above; `skipWhenPassable`; looks again from the new side after a click
  that moved the player; a travel object may stand the player still before carrying them off; a stop never cuts short
  an interaction under way; no click while a teleport landing would drop it, and an animation already playing at the
  click is not the interaction starting. Without a destination or `reached` it is done once the interaction carried the
  player to another floor or onto the next walk's tiles.
- **`DoorNode`**: above. Tries **"Quick open"** first, then Open, Go-through, Pass-through: "Quick open" skips the
  dialogue some gates start on "Open", which otherwise leaves the gate shut. Reached on the target tile, or beside it
  and nearer it than the near side.
- **`NpcNode`**, **`ItemOnObjectNode`**: nearest by walking, `EarlyStart`, re-route when unreachable.
- **`IFSlotNode`**: a failed click retries, a click that went through finishes the node. With **`MenuGuard`**, a
  teleport that opens a menu asks for a re-route when the player is attacked (a hit closes the menu), at most once per
  opener in a while.
- **`ItemNode`**: prefers the item that has the op, then a skillcape perk op on the worn cape (`SkillcapePerks`), then a
  name match. Memory strands come from the currency pouch (`PouchItems`, which opens a collapsed pouch). The last juju
  teleport spiritbag used triggers a restock from Papa Mambo (`JujuSpiritbag`), never while the master farmer hat is
  held. Non-teleport clicks wait only for their animation.
- **`TileExactNode`**: server-tile arrival; waits out a far landing and never declares itself stuck during one; a tile
  already being walked to is not clicked again.
- **`LodestoneNode`**: a blocking cast with a result; an interrupted cast while attacked fails the node.
- **`SpellNode`**: the book action, a Spellbook Swap first when needed.
- **`FairyRingNode`**: Travel Log one-click select when the ring is listed, else the three dials turn together; a failed
  teleport re-dials; Escape closes the dial on a stop or re-route. The **portable ring** dials from the item, activating
  an inactive one first; the game refuses that while an active one exists anywhere, the bank included, and that refusal
  keeps the portable ring out of routes until an active one is carried again (`ActivatedDevice`). The charge message is
  checked after the press. With no ring left it re-routes.
- **`SpiritTreeRerooterNode`**: the same activation latch as the portable ring.
- **`POAItemNode`**: the Passage of the Abyss, picked by position in its first menu.
- **`MasterFarmerHat`** keeps the hat's teleport count from its chooser, the bank grid, and landings; when nothing has
  shown a count yet, the planner opens the chooser once before routing.
- **`WebWalkNode`**: a whole web walk as one node.

## Writing a node

1. Subclass `TraversalNode`: `process`, `reached`, `copy`.
2. Add a builder method that appends it.
3. Keep `process` small and safe to call again; wait with `TraversalSupport.delayUntil` and end waits on
   `traversalStopRequested()`.
4. Call `requestTraversalRepath()` when the route is wrong, `failTraversal(reason)` when nothing can work.
5. Implement `WalkEquivalent` if the node is a walk the processor may skip over, and `EarlyStart` if it can start
   before the walk reaches it.

```kotlin
class WaitUntilNode(private val condition: () -> Boolean) : TraversalNode() {
    override suspend fun process(script: Script): Boolean {
        TraversalSupport.delayUntil(600) { traversalStopRequested() || condition() }
        return true
    }

    override fun reached(script: Script) = condition()

    override fun copy(): TraversalNode = WaitUntilNode(condition)
}
```

## Troubleshooting

- **Never advances:** give `ObjectNode` / `IFSlotNode` a `destination` or `reached`, or rely on `finishedCondition`.
- **Web walk fails at once with "start the Project X launcher":** navpath is not running on this machine.
- **A route keeps taking a ring that lands elsewhere:** it is set aside for 30 minutes after the first misroute; a ring
  the account cannot use at all is checked against our measured rings before walking.
- **Hops never fire right after a teleport:** the map is still arriving; walk and hop planning resume on static squares
  as soon as every square is live or static.
- **Door not found:** check the `DoorInfo` ids and tiles. After a teleport a missing door is waited for while the scene
  loads.
