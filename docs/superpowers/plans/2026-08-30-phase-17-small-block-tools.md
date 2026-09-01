# Phase 17 Small-Block Tools and Building Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:subagent-driven-development` (recommended) or `superpowers:executing-plans` to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. A controller must authorize each Gate before implementation; completing one Gate does not authorize the next.

**Goal:** Add one canonical coarse/precision interaction path that turns Phase 16 DETAIL geometry into Creative and Survival building actions while conserving ordinary integer `ItemStack` quantities.

**Architecture:** `BlockInteractionController` remains the sole fixed-step interaction owner and consumes the one existing `BlockHitResult`. Pure routing/targeting/preview helpers select a closed FULL/coarse/precision route, while two narrow `DetailMutationService` operations delegate one logical action to one `ChunkRepository` CAS; inventory and WorldItem reservations wrap, but never replace, that authority.

**Tech Stack:** Java 17 source/target, JDK 21-compatible Gradle Wrapper, JUnit Jupiter 6.1.1, JOML, existing LWJGL/OpenGL 4.1 and GLSL 410 renderer, Gson-backed resource/save codecs.

**Spec:** `docs/superpowers/specs/2026-08-30-phase-17-small-block-tools-design.md`

## Global Constraints

- Work only in `D:\Game Design\GaiaLegacy` on `feat/small-voxel-tools`, starting from `4e45a6681f169e070500e4580c62fb37dc53d4ed`; do not create another worktree.
- No staging, commit, push, PR, merge, tag, release, stash, reset, restore, or clean is authorized by this plan.
- Preserve the quarantined files and `dist/GaiaLegacy-v0.2.0-alpha.1-windows-x64.zip` byte-for-byte.
- `ChunkRepository`, `BlockRaycast`, `CollisionWorld`, `ChunkMeshManager`, and `BlockInteractionController` remain their sole respective authorities.
- Preserve FULL/DETAIL exclusivity, nonempty sparse DETAIL storage, typed `ParentCellState`, immutable `ChunkSnapshot`, exactly nine snapshots in `ChunkMeshInput`, separate `ChunkMeshingClaim`, `detail-blocks` persistence, and typed UNKNOWN/FAILED behavior.
- Preserve typed adjacent-parent observation before any byte-only FULL material read.
- Preserve the hybrid output cap `8,388,608`, all-Chunk CPU mesh budget `134,217,728`, and accepted/active/upload/destroy/aggregate limits `32/2/2/4/2`.
- Every behavioral Task follows RED -> verify the intended RED -> minimal GREEN -> adjacent regression -> refactor only while GREEN.
- One pressed-edge route decision may cause at most one logical mutation. No gameplay edit queue and no automatic stale retry are permitted.
- `ItemStack` remains exactly canonical item ID plus integer count. No metadata, fractional value, second item registry, generated/player provenance, or new save root is permitted.
- Phase 17 does not implement normal Survival chisel acquisition, FULL/64 conversion, crafting/fabrication UI, tool/harvest tiers, progression, GLB/Blender/model loading, or natural DETAIL worldgen.
- All OpenGL remains on the context-owning owner thread; preview and workers receive immutable data only.
- Use the Gradle Wrapper and focused tests per Task/Gate. Do not run the repository-wide suite until final Phase 17 closure authorization.

## Baseline and exact API audit

- `BlockInteractionController.fixedUpdate(...)` currently performs the one `PlayerBlockTargeting` query, owns mode/focus suppression, rejects `DetailRaycastTarget` as `detail_target_unsupported`, and dispatches existing `BlockBreakTransaction` / `BlockPlacementTransaction`.
- `BlockPlacementTransaction` already observes `BlockPlacementWorldView.parentStateAt(...)` before `blockAt(...)`; its typed ordering is a frozen regression.
- `BlockHitResult` already carries canonical parent/adjacent coordinates, face normal, canonical world point, runtime-to-game material identity, distance, owning Chunk revision, and sealed FULL/DETAIL target provenance.
- `DetailMutationService` currently exposes `convertFullToDetail`, `setSubVoxel`, and `compactDetailToFull`; `ChunkDetailMutation` and `ChunkRepository.mutateDetail(...)` are the existing sealed command/CAS boundary.
- `BodyInventoryService` already implements INSERT/EXTRACT reservations. `BodyInventoryReservationPlanner` supplies deterministic active-slot-first multi-slot insertion, and `BlockPlacementTransaction` demonstrates applied-state-aware extraction commit.
- `BlockBreakTransaction` already reserves/commits/rolls back one canonical WorldItem spawn and is the semantic template for coarse DETAIL output.
- `BlockRegistry` owns the sole `itemsById` index, but today fills it only from block definitions. `GaiaResourceLoader` indexes blocks/materials/atlases/UI and parses item forms only from a block's `item` object.
- `InventorySectionCodec` and `WorldItemsSectionCodec` persist generic `ResourceLocation` item IDs and counts; standalone Phase 17 items need compatibility tests, not a new save format.
- `GaiaVisualRegionResolver` and `GaiaWorldItemFaceResolver` currently require block backing. `FirstPersonItemVisual` already accepts immutable face regions, so standalone item visuals need only a narrow game-layer resolver seam.
- `InteractionFeedbackCoordinator` and `TransientBlockVisualPass` can carry one bounded render-only preview by adding an immutable quarter transform; collision/raycast/Chunk state remain uninvolved.
- Engine has `SoundEvent`, but the game has no existing bounded committed gameplay-SFX dispatch seam. Gate 17D therefore defers sound instead of creating an audio system.

## Planned interfaces and file structure

### Existing item authority, extended narrowly

- Create `game/src/main/java/com/gaia/blocks/ItemCapability.java` with only `DETAIL_PRECISION`.
- Create `game/src/main/java/com/gaia/blocks/ItemVisualType.java` with v1 `ATLAS_REGION` and an explicit future-compatible enum boundary.
- Create `game/src/main/java/com/gaia/blocks/ItemVisualReference.java` as immutable `(type, atlas, region)` presentation data.
- Create `game/src/main/java/com/gaia/blocks/StandaloneItemDefinition.java` as `(ItemFormDefinition form, Set<ItemCapability> capabilities, ItemVisualReference visual)`.
- Later create `game/src/main/java/com/gaia/blocks/DetailSupportDefinition.java` as a block-to-detail-unit mapping without changing `ItemStack`.
- Extend `BlockRegistry.create(...)` with standalone definitions while retaining one collision-checked `itemsById`; add canonical item capability/visual/detail-support lookups. `blockForItem(...)` remains optional and block-only.

### Pure routing, target, and preview

- Create `game/src/main/java/com/gaia/interaction/BlockInteractionRoute.java` with `FULL_NORMAL`, `DETAIL_COARSE_REMOVE`, `DETAIL_PRECISION_REMOVE`, `DETAIL_PRECISION_PLACE`, `REJECTED`, and `UNAVAILABLE`.
- Create `BlockInteractionIntent`, `BlockInteractionRouteRequest`, `BlockInteractionRouteDecision`, and `CanonicalBlockInteractionRouteResolver`; all are pure game-layer values/helpers.
- Create `DetailTargetWorldView` exposing only `ParentCellObservationResult observeCell(int x, int y, int z)`; `GaiaBlockWorldAccess` implements it through the existing typed observation seam.
- Create `DetailPrecisionTarget`, `DetailPlacementCandidate`, and `DetailTargeting`; these consume one `BlockHitResult`, perform bounded FULL face-quarter refinement or DETAIL face-offset wrapping, and never cast another ray.
- Create immutable `DetailPlacementPreview`, `DetailPreviewValidity`, and `DetailPreviewController`; the controller retains at most one current preview and has no mutation capability.
- Create `game/src/main/java/com/gaia/interaction/feedback/DetailPlacementGhostAdapter.java`; it contributes at most one `TransientBlockVisual` with a quarter-scale transform and does not add an excluded canonical block cell.

### Existing mutation authority, extended narrowly

- Create engine request records `RemoveDetailParentRequest` and `SculptParentSubVoxelRequest` under `com.overlord.interaction.api`.
- Add `removeDetailParent(...)` and `sculptParentSubVoxel(...)` to `DetailMutationService`.
- Add sealed `ChunkDetailMutation.RemoveDetailParent` and `ChunkDetailMutation.SculptParentSubVoxel` commands. `ChunkRepository.mutateDetail(...)` remains the only commit method.
- Create game transactions `CreativeDetailEditTransaction`, `SurvivalDetailEditTransaction`, and `DetailParentBreakTransaction`; they orchestrate existing mutation/inventory/WorldItem capabilities but own no canonical state or retry queue.
- Create pure `DetailActionPolicy`, `DetailActionDecision`, `DetailRecoveryKind`, `DetailParentComposition`, and `DetailCoarseHardness` values/helpers for the Phase 17/18 seam.

### Integration, UX, and documentation

