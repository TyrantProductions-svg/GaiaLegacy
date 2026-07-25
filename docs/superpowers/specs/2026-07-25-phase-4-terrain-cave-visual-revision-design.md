# Phase 4 Terrain and Cave Visual Revision Design

Date: 2026-07-25
Branch: `feat/worldgen-pipeline`
Starting HEAD: `bb788cf65d658dad0ead79886ec0514d52b6d501`

## Purpose

This revision keeps the approved Phase 4 repository, loading, cancellation,
revision, hashing, and six-stage CPU generation architecture while correcting
the failed manual visual acceptance:

- replace uniformly scattered stone columns with sparse biome-aware clusters;
- add original opaque oak log and leaf blocks and deterministic trees;
- make plains, rolling hills, and rocky highlands visually distinct;
- replace isolated noise pockets with chambers connected by deterministic
  tunnels and explicit surface entrances.

No renderer, GPU, interaction, inventory, physics, or mesh-lifecycle behavior
is changed.

## Audited baseline

The 128-by-64 block atlas registers grass top, grass side, dirt, stone, and
missing regions. It contains no registered log or leaf resource. Existing
registered pixel coordinates are preserved. Three new original 16-by-16 tiles
use the currently transparent physical tail:

- `gaia:oak_log_side`: `(80, 48)`;
- `gaia:oak_log_top`: `(96, 48)`;
- `gaia:oak_leaves`: `(112, 48)`.

The atlas dimensions remain 128 by 64, so existing normalized UV values remain
unchanged. The artwork provenance is `GaiaLegacy original project artwork`.
Leaves use the existing opaque material until a later renderer phase provides
reliable cutout rendering.

The current decoration stage tests every surface column at probability `1/96`
in every biome and writes a one-column outcrop of uniformly selected height
one through three. Seed 12345 produces 233 candidates in the default 81-Chunk
region. There is no clustering or spacing rule.

The current cave stage applies one octave of absolute-coordinate 3D value noise
at scale `0.045`, threshold `0.78`, with a two-block bedrock floor and
three-block surface buffer. It has no vertical bias, tunnel descriptor, or
entrance carver. A read-only seed-12345 audit found 33,078 carved cells in 17
disconnected components and no surface-air adjacency.

## Asset and block design

Add `gaia:oak_log` at stored ID 4 and `gaia:oak_leaves` at stored ID 5.
Both use `gaia:opaque` and have item forms using the same ResourceLocation as
the block. No item registry is introduced.

Log top and bottom use `gaia:oak_log_top`; its four sides use
`gaia:oak_log_side`. All leaf faces use `gaia:oak_leaves`.

## Terrain shaping

Biome weights remain continuous absolute-coordinate samples. Height generation
uses biome-specific fields rather than one shared linearly blended detail:

- plains use long wavelength, low amplitude, and a flattening curve;
- rolling hills use medium wavelength and amplitude;
- rocky highlands use a low-frequency ridge transform plus bounded detail.

The final height remains a smooth weight blend and is clamped to the existing
configured world-height bounds. Sampling uses `StrictMath` and never performs
per-Chunk normalization.

## Feature planning

The formal Stage count remains six. `gaia:decoration` becomes a composite
stage containing tree and stone-outcrop providers.

Features are described from world-space coarse cells. A descriptor depends
only on seed, algorithm version, stage/feature ID, cell coordinates, and
explicit salts. Each Chunk enumerates nearby cells and independently rebuilds
every descriptor whose conservative bounds intersect the Chunk.

Tree candidates use eight-block horizontal cells, a deterministic candidate
offset, and neighbor-priority suppression for a six-block minimum root
spacing. Acceptance is biome weighted. Tree validity is based only on
reproducible world-space biome, height, slope, entrance-mask, and spawn-reserve
queries, so a neighboring Chunk can reproduce a crossing canopy without
reading another generated Chunk.

Trees have four-to-seven-block trunks and an irregular opaque leaf canopy
about three-to-five blocks wide and two-to-four blocks high. Every accepted
tree contains both log and leaf blocks. Only the intersection with the current
`GenerationRegion` is written.

Stone outcrops use larger world-space cells, biome/slope/surface eligibility,
and cluster masks. Plains are nearly excluded, rolling hills are sparse, and
rocky highlands are primary. Most structures are one or two blocks high and
two-to-five blocks in footprint; tall structures are rare and never simple
unbounded columns. Trees and entrance clearance win conflicts.

## Hybrid cave design

`gaia:cave` becomes one `HybridCaveProvider` with three internal carvers:

1. `NoiseChamberCarver` blends low-frequency and high-frequency 3D fields with
   a depth bias. Non-entrance columns retain surface protection.
