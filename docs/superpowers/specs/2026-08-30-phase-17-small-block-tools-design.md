# Phase 17 Small-Block Tools and Building Design

Date: 2026-08-30

Status: controller-revised final design candidate; implementation is not authorized

Branch: `feat/small-voxel-tools`

Baseline: `4e45a6681f169e070500e4580c62fb37dc53d4ed`

## Purpose

Phase 17 turns the Phase 16 DETAIL core into deliberate coarse and precision
building interactions. It does not alter the canonical voxel representation,
world traversal, collision, mesh, persistence, or resource-budget authorities.

The design has four outcomes:

1. one deterministic interaction router chooses existing FULL interaction,
   coarse whole-parent DETAIL interaction, or precision DETAIL interaction;
2. a project-owned precision tool provides a read-only target and ghost preview;
3. Creative edits use one repository-authoritative mutation per logical action;
4. Survival edits conserve canonical integer `ItemStack` quantities without
   fractional metadata or provenance fields.

This document is a design specification, not an implementation plan.

## Audited baseline

### Repository and Phase 16

The primary checkout `D:\Game Design\GaiaLegacy` is on
`feat/small-voxel-tools`, based on `origin/main` at
`4e45a6681f169e070500e4580c62fb37dc53d4ed`, with zero divergence. All Phase 17
work remains in that primary checkout; no linked or phase-specific worktree is
permitted. The root repository guide is the only applicable `AGENTS.md`.

The following Phase 16 contracts remain frozen:

- `ChunkRepository` is the sole canonical Chunk, revision, dirty, invalidation,
  and unload-ticket authority.
- sparse DETAIL membership is the physical discriminator; a stored DETAIL
  parent has non-zero occupancy and an AIR backing FULL byte.
- `ParentCellState`, `ChunkSnapshot`, the nine-snapshot `ChunkMeshInput`, and
  separate `ChunkMeshingClaim` remain unchanged in authority.
- `GaiaDetailMutationService` delegates to `ChunkRepository`; it owns no state.
- `BlockRaycast` remains the only world DDA and DETAIL gaps pass through.
- `CollisionWorld` and `ChunkMeshManager` remain their respective authorities.
- streamed persistence continues to use the `detail-blocks` extension.
- hybrid output is capped at 8,388,608 bytes; the all-Chunk CPU mesh budget is
  134,217,728 bytes; accepted/active/upload/destroy/aggregate publication limits
  remain 32/2/2/4/2.

The Phase 16 handoff file still contains historical pre-merge wording near its
top even though the commit is present on `main`. Git is authoritative for the
merged baseline.

### Actual interaction and targeting APIs

- `PlayerBlockTargeting` obtains one `SpatialQueryResult<BlockHitResult>` from
  the existing raycast service.
- `BlockHitResult` carries canonical parent and adjacent coordinates, face,
  canonical world point, distance, material identity, owning Chunk revision,
  and a sealed `RaycastCellTarget`.
- a DETAIL target carries `VoxelScale.DETAIL_4` and one
  `LocalSubVoxelPosition`.
- `BlockInteractionController` is the current main-thread fixed-step input and
  interaction coordinator. It owns focus/mode cancellation and held-button
  suppression.
- the current controller rejects every DETAIL click as
  `detail_target_unsupported`; this is a safe Phase 16 placeholder, not Phase 17
  routing.
- normal placement checks typed `parentStateAt` before calling byte-only
  `blockAt`. A DETAIL adjacent parent is rejected without reading its backing
  AIR byte. This order is a permanent regression contract.
- Shift plus secondary click is reserved by `WorldInteractionInputRouter` for
  manual pickup. Q and Ctrl+Q are inventory-drop controls. F9-F12, 9, and 0 are
  development DETAIL controls.

### Actual mutation APIs

`DetailMutationService` currently exposes only:

- FULL to uniform DETAIL conversion;
- one subvoxel replacement in an expected parent state;
- compatible DETAIL to FULL compaction.

Every request carries canonical parent coordinates and expected Chunk revision;
subvoxel mutation also carries the exact expected parent state. The service has
no whole-parent DETAIL removal operation and no single operation that converts a
FULL parent and changes one resulting subvoxel in the same revision.