- Extend `BlockInteractionSnapshot`/`BlockInteractionViewModel`, `HudPresentationSnapshot`, `HudPresenter`, and `GaiaHudScreen` only with bounded current-state DETAIL presentation.
- Extend `GameSessionFactory` only for constructor composition and owner-thread fixed-step/render wiring.
- Use existing `InventoryDebugSeeder`/debug command boundaries for explicit acceptance provisioning; never auto-provision normal New World Survival players.
- Create `docs/architecture/small-block-tools-building.md`, `docs/testing/phase-17-small-block-tools-acceptance.md`, and `docs/agent-handoffs/phase-17-handoff.md` during Gate 17D.

## Gate execution protocol

At the start and end of every Gate:

```powershell
git branch --show-current
git rev-parse HEAD
git status --short --untracked-files=all
Get-FileHash game/src/main/java/com/gaia/world/streaming/ChunkStreamingMetricsRecorder.java -Algorithm SHA256
Get-FileHash game/src/test/java/com/gaia/session/ChunkStreamingSessionIntegrationTest.java -Algorithm SHA256
Get-FileHash dist/GaiaLegacy-v0.2.0-alpha.1-windows-x64.zip -Algorithm SHA256
```

Expected: branch/base and quarantine hashes match the approved baseline. Stop on unexpected source/artifact drift. Each Gate ends with focused verification, architecture scan, an implementation-notes section in `docs/agent-handoffs/phase-17-handoff.md`, independent review, `git diff --check`, and a controller STOP.

---

## Gate 17A: canonical route, item identity, targeting, input, and preview

Gate 17A is read-only with respect to world and inventory state.

### Task 1: Standalone item domain and one item-form index

**Files:**

- Create: `game/src/main/java/com/gaia/blocks/ItemCapability.java`
- Create: `game/src/main/java/com/gaia/blocks/ItemVisualType.java`
- Create: `game/src/main/java/com/gaia/blocks/ItemVisualReference.java`
- Create: `game/src/main/java/com/gaia/blocks/StandaloneItemDefinition.java`
- Modify: `game/src/main/java/com/gaia/blocks/BlockRegistry.java`
- Test: `game/src/test/java/com/gaia/blocks/BlockRegistryTest.java`
- Create test: `game/src/test/java/com/gaia/blocks/StandaloneItemDefinitionTest.java`

**Interfaces:**

- Produces `BlockRegistry.create(Collection<BlockDefinition>, Collection<StandaloneItemDefinition>, Map<Integer, BlockRenderInfo>)`, `itemForm(id)`, `itemCapabilities(id)`, `itemVisual(id)`, and unchanged optional `blockForItem(id)`.
- The old two-argument `create(...)` delegates with an empty standalone list so existing tests/callers remain source-compatible.

- [ ] Write RED tests showing a standalone `gaia:chisel` cannot currently enter `itemForm`, duplicate block/standalone IDs are not rejected together, and no capability/visual lookup exists.

```java
StandaloneItemDefinition chisel = standalone("gaia:chisel", 1, DETAIL_PRECISION);
BlockRegistry registry = BlockRegistry.create(blocks(), List.of(chisel), renderInfos());
assertEquals(chisel.form(), registry.itemForm(chisel.form().id()).orElseThrow());
assertEquals(Set.of(DETAIL_PRECISION), registry.itemCapabilities(chisel.form().id()));
assertTrue(registry.blockForItem(chisel.form().id()).isEmpty());
```

- [ ] Run RED:

```powershell
.\gradlew.bat :game:test --tests "com.gaia.blocks.BlockRegistryTest" --tests "com.gaia.blocks.StandaloneItemDefinitionTest"
```

Expected: compilation fails because standalone types and the overload/lookups do not exist.

- [ ] Implement immutable value validation, one combined `itemsById`, auxiliary metadata inside the same `BlockRegistry`, and duplicate-ID rejection. Do not rename `BlockRegistry` or make engine depend on game.
- [ ] Run GREEN with `BlockRegistryTest`, `BodyInventoryServiceTest`, and `BlockPlacementTransactionTest`; verify strict block-only placement still rejects `gaia:chisel` through empty `blockForItem`.

### Task 2: Strict standalone resource loading and the three canonical items

**Files:**

- Modify: `game/src/main/java/com/gaia/assets/GaiaResourceLoader.java`
- Modify: `game/src/main/resources/assets/gaia/resource-index.json`
- Create: `game/src/main/resources/assets/gaia/items/chisel.json`
- Create: `game/src/main/resources/assets/gaia/items/stone_detail_unit.json`
- Create: `game/src/main/resources/assets/gaia/items/dirt_detail_unit.json`
- Modify: `game/src/main/resources/assets/gaia/atlases/blocks.json`
- Modify: `game/src/main/resources/assets/gaia/textures/atlas.png`
- Test: `game/src/test/java/com/gaia/assets/GaiaResourceLoaderTest.java`
- Test: `game/src/test/java/com/gaia/assets/GaiaProductionAssetsTest.java`

**Interfaces:**

- Resource indexes gain optional safe `items` paths.
- Standalone JSON strictly supplies `id`, `maxStackSize`, `mouthHoldable`, `twoHanded`, `capabilities`, and `visual {type, atlas, region}`.
- Canonical IDs are exactly `gaia:chisel`, `gaia:stone_detail_unit`, and `gaia:dirt_detail_unit`; chisel is stack 1, one-handed, not mouth-holdable, and owns `DETAIL_PRECISION`; units are stack 64 and contain no material metadata.

- [ ] Write RED loader tests for successful standalone loading and exact diagnostics for unsafe paths, unknown/duplicate fields, duplicate IDs, unsupported capability/visual type, missing atlas/region, cross-namespace ownership violations, and block/standalone item collisions.

```java
assertEquals(1, catalog.blockRegistry().itemForm(CHISEL).orElseThrow().maxStackSize());
assertTrue(catalog.blockRegistry().itemCapabilities(CHISEL)
        .contains(ItemCapability.DETAIL_PRECISION));
assertEquals(ItemVisualType.ATLAS_REGION,
        catalog.blockRegistry().itemVisual(CHISEL).orElseThrow().type());
```

- [ ] Run RED with `GaiaResourceLoaderTest`; expect unknown `items` index field and absent resources.
- [ ] Add the narrow parser/index path, three project-owned JSON definitions, one original 16x16 chisel atlas region, and deterministic atlas metadata. Record asset author/date/provenance in the later architecture doc; do not download art.
- [ ] Run GREEN plus `GaiaProductionAssetsTest` and `:game:verifyPackagedResources --rerun-tasks`; verify `gaia:stone_detail_unit` and `gaia:dirt_detail_unit` point to existing stone/dirt regions.

### Task 3: Standalone item presentation without block backing

**Files:**

- Modify: `game/src/main/java/com/gaia/interaction/feedback/GaiaVisualRegionResolver.java`
- Modify: `game/src/main/java/com/gaia/interaction/feedback/GaiaWorldItemFaceResolver.java`
- Test: `game/src/test/java/com/gaia/interaction/feedback/GaiaVisualRegionResolverTest.java`
- Create test: `game/src/test/java/com/gaia/interaction/feedback/GaiaWorldItemFaceResolverTest.java`
- Test: `game/src/test/java/com/gaia/interaction/BlockPlacementTransactionTest.java`

**Interfaces:**

- Resolvers first use explicit `BlockRegistry.itemVisual(id)` for standalone items, then retain existing block-face behavior for block-backed items, then the existing bounded missing visual.
- Canonical identity and capability are never inferred from texture region.

- [ ] Write RED tests resolving chisel, stone unit, and dirt unit without `blockForItem`, while proving normal asymmetric block face resolution is unchanged.
- [ ] Run RED; expect current fallback/block-only behavior.
- [ ] Implement only `ATLAS_REGION` resolution to immutable `TextureRegion`/uniform `WorldItemFaceRegions`. Keep a closed switch so a future `3D_MODEL` type cannot silently masquerade as atlas data.
- [ ] Run GREEN plus first-person/world-item visual regressions and `BlockPlacementTransactionTest` proving standalone items remain non-placeable FULL blocks.

### Task 4: Pure canonical interaction route resolver

**Files:**

- Create: `game/src/main/java/com/gaia/interaction/BlockInteractionRoute.java`
- Create: `game/src/main/java/com/gaia/interaction/BlockInteractionIntent.java`
- Create: `game/src/main/java/com/gaia/interaction/BlockInteractionRouteRequest.java`
- Create: `game/src/main/java/com/gaia/interaction/BlockInteractionRouteDecision.java`
- Create: `game/src/main/java/com/gaia/interaction/CanonicalBlockInteractionRouteResolver.java`
- Create test: `game/src/test/java/com/gaia/interaction/CanonicalBlockInteractionRouteResolverTest.java`

**Interfaces:**

- `resolve(BlockInteractionRouteRequest)` returns one closed decision and never receives `World`, `ChunkRepository`, mutations, inventory, or render services.
- Deterministic precedence is pickup-consumed -> unavailable/no-target -> primary -> secondary. Simultaneous primary/secondary yields one primary route.

