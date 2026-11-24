# Traversal Parity — Implementation Plan

Written 2026-11-24, re-checked the same day after the local-navpath commits · Status: **port complete**. Waiting
on live validation (§7) and the open questions (see the status board). · Scope: full parity (option 2)

## Status board

Kept current while the port runs. Newest entries first in the log.

**Done**

| Phase | What landed | Tests |
|---|---|---|
| P0 | Hoor2's shipped hop data copied to `src/main/resources/hops/` (`contrib-*.jsonl`, `schedule-*.jsonl`). Synthetic-map test fixture: `traversal/local/LiveMapFixture.kt` (P3). | — |
| P1 (R1, R5–R14) | Ported from Hoor2's native layer with `GS_*`-cited offsets in `linux-x86_64-951-4.json`; struct fields mirrored to Windows. Covers: `PathingEntity.serverTile`, `facingAngle`/`facing()`/`isFacing`, `AttackMemory`, component and slot ops (`InterfaceComponent.ops`/`optionIndexOf`/`eventMask`/`onScreen`, `IFSlot.click(name)`), `Item.varDomainVarBit`, `ServerTick.millisIntoTick` + `ActionPacer` + `MovementCombos` (one-tick Surge/Dive pairs), `RecentChat.findSince`, `Script.lodestoneTeleport → LodestoneResult`, `SpellTeleports.cast(…, action, …)`, `Perks`, `Quests.progress`, `isComponentOnScreen`, `npcByServerIndex`. | Bearings, AttackLedger, RecentChat, ActionPacer, SpellBookAction |
| P1 (R2–R4) + P3 | `game/collision/` live collision map (worker thread, immutable snapshots, static squares from our cache on arrival, prewarm, worker jump time, blockers). `traversal/local/` holds `LocalNav` (trust gate), `GridSearch` and `ServerWalk` (`tickOn`, `click`). `LiveWalkMap` is now a typealias of `LocalNav`; the old map is `CacheWalkMap`, the fallback on Windows. Hop hooks are wired (`HopMap.install`, `HopBlockers.source`). | CollisionGrid, CollisionWorker, LocalNav, ArrivalAndServerWalk, LocalWalking |
| P2 | Core framework: interrupts (labelled), tick-deduped condition poll, `pollConditionsOnTick`/`Now`, suspend `run`, `repathSuspending`, D8 timeout constant, Dramen staff ladder, `traversalStopped`, `holdsStop`, `EarlyStart.readyFrom(serverTile)`, `WalkOutlook`. Also: R15 tick hook (`State.stateTickLoop`), `JavaTraversal` + `Wait.traversal` for Java scripts, builder `build` overloads, `nodeCount`/`buildNodes`/`requireDramenStaffFrom`. Processor reads the server tile and calls `prewarmLanding`. | TraversalProcessorTest (24) |
| P6 | Hop learning moved to `traversal/hops/` and brought to model 8: `Evidence`, `HopStore`, `HopKnowledge`, `HopObservation`/`Explainer`/`Recorder`, `HopExplorer`, `ScheduleStore`, `HopBlockers`, `HopLearning`. A model-6 store is moved aside on first start. | HopLearningTest (20), ScheduleStoreTest (9), HopModelTest (8) |
| P4a | `pathfinder/navpath` moved to `traversal/pathing`: `Step`, `ResultsParser`, `NavConfig`, `NavPathClient` (absorbs NavPlanner), `NavOptions`, `Reachability`, `RequirementsBuilder` (absorbs NavProfile and WebLinkPermissions' key evaluation), `NavRouteMapper`. **D6:** unsupported steps are dropped, not the route. All Java step fields and chain child types, hop geometry (`withHopWalks`), `/tile/exists`, `/reachable`, the 404 fallback, a stable seed, Java's timeouts. Every §6.4 key is added, the evaluation differences are fixed, and **D7** Wicked hood pools are in. `WebWalker` vars now read `WebWalker.options`. | NavpathTest, NavpathMergedMapTest, SpellStepTest, ItemChainHopsTest, HopWalkGeometryTest, RequirementsBuilderTest |
| P8 | Cutover and cleanup:<br>• Deleted: `traversal/walk/*` + `WalkPlanningTest`; `WebLinkActions` + test; `NodeWaits` + test; the `LiveWalkMap` alias (callers use `LocalNav`); `WebLinkPermissions` (dissolved); dead `WebCollision`; dead `WebGrid` door helpers; dead `DramenStaff` link marking.<br>• New homes: `nodes/ActivatedDevice` (portable-ring and re-rooter ids and latches), `pathing/RingPermissions` (links.json ring check), `webwalker/MeasuredLinks` (misroute memory, prompt answers, ring actions).<br>• Kept for public API: `WebPath` + `NavRouteMapper` (`WebWalkResult.path`), `PathingTraversal.setAside`.<br>• `traversal/README.md` rewritten.<br>• LiveMapTraversalTest parity: all 49 Java cases covered (38 already, 11 completed).<br>• Rule sweep fixes. | 622 tests, only the 9 baseline failures (verified independently) |
| P7b | **Full RouteWalker:** click choice, early start, stuck handling, Surge/Dive placement, probes, the hop schedule with one-tick combos, `lineUpSurge`, `keepMovingWhileArming`, the learned landing hold (`ArrivalWatch`), clicks planned from the landing, shared flight state (`HopFlight`), `HeadingTrack`.<br>• `AccelerationPlanner` v2.<br>• `SurgeNode`/`DiveNode` (planner-placed mode, cross-tick blocked-cast detection).<br>• `ChebychevNode`/`PathNode` rebuilt on `RouteWalker`.<br>• Shared `ServerWalk.shared` fed by `TraversalSupport.walkTo`; `LocalNav.withStaticSuspending`; `observe` fed the server tile.<br>• Builder `surge`/`dive`; Surge/Dive mappers produce the nodes when `clientPlacedAbilities = false`.<br>• Core: `Effect.POWERBURST_OF_ACCELERATION` added. | RouteWalkerTest (14), AbilityStepMapperTest (2); 647 tests, only the 9 baseline failures |
| P4b | **Web walks now run on the ported pipeline.**<br>• `PathingTraversal`: sample (toggles, `abilityCooldownMs`, requirements, `prepareItemRequirements`) → request off the game thread → `ResultsParser` → `abilityStepsAsMoves` → `mapSteps` → `TraversalNodeList`, every node tagged with its `NavMacro`. Re-routes go to the same goal from the server tile with `exclude_macros`. Tours via `TourStops`; D8 timeout.<br>• `abilityCooldownMs` (observed varc pair, MOBILE halving, burst guard, Double Surge, Bladed Dive) replaces the fixed 20 400 ms; `HopCharges` uses it too.<br>• Mappers (all 15 + `StepMapperRegistry`), plus ours: `PromptAnswerNode`.<br>• `WebWalker` is a façade: it builds a `PathingTraversal` and runs it in a `TraversalDriver`, with a misrouted-ring watch (set aside 30 min) and restart on FAILED/TIMED_OUT/STALLED excluding the failed edge.<br>• `hasMemoryStrands` is on. `ChebychevNode`/`PathNode` take route tiles + `ClientAbilities`; builder `chebychevPath`/`pathTiles` with abilities; `TraversalNode.navMacro`. | PortableFairyRingTest, SpiritTreeRerooterTest, DramenStaffStepTest, TourStitchTest, PathingTraversalTest (305 traversal + webwalker tests pass) |
| P7a | Route-walker leaf pieces:<br>• `HopSchedule`: the dynamic program, spot scans, `runTicks`, pair ordering, combos, joint plan.<br>• `ScheduleLearning`: hold margins, base gain, combo trust, `lock:` keys; dead time delegated to `Interactions`.<br>• `MovePrediction`: facing, `helps`, `diveClear`.<br>• `TourStops`, `ClientAbilities`, `StuckDetector` (moved).<br>• Shared helpers pulled out of RouteWalker: `RouteProgress`, `HopRules`, `RoutePicks`, `HopCharges`. | HopScheduleTest (12), HopScheduleMapTest (20), ScheduleLearningTest (8), RouteWalkingPiecesTest (11); 264 traversal tests pass |
| P5B | Teleport and item nodes, ported in full:<br>• `ItemNode`: prefer the item with the op, then a skillcape perk op, then a name match; pouch memory strand; MenuGuard; animation-only waits; spiritbag restock.<br>• `PouchItems`: open a collapsed pouch, 30 s backoff.<br>• `SkillcapePerks`.<br>• `LodestoneNode`: blocking cast with a result; fails on an interrupted cast while attacked; radius 3.<br>• `SpellNode`: book action.<br>• `FairyRingNode`: Java's phase machine, Travel Log select, dials together, portable charge check, under-attack reroute, Escape on stop, repath when no ring is left.<br>• `POAItemNode`: positional Passage of the Abyss pick.<br>• `SpiritTreeRerooterNode`.<br>• `DramenStaff` (stop-aware), `JujuSpiritbag` (combat and Mambo waits, 2 trades, Buy 1), `MasterFarmerHat` (`refreshIfUnknown`, 15-tick window), all moved into `traversal/`.<br>• Builder: `fairyRing(objectId…)`, `spiritTreeRerooter`, `castSpell(…, action, …)`, `poaItem` ×3. | MasterFarmerHatTest, FairyRingTravelLogTest, TeleportNodesTest, JujuSpiritbagTest |
| P5A | Walk-up nodes, ported in full:<br>• `TraversalSupport` (server-tile reads, tick timing, walk-click memory, suspend waits, the shared constants P7 needs).<br>• `ObjectNode` O1–O12.<br>• `DoorNode` (closed variants, `shutButUnlisted` through `CollisionMap.blockers`, retry then repath, door-centred search; our "Quick open" first is kept).<br>• `NpcNode`, `ItemOnObjectNode`, `IFSlotNode` + `MenuGuard` (under-attack reroute).<br>• `TileExactNode` (both race fixes, server-tile arrival).<br>• `LocalTargets` (twins).<br>• `Interactions` (learned dead time).<br>• Builder `interactObj` variants with `requestTile`, `destination` and `origin`/`skipWhenPassable`.<br>`NodeWaits` now delegates to `TraversalSupport`. | LiveMapNodesTest, LocalTargetsTest, InteractionNodesTest (205 traversal tests pass) |

**In progress**

- Nothing. Remaining work is the §7 live checklist and the open questions below.

**Waiting**

- **P4b** — DONE (see Done). Scope as planned, kept for reference:
  - `PathingTraversal`: route → node list, repath with exclusions, tours.
  - `abilityCooldownMs`, replacing the fixed 20 400 ms still in use.
  - `prepareItemRequirements`.
  - `pathing/mappers/*` + `MappingContext`.
  - `WebWalker` as a façade.
  - Turn on `hasMemoryStrands` through `PouchItems.has(PouchItems.memoryStrand, "Teleport")`, and
    call `PouchItems.prepare` and `MasterFarmerHat.refreshIfUnknown` from
    `prepareItemRequirements`.
  - Wire the `NavOptions` fields nothing reads yet: `skipPassableObjects`, `goalArea`,
    `preferChebyshev`. `exploreHops`/`scheduleHops` wait for P7.
  - The Java pathing tests that need it: PortableFairyRing, SpiritTreeRerooter, DramenStaffStep,
    TourStitch.
- **P7** — DONE (P7a + P7b). Scope as planned, kept for reference: `RouteWalker`, `HopSchedule`, `ScheduleLearning`, `MovePrediction`,
  `AccelerationPlanner` v2, `TourStops`, `ClientAbilities`, `SurgeNode`/`DiveNode`,
  walk nodes on `RouteWalker`. Walkers switch from the `LiveWalkMap` alias to `LocalNav`, feed
  `observe` the server tile, and wrap hop/walk planning in `allowStatic`.
- **P7 inherits from P5A:**
  - Wire a shared `ServerWalk` to `TraversalSupport.walkTo` and `forgetWalkClick`. Java's
    `Navigation.walkTo` notifies it; ours has no shared instance yet.
  - Move `StuckDetector` into `traversal/nodes`.
  - Have `ScheduleLearning` delegate its dead-time maths to `Interactions`.
- **P8 inherits from P5B:** the portable-ring and re-rooter latches and ids still live in
  `WebLinkPermissions`, shared by the nodes, WebLinkActions and RequirementsBuilder. Move them
  into the nodes when WebLinkPermissions is dissolved.
- **P8 inherits from P4b:**
  - Delete `NavRouteMapper` once `findPath` answers from `PathingTraversal`; also delete
    `WebLinkActions` (no caller left outside its own test), `WebPath`, `LiveRouteWalker` and the
    old `traversal/walk/*`.
  - Move the misroute memory and `PromptAnswerNode`'s prompt answers to wherever the
    `WebLinks` / `WebLinkPermissions` dissolution puts them.
  - Keep `WebWalkNode` as it is.
- **P8**, last: cutover, deletions, README rewrite.
- **Builder methods** deferred from P2 to P5 and P7: `pathTiles`/`chebychevPath` with route
  tiles, `surge`, `dive`.

**Needs a live client** (none of these readers has run injected yet)

- **Interface child lists. Check first; this goes beyond traversal.**
  - Our old Linux `STATIC_CHILDREN`/`DYNAMIC_CHILDREN` were 8 bytes below Hoor2's begin pointers,
    and there was no kind gate. Linux `slotChildren` callers (MakeX, Smithing, GE search, bank,
    inventory `IFSlot` ops, the inspector) now read the real vectors, gated on kind
    {5, 7, 20, 21, 22, 24}.
  - On Linux, check that `kind` decodes (layers read 20); `slotChildren`/`createdChildren` on
    backpack 1473:5 (28 slots) and root 1477:0 (21 children); `onScreen` for the fairy ring
    Travel Log and the GE offer/search swap.
  - On both platforms, check bank withdraw, inventory slot ops, and spell-book and lodestone
    availability, which now use `createdChildren` only.
  - On Windows, the mirrored `DYNAMIC_CHILDREN` and `DYNAMIC_SLOT_CHILDREN` should be empty or
    sensible on ordinary layers. Non-null garbage would make `onScreen` wrongly report false.

- `serverTile`, `facingAngle`, component `ops`/`eventMask`, the `Perks` varbit mapping, and the
  struct offsets mirrored to Windows.
- Collision worker: cost (`walkMicros`), the terrain-settings read (`SETTINGS_LEVELS`), static
  squares on arrival.

**Decisions (user, 2026-11-24)**, all applied (626 tests, only the 9 baseline failures):

- Bank "Withdraw-1" fallback op: **keep the engine's 1**. No change.
- Interface child-list offsets: **import Hoor2's** and port its child-list reading, including
  `onScreen`'s "parent draws this child" check.
- WebWalker restarts: **remove the façade restarts**. Only the driver's 3 repaths, with
  exclusions; after that the result goes back to the script, as in Hoor2.
- Walk-node `copy()`: **follow Hoor2**. `ChebychevNode`/`PathNode.copy()` drop the `fallback` and
  `reached` callbacks.
- Hand-built `ChebychevNode` route tiles: **follow Hoor2**, the raw `end`.
- `WebLinkPermissions`: **stays deleted**, no shim.
- `WebLinks.register` / `registerObjectLink`: **removed**, together with their script-API
  callers. This is an accepted source break.
- `WebPath.crossesDoor` / `nextDoor` / `doorCount`: **deprecated**.

**Notes**

- P4b notes:
  - A web walk returns NO_PATH with "navpath isn't running on this machine; start the Project X
    launcher" when navpath is down.
  - `WebWalkResult.path` is still a `WebPath`, built with `NavRouteMapper`.
  - The misroute watch and `PromptAnswerNode` only run in the WebWalker façade, not in routes
    built through the public `PathingTraversal.build`.
- P7b notes:
  - The same-tick-pair kill switch is `projectx.sameTickPairs` (Java's is `rs3.sameTickPairs`).
    The env var `RS3_SAME_TICK_PAIRS` is the same.
  - Logs print direction names as `NORTH_EAST` rather than `NE`.
- P7a notes:
  - The hop schedule kill switch is `projectx.hopSchedule` / env `RS3_HOP_SCHEDULE`. Java's is
    `rs3.hopSchedule`.
  - `HopCharges.fullCooldownMs` reads the cache cooldown until P4b's `abilityCooldownMs` replaces
    it; 4b was asked to do this.
  - Three of Hoor2's own `LiveMapTraversalTest` checks fail in Hoor2 itself under
    `DIVE_MIN_GAIN` 8: `armedShort` at (60,60) with the full need, the open-map ell combo, and
    `uniform(20, 7)`. We keep Java's behaviour and assert what it does. This was checked against
    Hoor2's existing `payload.jar`, built about an hour before `43350a3`.
- P5B behaviour changes to know about:
  - `FairyRingNode` re-dials without a limit, as Java does. A cap of 3 from my brief was removed
    on review.
  - A ring given by code only now waits for a ring, as Java does, instead of failing at once.
  - `ItemNode` name matching follows Java (contains, first match per tier). The old exact-first
    preference is gone.
  - Ours only: a ring with no known tile checks its landing against the measured `WebLinks`
    landing box, ±4 tiles.
  - P5B added two read-only accessors to `AbyssPassage` (`isPassage`, `slotValues`) so the slot
    var ids stay in one place.
- P5A behaviour changes to know about:
  - Nodes built with an old `util.Area` destination still check x/y only, because that type has
    no plane. Crossing detection, `passableNow` and `routeTiles` are off for them. The
    plane-aware path is the new `WorldArea` overloads.
  - `IFSlotNode` now follows Java: a failed click retries, and a successful click finishes the
    node. Before, the last node kept waiting for its destination.
  - Objects match on `isType`, which also matches the visible id of a multiloc, so it finds
    strictly more than Java's id check.
- P4a kept these vars as raw ids, in one named constant each, because they have no gameval name:
  - the Wicked hood per-altar pool varbits
  - the varbit behind the `varp_2793` key
  - the Temple Trekking client script
  - the Passage of the Abyss slot and charge vars
- P4a, still not sent (as before): `hasCaptainsLog` and `can_gote_zanaris_fairy_ring`, which
  Java doesn't send either, and `spell_199`, because the Max guild Teleport isn't in
  `TeleportSpell`.
- `Cache.mapSquare` is now also called from the collision worker thread. This was checked:
  `MapDecoder` holds no state, and `SQLiteCache` reads take a lock with thread-local
  decompression.
- Hoor2's `GS_LC_SPAWN` is the value our table calls `OLocationContainer.MULTI_LOCATIONS_BEGIN`.
  It was added as `SPAWNED_LOCATIONS_BEGIN` rather than renamed.
- Run tests with `-x buildNativeBootstrap -x configureNativeBootstrap`. Rebuilding the native
  library can crash a client that has the engine injected. Baseline: 9 known failures
  (InputContractParity, MotorParity, RecordingSchemaMirror, ImGuiEnumParity), all "could not
  locate the repository root".

**Log**

- 2026-11-24: User decisions applied. Child lists now follow Hoor2's reader; `WebWalker` routes
  once (only the driver repaths); walk-node `copy()` and raw `end` follow Hoor2;
  `WebLinks.register*` removed; `WebPath` door accessors deprecated.
- 2026-11-24: All 8 open questions answered by the user; the decisions are being applied.
- 2026-11-24: P8 done. The port is complete; live validation and the open questions remain.
- 2026-11-24: P7b done; all porting phases are complete. P8 (cutover, cleanup) started.
- 2026-11-24: P4b done (633 tests, only the 9 baseline failures). Web walks run on the ported
  pipeline. P7b told it may now rebuild the walk nodes.
- 2026-11-24: P7a done. P7b started on the new files; it touches the walk nodes and builder only
  after P4b.
- 2026-11-24: P5B done. The fairy-ring re-dial cap was removed to match Java. P7a and P4b started.
- 2026-11-24: P5A done.
- 2026-11-24: P4a done (551 tests, only the 9 baseline failures).
- 2026-11-24: P5A, P5B and P4a started. Processor switched to the server tile, with
  `prewarmLanding` added.
- 2026-11-24: P1 collision (R2–R4) and P3 done; hop hooks wired.
- 2026-11-24: P1 runtime readers done (R1, R5–R14). The user confirmed no reverse engineering is
  needed: Hoor2's native layer and `nxt_offsets.h` are the source.
- 2026-11-24: P2 and P6 done.
- 2026-11-24: P0 done; P1, P2 and P6 started.

## 0. Goal and ground rules

Bring the engine's traversal stack to **full behavioural parity** with the Hoor2 payload package
`launcher/payload/java/com/launcher/api/traversal/` (Java, ~23k lines, 90 files). From now on that
package is the **source of truth**: the Hoor2 author built it from material we sent, and we now own
it.

- **Pinned baseline:** Hoor2 `43350a3` (latest traversal commit `8d114bb`, 2026-11-24). Every
  "port X" below means "port X as it is at `43350a3`". If Hoor2 moves again, diff
  `43350a3..HEAD -- launcher/payload/java/com/launcher/api/traversal` and add the changes as a
  catch-up phase. Do not chase a moving target mid-phase.
- **Re-check (2026-11-24, after the pending commits landed).** The commits that landed were on
  **our** side, `6ac729f..ba2ea5b`, preparing for this port:
  - navpath is vendored under `navpath/` (upstream `47d6a57`, plus our edge names and
    `exclude_macros`).
  - Releases build navpath and a map with `links.json` merged in.
  - The launcher runs one local navpath per machine, which clients find through
    `~/.projectx/navpath/endpoint.json`.
  - The engine's own planner and the hosted fallback are deleted.

  Hoor2's traversal package is unchanged at `43350a3` (fetched 2026-11-24 20:48 UTC), so the
  baseline stands. This settles D5 and is reflected throughout.
  - The later `46222f5` (navpath's builder module, dropped by our ignore rules) is a navpath build
    fix with no engine impact.
- **Scope is the engine only.** The work happens in `client-plugin-engine/`, bringing
  engineCryptic up to par with Hoor2. **navpath is not touched:** neither the vendored service
  under `navpath/` nor the launcher's handling of it (`client/launcher/src/navpath.rs`, release CI).
  The engine is a client of navpath exactly as it ships today, just as Hoor2 is a client of its
  own navpath.
- **Behaviour wins.** When Java and Kotlin disagree, Java wins. The only exceptions are listed in
  §8 (things we keep) and §9 (Java bugs we do not copy).
- **Idiomatic Kotlin, not a transliteration.** Use suspend functions instead of blocking calls,
  `sealed`/`data` classes instead of extracted Java types, and default arguments instead of
  overload chains. Keep the **same numbers** (thresholds, radii, gaussian arguments, timings).
  Never re-derive them.
- **Repo rules apply.**
  - No addresses, offsets or opcodes in code comments or docs; per-build values go in
    `resources/offsets/*.json` plus the Ghidra DB.
  - Vars and items are read **by gameval name**, never by the raw ids the Java uses.
  - No inline FQNs, minimal comments, and no git operations (the user commits).
- **Reference material in Hoor2:**
  - `docs/nav_improvements/` (`TRAVERSAL_IMPROVEMENTS.md`, `DOOR_CLOSED_VARIANTS.md`,
    `SPIRIT_TREES.md`)
  - `docs/surge_dive_improvements/` (`SURGE_DIVE_IMPROVEMENTS.md`, `END_AWARE_HOPS.md`: read this
    before Phase 7)
  - `docs/collision/` (`IMPLEMENTATION_PLAN.md`, `STATIC_ARRIVAL_MAP_PLAN.md`)
  - the package `README.md`

## 1. Where we are

| Area | Hoor2 (Java) | Engine (Kotlin) today |
|---|---|---|
| Core framework | `Traversal`, `TraversalProcessor`, `TraversalBuilder`, `TraversalNode(List)`, `TraversalOutcome`, `WalkEquivalent`, `EarlyStart`, `WalkOutlook` | `traversal/*.kt`: ladder, outcome and repath plumbing present; many builder overloads, Dramen staff ladder, polling, `WalkOutlook` missing |
| Route → execution | local navpath (`127.0.0.1:8889`) route → `Step`s → mappers → **`TraversalNodeList`** run by the processor | launcher-run local navpath (`endpoint.json`) → `NavPlanner` → `NavRouteMapper` → **`WebPath` of `WebLink`s** run by `WebWalker`'s own loop; a re-plan excludes failed edges (`exclude_macros`); no in-engine planner since `d750a17` |
| Interaction nodes | 20+ rich nodes (O1–O12 object rules, door variants, pouch, perks, menu guard, POA, re-rooter, …) | thin `traversal/nodes/*`; most behaviour duplicated in `webwalker/WebWalker.kt` + `WebLinkActions.kt` |
| Walking + hops | `RouteWalker` (3.8k) + `HopSchedule`, `ScheduleLearning`, one-tick combos, probes, `AccelerationPlanner` v2 | `traversal/walk/LiveRouteWalker` (550) + `HopPlanner`; hops only in the web walker; old potion model |
| Hop learning | `hops/*`: model 8, manual evidence, places, type keys, blockers, explorer, schedule store | `pathfinder/hops/*`: model 6, no explorer, no schedule, no shipped baseline |
| Live map | native live collision masks, status, worker jump time, static squares on arrival, prewarm, blockers | cache-clipped `WorldCollision` + scene door changes; arrival = sampled unloaded check |
| Tests | 14 offline suites, ~3.6k lines (`launcher/tests/java/…/traversal`) | `TraversalProcessorTest`, `NodeWaitsTest`, `WalkPlanningTest`, webwalker + navpath tests |

The full gap list from the mapping pass is the tracker in §6.

## 2. Decisions

Decisions **D1–D4** are settled by choosing option 2. **D6–D8** were settled by the user on
2026-11-24. **D5** was settled by `d750a17` the same day. All decisions are closed.

- **D1 — Java's execution model.** A navpath route becomes a `TraversalNodeList` run by
  `TraversalProcessor`, through a Kotlin `PathingTraversal`. `WebWalker` stops being an executor
  and becomes a façade: it builds a `PathingTraversal` and drives it.
- **D2 — `traversal/` is the home.** Everything in Hoor2's package lands under
  `com.projectx.traversal` with the same sub-packages: `nodes/`, `pathing/`, `pathing/mappers/`,
  `hops/`, `local/`.
  - `pathfinder/hops/*` and `pathfinder/navpath/*` (`NavPlanner`, `NavPathClient`, `NavProfile`,
    `NavResultsParser`, `NavRouteMapper`, `NavStep`) move in.
  - `pathfinder/` keeps only engine-wide map pieces: `WorldCollision`, `StepValidator`,
    `RouteFinder`, `DynamicMapSquareCollision`, `RebuildRegionMap`.
- **D3 — Public API is kept, then extended.** Scripts compile against the external script-api
  jar, so these keep their signatures:
  - `Traversal(next, finished, nodes[, timeout])`
  - the `TraversalBuilder<T>` DSL (lambda receivers, default args, `webWalk`)
  - `Script.webWalk*`, `findWebPath*` and `canWebWalkTo*`
  - the `Wait.webWalk*` façade
  - `WebWalker.findPathAsync` / `findPath` / `walk`

  Java-shaped additions are **overloads alongside** these, never replacements.
- **D4 — Blocking becomes suspend.** Java's `Traversal.run()`, the repath supplier and HTTP calls
  become suspend functions run off the game thread, the same way `WebWalker` plans on its executor
  today. Nothing blocks `ClientPulseDispatcher`.
- **D5 — SETTLED by `d750a17`: navpath only, as in Hoor2.** Routes come only from the navpath the
  launcher runs on this machine. `WebPathfinder` and the hosted fallback are deleted. With no
  navpath running, a walk fails and tells the player to start the launcher.
  - `links.json` stays for two jobs only: the release merges it into navpath's map, and the
    engine reads per-link action data that the map lacks. That data covers prompt answers such as
    Kharid-et's "Choose destination." (`NavRouteMapper.answering`) and fairy-ring permission
    checks.
  - Under D1 that data feeds the mappers and nodes. Nothing plans from it.
- **D6 — SETTLED: unsupported steps follow Hoor2 exactly.** An unknown, `teleport` or
  `global_teleport` step is logged and dropped, and the rest of the route is walked.
  - A route is never refused for an unsupported step.
  - Since `d750a17` there is no local planner to fall back to: `NavRouteMapper`'s `Refused`
    makes the whole walk **fail**. That makes this change more urgent, and it should be among the
    first things Phase 4 lands.
- **D7 — SETTLED: Wicked hood follows Hoor2 (`c76df1e`).** An altar is usable when its own pool
  var or the omni pool var is above 0, with the hood held or worn.
  - Our model (one daily teleport plus bonus teleports, opened by a stored talisman, from
    `e685283`) is wrong and is replaced in Phase 4.
  - No live check is needed.
- **D8 — SETTLED: the default no-movement timeout is 15 s** for routes built by
  `PathingTraversal`/`webWalk`, matching Java.
  - A hand-built `Traversal(...)` keeps 0 (off) unless a timeout is passed, so existing scripts
    don't change behaviour.

## 3. Target layout

```
com.projectx.traversal
├── Traversal, TraversalProcessor, TraversalBuilder, TraversalNode, TraversalNodeList,
│   TraversalOutcome, WalkEquivalent, EarlyStart, WalkOutlook,
│   JujuSpiritbag, MasterFarmerHat                       (top level, as in Java)
├── nodes/     every Java node + DoorInfo/FairyRing/identifiers (sealed classes where Java split them),
│              RouteWalker, HopSchedule, ScheduleLearning, AccelerationPlanner, StuckDetector,
│              MovePrediction, LocalTargets, TourStops, ClientAbilities, Interactions,
│              MenuGuard, PouchItems, SkillcapePerks, DramenStaff, TraversalSupport
├── pathing/   PathingTraversal, ResultsParser, Step, NavPathClient, NavConfig, NavOptions,
│              Reachability, RequirementsBuilder, MappingContext
│   └── mappers/   one mapper per step type + StepMapperRegistry
├── hops/      HopModel, HopEdges, Evidence, HopBlockers, HopStore, HopLearning, HopKnowledge,
│              HopObservation, HopExplainer, HopRecorder, HopExplorer, ScheduleStore
└── local/     LocalNav, GridSearch, ServerWalk
com.projectx.webwalker   façade only: WebWalker (API), WalkGoal, WebWalkResult,
                          + links.json / WebLinks as per-link action data for mappers and nodes (D5)
```

`NavJson` is not ported; Gson does that job. `webwalker/WebLinkActions.kt` and
`webwalker/WebLinkPermissions.kt` are dissolved into the nodes and `RequirementsBuilder`. Anything
that is ours only and must survive (§8) moves with them.

## 4. Phases

Each phase ends **green**: `./gradlew :client-plugin-engine:test` passes, and the phase's ported
Java test suites pass as JUnit tests. Phases 1 and 6 can run in parallel with Phase 2. Phase 7
needs everything before it.

### Phase 0 — Harness and baseline (small)

1. Record the baseline: `git -C ../Hoor2 rev-parse HEAD` → `43350a3` in this file.
2. Add a synthetic collision map for tests: an equivalent of Java's
   `CollisionMap.installForTests`, behind an internal seam in `local/LocalNav` and `WorldCollision`.
3. Port the Java test helpers (`LiveMapTraversalTest$Map` and so on) to a Kotlin test fixture.
4. Copy Hoor2's shipped learning data (`launcher/payload/hop_knowledge/`: `contrib-*.jsonl` and
   `schedule-*.jsonl`) unchanged into `client-plugin-engine/src/main/resources/hops/`.
   `HopKnowledge` and `ScheduleStore` read it in Phase 6.

### Phase 1 — Engine and runtime prerequisites (ported from Hoor2's native layer; start first)

The Java stack calls runtime APIs the engine lacks. Each one below is a self-contained engine
change.

**No reverse engineering is needed: Hoor2 is the source of truth here too, and it targets the same
client build, 951-4.**
- Hoor2's readers are its native layer, `launcher/src/payload/gs_*.c`, exposed to Java through
  `jni_bridge.c`. They are ported to Kotlin over the engine's memory layer.
- Every offset they use is in Hoor2's `launcher/src/payload/nxt_offsets.h` (`GS_*`). Each one goes
  into `resources/offsets/linux-x86_64-951-4.json` the way existing entries already cite that
  file: "the third-party 951-4 table agrees (GS_…)".
- Struct offsets are mirrored to the Windows tables. Function addresses stay Linux-only.

| # | Capability | Java API | Needed by |
|---|---|---|---|
| R1 | **Server tile** of the local player (and other pathing entities) | `PathingEntity.getServerTile` | processor, every reached check, RouteWalker, ServerWalk |
| R2 | **Live collision masks**: snapshot, window base, status (`OK`/loading), "every square loaded", worker teleport timestamp (`lastJump`), `prewarm` | `CollisionMap.snapshot/lastJump/prewarm`, `Collision.canMove/contains` | LocalNav trust gate, every live-map decision |
| R3 | **Static squares on arrival**: per-square static collision from the cache, switched to live once loaded (`allowStatic`, `staticReady`, `usableForHops`) | `CollisionMap.STATIC` | LocalNav, hops right after a teleport. Our `WorldCollision` already builds static collision, so this is mostly wiring |
| R4 | **Collision blockers**: which locs block an edge | `HookBridge.collisionBlockers` | DoorNode `edgeBlockers`, HopBlockers, type keys. Can be derived from cache locs if no native read is wanted |
| R5 | **Player facing angle** | `Player.getFacingAngle` | MovePrediction `facingBearing`, SurgeNode |
| R6 | **Attack memory**: hitsplats on the local player plus NPC targeting/animation within 12 tiles, remembered 3 s, forgotten after 50 tiles or the attacker's death | `Hits.onLocalPlayerPresent`, `RequirementsBuilder.attackedRecently` | RequirementsBuilder gates, MenuGuard, IFSlotNode, LodestoneNode, portable ring |
| R7 | **Live item-slot and component ops**: op list and op index by name on a slot or component, including CS2-relabelled and perk ops | `IFSlot.click(String)`, `optionIndexOf`, `InterfaceComponent.getOp(i)/getOpCount` | SkillcapePerks, PouchItems, ItemNode |
| R8 | **Item var-domain varbit by inventory** | `Item.getVarDomainVarBit` | POAItemNode (Passage of the Abyss slot pick), lyre charges |
| R9 | **Paced follow-up clicks**: one-tick Surge→Dive and Dive→Surge, clicks 15–35 ms apart, sent at tick start | `ActionPacer.followUp`, `Combat.surgeThenDive/diveThenSurge`, `Combat.dive(tile, ability, min, max)` | RouteWalker combos |
| R10 | **Tick-start timing** precise enough for the combo window (check `ServerTick.millisSinceTick`) | `ServerTick.info/STATE` | RouteWalker `waitForTickStart` |
| R11 | **`RecentChat.findSince`**: return the matching message, not just a boolean | `ChatEvents.findSince` | Surge/Dive blocked-cast detection, PouchItems |
| R12 | **Lodestone teleport with a stop signal and a result** (including INTERRUPTED) | `Teleports.lodestoneTeleport(l, stop)` | LodestoneNode |
| R13 | **Spell cast with a book action** (alternate destinations) | `SpellTeleports.cast(spell, action, stop)` | SpellNode, SpellMapper |
| R14 | Small gaps: `Perk.hasPerk`, quest progress (not only completion), `InterfaceHandlers.isOnScreen`, `Npcs.byServerIndex`, `Summoning.isInCombat` | — | various |
| R15 | Per-server-tick hook for scripts (Java's `stateTickLoop` / tick worker) so `Traversal.pollConditionsOnTick` runs every tick | `State.stateTickLoop` | processor polling |

Acceptance: each capability has a unit test or a scripted live probe, plus an entry in the offset
table where one is needed.

### Phase 2 — Core framework parity

- **`Traversal`:**
  - Constructors and factories with an interrupt condition and a timeout.
  - Labelled `interruptWhen(name, cond[, onInterrupt])` and `onInterrupt(state)`.
  - `pollOnTick`, `pollConditionsOnTick`, `pollConditionsNow` (wired through R15).
  - A suspend `run(script)` driver.
  - Repath refusal sets the supported flag to false when there is no supplier, not only when the
    limit is hit.
  - The default timeout per D8.
- **`TraversalProcessor`:**
  - A tick-deduped condition poll (finished, then interrupt).
  - Tiles read from the server tile (R1), falling back to the client tile.
  - `advanceByProgress` stops at `holdsStop()`.
  - The **Dramen staff ladder**: wield before a node that needs it (fail after 3 tries); with no
    staff, repath or fail; restore once no later node needs it; restore on a non-repath stop.
  - `prewarmLanding` before a node that moves the player far (R2).
  - `traversalStopped(outcome)` delivered once to every node.
- **`TraversalNode` / `TraversalNodeList`:** `requireDramenStaff`, `needsDramenStaff`,
  `copyRouteFlagsFrom`, the `traversalStopped` hook; `copy()` carries the route flags.
- **`WalkEquivalent.holdsStop`; `EarlyStart.readyFrom(serverTile)`** (keep the `(x, y, plane)`
  form as an overload); **`WalkOutlook`** (new).
- **`TraversalBuilder`:** every Java method that is missing (full list in §6.1). Methods chain
  (return the builder), while the `Boolean`-returning DSL calls stay source-compatible.
- **Java-script access:** a `JavaState` adapter and a `Wait` façade, so `JavaStateMachineScript`
  scripts can run a traversal.
- **Pick one `Area` type** for the builder (`script/api/Area` with `Rectangular`, matching Java)
  and keep a `util/Area` overload.

Tests: extend `TraversalProcessorTest` with the Dramen staff ladder, the tick-dedup poll, tour
stops and `traversalStopped`.

### Phase 3 — Local navigation (needs R1–R3)

- **`local/LocalNav`:**
  - The trust gate: inside the 192² window, status OK, not arriving (window re-centred and
    loaded, stable for 1.8 s, at most 5 s), per-thread cached fields, `untrustedReason`,
    `trustedLive`.
  - Static squares on arrival.
  - Landing time taken from the worker jump.
  - Replaces `LiveWalkMap` and `ArrivalWatch`.
- **`local/GridSearch`:** already equivalent. Move it in and keep its tests.
- **`local/ServerWalk`:** add `tickOn` and the `click()` accessor. Drop the prediction on other
  synthetic fires (`SyntheticFires.count`) and on hops; re-predict a click made mid-hop from its
  landing.

Tests: the `LiveMapTraversalTest` cases covering grid search, LocalNav door and passage answers,
and line of sight.

### Phase 4 — Pathing: route → node list (D1, D5, D6)

- **`pathing/Step` + `ResultsParser`** (absorbing `NavStep`/`NavResultsParser`):
  - All Java step fields: spell `action`, `originArea` from a `from` area object, door
    `locationOpen`/`locationClosed`, NPC `firstInterfaceId`, item clicks by `option_num` only,
    the tile-exact `minimap` flag.
  - Every chain child type.
  - `withHopWalks`: route geometry kept for planned hops (`cfba642`).
  - Unsupported steps are logged and dropped (D6). Remove `NavStep.Unsupported`'s
    route-refusal path.
- **`pathing/NavPathClient` + `NavConfig`** (absorbing `NavPlanner`). The vendored service already
  serves every endpoint and field the Java uses, so this is engine-side only and navpath stays as
  it is:
  - The `/tile/exists` and `/reachable` calls.
  - A `return_geometry` toggle.
  - On a 404 from `/route_area`, retry `/route` to the goal tile.
  - A stable seed (drop the per-session salt).
  - `NavConfig` resolves the address in this order: the `projectx.navpath.url` property, then
    `endpoint.json`. That replaces Java's fixed `127.0.0.1:8889` default.
  - Keep ours (§8): the 503 warm-up retry, `exclude_macros`, `dive.available_in_ms`, and the
    "start the launcher" failure.
- **`pathing/NavOptions`:** every Java field and default (§6.3). It replaces the scattered
  `WebWalker` vars, which become views onto a default `NavOptions`.
- **`pathing/Reachability`:** `playerCanReach` and `closestByRoute` (nearest-first fan-out).
- **`pathing/RequirementsBuilder`** (absorbing `NavProfile`, `WebLinkPermissions` and the
  requirement half of `AbyssPassage`):
  - The Java gates: base and spell keys always; item keys with `useItems`; lodestones, ifSlot,
    menu and POA keys only when not `attackedRecently()` (R6), with the
    `assumeNotAttacked`/`assumeAttacked` overrides. A walk-only profile drops `hasQuickTele` and
    staff access.
  - The **missing keys** listed in §6.4.
  - The Java evaluation of `questDODCoinRealm` (progress, not a 0/1 flag), `hasLegatusPendant` as
    a base key, the lyre's charged variant, the cape keys from SkillcapePerks, the Wicked hood
    per-altar pools (D7, replacing `e685283`'s keys in `WebLinkPermissions`), and the
    combat-gating differences.
  - Everything read by gameval name.
- **`pathing/PathingTraversal`:**
  - Request, parse, map into a `TraversalNodeList`. Every node carries the `NavMacro` of the step
    it came from.
  - `repathWith` wired to a re-route to the same goal (at most 3). The re-route sends the edges
    behind failed nodes as `exclude_macros`, our addition to Java's repath (§8): a link given up
    on, a locked door, a ring that landed elsewhere, a ring the account can't use.
  - `abilityCooldownMs`: observed or base cooldown, MOBILE halving, burst and double surge,
    Bladed Dive.
  - `prepareItemRequirements` (`MasterFarmerHat.refreshIfUnknown`, open the currency pouch for
    memory strands).
  - Tours and legs (`buildTour`, `TourStops`).
  - `routingOptions`, `walkOnlyRoutingOptions`, `routingOptionsUnderAttack`.
  - No tail walk when the route already ends on the goal.
- **`pathing/mappers/*`:** one per step type, as in Java, including `MoveMapper.abilityStepsAsMoves`
  (walk the `walked` geometry) and `skipPassableObjects`.
- **`WebWalker` becomes a façade (D1):**
  - `walk` builds a `PathingTraversal` and runs it.
  - Our extra re-plan triggers move into `PathingTraversal`'s repath policy or into nodes, and
    nothing is lost: misrouted fairy rings put aside for 30 min, links set aside, locked doors,
    each sent as an exclusion.
  - `useHostedPlanner` stays as a no-op for compatibility.

Tests: port `SpellStepTest`, `SpiritTreeRerooterTest`, `TourStitchTest`, `PortableFairyRingTest`,
`DramenStaffStepTest`, `ItemChainHopsTest` and `HopWalkGeometryTest`. Keep `NavpathTest`,
`NavpathMergedMapTest` (its merged-map fixtures are good mapper inputs) and the webwalker tests
green.

### Phase 5 — Interaction nodes

Port each Java node in full; the parity items are in §6.2. In short:

- **ObjectNode:**
  - O1–O12: anchoring and the twin pick, `skipWhenPassable`, repath when unreachable or on the
    first "can't reach", re-pick after a move, the travel-object settle, letting an interaction
    under way finish on a stop.
  - No click within the landing hold, and an animation already playing at click time does not
    count as the start.
  - The 1.5 s start window when adjacent.
- **DoorNode:** closed variants, `shutButUnlisted`, retry then repath, a door search around the
  door's own tile, the def checks. Keep our "Quick open" first (§8).
- **NpcNode / ItemOnObjectNode:** EarlyStart, repath when unreachable, the `closestReachable`
  fallback, MenuGuard.
- **ItemNode + PouchItems + SkillcapePerks:**
  - The memory strand from the currency pouch (with the pouch auto-opened and the backoff).
  - Perk ops on the worn cape.
  - Prefer an item that has the op.
  - Spiritbag restock and `restockLanded` as reached.
- **IFSlotNode + MenuGuard:** the under-attack reroute (R6), deduped once per opener per 30 s.
- **TileExactNode:**
  - The server-tile arrival default.
  - Stuck, then repath.
  - Both race fixes: `935ac8a`, wait for a far landing; and `56644bc`, no stuck repath during a
    teleport landing.
  - `alreadyWalkingHere` against the last walk click.
- **LodestoneNode:** a blocking cast with a result (R12), fail on an interrupted cast while
  attacked, reached radius 3.
- **SpellNode:** the book action (R13).
- **FairyRingNode:**
  - The Travel Log one-click select.
  - The portable ring's charge-message check.
  - The under-attack reroute.
  - `closeDial` on a stop or reroute.
  - The max-opens fail, and repath when no ring is left.
- **POAItemNode:** a positional first-menu pick for the Passage of the Abyss (R8), the
  under-attack reroute.
- **SpiritTreeRerooterNode:** a node of its own, with the max-opens fail and MenuGuard.
- **DramenStaff:** stop-aware, driven by the processor ladder (Phase 2), not by `WebWalker`.
- **JujuSpiritbag:**
  - Wait up to 15 s for combat to clear and up to 5 s for Papa Mambo to load.
  - Two Trade attempts, and the "Buy 1" op.
  - Find Mambo by definition id.
  - Skip the landing wait while the master farmer hat is held.
- **MasterFarmerHat:** `refreshIfUnknown` (Teleport, read the chooser, Cancel) and a 15-tick
  landing window.
- **Interactions:** the learned dead time per interaction. It is used by HopSchedule, so it can
  land with Phase 7.
- **TraversalSupport:** server-tile reads, `msIntoTick`, `noteWalkClick`/`recentlyClicked`,
  `awaitTeleport`. It replaces `NodeWaits`.

Tests: `FairyRingTravelLogTest`, `MasterFarmerHatTest`, the `LiveMapTraversalTest` node cases, and
the existing `JujuSpiritbagTest` and `WebLinkActionsTest` (rewritten against the nodes).

### Phase 6 — Hop learning (can run alongside Phases 2–5)

- **`HopModel`:** `VERSION` 8, `DISCARD_BELOW` 8 and `SCHEDULE_DISCARD_BELOW` 8.
- **`HopEdges`:** `pt:<loc>:<shape>` type keys.
- **`Evidence`:** manual evidence (it outranks learned evidence), places (`pl`), the type-usable
  rule. Write `c` (not `cs`).
- **`HopBlockers`** (R4).
- **`HopStore`:**
  - Check the model header.
  - Keep observations 90 days, up to 200 MB.
  - `rebuildIfModelChanged`, and `discardIfOld` into `hops-reset-v<model>-<stamp>/`.
  - Never overwrite a file it cannot parse.
- **`HopKnowledge`:** three layers: shipped contrib data (Phase 0) re-read every 30 s, the local
  store, pending. Drop lines with `m` below 8. Stamp the build from `OffsetTable.build`.
- **`HopObservation` / `HopExplainer` / `HopRecorder`:** blocker attribution, the manual flag,
  counters, the newer observation schema.
- **`HopExplorer`** (H5 probes):
  - Safety gates: instance, Wilderness, in combat, health below 60 %.
  - A 3 s gap between probes, a 10 min gap per edge.
  - Try, facing, misface, stand and dive-aim keys.
  - One dive probe per tile and aim, one surge probe per tile and way.
- **`ScheduleStore`:**
  - Weighted sums per key, layered: shared file, local store under a lock, pending.
  - Weight caps 20/100/200, a 30 s reload and flush.
  - Probe-prefix keys.
- **`HopLearning`:** keep our `-Dprojectx.hopLearning`/`hopContributor` switches and
  `~/.projectx/hops`, and accept the Java env names `RS3_HOP_LEARNING`/`RS3_HOP_CONTRIBUTOR` as
  aliases.
- **Store migration:** existing `~/.projectx/hops/knowledge.jsonl` (model 6) is moved aside by
  `discardIfOld` on first start. That is the Java behaviour; nothing is merged.

Tests: port `HopLearningTest`, `ScheduleStoreTest` and `HopModelCluetrainerCheck` (the last one is
optional, needs `CLUETRAINER_DIR`).

### Phase 7 — Route walker and hops (needs Phases 1–6)

Read `END_AWARE_HOPS.md` part A first. Then port the pieces, then the walker:

- **`MovePrediction`:** `facingBearing` (R5), `helps`, `diveClear`.
- **`StuckDetector`:** already equivalent. Move it in.
- **`LocalTargets`:** anchored twin disambiguation and the choice by destination.
- **`TourStops`, `ClientAbilities`:** per-node flags for surge, dive, potion, explore and
  schedule.
- **`AccelerationPlanner`**, the v2 model:
  - A clock-driven replay of the 6 s burst on the predicted server walk; Surge before Dive;
    chained surges; recast after 1.3 s; no dive in the last tick.
  - The fractional "anyway" cost; double surge charges.
  - `MIN_SAVING` and `BETTER_AHEAD` as in Java.
- **`ScheduleLearning`, `HopSchedule`:** the dynamic program, cast now vs keep, spot scans,
  `runTicks` smoothing, pair ordering, joint plans with post-interaction hops.
- **`RouteWalker`**, everything `LiveRouteWalker` lacks:
  - The schedule (keep, `withinTick`, `pairHold`, `surgePastKeptDive`).
  - **One-tick combos** (R9, R10): `notePair`/`settlePair` and combo learning.
  - Probes, `lineUpSurge`, `keepMovingWhileArming`, the `positionJumped` filter.
  - A **learned per-source landing hold** (`arrivalKey`/`lockMs`), plus re-clicking a walk the
    landing dropped.
  - Walk clicks planned from the hop's landing (`e65437a`).
  - The longer far-hold under attack.
  - TourStops barriers (`untilStop`), `SURGE_END_SHORT_MAX`, `DIVE_MIN_GAIN` 8, flight state
    shared across walkers, `msUntilHopsRecharged`, `WalkOutlook`.
  - The arrival-gate and idle-hop logs.
- **`ChebychevNode` / `PathNode`:** on `RouteWalker`, with `ClientAbilities` and TourStops; hops
  enabled in traversal walks (today `NodeWalker` turns them off).
- **`SurgeNode` / `DiveNode`:** the planner-placed mode (`clientPlacedAbilities = false`).
  - Predict and pre-check, face first.
  - Cross-tick blocked-cast detection (R11), a 10 s cooldown, a walk fallback.
  - Repath after the fallback walk finishes.

Tests: the rest of `LiveMapTraversalTest` (click choice, surge landing, hop placement) and
`ActionPacerTest`. Then run the §7 live checklist.

### Phase 8 — Cutover and cleanup

1. Point `Script.webWalk*`, `Wait.webWalk*`, `JavaScript.kt` and `ClientMainLogic`'s
   `MasterFarmerHat.tick` at the new stack. Check that `JavaApiParityScript` still compiles.
2. Delete what was replaced:
   - `traversal/walk/*` (`LiveRouteWalker`, `HopPlanner`, `NodeWalker`, `ClickPicker`, the old
     `AccelerationPlanner`)
   - `traversal/nodes/NodeWaits.kt` and `WebWalkNode` (unless it is kept as a builder shortcut)
   - `pathfinder/navpath/NavRouteMapper.kt` and `NavPlanner.kt` (absorbed by `pathing/`)
   - the execution half of `webwalker/WebWalker.kt`
   - `webwalker/WebLinkActions.kt` and `WebLinkPermissions.kt` (once each behaviour has a home)
   - `pathfinder/WalkMap.kt` (`LiveWalkMap`, replaced by `local/LocalNav`)
3. Rewrite `traversal/README.md`. Fold in the parts of Hoor2's README that still hold: the live
   map, repath, hops and the divergences in §8. Leave out ids and offsets.
4. If the Java-side commit notes revealed a lasting pitfall, record it in the shared memory store
   (rarely; follow `memory-policy.md`).

## 5. Running order and effort

```
P0 ─┬─ P1 (RE, engine) ─────────────┬─ P3 ─┬─ P4 ─ P5 ─┬─ P7 ─ P8
    ├─ P2 (core) ───────────────────┘      │           │
    └─ P6 (hop learning) ──────────────────┴───────────┘
```

Rough size: ~15k lines of Kotlin across ~90 files, plus ~3.6k lines of tests. P1 is the risk,
because R1, R2, R5, R9 and R10 port native readers from Hoor2's C layer and need live validation. P7 is the largest: `RouteWalker`
and `HopSchedule` come to ~4.5k Java lines.

## 6. Parity tracker

Status: `☐` not started · `◐` in progress · `☑` done. Update as you go.

### 6.1 Core

| Java | Kotlin target | Today | Gap | ☐ |
|---|---|---|---|---|
| Traversal | traversal/Traversal.kt | partial | interrupt ctor/factories, labelled interrupts, `onInterrupt`, polling API, `run`, repath refusal, default timeout | ☑ |
| TraversalProcessor | TraversalProcessor.kt | partial | tick-deduped poll, server tile, `holdsStop`, Dramen ladder, `prewarmLanding`, `traversalStopped` | ☑ |
| TraversalBuilder | TraversalBuilder.kt | partial | missing methods below | ☑ |
| TraversalNode / NodeList | same | partial | Dramen flags, `copyRouteFlagsFrom`, `traversalStopped`, copy flags | ☑ |
| TraversalOutcome | same | equivalent | — | ☑ |
| WalkEquivalent | same | partial | `holdsStop` | ☑ |
| EarlyStart | same | partial | `readyFrom(serverTile)`; implementers Object/Npc/ItemOnObject | ☑ |
| WalkOutlook | WalkOutlook.kt | absent | whole file | ☑ |
| JujuSpiritbag | JujuSpiritbag.kt | partial | combat/Mambo waits, 2 trades, Buy 1, defId, hat-held skip | ☑ |
| MasterFarmerHat | MasterFarmerHat.kt | partial | `refreshIfUnknown`, 15-tick window | ☑ |

Builder methods to add:
- `interactObj`
  - `requestTile` overloads (action-only, by id, by name, each with and without `reached`)
  - the `destination + requestTile` variants
  - the `ObjectIdentifier` + `origin` + `skipWhenPassable` variant
- `pathTiles(start, end, tiles[, abilities])`
- `chebychevPath(start, end, routeTiles[, abilities])`
- `surge(…)` ×3 and `dive(…)` ×2
- `fairyRing(objectId, code, action[, r][, reached])`
- `spiritTreeRerooter(dest, reached)`
- `castSpell(spell, action, dest, reached)`
- `useItemOnObj(…, destination, reached)` ×4
- `poaItem(…)` ×3
- `build(next, finished, interrupt[, timeout])` and the legacy no-`next` `build` overloads
- `buildNodes`, `nodeCount`, `requireDramenStaffFrom`

### 6.2 Nodes

| Java | Today (engine) | Gap | ☐ |
|---|---|---|---|
| ObjectNode (+ObjectIdentifier) | partial (O5, O6) | O1–O4, O7–O12 | ☑ |
| DoorNode (+DoorInfo, DoorDirection) | partial | closed variants, `shutButUnlisted`, retry then repath, door-centred search, def checks | ☑ |
| NpcNode (+NpcIdentifier) | partial | EarlyStart, repath when unreachable, MenuGuard | ☑ |
| ItemNode (+ItemIdentifier, ItemSelector) | partial | pouch, perks, op preference, restock, MenuGuard | ☑ |
| PouchItems | absent | whole file (R7, R11) | ☑ |
| SkillcapePerks | absent | whole file (R7) | ☑ |
| MenuGuard | absent | whole file (R6) | ☑ |
| ItemOnObjectNode | mostly | EarlyStart, `closestReachable` | ☑ |
| IFSlotNode | partial | under-attack reroute | ☑ |
| TileExactNode | partial | server-tile arrival, stuck repath, both race fixes, `alreadyWalkingHere`, WalkEquivalent | ☑ |
| LodestoneNode | partial | result and interrupt (R12), radius 3 | ☑ |
| SpellNode | mostly | book action (R13) | ☑ |
| FairyRingNode (+FairyRing) | partial | Travel Log, charge check, reroute, `closeDial`, max opens, repath when no ring | ☑ |
| POAItemNode | partial (in WebLinkActions) | own node, Abyss pick (R8), reroute | ☑ |
| SpiritTreeRerooterNode | mostly (in WebLinkActions) | own node, max opens, MenuGuard | ☑ |
| DramenStaff | partial (WebWalker) | processor-driven, stop-aware | ☑ |
| Interactions | absent | whole file | ☑ |
| TraversalSupport | partial (NodeWaits) | server tile, tick timing, walk-click memory, `awaitTeleport` | ☑ |
| LocalTargets | partial | twins (anchor and destination) | ☑ |
| TourStops | absent | whole file | ☑ |
| ClientAbilities | partial | explore/schedule flags, per-node wiring | ☑ |
| StuckDetector | equivalent | move only | ☑ |
| MovePrediction | partial (HopPlanner) | facing, `helps`, `diveClear` | ☑ |
| AccelerationPlanner | partial (old model) | v2 model | ☑ |
| HopSchedule | absent | whole file | ☑ |
| ScheduleLearning | absent | whole file | ☑ |
| RouteWalker | partial (LiveRouteWalker) | see Phase 7 | ☑ |
| ChebychevNode / PathNode | partial | RouteWalker, hops, TourStops | ☑ |
| SurgeNode / DiveNode | absent | whole files | ☑ |

### 6.3 Pathing

| Java | Today | Gap | ☐ |
|---|---|---|---|
| PathingTraversal | WebWalker + NavPlanner + NavRouteMapper | route → node list, repath with exclusions, cooldowns, prepare items, tours, option sets | ☑ |
| ResultsParser / Step | NavResultsParser / NavStep (now carries `NavMacro`) | fields, all chain children, hop geometry, drop unsupported steps (D6) | ☑ |
| NavPathClient / NavConfig | partial (local endpoint, exclusions done) | `/tile/exists`, `/reachable`, geometry, 404 fallback, stable seed | ☑ |
| NavOptions | scattered | single options object, Java defaults | ☑ |
| Reachability | absent | whole file | ☑ |
| RequirementsBuilder | NavProfile + WebLinkPermissions | gates and keys (6.4) | ☑ |
| MappingContext | n/a | port with the mappers | ☑ |
| mappers/* (15) | NavRouteMapper `when` | one per type; Move geometry, Object skip, Item `option_num`, Spell action, TileExact minimap, Npc `firstInterfaceId`, origin areas | ☑ |

`NavOptions` fields and Java defaults:

| Field | Default |
|---|---|
| `serviceBaseUrl` | ours (§8) |
| `timeoutMs` | 120 000 |
| `skipExecution` | false |
| `goalArea` | — |
| `jsonActionsOnly` | true |
| `preferChebyshev` | true |
| `useItems` | true |
| `skipPassableObjects` | false |
| `clientPlacedAbilities` | true |
| `useAccelerationPotion` | true |
| `exploreHops` | true |
| `scheduleHops` | true |
| `hops` | true |
| `teleports` | true |
| `surge`/`dive` | from the action bar and cooldowns |
| `seed` | name hash, stable |
| `copy()` | field-for-field |

### 6.4 Requirement keys missing from ours

`varbit_15629` is the navpath key; read it by its gameval name, atvar elite done. The others:
- `arcSailing`, `arcCyclosisUnlocked`
- `hasScepterOfTheGodsEquiped`, `hasMagicalMetorChunk`, `hasMemoryStrands`, `hasHeartTeleport`,
  `hasHazelmereSignet`
- `hasTokkulZo`, `hasTokkulZoEquiped`
- `arcJournalWaiko`, `arcJournalWhalesMaw`, `arcJournalAminishi`, `arcJournalCyclosis`,
  `arcJournalTuaiLeit`, `arcJournalTurtles`, `arcJournalGoshima`

Evaluation differences to align:
- `questDODCoinRealm`: read as progress.
- `hasLegatusPendant`: a base key.
- `hasEnchantedLyre`: also accept the charged variant whose item var is above 0.
- `hasSlayerCape`/`hasDungCape`/`hasQuestCape`: also met by a stored perk plus a worn cape.
  Not behind `useItems`.
- Wicked hood: Java's per-altar pool vars, or the omni pool, with the hood held or worn (D7).
- Combat gating:
  - `hasWS4inInv` and the hood keys are gated under attack.
  - `hasMasterFarmerHabitat` is not gated.
  - The gate is attack memory (R6), not the combat varbit.

### 6.5 Hop learning

| Java | Today (`pathfinder/hops`) | Gap | ☐ |
|---|---|---|---|
| HopModel | v6 | v8, discard thresholds | ☑ |
| HopEdges | partial | type keys | ☑ |
| Evidence | partial (`HopEvidence`) | manual, places, type rule, `c` field | ☑ |
| HopBlockers | absent | R4 | ☑ |
| HopStore | partial | header check, 90 d / 200 MB, rebuild and discard | ☑ |
| HopKnowledge | partial | layers, 30 s reload, model filter, shipped data | ☑ |
| HopObservation / Explainer / Recorder | partial | blockers, manual, counters, schema | ☑ |
| HopExplorer | absent | whole file | ☑ |
| ScheduleStore | absent | whole file | ☑ |
| HopLearning | equivalent (other switches) | env aliases | ☑ |

### 6.6 Local

| Java | Today | Gap | ☐ |
|---|---|---|---|
| LocalNav | `LiveWalkMap` / `ArrivalWatch` | trust gate on live status, static arrival, worker jump, reasons | ☑ |
| GridSearch | `pathfinder/GridSearch` | move only | ☑ |
| ServerWalk | `pathfinder/ServerWalk` | `tickOn`, `click()` | ☑ |

### 6.7 Tests to port (Hoor2 `launcher/tests/java/com/launcher/api/traversal/`)

| Java suite | Phase | ☐ |
|---|---|---|
| LiveMapTraversalTest (52 cases) | 3, 5, 7 | ☑ |
| HopLearningTest | 6 | ☑ |
| ScheduleStoreTest | 6 | ☑ |
| HopModelCluetrainerCheck (optional) | 6 | ☐ |
| pathing/SpellStepTest | 4 | ☑ |
| pathing/SpiritTreeRerooterTest | 4 | ☑ |
| pathing/TourStitchTest | 4 | ☑ |
| pathing/PortableFairyRingTest | 4 | ☑ |
| pathing/DramenStaffStepTest | 4 | ☑ |
| pathing/ItemChainHopsTest | 4 | ☑ |
| pathing/HopWalkGeometryTest | 4 | ☑ |
| nodes/FairyRingTravelLogTest | 5 | ☑ |
| MasterFarmerHatTest | 5 | ☑ |
| ActionPacerTest | 1 (R9) | ☑ |

## 7. Live validation checklist (after Phase 7)

- [ ] Long web walk with lodestones on the launcher's local navpath. With navpath stopped, the walk
      fails with the "start the launcher" reason and does not hang.
- [ ] A failed link, door or ring is re-routed around through `exclude_macros`, at most 3 times.
- [ ] Door with two closed ids (gate variant); locked door fails the traversal; "can't reach"
      triggers a repath.
- [ ] Fairy ring (Travel Log select), portable ring, ring the account hasn't unlocked (set aside
      for 30 min).
- [ ] Memory strand teleport with the currency pouch collapsed.
- [ ] Skillcape perk teleport from a worn max cape.
- [ ] Juju spiritbag last-bag restock; master farmer hat count refresh.
- [ ] Spell with a book action (e.g. an alternate destination); lodestone interrupted by a hit.
- [ ] Dramen staff wielded for a ring and restored straight after.
- [ ] Surge/Dive: one-tick combo lands as planned; blocked dive falls back and repaths; schedule
      keeps a hop for a better spot; potion drink decision logged with its projection.
- [ ] Teleport arrival: hops start on static squares before the live window has loaded.
- [ ] Hop learning: observations written, model-8 store, probes respect the safety gates.
- [ ] A route containing a `teleport`/`global_teleport` step: the step is dropped and the
      walk carries on (D6).

## 8. Ours-only behaviour to keep (not drift)

These exist only on our side. They are kept because they do not contradict the Java and were
measured live.

- Doors try **"Quick open"** first, then Open, Go-through or Pass-through.
- The measured links in `links.json`, merged into navpath's map at release, plus the per-link
  action data the map lacks (prompt answers, ring permissions). Misrouted fairy rings are put
  aside for 30 min.
- The engine's navpath client behaviour:
  - finding the launcher-run instance through `endpoint.json`;
  - the 503 warm-up retry;
  - `dive.available_in_ms`;
  - re-routes that exclude failed edges (`exclude_macros`) instead of re-planning blind.
- Keys `hasPontifexRing` and `hasGhostspeakWorn`, plus the extra `links.json` var keys.
- Requirement and item reads by gameval name. Varbits come from the cache layout (Passage of the
  Abyss), not from raw ids.
- Hop store at `~/.projectx/hops`, with the `-Dprojectx.*` switches.

## 9. Java bugs not to copy

- `"hasHazelmereSignet "` is sent with a trailing space. navpath trims keys, so ours sends
  `hasHazelmereSignet` (P4a).
- Java puts a copy of a lodestone or interface-click step inside its own chain (taken from
  `metadata.steps[0]`), which casts or clicks twice. Ours leaves the parent out of its own chain
  (P4a).
- Anything a live check contradicts. Add it here with the evidence.