Therefore coarse removal must not be implemented with 64 `setSubVoxel` calls,
and precision sculpting of a FULL block must not publish a hidden intermediate
uniform DETAIL revision. Phase 17 needs narrow new variants on the existing
repository-authoritative mutation seam, not a new service authority:

- `RemoveDetailParent`: expected revision plus exact expected
  `DetailCellState`, atomically producing FULL AIR;
- `SculptParentSubVoxel`: expected revision plus exact expected FULL or DETAIL
  state and one local replacement, atomically applying the logical result. For
  non-AIR FULL removal it means “uniform 64-cell conversion, then change this
  cell” inside one repository transaction. For FULL AIR placement it creates a
  one-cell DETAIL parent. For DETAIL it is the existing CAS semantic.

Names are illustrative. The important contract is one repository CAS, one
revision, one dirty/invalidation outcome, and no externally visible dual state.

### Actual inventory and WorldItem APIs

- engine `ItemStack` is exactly `(ResourceLocation itemId, int count)`. It has
  no metadata, component payload, or material field.
- `ItemFormDefinition` provides ID, stack size, mouth eligibility, and two-hand
  status. It is currently nested in a block definition.
- `BlockRegistry` owns the sole item-form index, derived only from block
  definitions. `blockForItem` assumes a block-backed item.
- `BodyInventoryService` supports exact INSERT and EXTRACT reservations and
  applied-state-aware commit/rollback.
- `BodyInventoryReservationPlanner` can reserve a complete insertion across the
  three body slots in deterministic preferred-slot-first order.
- `WorldItemSpawnReservations` supports reserve, commit, and rollback around a
  canonical spawn. Existing FULL break uses this seam rather than inserting the
  drop directly into inventory.
- no crafting/recipe subsystem currently exists.

These facts rule out a generic detail-unit stack carrying arbitrary material
metadata. They also mean a standalone chisel cannot simply be fed through
`blockForItem` without extending the one existing item-form catalog.

Phase 17 freezes the conservation equivalence between a supported FULL block
and 64 matching detail units, but does not add a temporary crafting or
conversion subsystem. Normal Survival acquisition of the chisel and
player-facing FULL-to-units/units-to-FULL conversion belong to Phase 18.

### Actual presentation and resource APIs

- `GaiaResourceLoader` indexes only blocks, materials, atlases, and UI assets.
- item forms are parsed only from a block's optional `item` object.
- `GaiaVisualRegionResolver` and `GaiaWorldItemFaceResolver` map item IDs back
  through `BlockRegistry.blockForItem`.
- first-person held presentation already consumes immutable item face regions;
  it does not require the held item to remain a block if the game resolver can
  supply those regions.
- the production block atlas is `gaia:blocks`, described by
  `assets/gaia/atlases/blocks.json` and backed by
  `assets/gaia/textures/atlas.png`.
- the HUD already projects active item, interaction target/mode/failure, and
  bounded notices from immutable view models.

Canonical item identity, gameplay capability, and visual presentation must be
separate data. A standalone item form identifies and constrains the item; a
canonical item policy supplies capabilities such as `DETAIL_PRECISION`; an
immutable item visual reference selects its current presentation. Neither
capability nor transaction behavior may be inferred from `blockForItem`, cube
faces, atlas coordinates, or renderer geometry.

## Design alternatives

### Approach A: unified canonical interaction router (recommended)

Keep `BlockInteractionController` as the one fixed-step owner. Add a pure,
injected route resolver and narrow DETAIL transaction collaborators. The
controller performs one targeting observation, resolves one action route, and
dispatches exactly one transaction or read-only preview calculation.

The route resolver receives:

- game mode;
- canonical active item/tool capability;
- the typed `BlockHitResult.target`;
- primary/secondary edge state and pickup consumption state.

It returns a closed route such as FULL_NORMAL, DETAIL_COARSE_REMOVE,
DETAIL_PRECISION_REMOVE, DETAIL_PRECISION_PLACE, or REJECTED. It does not mutate
the world.

Benefits:

- one input/focus/replay authority;
- one world raycast observation per update;
- typed FULL/DETAIL routing before any legacy byte read;
- transaction outcomes and HUD state share one view model;
- stale observations are uniformly rejected by repository CAS;
- Phase 18 can replace policy decisions without replacing transactions.

Cost:

- the existing controller gains collaborators and needs careful decomposition;
- the one item-form index must be extended to standalone items;
- two narrow repository mutation variants are needed.