- [ ] Write table-driven RED tests for ordinary FULL, ordinary DETAIL coarse primary, unsupported DETAIL secondary, chisel FULL/DETAIL primary/secondary, UNKNOWN/FAILED/no hit, simultaneous edges, and pickup-consumed secondary.

```java
assertEquals(DETAIL_COARSE_REMOVE,
        resolver.resolve(request(SURVIVAL, ordinaryItem(), detailHit(), primary())).route());
assertEquals(DETAIL_PRECISION_PLACE,
        resolver.resolve(request(SURVIVAL, chisel(), detailHit(), secondary())).route());
```

- [ ] Run RED; expect missing route types.
- [ ] Implement the pure resolver with typed `RaycastCellTarget` matching; do not read the backing block byte and do not add another controller.
- [ ] Run GREEN plus `BlockInteractionControllerTest`'s current safe DETAIL rejection and `WorldInteractionInputRouterTest` pickup precedence.

### Task 5: FULL face-local quarter refinement

**Files:**

- Create: `game/src/main/java/com/gaia/interaction/DetailPrecisionTarget.java`
- Create: `game/src/main/java/com/gaia/interaction/DetailTargeting.java`
- Create test: `game/src/test/java/com/gaia/interaction/DetailTargetingTest.java`
- Test: `game/src/test/java/com/gaia/interaction/DetailTargetingIntegrationTest.java`

**Interfaces:**

- `DetailTargeting.removalTarget(BlockHitResult)` reuses `DetailRaycastTarget.position()` or refines a `FullRaycastTarget` from canonical `worldPoint*` and face.
- Tangential quarter index is `min(3, floor(4 * parentFraction))`; exact internal planes choose the higher interval and exact outer edges clamp. The face normal fixes its axis to 0 or 3.

- [ ] Write RED cases for all six FULL faces, quarter planes, parent edges/corners, negative coordinates, large safe canonical coordinates, and rebase-invariant canonical world points.
- [ ] Run RED; expect missing helper.
- [ ] Implement a fixed bounded integer/`Math.floor` helper using `BlockFace`; do not raycast, allocate 64 objects, or use arbitrary epsilons.
- [ ] Run GREEN plus `DetailTargetingIntegrationTest`, `GaiaBlockRaycastServiceTest`, and the typed-placement safety test.

### Task 6: DETAIL placement candidates and typed destination wrapping

**Files:**

- Create: `game/src/main/java/com/gaia/interaction/DetailTargetWorldView.java`
- Create: `game/src/main/java/com/gaia/interaction/DetailPlacementCandidate.java`
- Modify (created in Task 5): `game/src/main/java/com/gaia/interaction/DetailTargeting.java`
- Modify: `game/src/main/java/com/gaia/interaction/GaiaBlockWorldAccess.java`
- Create test: `game/src/test/java/com/gaia/interaction/DetailPlacementCandidateTest.java`
- Test: `game/src/test/java/com/gaia/interaction/DetailTargetingIntegrationTest.java`

**Interfaces:**

- `placementCandidate(hit, material, view)` adds the hit-face unit to the local coordinate, wraps `[0,3]` across parent/Chunk boundaries, then consumes exactly one typed `ParentCellObservationResult`.
- Valid destinations are empty DETAIL slot or FULL AIR; occupied DETAIL/non-AIR FULL reject; UNKNOWN and FAILED remain distinct typed results.

- [ ] Write RED tests for same-parent, all six wraps, cross-Chunk, negative Chunk, FULL AIR, occupied DETAIL, non-AIR FULL, UNKNOWN, FAILED, and stale destination revision capture.
- [ ] Add an integration RED proving a ray through an empty DETAIL gap targets the later real hit rather than synthesizing a gap target.
- [ ] Run RED; expect missing candidate/world view.
- [ ] Implement checked coordinate wrapping and immutable result values only. Preserve source-hit and destination-observation revisions separately.
- [ ] Run GREEN plus existing raycast UNKNOWN/FAILED and legacy typed placement regressions.

### Task 7: Immutable preview, input/focus lifecycle, and bounded ghost

**Files:**

- Create: `game/src/main/java/com/gaia/interaction/DetailPreviewValidity.java`
- Create: `game/src/main/java/com/gaia/interaction/DetailPlacementPreview.java`
- Create: `game/src/main/java/com/gaia/interaction/DetailPreviewController.java`
- Create: `game/src/main/java/com/gaia/interaction/DetailMaterialSelection.java`
- Create: `game/src/main/java/com/gaia/interaction/feedback/DetailPlacementGhostAdapter.java`
- Modify: `engine/src/main/java/com/overlord/config/GameConfig.java`
- Modify: `engine/src/main/java/com/overlord/renderer/feedback/TransientBlockVisual.java`
- Modify: `game/src/main/java/com/gaia/interaction/BlockInteractionController.java`
- Modify: `game/src/main/java/com/gaia/interaction/BlockInteractionSnapshot.java`
- Modify: `game/src/main/java/com/gaia/interaction/BlockInteractionViewModel.java`
- Modify: `game/src/main/java/com/gaia/interaction/feedback/InteractionFeedbackCoordinator.java`
- Modify: `game/src/main/java/com/gaia/session/GameSessionFactory.java`
- Create test: `game/src/test/java/com/gaia/interaction/DetailPlacementPreviewTest.java`
- Create test: `game/src/test/java/com/gaia/interaction/DetailPreviewLifecycleTest.java`
- Create test: `game/src/test/java/com/gaia/interaction/DetailInteractionArchitectureTest.java`
- Test: `game/src/test/java/com/gaia/interaction/MouseInteractionLifecycleTest.java`
- Test: `game/src/test/java/com/gaia/interaction/feedback/InteractionFeedbackCoordinatorTest.java`

**Interfaces:**

- The preview stores action/tool/source hit revision/candidate parent/local/face/material/validity/reason/quarter AABB; it stores no mutable service.
- `KEY_DETAIL_MATERIAL_CYCLE` is provisionally GLFW R and only changes bounded current selection while the chisel is active.
- The ghost is at most one render-only `TransientBlockVisual.Type.PREVIEW` with scale `0.25`; it is never placed in `excludedBlockCells` and never enters collision, raycast, snapshots, save, dirty, or mutation APIs.

- [ ] Write RED tests for immutability/defensive values, zero revision/dirty/inventory changes during preview, clearing on focus/tool/mode/load/target revision/material changes, R edge-only behavior, and one bounded ghost.
- [ ] Add architecture REDs proving no second raycaster/controller, preview constructors cannot accept `World`/`ChunkRepository`/`DetailMutationService`, and worker/OpenGL ownership is unchanged.
- [ ] Run RED; expect missing preview types and controller still reports `detail_target_unsupported` only.
- [ ] Integrate read-only route/preview state into the existing controller and feedback snapshot. Do not dispatch any new mutation or inventory operation in Gate 17A.
- [ ] Run GREEN plus `BlockInteractionControllerTest`, `MouseInteractionLifecycleTest`, `WorldInteractionInputRouterTest`, `BodyInventoryInputControllerTest`, `DetailTargetingIntegrationTest`, and feedback tests. Verify Q/Ctrl+Q, Shift-secondary, F9-F12/9/0, normal placement, and focus replay remain unchanged.

### Gate 17A required RED coverage map

| Required behavior | Primary test |
| --- | --- |
| chisel/detail-unit identities, visuals, capability | `StandaloneItemDefinitionTest`, `GaiaResourceLoaderTest`, visual resolver tests |
| DETAIL new closed route / no second authority | `CanonicalBlockInteractionRouteResolverTest`, `DetailInteractionArchitectureTest` |
| FULL refinement / DETAIL placement / cross parent+Chunk | `DetailTargetingTest`, `DetailPlacementCandidateTest` |
| UNKNOWN/FAILED/occupied/gap passthrough | `DetailPlacementCandidateTest`, `DetailTargetingIntegrationTest` |
| immutable non-mutating preview and clearing | `DetailPlacementPreviewTest`, `DetailPreviewLifecycleTest` |
| typed normal placement / pickup / Q controls | existing `DetailTargetingIntegrationTest`, `WorldInteractionInputRouterTest`, `BodyInventoryInputControllerTest` |

### Gate 17A focused verification

```powershell
.\gradlew.bat :game:test --tests "com.gaia.blocks.*" --tests "com.gaia.assets.GaiaResourceLoaderTest" --tests "com.gaia.assets.GaiaProductionAssetsTest"
.\gradlew.bat :game:test --tests "com.gaia.interaction.*" --tests "com.gaia.interaction.feedback.*" --tests "com.gaia.worlditem.WorldInteractionInputRouterTest"
.\gradlew.bat :game:verifyPackagedResources --rerun-tasks --console=plain --no-daemon
.\gradlew.bat :engine:verifyPackagedShaderResources --rerun-tasks --console=plain --no-daemon
rg -n "new BlockRaycast|DetailToolController|DetailWorldRaycast|DetailStorage" game/src/main/java engine/src/main/java
git diff --check
```

