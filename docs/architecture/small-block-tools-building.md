# Small-block tools and building architecture

## Scope

Phase 17 turns the Phase 16 `4x4x4` DETAIL representation into an explicit
coarse/precision interaction. It adds no voxel, raycast, collision, mesh,
inventory-value, save-root, or GPU authority. `ChunkRepository`,
`BlockRaycast`, `CollisionWorld`, `ChunkMeshManager`, the body inventory, and
the canonical WorldItem service remain the owners of their respective state.

The Phase 17 chisel visual is intentionally a temporary atlas presentation.
The canonical `gaia:chisel` identity and `DETAIL_PRECISION` capability do not
depend on that presentation. Phase 18 owns the first planned 3D tool pipeline.

## Canonical items

| Item | Stack | Capability | Current visual |
| --- | ---: | --- | --- |
| `gaia:chisel` | 1 | `DETAIL_PRECISION` | project-owned atlas region |
| `gaia:stone_detail_unit` | 64 | none | explicit stone atlas region |
| `gaia:dirt_detail_unit` | 64 | none | explicit dirt atlas region |

All three occupy the existing single item-form ownership slot. `ItemStack`
remains `(ResourceLocation, integer count)`: there is no metadata, fractional
quantity, or second item registry.

## Routing and targeting

`BlockInteractionController` remains the only fixed-step interaction owner. A
pure `CanonicalBlockInteractionRouteResolver` maps one canonical raycast result,
active item capability, game mode, and one pressed edge to one of:

- `FULL_NORMAL`;
- `DETAIL_COARSE_REMOVE`;
- `DETAIL_PRECISION_REMOVE`;
- `DETAIL_PRECISION_PLACE`;
- typed rejected/unavailable state.

The existing `BlockRaycast` is reused. DETAIL provenance supplies the exact
parent, quarter coordinate, face, material, and observed Chunk revision. FULL
precision targeting performs a bounded face-local quarter refinement, not a
second traversal. Empty DETAIL quarters remain raycast gaps.

Right-click placement wraps canonical quarter coordinates across parent and
Chunk boundaries, then observes the destination as typed FULL/DETAIL state.
UNKNOWN and FAILED are never treated as AIR.

## Mutation transactions

Every committed action revalidates the observed revision and exact parent
state. No action retries automatically.

- `RemoveDetailParent` performs one compare-and-set publication from DETAIL to
  FULL AIR. It never executes 64 subvoxel mutations.
- `SculptParentSubVoxel` publishes the final parent state in one revision:
  FULL solid to 63-cell DETAIL, FULL AIR to one-cell DETAIL, DETAIL to DETAIL,
  or the last-cell removal to FULL AIR.
- Uniform 64/64 DETAIL remains DETAIL; Phase 17 adds no automatic compaction.

The game adapter translates material identity only. Revision, dirty state,
neighbor invalidation, persistence, and stale semantics remain repository
owned.

## Creative and Survival matrix

| Parent/action | Creative | Survival |
| --- | --- | --- |
| FULL normal | existing FULL behavior | existing FULL behavior |
| DETAIL coarse | immediate whole-parent removal, no output | existing hardness timing; output policy below |
| precision remove | exact quarter, no item output | reserve one matching-unit INSERT before CAS |
| precision place | exact quarter, unlimited material | reserve one matching-unit EXTRACT before CAS |

Survival coarse output:

| DETAIL composition | Output |
| --- | --- |
| uniform 64/64 supported material | exactly one canonical FULL-block WorldItem |
| partial single material | none; intentionally destructive |
| mixed material | none; intentionally destructive |

Coarse hardness is the maximum `BlockDefinition.hardness()` over occupied
materials. Occupancy fraction never lowers it. Unknown materials fail closed.
The result feeds the existing `BlockInteractionPolicy`, `BreakRule`, and
`BlockBreakTracker`.

## Conservation and failure ordering

Precision removal reserves a guaranteed inventory INSERT before the repository
CAS; inventory-full rejects before world mutation. Precision placement reserves
one matching-unit EXTRACT before the CAS. Rejected/stale mutations roll the
reservation back; APPLIED mutations finish the already-guaranteed inventory
commit exactly once. Applied-state notification failures preserve the canonical
outcome and reconcile the reserved inventory effect.

Uniform coarse recovery reserves one WorldItem spawn before whole-parent
removal. Partial/mixed coarse removal intentionally returns no micro items. No
coarse path creates 1-64 detail-unit entities.

Generated and player-placed equal materials use the same path. No provenance
field exists for recovery.

## HUD, preview, and feedback

The immutable HUD projection contains only current state: DETAIL mode, selected
material, Survival matching-unit count, optional local `[x,y,z]`, preview
validity/reason, and one current bounded failure. Creative omits inventory
requirements. Focus loss, blocking UI, and load transitions clear transient
preview/HUD state.

There is one current ghost at most. It is presentation-only and cannot mutate,
collide, raycast, dirty, save, reserve inventory, or own a GPU lifecycle.
Committed quarter remove/place feedback enters the existing bounded particle
system only after APPLIED. Sound is deferred because the game has no existing
bounded committed gameplay-SFX seam.

## Controls

- chisel active + primary pressed: precision remove;
- chisel active + secondary pressed: precision place;
- `R`: cycle stone/dirt selection while the chisel is active;
- Shift-secondary: existing pickup precedence;
- `Q` / `Ctrl+Q`: existing drop controls;
- `F9`-`F12`, `9`, and `0`: existing development DETAIL diagnostics.

Development-only provisioning commands `detail-stone` and `detail-dirt` supply
one chisel plus one supported 64-unit stack through normal inventory APIs. They
are never called automatically for a normal New World.

## Resource and persistence contracts

Phase 17 creates no edit queue or special mesh lane. Frozen Phase 16 limits are:

- hybrid output: 8,388,608 bytes per Chunk;
- all-Chunk CPU mesh lifecycle: 134,217,728 bytes;
- accepted/active mesh work: 32/2;
- upload/destruction: 2/4 per owner frame;
- aggregate Chunk publication plus upload: 2 per owner frame.

Voxel edits persist through existing Chunk dirty/revision state and the
`detail-blocks` extension. Chisel, unit inventory stacks, and legal coarse
WorldItems use existing generic item identity codecs. Preview and selected
material intent are session-transient.

## Forward contracts

Phase 18 owns normal chisel acquisition, crafting-lite/fabrication, FULL block
to/from 64 matching units, tool and harvest tiers, progression, and the planned
Blender MCP to `.blend` to GLB to Gaia Model Inspector tool-asset pipeline.
Phase 17 implements none of those.

Phase 19 generated DETAIL must use the same `ParentCellState`, interaction,
recovery, persistence, and resource systems; it receives no generated/player
provenance and must have its own generation density budget.