### Approach B: a separate detail-mode controller before block interaction

Add an independent `DetailToolController` that consumes mouse input when the
chisel is active; allow the current block controller to run otherwise. Coarse
DETAIL break would either be forwarded back to the block controller or handled
by the detail controller.

Benefits:

- less immediate change to the existing FULL controller;
- precision code is physically grouped.

Costs and risks:

- two coordinators must agree on focus cancellation, held-button suppression,
  pickup consumption, active item, failure display, and target lifetime;
- coarse DETAIL semantics have ambiguous ownership;
- ordering mistakes can cause both controllers to act on one click;
- a separate preview/target state can drift from the normal interaction view;
- rapid-edit and stale-revision handling becomes harder to prove.

This approach is not recommended because its smaller local diff creates a
second practical interaction authority.

### Rejected broad alternative: declarative rewrite of all interactions

A new universal command/transaction framework could replace break, placement,
pickup, and DETAIL actions. It would be internally elegant but is a Phase 9-15
interaction rewrite, expands risk to stable FULL behavior, and is unnecessary
for Phase 17.

## Recommended ownership and data flow

```text
InputSnapshot + game mode + active canonical item
                    |
                    v
       BlockInteractionController (sole fixed-step owner)
                    |
           one PlayerBlockTargeting query
                    |
                    v
        CanonicalInteractionRouteResolver (pure)
          /                 |                  \
 FULL_NORMAL      DETAIL_COARSE       DETAIL_PRECISION
 existing tx      coarse parent tx     preview / detail tx
          \                 |                  /
                    v
         ChunkRepository mutation authority
                    |
       revision + dirty + neighbor invalidation
                    |
          existing mesh/save/streaming lifecycle
```

Inventory and WorldItem reservations are capabilities held by a transaction,
not mutation authorities:

```text
reserve guaranteed material output/input
                 |
                 v
repository CAS mutation --rejected--> rollback reservation
                 |
               applied
                 |
commit guaranteed reservation; interpret applied-state notification failures
```

## Q1: deterministic interaction routing

The sole routing authority is a pure route resolver owned and called by
`BlockInteractionController` after the one typed target observation.

| Active context | Typed target | Primary | Secondary |
| --- | --- | --- | --- |
| ordinary item/no precision capability | FULL | existing FULL break | existing FULL placement |
| ordinary item/no precision capability | DETAIL | coarse whole-parent break | explicit unsupported; never legacy placement through DETAIL |
| `gaia:chisel` precision capability | FULL | precision sculpt of selected surface quarter | precision placement in adjacent quarter if valid |
| `gaia:chisel` precision capability | DETAIL | remove exact hit subvoxel | place against exact hit face |
| any | UNKNOWN/FAILED/no hit | typed unavailable/no target | typed unavailable/no target |

Shift-secondary remains pickup and is removed by the existing input router
before block/detail routing. No route may call raw `blockAt` until typed state is
known to be FULL.

Creative precision mode is represented by selecting the same canonical
`gaia:chisel` item form in the Creative selection, not by an unrelated hidden
boolean. Survival requires that item in the active slot. Thus both modes feed
the same capability check.

## Q2: precision targeting and placement

Phase 17 consumes `BlockHitResult`; it never casts another world ray.

### DETAIL hit

Removal uses the canonical parent, local `[x,y,z]`, material, face, world point,
distance, and Chunk revision already present in the Phase 16 hit. The transaction
re-observes the typed parent and submits the exact expected state and revision.

Placement adds the hit-face unit vector to the local coordinate. A coordinate in
`[0,3]` remains in the same parent. A coordinate crossing an edge wraps to 0 or
3 and moves the canonical parent by one. The destination parent is observed
through the typed parent observation seam:

- DETAIL plus empty destination subvoxel: candidate valid;
- DETAIL plus occupied destination subvoxel: occupied rejection;
- FULL AIR: candidate is an atomic first-cell DETAIL placement;
- non-AIR FULL: occupied rejection;
- UNKNOWN or FAILED: preserve typed unavailability.

### FULL hit

The ray hit remains a FULL hit; Phase 17 performs only a bounded face-local
quarter refinement. The face fixes the normal-axis quarter (0 or 3), while each
tangential coordinate uses `floor(4 * parentFraction)` clamped to `[0,3]`.
Exact internal quarter planes select the higher tangential interval; an exact
outer parent edge clamps to the final interval. This rule is deterministic and
must be implemented as a shared pure helper using the Phase 16 coordinate/tie
conventions.