Expected: focused GREEN, no new traversal/controller/storage authority, and no world/inventory mutation from preview. Record Task RED outputs and Gate results in `docs/agent-handoffs/phase-17-handoff.md`, obtain independent review, then STOP for Gate 17B approval.

---

## Gate 17B: atomic mutation extensions and Creative editing

### Task 8: Atomic `RemoveDetailParent` engine command

**Files:**

- Create: `engine/src/main/java/com/overlord/interaction/api/RemoveDetailParentRequest.java`
- Modify: `engine/src/main/java/com/overlord/interaction/api/DetailMutationService.java`
- Modify: `engine/src/main/java/com/overlord/voxel/ChunkDetailMutation.java`
- Modify: `engine/src/main/java/com/overlord/voxel/ChunkRepository.java`
- Create test: `engine/src/test/java/com/overlord/voxel/ChunkDetailParentRemovalTest.java`
- Test: `engine/src/test/java/com/overlord/voxel/ChunkDetailMutationConcurrencyTest.java`
- Test: `engine/src/test/java/com/overlord/voxel/ChunkRepositoryPersistenceTest.java`

**Interfaces:**

- `RemoveDetailParentRequest(context, x, y, z, expectedChunkRevision, expectedState)` requires an exact nonempty `DetailCellState`.
- `ChunkDetailMutation.RemoveDetailParent` prepares `new FullCellState((byte) 0)` inside the existing synchronized CAS and shared dirty-neighbor publication path.

- [ ] Write REDs for exact DETAIL -> FULL AIR, one revision, one canonical dirty result, stale revision, wrong expected state, FULL representation conflict, active/finalized unload, interior/cardinal/corner invalidation, and save/mesh candidate visibility.

```java
ChunkDetailMutationOutcome result = repository.mutateDetail(
        new ChunkDetailMutation.RemoveDetailParent(x, y, z, revision, expected));
assertEquals(APPLIED, result.status());
assertEquals(new FullCellState((byte) 0), result.newState().orElseThrow());
assertEquals(revision + 1, result.resultingChunkRevision());
```

- [ ] Run RED; expect missing sealed variant.
- [ ] Add only the request/service signature, sealed command, and one `prepareDetailChange` branch. Reuse existing revision allocation, dirty tracker, unload invalidation, and capacity behavior.
- [ ] Run GREEN plus existing mutation concurrency/persistence tests; rejected operations must allocate no revision or neighbor dirty result.

### Task 9: Atomic `SculptParentSubVoxel` engine command

**Files:**

- Create: `engine/src/main/java/com/overlord/interaction/api/SculptParentSubVoxelRequest.java`
- Modify: `engine/src/main/java/com/overlord/interaction/api/DetailMutationService.java`
- Modify: `engine/src/main/java/com/overlord/voxel/ChunkDetailMutation.java`
- Modify: `engine/src/main/java/com/overlord/voxel/ChunkRepository.java`
- Create test: `engine/src/test/java/com/overlord/voxel/ChunkDetailSculptTest.java`
- Test: `engine/src/test/java/com/overlord/voxel/ChunkDetailMutationTest.java`
- Test: `engine/src/test/java/com/overlord/voxel/ChunkDetailMutationConcurrencyTest.java`

**Interfaces:**

- `SculptParentSubVoxelRequest` carries exact `ParentCellState`, local position, expected revision, and optional replacement material.
- Engine command carries translated runtime byte replacement. FULL non-AIR removal creates a 63-cell DETAIL state in one CAS; FULL AIR placement creates one-cell DETAIL; DETAIL edit retains existing final-clear semantics.

- [ ] Write REDs for FULL solid -> 63 occupied, FULL AIR -> one occupied, DETAIL replace/remove, final clear -> FULL AIR, no-op, unknown/zero replacement rules, cap 1,024, boundaries, negative coordinates, stale revision/state, and no intermediate revision.
- [ ] Add a deterministic assertion that mutation observers see only revision N FULL then N+1 final DETAIL, never a uniform intermediate revision.
- [ ] Run RED; expect current `SetSubVoxel` to reject sculpting non-AIR FULL.
- [ ] Add one prepared-change branch using checked immutable state creation; do not implement the operation by calling convert then set.
- [ ] Run GREEN plus all existing `ChunkDetailMutationTest` and snapshot/storage regressions.

### Task 10: Game registry translation for both atomic operations

**Files:**

- Modify: `game/src/main/java/com/gaia/interaction/GaiaDetailMutationService.java`
- Modify: `game/src/test/java/com/gaia/interaction/GaiaDetailMutationServiceTest.java`
- Test: `game/src/test/java/com/gaia/interaction/DetailTargetingIntegrationTest.java`

**Interfaces:**

- `removeDetailParent(request)` delegates without material translation.
- `sculptParentSubVoxel(request)` translates optional canonical block identity through the existing `BlockRegistry`; AIR means empty only through `Optional.empty()`, and unknown material returns `UNKNOWN_MATERIAL` before repository mutation.

- [ ] Write REDs that preserve interaction context, exact old/new state, revisions, dirtied neighbors, unknown-material rejection, main-thread guard, and no mutable storage exposure.
- [ ] Run RED; expect interface methods absent.
- [ ] Implement minimal mappings to the two engine commands and reuse the existing `map(...)` result adapter.
- [ ] Run GREEN plus `GaiaDetailMutationServiceTest`, `DetailTargetingIntegrationTest`, and `DetailArchitectureContractTest`.

### Task 11: Quarter-AABB overlap validation and Creative precision transaction

**Files:**

- Create: `game/src/main/java/com/gaia/interaction/DetailPlacementCollisionValidator.java`
- Create: `game/src/main/java/com/gaia/interaction/CreativeDetailEditTransaction.java`
- Create: `game/src/main/java/com/gaia/interaction/DetailEditResult.java`
- Create test: `game/src/test/java/com/gaia/interaction/CreativeDetailEditTransactionTest.java`
- Test: `game/src/test/java/com/gaia/PhysicsDetailCompositionTest.java`

**Interfaces:**

- Transaction consumes immutable `DetailPrecisionTarget`/`DetailPlacementCandidate`, selected supported block material, player `PhysicsBody`, and `DetailMutationService`.
- Creative remove/place/sculpt performs no inventory reservation and yields no gameplay drop. Placement validates exact 0.25 AABB overlap before submitting one mutation.

- [ ] Write REDs for FULL sculpt, DETAIL remove, FULL AIR first placement, DETAIL placement, replacement rejection, cross-parent/Chunk candidate, player overlap, stale result, notification/applied status, and zero inventory/world-item calls.
- [ ] Run RED; expect transaction types absent.
- [ ] Implement the narrow transaction and typed result statuses. Do not recompute a ray, mutate the preview, auto-compact uniform DETAIL, or dispatch feedback before APPLIED.
- [ ] Run GREEN plus collision composition and existing FULL placement overlap tests.

### Task 12: Creative coarse whole-parent transaction

**Files:**

- Create: `game/src/main/java/com/gaia/interaction/DetailParentBreakTransaction.java`
- Create: `game/src/main/java/com/gaia/interaction/DetailParentBreakResult.java`
- Create test: `game/src/test/java/com/gaia/interaction/DetailParentBreakTransactionTest.java`
- Test: `game/src/test/java/com/gaia/interaction/BlockBreakTransactionTest.java`

**Interfaces:**

- Gate 17B entry point `executeCreative(BlockHitResult, DetailCellState, BodySlot, tick, timestamp)` submits one `RemoveDetailParentRequest` and produces no WorldItem/inventory output.
- The class is deliberately shaped to accept the optional single coarse output reservation in Gate 17C without changing mutation authority.

- [ ] Write REDs for atomic partial/mixed/uniform removal, exact hit revision/state, stale rejection, no 64-call loop, no output in Creative, one committed-feedback eligibility fact, and no feedback on rejection.
- [ ] Run RED; expect transaction absent.
- [ ] Implement only the Creative no-output path and typed result; invocation count on `DetailMutationService.removeDetailParent` must equal one.
- [ ] Run GREEN plus FULL `BlockBreakTransactionTest` to freeze ordinary block behavior.

### Task 13: Route Creative actions through the sole controller

**Files:**

- Modify: `game/src/main/java/com/gaia/interaction/BlockInteractionController.java`
- Modify: `game/src/main/java/com/gaia/interaction/BlockInteractionSnapshot.java`
- Modify: `game/src/main/java/com/gaia/interaction/feedback/CommittedGameplayFeedback.java`
- Modify: `game/src/main/java/com/gaia/interaction/feedback/InteractionFeedbackCoordinator.java`
- Modify: `game/src/main/java/com/gaia/session/GameSessionFactory.java`
- Create test: `game/src/test/java/com/gaia/interaction/BlockInteractionControllerDetailCreativeTest.java`
- Test: `game/src/test/java/com/gaia/interaction/BlockInteractionControllerTest.java`
- Test: `game/src/test/java/com/gaia/interaction/feedback/FeedbackTransactionIsolationTest.java`

