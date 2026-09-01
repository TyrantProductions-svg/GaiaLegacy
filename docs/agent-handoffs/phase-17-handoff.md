# Phase 17 handoff - small-block tools and building

## Status

Gates 17A-D are controller-accepted. Phase 17 implementation, automated
verification, Windows interactive acceptance, resource/package verification,
and independent review are complete. Native Apple Silicon interactive remains
`NOT RUN`. Phase 17 is ready for the separately authorized integration
checkpoint and is not yet committed, pushed, opened as a PR, merged, tagged,
or released.

## Completed architecture

- one existing `BlockInteractionController` and pure canonical route resolver;
- standalone `gaia:chisel`, `gaia:stone_detail_unit`, and
  `gaia:dirt_detail_unit` in the existing item ownership slot;
- one Phase 16 raycast plus bounded FULL face-local refinement;
- immutable one-current preview and existing ghost renderer path;
- atomic `RemoveDetailParent` and `SculptParentSubVoxel` CAS operations;
- Creative precision/coarse editing with no inventory output;
- Survival one-unit reserve-before-mutate conservation;
- uniform 64/64 coarse one-FULL-block output; partial/mixed destructive output;
- maximum-material coarse hardness through the existing break tracker;
- bounded HUD, APPLIED-only particles, generic save identity, and rapid-edit
  measurement fixture.

## Frozen resource contracts

- hybrid output cap: 8,388,608 bytes;
- all-Chunk CPU mesh lifecycle budget: 134,217,728 bytes;
- mesh accepted/active: 32/2;
- upload/destruction: 2/4 per owner frame;
- aggregate publication/upload: 2 per owner frame;
- exact-sized builder retained; ownership-transfer Steps B/C remain not needed.

Phase 16's accepted `-Xmx512m` mixed stress peaked at 334,315,448 bytes
(62.27%), with four runs spanning 58.00%-62.27%. Phase 17 does not retune it.

## Persistence and conservation

Voxel state continues through Chunk revision/dirty tracking and `detail-blocks`.
Standalone item stacks and coarse WorldItems use existing generic
`ResourceLocation`/integer-count codecs. Preview and material-cycle intent are
not serialized. Automated production-session and unload/return integration
tests verify exact state reconstruction.

## Final local automated verification

Focused Gate 17D HUD, feedback, persistence/streaming, rapid-edit, and mesh
budget regressions are GREEN. Fresh proportional results are engine 658/658,
game 842/842, an independent save/streaming matrix 642/642, and tools 33 passed
/ 1 skipped. Review-driven additions further prove production-composed
Survival precision recovery, coarse WorldItem recovery, restored raycast,
collision and installed mesh presentation, plus real mesh lifecycle/backpressure
metrics with current completions distinguished from stale results. Packaged
resources, packaged shaders, installed shaders/audio, and `installDist` are
GREEN. The broad save/streaming matrix completed in 10h16m45s, including the
pre-existing deliberately heavy Phase 14 migration and 1,024-owner staging
cases.

The fresh post-review repository-wide wrapper
`gradlew.bat clean test build --console=plain --no-daemon` completed with exit
0 in 5h38m6s (31/31 tasks): engine 1505/1505, game 2275/2275, and tools 34
passed / 1 skipped, with zero failures/errors. That run also completed packaged
resources, packaged/installed shader and audio checks, deterministic generated
UI asset verification, `installDist`, and all module build tasks.

An initial full run reached a genuine deterministic resource RED only after all
tests and packaging: the checked-in Phase 17 chisel atlas cell was not generated
by `BlockIconGenerator`. The TDD fix extended the existing generator through
canonical standalone `itemVisual` resolution. It introduced no second item
registry and no block-backing assumption. The corrected atlas and metadata are
deterministic and the complete wrapper rerun is GREEN.

Final independent review after the persistence and mesh-measurement corrections
reported 0 Critical, 0 Important, and 0 Minor findings.

## Platform status

- Windows interactive: `PASS`. The controller-accepted manual production
  GLFW/OpenGL matrix verified the chisel placeholder and readable HUD/tool
  state; Creative FULL sculpt, DETAIL remove/place, whole-parent coarse remove,
  rapid edits, and ghost refresh; Survival one-unit recovery/consumption,
  inventory-full rejection, uniform 64/64 one-FULL-block output, and
  partial/mixed destructive behavior; real unload/return with restored shape,
  material, raycast, collision, and mesh; Save & Quit plus fresh-process
  restore of DETAIL and item identities/counts; Alt+Tab without input replay;
  resize alignment; ordinary FULL interaction, Q/Ctrl+Q, pickup, pause/resume;
  and clean exit.
- Native Apple Silicon interactive: `NOT RUN`.

## Known risks and intentional limits

- chisel visual is an `ACCEPTED PLACEHOLDER`; this is not a gameplay bug, and
  `gaia:chisel` plus `DETAIL_PRECISION` remain presentation-independent;
- the future visual replacement is Phase 18's atlas placeholder ->
  Blender-authored low-poly tool -> GLB runtime asset path, without changing
  canonical item/save identity;
- gameplay chisel SFX is `DEFERRED` because no suitable bounded committed
  gameplay-SFX seam exists;
- partial/mixed coarse removal intentionally returns no micro units;
- full repository verification is intentionally expensive (about 5h38m), and
  the broad save/streaming matrix exceeds ten hours because of existing heavy
  migration, staging, and streaming scenarios; this is not a Phase 17
  correctness regression. Future engineering may audit Gradle tiers such as
  fast, integration, heavy, and release without deleting or weakening heavy
  release coverage;
- complexity failure can preserve a last-known-good visual mesh while current
  canonical raycast/collision reflect the failed revision.

## Phase 18 contract

Phase 18 owns normal chisel acquisition, crafting-lite/fabrication, FULL block
to/from 64 units, tool/harvest tiers, progression, and balance. Planned asset
pipeline (documentation only): audit/select Blender MCP, install its add-on and
server, configure a localhost-only Codex connection, create a low-poly draft,
perform human art review/adjustment, preserve the `.blend` source, export GLB,
integrate Gaia's runtime presentation, inspect it in Gaia Model Inspector,
human-tune position/rotation/scale/held transforms, then apply the approved
presentation configuration.

## Phase 19 contract

Generated DETAIL uses the same `ParentCellState`, mutation/recovery semantics,
raycast/collision/mesh/persistence systems, no generated/player provenance, and
a future generation-specific density budget.

## Interfaces future phases must not break

- `ChunkRepository` remains canonical revision/dirty authority;
- FULL/DETAIL exclusivity and no empty DETAIL;
- exactly nine snapshots in `ChunkMeshInput`, separate `ChunkMeshingClaim`;
- one raycast, collision authority, mesh manager, item ownership slot, and
  interaction controller;
- typed adjacent placement before byte-only FULL material access;
- ItemStack remains canonical item ID plus integer count;
- reserve-before-mutate and applied-state reconciliation conservation rules.