Primary precision removal submits atomic `SculptParentSubVoxel`, so a solid FULL
parent becomes DETAIL with the selected surface quarter empty in one revision.
Primary on FULL AIR is invalid. Secondary placement goes to the adjacent parent
and quarter; it never replaces the hit FULL block.

### Empty DETAIL gap

There is no synthetic gap target. Phase 16 continues the same DDA and returns a
later actual hit or typed UNKNOWN/FAILED. Preview and edit attach to that later
hit only.

## Read-only placement preview

`DetailPlacementPreview` is an immutable game-layer view containing:

- action and precision tool identity;
- source hit identity and observed Chunk revision;
- candidate canonical parent and local coordinate;
- face and canonical material;
- validity enum and bounded reason;
- optional candidate quarter AABB for rendering.

The preview is computed from the hit plus typed immutable observations. It holds
no repository, mutation, collision, or mesh capability. It is cleared when the
target, tool/mode, selected material, focus/cursor eligibility, load lifecycle,
or observation revision changes.

The ghost is a render-only transient submitted through the existing interaction
feedback/render path on the owner thread. It does not enter `ChunkSnapshot`,
collision, raycast, persistence, or dirty tracking. It is bounded to one current
candidate and uses no history.

## Q3: whole-parent DETAIL break

The current API cannot express the operation atomically. The accepted design is
one new `RemoveDetailParent` mutation variant under the existing
`DetailMutationService`/`GaiaDetailMutationService`/`ChunkRepository` chain.

The request contains canonical parent coordinates, expected positive Chunk
revision, and exact expected `DetailCellState`. Repository validation and commit
produce FULL AIR in one logical mutation. Rejection produces no revision, dirty
state, neighbor invalidation, inventory/WorldItem side effect, or partial clear.

The coarse gameplay transaction derives any output before mutation, reserves
that output through the existing WorldItem spawn reservation when present,
commits the one parent mutation, and then commits the guaranteed spawn. It uses
the same applied-state and indeterminate-failure handling as existing FULL break.

## Q4: material-unit identity

### Option A: one detail-unit item form per supported material

Examples for the intentionally small initial set:

- `gaia:stone_detail_unit` maps to `gaia:stone`;
- `gaia:dirt_detail_unit` maps to `gaia:dirt`.

Counts remain ordinary integer `ItemStack` counts. Each form has max stack 64,
so one stack exactly represents one full block's 64 units.

### Option B: one generic item carrying material identity

This requires stack metadata that does not exist. Adding it would affect engine
inventory identity, equality, save codecs, reservations, WorldItems, HUD, and
stacking. It is not a narrow Phase 17 choice.

### Recommendation

Use Option A. Extend the existing single item-form index rather than adding a
second registry:

- resource indexes may declare standalone `items` definitions;
- `GaiaResourceLoader` resolves block-backed and standalone forms together;
- `BlockRegistry` remains the existing ownership slot and gains the narrowest
  construction/resolution extension needed for one collision-checked
  `itemsById` map containing both kinds of form;
- canonical item-form resolution by item ID works for either kind;
- `blockForItem` remains optional and succeeds only for block-backed forms;
- strict block-only APIs remain unchanged where their block requirement is
  semantically correct; unrelated callers are not forced through a broad
  Optional migration;
- a block's optional detail-support definition maps its canonical block ID to
  one standalone detail-unit item ID;
- each standalone item definition contains an explicit immutable visual
  reference, and visual resolution uses that reference instead of requiring
  `blockForItem` to succeed;
- the chisel's `DETAIL_PRECISION` capability comes from canonical item/resource
  policy, never from a separate hidden mode boolean or its visual type.

No second item registry, fractional `ItemStack`, or game identity enters engine
voxel storage.

The initial supported materials are stone and dirt. Additional materials are
ordinary data additions after their item forms, visual references, and recovery
policy are reviewed.

## Conservation equivalence and Phase 18 conversion

Phase 17 freezes the exact identity relation:

```text
1 supported FULL block == 64 matching detail units
```

Phase 17 implements only one-unit recovery through approved precision removal
and one-unit consumption through precision placement. It does not implement a
`DetailUnitConversionTransaction`, crafting substitute, or player-facing
FULL-to-64/64-to-FULL action. Phase 18's crafting-lite/fabrication work owns that
transaction and must reuse these canonical item identities and exact ratio.