**Interfaces:**

- `BlockInteractionController` calls one route resolver after one target query and dispatches exactly one existing FULL or new DETAIL transaction.
- Add bounded committed DETAIL feedback callbacks carrying immutable action/position/material facts; callbacks remain presentation-only.

- [ ] Write RED integration tests for ordinary-item DETAIL primary -> coarse removal, chisel FULL primary -> sculpt, chisel DETAIL primary -> precise remove, secondary placement, simultaneous-edge precedence, overlap rejection, and APPLIED-only feedback.
- [ ] Run RED; expect read-only Gate 17A controller not to mutate.
- [ ] Wire the two transactions without introducing `DetailToolController`, a second target, or direct repository access outside the existing service composition.
- [ ] Run GREEN plus all `BlockInteractionControllerTest`, feedback isolation, FULL break/place, pickup precedence, and focus lifecycle tests.

### Gate 17B focused verification

```powershell
.\gradlew.bat :engine:test --tests "com.overlord.voxel.ChunkDetail*" --tests "com.overlord.voxel.Detail*" --tests "com.overlord.voxel.ChunkRepositoryPersistenceTest"
.\gradlew.bat :game:test --tests "com.gaia.interaction.GaiaDetailMutationServiceTest" --tests "com.gaia.interaction.CreativeDetailEditTransactionTest" --tests "com.gaia.interaction.DetailParentBreakTransactionTest" --tests "com.gaia.interaction.BlockInteractionControllerDetailCreativeTest"
.\gradlew.bat :game:test --tests "com.gaia.interaction.BlockInteractionControllerTest" --tests "com.gaia.interaction.BlockBreakTransactionTest" --tests "com.gaia.interaction.BlockPlacementTransactionTest" --tests "com.gaia.interaction.feedback.*"
rg -n "for .*64|setSubVoxel\(" game/src/main/java/com/gaia/interaction engine/src/main/java/com/overlord/voxel
git diff --check
```

Expected: one-CAS/one-revision behavior, exact invalidation, no sequential coarse loop or second controller, and frozen FULL behavior. Update Gate notes, obtain independent engine+game-owner review, then STOP for Gate 17C approval.

---

## Gate 17C: Survival material conservation

### Task 14: Stone/dirt DETAIL support mapping and minimal action policy

**Files:**

- Create: `game/src/main/java/com/gaia/blocks/DetailSupportDefinition.java`
- Modify: `game/src/main/java/com/gaia/blocks/BlockDefinition.java`
- Modify: `game/src/main/java/com/gaia/blocks/BlockRegistry.java`
- Modify: `game/src/main/java/com/gaia/assets/GaiaResourceLoader.java`
- Modify: `game/src/main/resources/assets/gaia/blocks/stone.json`
- Modify: `game/src/main/resources/assets/gaia/blocks/dirt.json`
- Create: `game/src/main/java/com/gaia/interaction/DetailAction.java`
- Create: `game/src/main/java/com/gaia/interaction/DetailRecoveryKind.java`
- Create: `game/src/main/java/com/gaia/interaction/DetailActionDecision.java`
- Create: `game/src/main/java/com/gaia/interaction/DetailActionPolicy.java`
- Create: `game/src/main/java/com/gaia/interaction/Phase17DetailActionPolicy.java`
- Create test: `game/src/test/java/com/gaia/interaction/DetailActionPolicyTest.java`
- Test: `game/src/test/java/com/gaia/assets/GaiaResourceLoaderTest.java`
- Test: `game/src/test/java/com/gaia/blocks/BlockRegistryTest.java`

**Interfaces:**

- Stone maps to `gaia:stone_detail_unit`; dirt maps to `gaia:dirt_detail_unit`; reverse lookup is collision-checked and remains inside `BlockRegistry`.
- `decide(mode, action, toolItem, material, composition)` returns allowed/rejected plus `NONE`, `DETAIL_UNIT`, or `FULL_BLOCK`; it owns no inventory, mutation, hardness, or progression state.

- [ ] Write REDs for strict optional block `detailSupport`, missing/unknown/mismatched/duplicate unit mappings, stone/dirt lookup, unsupported materials, Creative NONE, chisel precision recovery, and the exact coarse matrix.
- [ ] Run RED; expect missing mapping/policy types.
- [ ] Add the narrow block field/parser and pure Phase 17 policy. Preserve all existing block constructor test fixtures by updating their explicit constructor arguments; do not introduce metadata or a second registry.
- [ ] Run GREEN plus production asset, BlockRegistry, normal FULL policy, and packaged-resource tests.

### Task 15: Deterministic coarse composition and maximum hardness

**Files:**

- Create: `game/src/main/java/com/gaia/interaction/DetailParentComposition.java`
- Create: `game/src/main/java/com/gaia/interaction/DetailCoarseHardness.java`
- Modify: `game/src/main/java/com/gaia/interaction/BlockInteractionPolicy.java` only to accept an already-resolved hardness value through the existing `BreakRule` calculation
- Create test: `game/src/test/java/com/gaia/interaction/DetailCoarseHardnessTest.java`
- Test: `game/src/test/java/com/gaia/interaction/BlockInteractionPolicyTest.java`
- Test: `game/src/test/java/com/gaia/interaction/BlockBreakTrackerTest.java`

**Interfaces:**

- `DetailParentComposition.from(DetailCellState, BlockRegistry)` scans indices 0..63, resolves every occupied runtime ID, reports uniform/full compatibility, and fails closed on unknown material.
- `DetailCoarseHardness.resolve(composition)` returns maximum canonical `BlockDefinition.hardness()` independent of occupancy count.

- [ ] Write REDs for dirt partial, stone partial, mixed dirt+harder stone, insertion-order independence, 1/64 vs 64/64 equal hardness, unknown material, Creative instant, Survival break progression, and FULL regression.

```java
assertEquals(1.5f, hardness.resolve(mixedDirtStone), 0.0f);
assertEquals(hardness.resolve(oneStone), hardness.resolve(sixtyFourStone), 0.0f);
```

- [ ] Run RED; expect missing aggregator and current controller cannot derive DETAIL break rules.
- [ ] Implement deterministic pure aggregation and feed its result into the existing `BlockInteractionPolicy`/`BreakRule`/`BlockBreakTracker`; add no detail tracker and no occupancy multiplier.
- [ ] Run GREEN with the three focused classes plus ordinary FULL break timing tests.

### Task 16: Survival precision removal with guaranteed INSERT

**Files:**

- Create: `game/src/main/java/com/gaia/interaction/SurvivalDetailEditTransaction.java`
- Create: `game/src/main/java/com/gaia/interaction/SurvivalDetailEditResult.java`
- Create test: `game/src/test/java/com/gaia/interaction/SurvivalDetailRemovalTransactionTest.java`
- Test: `game/src/test/java/com/gaia/inventory/BodyInventoryReservationPlannerTest.java`

**Interfaces:**

- `removeRecoverable(target, decision, preferredSlot, context)` reserves complete INSERT of one matching unit before submitting one CAS; partial reservation is rolled back and returns `INVENTORY_FULL` without mutation.
- APPLIED mutation commits every reservation; applied-state inventory notification failure is reported as applied and never retried/duplicated.

- [ ] Write REDs for exact `-1 occupied/+1 unit`, multi-slot insertion, full inventory pre-rejection, stale mutation rollback, mutation exception before/after applied state, commit notification failure, unsupported/destructive decisions, repeated input, and conservation audit fields.
- [ ] Run RED; expect missing transaction.
- [ ] Implement one-operation orchestration using `BodyInventoryReservationPlanner`, `DetailMutationService.sculptParentSubVoxel`, and exact commit/rollback status checks. No WorldItem fallback is allowed.
- [ ] Run GREEN plus inventory reservation contract and fault-injecting inventory tests.

### Task 17: Survival precision placement with guaranteed EXTRACT

**Files:**

- Modify: `game/src/main/java/com/gaia/inventory/BodyInventoryReservationPlanner.java`
- Modify (created in Task 16): `game/src/main/java/com/gaia/interaction/SurvivalDetailEditTransaction.java`
- Create test: `game/src/test/java/com/gaia/interaction/SurvivalDetailPlacementTransactionTest.java`
- Test: `game/src/test/java/com/gaia/inventory/BodyInventoryReservationPlannerTest.java`
- Test: `game/src/test/java/com/gaia/interaction/BlockPlacementTransactionTest.java`

**Interfaces:**

- Add deterministic preferred-slot-first `reserveExtraction(owner, preferredSlot, ItemStack)` returning the same immutable `InventoryReservationBatch` contract.
- `place(...)` validates material mapping/candidate/overlap/revision, reserves exactly one matching unit, submits one CAS, rolls back on rejection, and commits extraction only after APPLIED.

