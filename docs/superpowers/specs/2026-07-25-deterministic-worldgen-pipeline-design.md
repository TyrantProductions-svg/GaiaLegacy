# Phase 4 Deterministic World Generation Pipeline Design

Date: 2026-07-25
Branch: `feat/worldgen-pipeline`
Base: `origin/main` at `f1ca80beb47616025bea21615d7e3fccaa5b31c6`

## Purpose

Phase 4 replaces the monolithic `GaiaWorldGenerator` with a deterministic,
composable, independently testable finite-world generation pipeline. It keeps
Gaia-specific generation under `game`, preserves the Phase 3 repository and
mesh lifecycle as the authority for loaded chunks, and does not use Phase 7
gameplay mutation or inventory contracts for bulk terrain creation.

The default world is a centered square with inclusive Chunk coordinates
`[-4, 4]` on both horizontal axes, for 81 initial Chunks.

## Baseline audit

The pre-Phase-4 baseline builds successfully on Windows with the checked-in
Gradle Wrapper:

```text
BUILD SUCCESSFUL
18 actionable tasks executed
engine: 48 suites, 489 tests
game: 11 suites, 107 tests
total: 59 suites, 596 tests
failures/errors/skipped: 0
```

Current generation has the following limitations:

- `GaiaWorldGenerator` owns one static `PerlinNoise`, hard-coded generation
  constants, terrain height, strata, and surface placement in one class.
- `WorldLoader` hard-codes a radius-like half extent of two and therefore
  produces `[-2, 1]`, a non-inclusive 4-by-4 area.
- `WorldLoader` repairs an empty spawn column through repeated
  `World.setBlock`, which uses the normal dirty mutation path rather than a
  generation transaction.
- `ChunkRepository.generate` accepts a mutable-`Chunk` callback. It protects a
  single entry and removes the entry on failure, but it does not expose a
  typed generation attempt, immutable generated payload, explicit generation
  status, or rebuild conflict result.
- Loading already runs on the dedicated world worker and its exceptional
  completion prevents `GameLoop` from reaching `RUNNING`.
- Phase 3 already owns Chunk state, revisions, dirty propagation, immutable
  meshing snapshots, stale-result rejection, GPU upload, replacement, and
  unload.
- Phase 7 gameplay mutation publishes interaction events only through
  `WorldMutationService`. Direct production `.setBlock(...)` calls are
  currently allowlisted only in the two world-generation files.
- Production resources currently contain only
  `gaia:air`, `gaia:grass`, `gaia:dirt`, and `gaia:stone`.

## Goals

- The same world seed, algorithm version, complete generation configuration,
  and Chunk coordinates produce identical block bytes.
- Generation is independent of Chunk scheduling order and worker interleaving.
- Biome and height sampling is continuous in absolute world coordinates.
- Plains, rolling hills, rocky highlands, simple caves, and grass/dirt/stone
  strata are visible with the fixed default seed.
- Every Stage and Provider can be tested without creating a renderer, Mesh,
  OpenGL context, GPU resource, player interaction, or inventory transaction.
- Initial loading and debug rebuild use typed repository generation
  transactions.
- A single Chunk publishes atomically. A failed batch never enters
  `GameLoop.RUNNING`.
- Spawn selection is deterministic, recoverable, and never manufactures a
  fallback column through gameplay-style writes.

## Non-goals

Phase 4 does not implement:

- infinite streaming, persistence, rivers, climate simulation, oceans,
  volcanoes, restricted zones, or the advanced biome catalog;
- new block definitions, textures, materials, tree models, structures, or
  ecological simulation;
- gameplay breaking, placement, inventory, item entities, drops, or UI;
- renderer, `PlayerController`, `ChunkMeshManager`, Phase 5 rendering
  interfaces, or GPU changes;
- a new random-number or noise dependency copied from another project.

## Module and ownership boundaries

- Generic repository transaction values and behavior belong in
  `engine/src/main/java/com/overlord/voxel`.
- Gaia configuration, Providers, Stages, Pipeline, loading, spawn selection,
  snapshot utilities, and manual-check metadata belong in
  `game/src/main/java/com/gaia/world`.
- `engine` must not depend on `game`.
- The engine owner reviews repository transaction and lifecycle changes. The
  game owner reviews the pipeline, content, configuration, and composition.
  Both owners review shared documentation.

## Architecture