Phase 17 automated and manual Survival acceptance may provision the canonical
chisel and detail units through explicit approved test/debug setup. New Survival
players are not automatically granted a chisel, and Phase 17 defines no normal
acquisition recipe. Phase 18 owns primitive chisel acquisition and progression.

## Q5: inventory-full precision recovery

Choose policy A: reject before world mutation.

For a Survival precision removal whose policy yields one detail unit, the
transaction first reserves complete insertion of exactly one unit using
`BodyInventoryReservationPlanner`. If the remainder is non-empty, all partial
reservations roll back and the voxel remains unchanged.

This policy gives clear conservation, produces no micro-item spam, and reuses
the existing inventory guarantee. Reserving a WorldItem spawn remains available
for coarse full-block output, where it already matches existing behavior, but is
not the v1 fallback for precision chiseling.

## Q6: coarse break matrix

“Recovery-capable” below means the injected harvest policy returns a legal
output for the current mode/action/material. Phase 17's minimal policy preserves
ordinary FULL behavior and recognizes the Phase 17 chisel for precision work;
tool category/tier progression remains Phase 18.

| Parent composition | Creative coarse | Survival coarse with legal recovery | Survival destructive/non-recovery |
| --- | --- | --- | --- |
| FULL | existing whole block removal, no output | existing FULL rule; one canonical full-block WorldItem when currently allowed | remove with no output only when the injected policy explicitly allows it |
| DETAIL uniform 64/64 and FULL-compatible | atomic parent removal, no output | exactly one canonical full-block WorldItem | atomic removal, no output |
| DETAIL partial, one material | atomic parent removal, no output | atomic removal, no detail units | atomic removal, no output |
| DETAIL mixed material | atomic parent removal, no output | atomic removal, no detail units | atomic removal, no output |

No coarse action emits 1-64 detail-unit WorldItems. Unsupported/unbreakable
policy results do not mutate. Uniform compatibility requires all 64 occupied,
identical canonical material and no metadata loss, matching the Phase 16
compaction compatibility rule.

### Coarse DETAIL break progression

Creative coarse DETAIL removal is instant, matching existing Creative FULL
behavior.

Survival coarse DETAIL break derives one effective parent hardness by scanning
the occupied cells in deterministic subvoxel index order, resolving every
runtime block ID through the existing `BlockRegistry`, and taking the maximum
canonical `BlockDefinition.hardness()` among occupied cells. Occupancy fraction
does not scale hardness. Thus 13 dirt cells use dirt hardness, while 12 dirt plus
one harder stone cell use stone hardness even though the parent is partial.

The existing API expresses this narrowly: `BlockDefinition` already owns finite
non-negative canonical hardness, `BlockInteractionPolicy` maps hardness and the
existing positive base break speed to `BreakRule.requiredSeconds`, and
`BlockBreakTracker` advances that rule under the existing fixed-step lifecycle.
Phase 17 adds only the pure DETAIL composition aggregation before asking the
existing policy/tracker to progress; it adds no hardness authority.

Every occupied runtime ID must resolve to a canonical block definition. An
unknown or unsupported material fails closed as a typed unsupported/unbreakable
route and never contributes zero hardness. Phase 18 may add tool/tier/speed
multipliers after aggregation, but it must retain maximum occupied-material
hardness as the canonical coarse composition rule.

## Creative transaction model

- precision removal and placement use the atomic parent/subvoxel mutation seam;
- selected detail material must be an explicitly supported canonical block
  material;
- no inventory reservation or gameplay output occurs;
- coarse DETAIL removal uses one parent mutation and no output;
- overlap validation uses the candidate quarter AABB and existing player body
  semantics before placement;
- committed particles/sound occur only after APPLIED; preview never triggers
  committed feedback.

Automatic DETAIL-to-FULL compaction is not added. An explicit compact action is
also deferred unless controller approval later adds a clear control and UX
reason. Uniform DETAIL remains valid canonical DETAIL.

## Survival transaction model

### Precision remove with recovery