- [ ] Write REDs for exact `-1 unit/+1 occupied`, active/other slot ordering, no unit, wrong unit, occupied/overlap/UNKNOWN/FAILED/stale destination, FULL AIR first placement, cross-parent/Chunk placement, notification-applied behavior, and no duplicate retry.
- [ ] Run RED; expect insertion-only planner and no Survival placement path.
- [ ] Implement deterministic extraction reservation and the transaction branch. Do not remove inventory directly or use selected item metadata.
- [ ] Run GREEN plus normal FULL placement and inventory service/reservation regressions.

### Task 18: Survival coarse break and one legal FULL-block WorldItem

**Files:**

- Modify (created in Task 12): `game/src/main/java/com/gaia/interaction/DetailParentBreakTransaction.java`
- Modify (created in Task 12): `game/src/main/java/com/gaia/interaction/DetailParentBreakResult.java`
- Modify: `game/src/main/java/com/gaia/interaction/BlockInteractionController.java`
- Modify test (created in Task 12): `game/src/test/java/com/gaia/interaction/DetailParentBreakTransactionTest.java`
- Test: `game/src/test/java/com/gaia/interaction/BlockBreakTransactionTest.java`
- Test: `game/src/test/java/com/gaia/worlditem/WorldItemSpawnCommitResolverTest.java`

**Interfaces:**

- Survival execution derives `DetailParentComposition`, asks `DetailActionPolicy`, reserves at most one FULL-block `ItemStack` spawn for uniform compatible 64/64, mutates once, and resolves the spawn using existing `WorldItemSpawnCommitResolver` semantics.
- Partial/mixed DETAIL output is empty; no coarse action emits detail units.

- [ ] Write RED matrix tests for FULL unchanged, uniform stone/dirt one full item, partial single-material zero, mixed zero, Creative zero, destructive zero, unsupported/unresolved fail closed, reserve rejection before mutation, rollback on stale mutation, applied notification failure, and unresolved spawn close/reconciliation.
- [ ] Run RED; expect Creative-only transaction.
- [ ] Reuse the existing spawn reservation/commit pattern without changing `BlockBreakTransaction` authority or adding inventory insertion.
- [ ] Run GREEN plus WorldItem capacity/persistence and ordinary FULL break regressions.

### Task 19: Survival controller integration, explicit debug provisioning, and item persistence

**Files:**

- Modify: `game/src/main/java/com/gaia/interaction/BlockInteractionController.java`
- Modify: `game/src/main/java/com/gaia/session/GameSessionFactory.java`
- Create: `game/src/main/java/com/gaia/debug/DetailToolDebugProvisioner.java`
- Modify: `game/src/main/java/com/gaia/inventory/InventoryDebugCommands.java`
- Create test: `game/src/test/java/com/gaia/interaction/BlockInteractionControllerDetailSurvivalTest.java`
- Create test: `game/src/test/java/com/gaia/debug/DetailToolDebugProvisionerTest.java`
- Create test: `game/src/test/java/com/gaia/save/DetailItemPersistenceTest.java`
- Test: `game/src/test/java/com/gaia/save/codec/InventorySectionCodecTest.java`
- Test: `game/src/test/java/com/gaia/save/codec/WorldItemsSectionCodecTest.java`
- Test: `game/src/test/java/com/gaia/save/WorldItemPersistenceRegressionTest.java`

**Interfaces:**

- Controller uses the same route and dispatches Survival transactions; stale/conflict returns one bounded notice and no automatic retry.
- Explicit debug commands `detail-stone` and `detail-dirt` provision chisel plus one selected 64-unit stack through public inventory operations. They are development/manual-only and are never called by New World bootstrap.
- Existing inventory/world-item codecs must round-trip the three IDs generically; production codec changes are allowed only if RED proves a block-backing assumption.

- [ ] Write REDs for controller quantity conservation, inventory-full rejection, wrong tool/no hidden destruction, pressed-edge behavior, debug provisioning authority, no automatic New World gift, and inventory/WorldItem save round-trips.
- [ ] Run RED; expect absent Survival wiring/provisioner while generic codec tests should reveal whether any narrow fix is actually needed.
- [ ] Implement only controller composition and explicit provisioning. If codecs already GREEN with standalone IDs, leave production save code unchanged and retain the new regression tests.
- [ ] Run GREEN plus `GameSessionFactoryTest`, `GameSessionLifecycleTest`, inventory/world-item persistence tests, and streamed DETAIL persistence tests.

### Gate 17C focused verification

```powershell
.\gradlew.bat :game:test --tests "com.gaia.interaction.DetailActionPolicyTest" --tests "com.gaia.interaction.DetailCoarseHardnessTest" --tests "com.gaia.interaction.SurvivalDetail*" --tests "com.gaia.interaction.DetailParentBreakTransactionTest"
.\gradlew.bat :game:test --tests "com.gaia.inventory.*" --tests "com.gaia.worlditem.*" --tests "com.gaia.save.DetailItemPersistenceTest" --tests "com.gaia.save.codec.InventorySectionCodecTest" --tests "com.gaia.save.codec.WorldItemsSectionCodecTest"
.\gradlew.bat :game:test --tests "com.gaia.interaction.BlockInteractionController*" --tests "com.gaia.session.GameSessionFactoryTest" --tests "com.gaia.save.streaming.StreamedDetailPersistenceTest"
rg -n "metadata|fraction|generated.*provenance|player.*provenance|DetailItemRegistry|DetailBreakTracker" engine/src/main/java game/src/main/java
git diff --check
```

Expected: exact unit conservation, coarse matrix/hardness behavior, generic item persistence, no metadata/provenance/second registry/tracker, and frozen FULL behavior. Update Gate notes, obtain independent transaction/conservation review, then STOP for Gate 17D approval.

---

## Gate 17D: bounded UX, runtime, performance, and handoff

### Task 20: Bounded HUD projection for precision/coarse state

**Files:**

- Modify: `game/src/main/java/com/gaia/interaction/BlockInteractionSnapshot.java`
- Modify: `game/src/main/java/com/gaia/interaction/BlockInteractionViewModel.java`
- Modify: `game/src/main/java/com/gaia/ui/HudPresentationSnapshot.java`
- Modify: `game/src/main/java/com/gaia/ui/HudPresenter.java`
- Modify: `game/src/main/java/com/gaia/ui/GaiaHudScreen.java`
- Create test: `game/src/test/java/com/gaia/ui/DetailHudPresentationTest.java`
- Test: `game/src/test/java/com/gaia/ui/HudPresenterTest.java`
- Test: `game/src/test/java/com/gaia/ui/GaiaHudScreenIntegrationTest.java`

**Interfaces:**

- Immutable current-state HUD fields are mode, selected material, available matching unit count, optional local `[x,y,z]`, preview validity/reason, and one bounded latest failure notice.
- No history, palette browser, backpack, mutable repository, or inventory authority enters UI.

- [ ] Write REDs for Creative/Survival precision display, coarse DETAIL display, valid/invalid candidate, local coordinate, unit count, stale/inventory-full/unsupported notices, focus/load clearing, and bounded replacement of old notice.
- [ ] Run RED; expect snapshot/presenter fields absent.
- [ ] Extend immutable view models and current HUD layout using existing typography/widgets; do not add a new UI framework or persist transient selection/preview.
- [ ] Run GREEN plus HUD layout matrix, screen, inventory HUD, and interaction snapshot tests.

### Task 21: Held chisel visual and APPLIED-only DETAIL feedback

**Files:**

- Modify: `game/src/main/java/com/gaia/interaction/feedback/CommittedGameplayFeedback.java`
- Modify: `game/src/main/java/com/gaia/interaction/feedback/InteractionFeedbackCoordinator.java`
- Modify: `game/src/main/java/com/gaia/interaction/feedback/GameplayParticleFeedback.java`
- Create test: `game/src/test/java/com/gaia/interaction/feedback/DetailInteractionFeedbackTest.java`
- Test: `game/src/test/java/com/gaia/interaction/feedback/InteractionFeedbackCoordinatorTest.java`
- Test: `game/src/test/java/com/gaia/InteractionFeedbackGameLoopTest.java`

**Interfaces:**

- Existing face resolver supplies the placeholder held chisel `ATLAS_REGION` visual.
- Committed quarter remove/place emits bounded particles at the canonical quarter location and one existing first-person action trigger only after APPLIED.
- Sound remains deferred because the audit found no existing committed gameplay-SFX dispatch seam; this Task must not create one.

- [ ] Write REDs for held chisel region, exact quarter feedback position/material, zero success feedback on preview/rejection/stale/inventory-full, bounded particle count, deterministic event identity, and clear/focus lifecycle.
- [ ] Run RED; expect no DETAIL committed callback/particle adapter.
- [ ] Extend the existing coordinator and particle helper minimally. Keep all renderer work owner-thread-only and do not add shader/model/resource systems.
- [ ] Run GREEN plus all feedback, particle-allocation, first-person, and resource resolver tests.