```text
WorldGenerationConfig
        |
WorldLoader on the dedicated world worker
        |
WorldGenerator / StagedWorldGenerator
        |
Biome -> Height -> Strata/Density -> Cave -> Surface -> Decoration
        |
GenerationRegion (one mutable CPU-only Chunk work buffer)
        |
ChunkGenerationData (single immutable engine publication value)
        |
ChunkRepository generation transaction
        |
GENERATED or DIRTY
        |
Phase 3 snapshot/meshing/stale checks
        |
main-thread ChunkMeshManager upload and replacement
```

The Pipeline produces one Chunk at a time. Providers may sample any absolute
world coordinate needed for continuity, but a `GenerationRegion` rejects every
write outside its own Chunk and configured vertical bounds.

## Engine generation transaction

### Values

The engine exposes game-neutral values:

- `ChunkGenerationMode`: `INITIAL` or `REBUILD`.
- `ChunkGenerationStatus`: `IDLE`, `GENERATING`, `COMMITTED`, `FAILED`.
- `ChunkGenerationTicket`: immutable key, mode, opaque attempt token, and
  captured base revision.
- `ChunkGenerationData`: immutable key, world height, and defensively copied
  Chunk block bytes.
- `ChunkGenerationResult`: `COMMITTED`, `CONFLICT`, or `FAILED`, with the
  committed revision when present and an explicit failure when present.

The exact API is an encapsulated equivalent of:

```java
ChunkGenerationTicket beginGeneration(
        ChunkKey key, ChunkGenerationMode mode);

ChunkGenerationResult commitGeneration(
        ChunkGenerationTicket ticket,
        ChunkGenerationData data);

ChunkGenerationResult failGeneration(
        ChunkGenerationTicket ticket,
        Throwable failure);

ChunkGenerationStatus generationStatus(ChunkKey key);
```

### Semantics

- Only one live generation ticket may own a key.
- `INITIAL` captures the absence/empty baseline. `REBUILD` captures the loaded
  Chunk revision without invalidating or replacing its current render object.
- CPU generation occurs in a detached buffer. It holds no repository entry
  lock while Providers run.
- Commit validates the ticket, key, mode, world height, current revision, and
  unload state under repository ownership.
- An initial commit installs the complete Chunk, allocates one repository
  revision, and makes it `GENERATED`.
- A rebuild commit replaces the complete CPU Chunk, allocates one repository
  revision, and makes the target `DIRTY`.
- Repository code compares affected horizontal edges and is the only code
  allowed to invalidate loaded neighbors or allocate their dirty revisions.
- Any current meshing claim becomes stale through the existing Phase 3
  revision checks. No transaction creates, uploads, replaces, or destroys a
  GPU object.
- A Stage or pipeline failure is reported through `failGeneration`. An
  initial failure exposes explicit generation failure without publishing a
  partial Chunk. A rebuild failure preserves the last committed CPU Chunk,
  revision, lifecycle state, and render object.
- A ticket conflict is terminal for that attempt. Callers must begin a new
  attempt after inspecting current repository state; they must not blindly
  reuse an old ticket.
- The existing callback-style `World.generate` may remain as a compatibility
  wrapper, but Phase 4 production loading and rebuild code use the typed
  transaction API.

The generation path does not publish `BeforeBlockChangedEvent`,
`BlockChangedEvent`, gameplay `ChunkDirtyEvent`, inventory operations, or
world-item operations. Those contracts describe player mutations, not bulk
loading.

## Game generation contracts

### WorldGenerator

`WorldGenerator` accepts an immutable `GenerationContext` and one `ChunkKey`.
It returns a `WorldGenerationResult` containing either the single immutable
engine `ChunkGenerationData` publication value plus ordered Stage results, or
an explicit failure plus the completed Stage results.

It does not receive `World`, `ChunkRepository`, renderer services, or a
gameplay mutation service.

### GenerationContext

`GenerationContext` owns:

- the immutable complete `WorldGenerationConfig`;
- resolved stored IDs for the existing air, grass, dirt, and stone resources;
- the deterministic coordinate sampler;
- the ordered immutable Pipeline definition.

Block IDs are resolved once from `BlockRegistry` through
`ResourceLocation`. Providers do not hard-code numeric stored IDs and no
second block registry is introduced.

### GenerationRegion

`GenerationRegion` represents exactly one Chunk:

- immutable Chunk key, horizontal world origin, width, and world height;
- mutable block bytes confined to local X/Z and configured Y bounds;
- typed per-column biome weights and height samples;
- bounds-checking read/write methods;
- a freeze operation that returns immutable generated data.

Stage code may not retain a mutable Region after the pipeline completes.

### Stage and Provider boundaries

`WorldGenerationStage` has a stable `ResourceLocation id()` and one execution
method. Each Stage returns an immutable `GenerationStageResult` with:

- Stage ID;
- `SUCCEEDED` or `FAILED`;
- deterministic write/sample statistics;
- an optional failure value only when failed.

The Pipeline stops on the first failed Stage and never freezes or publishes
that Region.

Provider interfaces and their default implementations are:

1. `BiomeProvider`
   - Samples low-frequency continuous absolute-coordinate fields.
   - Returns normalized weights for plains, rolling hills, and rocky
     highlands.
   - Never normalizes values independently per Chunk.
2. `HeightProvider`
   - Produces the column height by smoothly blending biome-specific base,
     amplitude, and ruggedness functions.
   - Clamps every height below the configured ceiling and above the configured
     minimum.
3. `StrataDensityProvider`
   - Fills deterministic stone mass and a bounded dirt band below the sampled
     surface.
4. `CaveProvider`
   - Removes cells selected by a continuous three-dimensional world field.
   - Preserves configured bedrock and surface-safety depths.
5. `SurfaceProvider`
   - Resolves the exposed surface to grass, dirt, or stone according to biome
     weights, slope, and depth.
6. `DecorationProvider`
   - Adds sparse, bounded, single-Chunk surface variations and stone outcrops
     using only the four existing block resources.
   - Does not place trees, multi-Chunk structures, new resources, or GPU
     objects.

## Determinism

`WorldGenerationConfig` is an immutable value containing every input that can
affect output, including:

- `long seed`;
- positive `int algorithmVersion`;
- non-negative inclusive `int chunkRadius`;
- biome, height, strata, cave, surface, decoration, and spawn-search tuning.

The production default is seed `12345L`, algorithm version `1`, and radius
`4`.

Every Stage has a stable ID such as `gaia:biome`, `gaia:height`, or
`gaia:cave`. Random-looking values are derived from:

```text
seed
algorithmVersion
stage ResourceLocation
absolute world X/Y/Z
explicit salt
```

The implementation uses a project-owned documented fixed 64-bit integer mix
and deterministic bit-to-unit-interval conversion. It does not share
`Random`, `SplittableRandom`, mutable permutation arrays, or consumption-order
state. Continuous interpolation uses absolute coordinates and `StrictMath`.

Consequences:

- scheduling Chunks in forward, reverse, or concurrent order cannot change a
  result;
- iterating unrelated candidates cannot consume another candidate's random
  values;
- adjacent Chunks sample identical mathematical values at the same world
  coordinates;
- algorithm changes require an explicit version and snapshot update.

## Loading, failure, and cancellation

The initial batch contains the 81 keys from `[-4, 4]` in a deterministic
key order. Work still runs through the existing dedicated world executor.

The load operation exposes explicit `RUNNING`, `SUCCEEDED`, `FAILED`, and
`CANCELLED` states. Its successful value contains the initial keys, selected
spawn, config fingerprint, and deterministic region hash. Its failed value
contains completed keys, failed key, failed Stage or repository operation, and
cause.

Each key follows this sequence:

1. check cancellation;
2. begin its repository generation transaction;
3. run the CPU Pipeline into a detached Region;
4. fail the ticket if a Stage fails;
5. commit immutable data if every Stage succeeds;
6. record the exact repository outcome.

A failed key ends the initial batch as `FAILED`. Previously committed Chunks
remain available for diagnosis or an explicit configured retry, but the
`GameLoop` cannot enter `RUNNING`. It transitions through an explicit failed
load branch and stops or reports the cause; it never treats a partial batch as
successful.

Cancellation fails/cancels the active ticket without committing its detached
buffer. Shutdown still stops the world executor before mesh workers and
main-thread renderer teardown.

## Safe spawn

The loader no longer uses `World.setBlock` to create a fallback column.

After all initial commits, a deterministic spawn selector searches the
generated range from the origin outward. Candidates are ordered by:

1. squared horizontal distance;
2. world X;
3. world Z;
4. feet Y.

A candidate requires:

- a solid generated support block;
- enough empty space for the Phase 6 player collider and head;
- coordinates inside the committed initial range.

The selected position is passed through the existing Phase 6 penetration
recovery after loading. If no valid candidate exists, loading becomes
`FAILED`; terrain is not modified to manufacture a spawn.

## Debug rebuild

`WorldLoader.rebuildRegion(...)` is a programmatic API. It accepts the
immutable generation configuration and an explicit finite set/range of loaded
Chunk keys.

It:

- runs on the world/loading worker mechanism;
- uses `REBUILD` tickets and the same Pipeline as initial generation;
- rejects stale base revisions rather than overwriting later changes;
- commits only complete Chunk data;
- advances repository revisions and lets Phase 3 schedule CPU remeshing;
- never calls gameplay mutation services and never touches
  `ChunkMeshManager` or a GPU object directly.

Phase 4 does not bind this API to a key, console, command line, UI, or platform
property.

## Snapshot contract

Deterministic snapshots use SHA-256 from the Java standard library. The input
is:

1. algorithm version and canonical configuration fingerprint;
2. Chunk keys in ascending X then Z order;
3. each Chunk's raw block bytes in the canonical engine snapshot layout.

`docs/architecture/phase-04-deterministic-snapshots.md` records:

- the fixed seed and complete config fingerprint;
- representative Chunk hashes and the 81-Chunk aggregate hash;
- the algorithm version;
- manual inspection coordinates selected by deterministic provider queries
  for plains, rolling hills, rocky highlands, cave exposure, negative
  coordinates, and Chunk boundaries;
- the exact rule for intentionally updating snapshots.

Snapshot constants are established only after the approved implementation
passes behavior tests. Updating a hash requires an algorithm-version or
configuration change plus an explanation in the snapshot document.

## Testing strategy

Tests are written before each production increment.

### Engine tests

- generation ticket exclusivity and terminal outcomes;
- initial atomic commit and failure without partial publication;
- rebuild base-revision conflict;
- rebuild failure preserving the previous Chunk and lifecycle;
- repository-owned revision and neighbor invalidation;
- stale mesh claim/result rejection after rebuild;
- unload and generation conflict;
- generation payload defensive copying.

### Game tests

- each Provider and Stage in isolation;
- same seed/config/key byte-for-byte equality;
- different seed output difference;
- forward, reverse, and shuffled/concurrent scheduling equality;
- neighboring boundary height/surface continuity;
- cave, strata, and decoration write bounds;
- representative fixed-seed snapshots;
- all three biome types and a cave sample within the default finite world;
- successful deterministic safe spawn;
- no-valid-spawn explicit failure;
- Stage, commit, and cancellation failure state transitions;
- debug rebuild through repository revisions and normal mesh eligibility.

### Structure tests

Source/dependency tests prove:

- world generation imports no renderer, Mesh, LWJGL, GLFW, OpenGL, GPU, Phase
  5 interface, `WorldMutationService`, Phase 7 block-change event, inventory,
  or world-item type;
- generation and loader production code contain no direct `World.setBlock`;
- only the repository generation transaction publishes generated data;
- no Stage owns a static/global `Random` or mutable shared noise object;
- `Renderer`, `PlayerController`, and `ChunkMeshManager` are unchanged.

## Manual acceptance

Windows manual verification uses the fixed default config and inspection list
from the snapshot document:

- observe recognizable plains, rolling hills, rocky highlands, and a simple
  cave opening;
- walk across multiple positive and negative Chunk boundaries;
- inspect for vertical cliffs caused by discontinuity, permanent mesh seams,
  spawn obstruction, and rebuild artifacts;
- verify debug rebuild retains the old render until normal replacement and
  does not leak or directly replace GPU resources.

Native macOS verification repeats the same seed and records the same aggregate
hash, then checks the same coordinates visually.

## Delivery

Phase 4 produces:

- this design;
- a detailed TDD implementation plan;
- `docs/architecture/phase-04-deterministic-snapshots.md`;
- `docs/agent-handoffs/phase-04-handoff.md`;
- fixed test seed and manual inspection coordinates;
- final build, packaged-resource, diff, hygiene, owner-review, and platform
  evidence.

Suggested final commit:

```text
feat(worldgen): introduce deterministic staged terrain generation
```

Suggested pull request title:

```text
feat(worldgen): add composable deterministic small-world pipeline
```