1. observe exact hit, typed DETAIL state, revision, material, and action policy;
2. reserve complete insertion of one matching detail-unit item;
3. submit one repository-authoritative removal CAS;
4. on rejection, roll back all insertion reservations;
5. on APPLIED, commit every guaranteed insertion reservation;
6. audit `occupied decrease 1 == unit increase 1` and emit committed feedback.

### Precision destructive remove

Only an explicit policy result may authorize it. Submit one CAS, produce no
item, and report the destructive outcome. Unsupported tools do not silently
default to destruction.

### Precision place

1. resolve selected supported material and exact unit item;
2. reserve EXTRACT of one unit from a deterministic slot search;
3. validate candidate typed state, player overlap, expected revision/state;
4. submit one repository-authoritative placement CAS;
5. on rejection, roll back extraction;
6. on APPLIED, commit extraction and audit `unit decrease 1 == occupied increase 1`.

### Coarse parent break

Derive the matrix result, reserve its single WorldItem output if any, mutate the
parent once, and commit/rollback the spawn using existing break-transaction
semantics.

No transaction removes inventory first and hopes a mutation succeeds. A
notification exception whose state change is already applied is reconciled as
applied, not duplicated by retry.

## Q7: rapid edits and backpressure

V1 uses pressed-edge precision actions. One fixed-step route decision chooses at
most one logical world mutation; deterministic route precedence prevents
simultaneous primary/secondary edges from producing two mutations. There is no
gameplay edit queue.

- every click carries the hit's revision and exact expected parent state;
- if revision N is remeshing, an N+1 canonical edit may still commit normally;
- existing mesh claim validation rejects stale N output;
- mesh admission/backpressure remains entirely in `ChunkMeshManager`;
- a stale click is rejected and shown once; the controller does not retry it;
- targeting/preview refresh on the next fixed update supplies current identity;
- committed feedback is keyed to APPLIED outcomes only.

This bounds gameplay work while allowing canonical edits to advance independently
of presentation. If measurement later requires held-button repetition, it needs
an explicit fixed-step cooldown and remains subject to the same no-queue rule.

## Q8: Phase 18 seam

Phase 17 defines a pure game-layer `DetailActionPolicy` seam. Inputs are mode,
action, canonical tool item, canonical material/block definition, and parent
composition. Its output states allowed/rejected and one recovery kind:

- NONE;
- DETAIL_UNIT;
- FULL_BLOCK.

Phase 17 supplies only the minimum rules needed for `gaia:chisel`, Creative
editing, supported materials, and the coarse matrix. Phase 18 may add tool
category, harvest tier, material tags, break-speed multipliers, independently
approved durability, primitive chisel acquisition, FULL-block/64-unit
crafting-lite conversion, and progression by replacing/enriching the policy and
fabrication composition. It must not replace target provenance, route authority,
repository CAS, inventory/WorldItem reservation transactions, or canonical
detail-unit identities. The maximum occupied-material hardness aggregation runs
before any future tool multiplier and remains the coarse composition rule.

## Q9: Phase 19 seam

Generated and player-authored DETAIL with identical canonical material use the
same targeting, policy, transaction, and recovery path. No generated/player
provenance field or side store is added. Phase 19 base generation writes the
same `ParentCellState` through its deterministic generation boundary and obeys
its own generated-detail budget; Phase 17 behavior depends only on current
canonical composition and policy.

## Precision tool identity and resources

Approved project-owned v1 tool:

| Property | Design |
| --- | --- |
| canonical ID | `gaia:chisel` |
| item definition | `game/src/main/resources/assets/gaia/items/chisel.json` (new standalone-item convention) |
| gameplay capability | `DETAIL_PRECISION`; no tier or harvest taxonomy yet |
| stack/slots | max stack 1, not mouth-holdable, one-handed |
| v1 visual type | immutable `ATLAS_REGION` reference |
| held presentation | existing `InteractionFeedbackCoordinator` -> `FirstPersonItemVisual`; game resolver adapts the current visual reference |
| texture region | `gaia:chisel` |
| source/runtime texture | project-owned 16x16 region added to `game/src/main/resources/assets/gaia/textures/atlas.png` |
| atlas metadata | region entry in `game/src/main/resources/assets/gaia/atlases/blocks.json`, atlas `gaia:blocks` |
| packaging | existing classpath resource index and packaged-resource verification |
| provenance | original GaiaLegacy asset created for Phase 17; document author/date; no external art |