### Task 22: Save, unload, reload, and fresh-session interaction round trip

**Files:**

- Create test: `game/src/test/java/com/gaia/session/DetailToolPersistenceIntegrationTest.java`
- Create test: `game/src/test/java/com/gaia/session/DetailToolStreamingIntegrationTest.java`
- Test: `game/src/test/java/com/gaia/save/SaveLoadCanonicalRoundTripTest.java`
- Test: `game/src/test/java/com/gaia/save/streaming/StreamedDetailPersistenceTest.java`
- Test: `game/src/test/java/com/gaia/save/WorldItemPersistenceRegressionTest.java`

**Interfaces:**

- No new Phase 17 save section. Canonical voxel edits persist through Chunk revision/dirty + `detail-blocks`; chisel/units persist through existing generic inventory/WorldItem identities.
- Preview/material-cycle intent clears on load and is never serialized.

- [ ] Write production-composition REDs for Creative sculpt save/reload, Survival unit conservation save/reload, uniform coarse FULL drop persistence, player-modified DETAIL unload/return, exact occupancy/material/hash, rebuilt raycast/collision/mesh, and transient preview absence after restore.
- [ ] Run RED and identify whether failure is missing interaction composition rather than codec redesign.
- [ ] Treat these as integration-contract tests. No save/streaming production file is planned for modification; if a RED proves the approved architecture cannot compose through existing seams, STOP for controller review instead of redesigning `detail-blocks` or adding a save root.
- [ ] Run GREEN plus Phase 14/15 old-save compatibility, ordinary FULL save/load, inventory, WorldItem paging, and session lifecycle tests.

### Task 23: Rapid-edit/backpressure fixture and bounded diagnostics

**Files:**

- Create: `tools/src/main/java/com/gaia/tools/DetailEditPerformanceFixture.java`
- Create test: `tools/src/test/java/com/gaia/tools/DetailEditPerformanceFixtureTest.java`
- Create test: `game/src/test/java/com/gaia/interaction/DetailRapidEditIntegrationTest.java`
- Test: `engine/src/test/java/com/overlord/voxel/DetailChunkMeshLifecycleTest.java`
- Test: `engine/src/test/java/com/overlord/voxel/ChunkMeshMemoryBudgetTest.java`
- Test: `game/src/test/java/com/gaia/HybridMeshOutputCanonicalSafetyTest.java`
- Modify: `tools/build.gradle` only to add a bounded `profileDetailEdits` JavaExec task if the production-composition fixture needs manual repeatable execution

**Interfaces:**

- Fixture emits deterministic pressed-edge edits against one local area and records current bounded counters: attempts/APPLIED/stale/rejected, edit latency, affected Chunk keys, mesh queue/activity, output diagnostics, and allocation estimate. It retains no per-edit history.
- Existing mesh manager owns all backpressure; stale mesh claims and stale click revisions reject through existing authority.

- [ ] Write REDs for one mutation per edge, repeated fixed updates, simultaneous-edge precedence, edit N+1 while mesh N builds, stale mesh rejection, admission backpressure, no gameplay retry, bounded latest notice, no unrelated-Chunk rebuild, and bounded fixture storage.
- [ ] Run RED; expect no fixture and missing integrated metrics.
- [ ] Add test/tooling adapters only; change production behavior solely if a reproduced correctness defect has its own RED and remains inside approved Phase 17 scope.
- [ ] Run GREEN, then record representative FULL baseline, single/rapid DETAIL edits, mesh rebuild latency, affected Chunk count, allocation, and resource-budget observations. Do not retune Phase 16 caps.

### Task 24: Architecture docs, acceptance, full affected verification, and handoff

**Files:**

- Create: `docs/architecture/small-block-tools-building.md`
- Create: `docs/testing/phase-17-small-block-tools-acceptance.md`
- Create/update: `docs/agent-handoffs/phase-17-handoff.md`

**Interfaces:**

- Architecture doc records route/mutation/item/recovery/coarse-hardness/conservation matrices, item visual/provenance paths, controls, resource limits, and Phase 18/19 seams.
- Acceptance doc records exact commands, runtime steps, evidence, and platform limitations.

- [ ] Document the final implementation with no future feature described as present. Explicitly record `1 FULL == 64 matching units` as a frozen equivalence but FULL/64 player conversion and normal chisel acquisition as Phase 18 deferred.
- [ ] Run the Gate 17D focused suites and proportional engine/game/tools matrices below.
- [ ] Run packaged resources, installed shader resources, `installDist`, and a short installed-runtime smoke only after focused GREEN.
- [ ] Execute Windows manual acceptance: FULL regressions; Creative stairs/notches/openings; precision FULL/DETAIL and cross-Chunk edits; collision; rapid edges; focus/resize; Survival one-unit remove/place/full inventory; uniform/partial/mixed coarse matrix; save & quit; fresh-process load; unload/return; clean exit.
- [ ] Report native Apple Silicon interactive status exactly `PASS` only if actually run, otherwise `NOT RUN`; CI is not interactive evidence.
- [ ] Obtain fresh independent review for duplicate authority, raw-byte bypass, transaction loss/duplication, stale handling, resource/thread ownership, Phase 18/19 leakage, and artifact hygiene. Resolve Critical/Important findings tests-first under a new controller-approved fix cycle.
- [ ] Run `git diff --check`, full file/artifact audit, quarantine hashes, and STOP for final Phase 17 closure authorization. Do not stage or commit.

### Gate 17D focused and proportional verification

```powershell
.\gradlew.bat :game:test --tests "com.gaia.ui.*" --tests "com.gaia.interaction.feedback.*" --tests "com.gaia.InteractionFeedbackGameLoopTest"
.\gradlew.bat :game:test --tests "com.gaia.session.DetailTool*" --tests "com.gaia.interaction.DetailRapidEditIntegrationTest" --tests "com.gaia.save.*" --tests "com.gaia.save.streaming.*"
.\gradlew.bat :engine:test --tests "com.overlord.voxel.*" --tests "com.overlord.physics.*" --tests "com.overlord.inventory.api.*"
.\gradlew.bat :game:test --tests "com.gaia.blocks.*" --tests "com.gaia.assets.*" --tests "com.gaia.interaction.*" --tests "com.gaia.inventory.*" --tests "com.gaia.worlditem.*" --tests "com.gaia.session.*"
.\gradlew.bat :tools:test
.\gradlew.bat :game:verifyPackagedResources --rerun-tasks --console=plain --no-daemon
.\gradlew.bat :engine:verifyPackagedShaderResources --rerun-tasks --console=plain --no-daemon
.\gradlew.bat :game:verifyInstalledShaderResources --rerun-tasks --console=plain --no-daemon
.\gradlew.bat :game:installDist --console=plain --no-daemon
git diff --check
git diff --name-status
git diff --stat
git ls-files --others --exclude-standard
git status --short --untracked-files=all
```

The repository-wide `clean test build` remains a separate final-closure command and must not be inferred from these proportional matrices.

## Manual acceptance sequence

1. Launch the real Windows GLFW/OpenGL production path with development commands enabled.
2. Confirm ordinary FULL break/place and typed adjacent placement through a DETAIL gap remain normal.
3. Explicitly provision `gaia:chisel` plus stone/dirt units through the development-only seam; verify no automatic Survival grant occurred.
4. Creative: sculpt a FULL parent, remove/place exact quarters, make stairs/notches/openings, cross a parent and Chunk boundary, and verify ghost validity/clearing.
5. Survival: remove one supported quarter and observe exactly one unit, place it back and observe exactly one unit consumed, then fill inventory and verify pre-mutation rejection.
6. Coarse: verify uniform 64/64 gives exactly one legal FULL WorldItem in Survival, while partial and mixed DETAIL give no micro output; verify maximum occupied-material hardness timing.
7. Generate rapid pressed edges while an earlier mesh is active/backpressured; verify canonical progress, stale rejection, no retry storm, bounded notice, and eventual current mesh.
8. Save & Quit, launch a fresh process, verify canonical occupancy/material/unit counts and raycast/collision/mesh; leave unload radius and return.
9. Verify resize, focus loss/recovery, no input replay, R material cycle suppression, Shift-secondary pickup, Q/Ctrl+Q, and clean exit.

## Deliverables and phase boundaries

### Phase 17 deliverables

- One controller-owned closed interaction route and immutable preview/ghost.
- Three canonical standalone items and ATLAS_REGION presentation.
- Two atomic mutation commands under the existing repository CAS.
- Creative precision/coarse actions and Survival one-unit conservation.
- Maximum-material coarse hardness and exact coarse recovery matrix.
- Existing save/streaming/mesh/raycast/collision integration with bounded diagnostics.
- Architecture, controls/UX, conservation/recovery tables, acceptance evidence, and Phase 17 handoff.

### Explicit Phase 18 deferrals