2. `DeterministicTunnelCarver` derives bounded tunnel systems from world-space
   3D coarse cells. Each descriptor has a stable start, direction, length,
   radius, bend sequence, and bounded branch list. Fixed path steps carve
   spheres or ellipsoids.
3. `SurfaceEntranceCarver` derives horizontal coarse-cell candidates and
   creates a sloped tube that is a branch of a deterministic tunnel system.
   Only this mask may bypass surface protection.

For a target Chunk, the Cave Stage enumerates all nearby source cells whose
maximum descriptor reach could intersect that Chunk. It reconstructs and
carves only the local intersection. Generation order and adjacent-Chunk
availability therefore cannot affect a boundary.

Default seed acceptance requires at least two visible entrances, one connected
depth greater than ten blocks, and one reachable component spanning at least
two Chunks. Small isolated noise pockets remain allowed.

## Conflict and safety rules

- A deterministic twelve-block origin reserve excludes trees, outcrops, and
  entrances so safe spawn has a stable clear search area.
- Entrance clearance excludes tree roots and outcrop descriptors.
- Trees take precedence over outcrops when feature bounds overlap.
- Generation writes only detached CPU data through `GenerationRegion`.
- Repository publication remains exclusively through the existing generation
  ticket transaction.

## Candidate parameters

- tree cells: 8 blocks; minimum root spacing: 6 blocks;
- tree density targets per Chunk: plains 0.5-1.2, hills 1.5-3.0,
  highlands 0-0.2;
- trunk height: 4-7; canopy horizontal radius: 2;
- outcrop total target: 20-35 roots in the default region;
- chamber scales: 0.025 and 0.07; initial blended threshold: 0.72;
- tunnel radius: 1.75-2.75; length: 48-96 blocks;
- branch probability: 0.15 at bounded checkpoints, maximum two branches;
- entrance cells: 32 blocks; default-region target: 2-5 entrances;
- non-entrance surface protection: 5 blocks.

Tests, not unrecorded tuning, define the final accepted ranges.

## Snapshot promotion

The approved version-1 configuration, constants, hashes, and documentation
remain intact while the revision is a candidate. Candidate generation has an
explicit composition/config entry point and prints a candidate aggregate hash.
It must not replace the version-1 locked assertion.

After Windows visual approval only:

1. promote the candidate algorithm to version 2;
2. update the canonical fingerprint;
3. reproduce representative and aggregate hashes in two independent clean
   Gradle processes;
4. update the normative snapshot document with the v1-to-v2 reason.

The user granted that approval as **APPROVED WITH DEFERRED RENDERING
LIMITATIONS**. The final lock therefore uses algorithm version `2` and
regenerates every fingerprint, hash, statistic, and inspection coordinate.
Version-1 history remains unchanged. Fullbright cave appearance, missing
lighting/AO/shadows, and final stone material balance are explicitly deferred
to Phase 5 rather than addressed by further world-generation changes.

## Acceptance

Automated tests cover resource loading, existing UV preservation, feature
density, tree completeness, cross-Chunk reproduction, biome height/slope
statistics, cave surface protection, entrance/tunnel continuity, BFS reach,
negative coordinates, scheduling order, cancellation, and repository conflict
semantics.

Windows interactive acceptance and explicit user visual approval are required
before version-2 hashes are locked. The user approved the visual direction,
confirmed an entrance had been found, and waived further agent entrance
inspection. Three surface screenshot categories were captured outside the
repository; the cave entrance and underground screenshot categories remain
uncaptured. Native macOS remains explicitly unverified unless run on macOS.

## Implementation evidence

The approved design is implemented as algorithm version `2`. The final
composition retains six stages and the production graphics/physics boundaries.
The locked config fingerprint is
`56cb2f243319c7cf275ade89f480f9208ce5c1f85334eb225e6b56ed18e3012a`;
the seed-12345 81-Chunk aggregate hash is
`ec2c76a97f36d34b7360ae9abbb0be60fb8790f275fdaf5227a7daeae9754353`.

Automated evidence records 220 log blocks, 2,089 leaf blocks, 33 highland
outcrop columns and none in plains/hills, plus ten entrance-reachable cave
components. The largest contains 73,558 air cells, reaches 63 blocks below an
entrance, and spans 23 Chunks. Every top trunk log has a leaf directly above
it.

Windows visual review confirmed the safe surface spawn, recognizable oak
trunks/crowns, rolling terrain, sparse rocky highlands, F1, resize, and Escape.
The user approved the terrain/cave direction and confirmed that an entrance
was found. Three surface screenshot categories were captured outside the
repository; cave entrance and underground screenshots remain absent. Native
macOS verification was not run.