Using the existing atlas avoids a new renderer, atlas upload, or material
registry. The resource index requires a new optional `items` list and strict
loader validation. A v1 standalone definition carries an explicit immutable
visual reference conceptually containing `type = ATLAS_REGION`, atlas identity,
and region identity. The exact JSON field names may follow loader conventions.
Visual references are presentation data, not item or material identity.

The two detail-unit items reuse existing representative material regions rather
than adding bespoke artwork:

| Item | Canonical material mapping | V1 visual reference |
| --- | --- | --- |
| `gaia:stone_detail_unit` | `gaia:stone` | `ATLAS_REGION gaia:blocks / gaia:stone` |
| `gaia:dirt_detail_unit` | `gaia:dirt` | `ATLAS_REGION gaia:blocks / gaia:dirt` |

This explicit mapping supports HUD, held-item, and WorldItem presentation
without requiring `blockForItem()` or reverse-engineering material identity from
texture coordinates. Block-backed items retain their existing face behavior.

The chisel can use the existing held cube/atlas presentation as a Phase 17 v1
placeholder. Its canonical `gaia:chisel` identity and `DETAIL_PRECISION`
capability must not depend on cube geometry, six faces, or atlas rendering. A
future resource version may replace only the visual reference with a
`3D_MODEL`/GLB reference while preserving ItemStack identity, capability,
transactions, harvest behavior, and saves.

Future GaiaLegacy asset work may use Blender- or Blockbench-authored GLB models
for tools and characters. Phase 17 defines only this replaceable presentation
seam; it does not design or implement a GLB loader, model editor, skeletal
animation, or custom 3D tool renderer. Phase 18 variants can declare additional
item forms and capabilities without changing the precision route.

## Input and material selection proposal

- active `gaia:chisel`: primary pressed removes one quarter; secondary pressed
  places one quarter;
- Shift-secondary remains manual pickup and therefore never reaches precision
  placement;
- R is the provisional v1 binding that cycles selected supported DETAIL material
  while the chisel is active; the current audit found R unassigned, but this is
  replaceable/configurable input policy rather than an architecture contract;
- Creative selection can select the registered chisel and remembers a supported
  detail material independently of the current tool item;
- Survival placement selects only a supported material for which a matching
  unit exists; extraction still revalidates actual inventory at commit time;
- Q, Ctrl+Q, F9-F12, 9, 0, mouse capture, HUD, mode, and slot controls remain
  unchanged.

Material selection state is bounded to one canonical material ID and is not
world authority, Chunk state, ItemStack metadata, or save-format world state.
On focus loss, menu/blocking UI, mode/tool change, or load transition, the
existing input lifecycle suppresses held/pressed state and clears both material
cycle intent and preview. The binding may not conflict with pickup, Q, Ctrl+Q,
debug controls, normal placement, or slot controls.

## HUD and feedback

Extend the existing immutable interaction/HUD projection with:

- active precision/coarse mode;
- selected detail material and available unit count;
- target local `[x,y,z]` when DETAIL precision is active;
- valid/invalid preview reason;
- one bounded stale/inventory-full/unsupported-material failure notice.

No backpack or palette browser is added. Existing particles and action animation
may receive a bounded DETAIL-specific committed event using the selected atlas
region and canonical quarter position. Sound is added only if an existing
bounded sound event seam is present when Gate 17D begins; otherwise it is
deferred rather than creating an audio system.

## Save and streaming behavior

Tool selection and transient preview are presentation/session state and do not
enter Chunk persistence. Canonical edits use the existing Chunk revision/dirty
path and streamed `detail-blocks` codec. Item stacks and WorldItems use their
existing save sections.

Save, unload, and reload cannot observe a partially converted parent because
each logical voxel action is one repository mutation. Inventory and WorldItem
transactions retain their existing applied-state semantics. No new save root,
DETAIL registry, or background worker authority is introduced.

## Gate decomposition

The supplied four gates remain the best decomposition with one explicit boundary
clarification:

### 17A: canonical route, target, input, preview, and item identity seam

Read-only world behavior. Establish the one route resolver, standalone item-form
loading/index extension, `gaia:chisel` identity, precision target refinement,
immutable preview, input lifecycle, and ghost presentation. It must not mutate
world or inventory. Including the item identity seam here is necessary because
“tool active” cannot be truthfully tested with the current block-only catalog.

### 17B: Creative editing and atomic parent operations