- Normal Survival chisel crafting/acquisition or automatic grant.
- FULL block -> 64 units and 64 units -> FULL player-facing conversion.
- Crafting-lite/fabrication UI, tool tiers, harvest tiers, material tags, durability, break-speed progression, variants, GLB models, Blender MCP, and Gaia Model Inspector.
- Ownership-transfer builder optimizations or any Phase 16 resource-policy retuning.

### Phase 19 compatibility

Future deterministic generated DETAIL uses the same `ParentCellState`, target, route, action policy, and repository mutation semantics. Phase 17 adds no generated/player provenance; identical material has identical recovery behavior. Phase 19 generation remains responsible for deterministic staged base generation and its substantially lower generated-detail budget.

## Stop conditions

Stop and request controller review if any Task requires:

- changing `ItemStack` metadata/value semantics or adding another item registry;
- another interaction controller, raycast, mutation authority, edit queue, break tracker, save root, mesh/GPU lifecycle, or world lookup from a worker;
- sequential 64-cell coarse mutation or two visible revisions for one sculpt;
- material destruction after failed/full-inventory recoverable removal;
- changing `detail-blocks` v1, Phase 16 canonical representation, mesh caps/budgets, or exactly-nine-snapshot input;
- occupancy-scaled hardness, unknown material as zero hardness, or a new hardness authority;
- preview entering canonical state, dirty tracking, collision, raycast, or persistence;
- Phase 18 crafting/acquisition/progression/model work or Phase 19 worldgen/provenance;
- a broad BlockRegistry, interaction, transaction, audio, renderer, or shader rewrite.

## Exact per-Task RED/GREEN commands

Run each command first immediately after its Task's tests are written and confirm the stated missing/incorrect behavior; run the same command after minimal production work and require PASS. Adjacent regressions named in the Task are then run before review.

| Task | Focused command used for both RED and GREEN |
| ---: | --- |
| 1 | `.\gradlew.bat :game:test --tests "com.gaia.blocks.BlockRegistryTest" --tests "com.gaia.blocks.StandaloneItemDefinitionTest"` |
| 2 | `.\gradlew.bat :game:test --tests "com.gaia.assets.GaiaResourceLoaderTest" --tests "com.gaia.assets.GaiaProductionAssetsTest"` |
| 3 | `.\gradlew.bat :game:test --tests "com.gaia.interaction.feedback.GaiaVisualRegionResolverTest" --tests "com.gaia.interaction.feedback.GaiaWorldItemFaceResolverTest" --tests "com.gaia.interaction.BlockPlacementTransactionTest"` |
| 4 | `.\gradlew.bat :game:test --tests "com.gaia.interaction.CanonicalBlockInteractionRouteResolverTest"` |
| 5 | `.\gradlew.bat :game:test --tests "com.gaia.interaction.DetailTargetingTest" --tests "com.gaia.interaction.DetailTargetingIntegrationTest"` |
| 6 | `.\gradlew.bat :game:test --tests "com.gaia.interaction.DetailPlacementCandidateTest" --tests "com.gaia.interaction.DetailTargetingIntegrationTest"` |
| 7 | `.\gradlew.bat :game:test --tests "com.gaia.interaction.DetailPlacementPreviewTest" --tests "com.gaia.interaction.DetailPreviewLifecycleTest" --tests "com.gaia.interaction.DetailInteractionArchitectureTest" --tests "com.gaia.interaction.feedback.InteractionFeedbackCoordinatorTest"` |
| 8 | `.\gradlew.bat :engine:test --tests "com.overlord.voxel.ChunkDetailParentRemovalTest" --tests "com.overlord.voxel.ChunkDetailMutationConcurrencyTest"` |
| 9 | `.\gradlew.bat :engine:test --tests "com.overlord.voxel.ChunkDetailSculptTest" --tests "com.overlord.voxel.ChunkDetailMutationTest"` |
| 10 | `.\gradlew.bat :game:test --tests "com.gaia.interaction.GaiaDetailMutationServiceTest" --tests "com.gaia.interaction.DetailTargetingIntegrationTest"` |
| 11 | `.\gradlew.bat :game:test --tests "com.gaia.interaction.CreativeDetailEditTransactionTest" --tests "com.gaia.PhysicsDetailCompositionTest"` |
| 12 | `.\gradlew.bat :game:test --tests "com.gaia.interaction.DetailParentBreakTransactionTest" --tests "com.gaia.interaction.BlockBreakTransactionTest"` |
| 13 | `.\gradlew.bat :game:test --tests "com.gaia.interaction.BlockInteractionControllerDetailCreativeTest" --tests "com.gaia.interaction.BlockInteractionControllerTest" --tests "com.gaia.interaction.feedback.FeedbackTransactionIsolationTest"` |
| 14 | `.\gradlew.bat :game:test --tests "com.gaia.interaction.DetailActionPolicyTest" --tests "com.gaia.assets.GaiaResourceLoaderTest" --tests "com.gaia.blocks.BlockRegistryTest"` |
| 15 | `.\gradlew.bat :game:test --tests "com.gaia.interaction.DetailCoarseHardnessTest" --tests "com.gaia.interaction.BlockInteractionPolicyTest" --tests "com.gaia.interaction.BlockBreakTrackerTest"` |
| 16 | `.\gradlew.bat :game:test --tests "com.gaia.interaction.SurvivalDetailRemovalTransactionTest" --tests "com.gaia.inventory.BodyInventoryReservationPlannerTest"` |
| 17 | `.\gradlew.bat :game:test --tests "com.gaia.interaction.SurvivalDetailPlacementTransactionTest" --tests "com.gaia.inventory.BodyInventoryReservationPlannerTest" --tests "com.gaia.interaction.BlockPlacementTransactionTest"` |
| 18 | `.\gradlew.bat :game:test --tests "com.gaia.interaction.DetailParentBreakTransactionTest" --tests "com.gaia.interaction.BlockBreakTransactionTest" --tests "com.gaia.worlditem.WorldItemSpawnCommitResolverTest"` |
| 19 | `.\gradlew.bat :game:test --tests "com.gaia.interaction.BlockInteractionControllerDetailSurvivalTest" --tests "com.gaia.debug.DetailToolDebugProvisionerTest" --tests "com.gaia.save.DetailItemPersistenceTest"` |
| 20 | `.\gradlew.bat :game:test --tests "com.gaia.ui.DetailHudPresentationTest" --tests "com.gaia.ui.HudPresenterTest" --tests "com.gaia.ui.GaiaHudScreenIntegrationTest"` |
| 21 | `.\gradlew.bat :game:test --tests "com.gaia.interaction.feedback.DetailInteractionFeedbackTest" --tests "com.gaia.interaction.feedback.InteractionFeedbackCoordinatorTest" --tests "com.gaia.InteractionFeedbackGameLoopTest"` |
| 22 | `.\gradlew.bat :game:test --tests "com.gaia.session.DetailToolPersistenceIntegrationTest" --tests "com.gaia.session.DetailToolStreamingIntegrationTest" --tests "com.gaia.save.streaming.StreamedDetailPersistenceTest"` |
| 23 | `.\gradlew.bat :tools:test --tests "com.gaia.tools.DetailEditPerformanceFixtureTest"` followed by `.\gradlew.bat :game:test --tests "com.gaia.interaction.DetailRapidEditIntegrationTest"` |
| 24 | Documentation/acceptance Task: no fabricated behavioral RED; run the complete Gate 17D focused/proportional matrix and compare recorded evidence with the deliverable checklist. |

## Plan self-review checklist

- [ ] Every new class referenced later is defined in Planned interfaces or the Task that first creates it.
- [ ] Every behavioral Task starts with a concrete RED and named focused command before production work.
- [ ] Gate 17A has no world/inventory mutation; Gate 17B has no material accounting; Gate 17C has no crafting/acquisition; Gate 17D adds no new authority.
- [ ] No Task uses `DetailToolController`, a second raycaster, direct `DetailStorage`, 64 sequential operations, ItemStack metadata, generated/player provenance, or occupancy-scaled hardness.
- [ ] Stone/dirt item identities exist in 17A; block-to-unit recovery mapping is added in 17C, resolving the Gate boundary without duplication.
- [ ] Existing generic item save codecs are tested before any production codec edit.
- [ ] V1 visual is atlas-only, the public item identity/capability is presentation-independent, and no 3D pipeline appears.
- [ ] FULL break/place, Shift-secondary, Q/Ctrl+Q, focus suppression, UNKNOWN/FAILED, and typed adjacent placement have explicit adjacent regressions.
- [ ] Resource/package tasks use real repository task names.
- [ ] Each Gate ends with implementation notes, independent review, `git diff --check`, and controller STOP.

## Task count and dependency order

- Gate 17A: Tasks 1-7.
- Gate 17B: Tasks 8-13; depends on controller approval of 17A.
- Gate 17C: Tasks 14-19; depends on controller approval of 17B.
- Gate 17D: Tasks 20-24; depends on controller approval of 17C.
- Total: 24 implementation Tasks. No Gate is automatically authorized by this document.