Add the two narrow repository-authoritative mutation variants, coarse whole-parent
DETAIL removal, FULL sculpt/first-cell placement, Creative transactions, overlap
validation, and committed feedback. No material accounting.

### 17C: Survival material conservation

Add stone/dirt detail-unit definitions, precision INSERT/EXTRACT reservation
transactions, coarse harvest/drop matrix, maximum-hardness aggregation,
WorldItem integration for the single legal coarse full-block output, and exact
conservation tests. It does not add FULL-block/64-unit crafting conversion or
normal Survival chisel acquisition; both are Phase 18 work.

### 17D: bounded UX and runtime acceptance

Finish HUD notices, held visual, bounded particles/sound if supported, save and
streaming acceptance, rapid-edit measurements, and Windows/macOS evidence.

The gates are not silently rearranged. The only adjustment is making item/tool
identity an explicit prerequisite inside 17A rather than discovering it during
Survival work.

## Runtime acceptance

Phase 17 directly supported acceptance must cover:

- normal FULL break/place unchanged, including typed adjacent placement safety;
- normal primary on DETAIL performs one coarse parent action;
- precision FULL sculpt, DETAIL remove, face placement, cross-parent and
  cross-Chunk placement, gap passthrough, negative coordinates, and stale hit;
- preview clearing on focus, tool, mode, target, and load changes;
- Creative stairs/notches/openings and no outputs;
- Survival one-unit recovery and placement, full inventory rejection, failed
  mutation rollback, notification-applied handling, and save/load;
- uniform coarse output of one full item; partial/mixed coarse output of none;
- Creative instant coarse break and Survival maximum-occupied-hardness coarse
  break progression without occupancy scaling;
- generated/player-identical policy behavior via equivalent fixtures;
- collision, mesh, raycast, save/unload/reload after edits;
- rapid edge clicks under mesh backpressure without a second queue;
- Windows input/focus/resize and native Apple Silicon status reported truthfully.

Because normal Survival acquisition and fabrication are deferred, automated and
manual Survival acceptance provisions `gaia:chisel`,
`gaia:stone_detail_unit`, and `gaia:dirt_detail_unit` through approved bounded
test/debug setup. Acceptance must not claim Phase 17 provides a recipe,
crafting UI, automatic chisel gift, or player-facing FULL/64 conversion.

## Risks and stop conditions

Stop for controller review if implementation would require:

- metadata/fractional material semantics inside `ItemStack`;
- a second item, raycast, mutation, dirty, mesh, save, or interaction authority;
- 64 sequential mutations for one coarse removal;
- a visible intermediate FULL-to-DETAIL state for one precision action;
- preview mutation, collision, raycast, persistence, or dirty authority;
- material loss when precision recovery inventory is full;
- hidden generated/player provenance;
- changes to Phase 16 mesh/resource limits;
- a generic transaction framework rewrite;
- making canonical item identity or gameplay capability depend on cube/atlas
  presentation;
- a second standalone-item registry or a broad `BlockRegistry` rewrite solely
  to host standalone items;
- a temporary Phase 17 crafting/conversion subsystem or automatic Survival
  chisel gifting;
- occupancy-fraction hardness scaling, unknown materials becoming zero
  hardness, or a new hardness authority;
- a GLB/model/animation pipeline expansion;
- Phase 18 tool tier, progression, or acquisition implementation;
- custom tool rendering that requires a broad renderer or shader redesign;
- unacceptable resource complexity from the small standalone item set.

## Design self-review

- Authority duplication: one fixed-step controller, one raycast, one repository
  CAS path, one item-form index, existing inventory/WorldItem reservations.
- Stale assumptions corrected: no generic stack metadata, no crafting system,
  no whole-parent DETAIL mutation, no standalone item loader, and current DETAIL
  clicks are explicitly unsupported.
- Boundedness: one preview, one action attempt per edge/fixed update, no edit
  queue, at most one precision recovery unit, and at most one coarse WorldItem.
- Frozen contracts: typed placement ordering, Phase 16 representation,
  persistence, mesh lifecycle, and all byte/count/GPU limits remain unchanged.
- Scope: no Phase 17 fabrication substitute or normal Survival chisel
  acquisition, no Phase 18 tiers/progression, no Phase 19 worldgen, no survival
  backpack, no provenance, no 3D asset pipeline, and no production
  implementation in this gate.
