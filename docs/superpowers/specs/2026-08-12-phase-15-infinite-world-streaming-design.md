# Phase 15 Infinite World Streaming and Deterministic Generation Design

## Status and authority

- Phase: Milestone 2 Phase 15.
- Branch: `feat/infinite-world-streaming`.
- Baseline: merged Phase 14 at
  `origin/main@ddd6a961826ebb593ce8d45458f48e7f86e9559b`.
- Entry audit: HEAD and `origin/main` match with divergence `0/0`; the only
  working-tree item at entry was the deliberately preserved untracked `dist/`
  artifact.
- This document records the approved design only. It does not authorize
  staging, committing, pushing, a pull request, or a merge.

Phase 15 upgrades the finite 81-Chunk world into a practically unbounded
horizontal world. Only a bounded player-centered working set is resident.
Missing Chunks are loaded from the Phase 14 save authority or generated as a
pure function of seed, generator version, stage version, and global
coordinates. Modified state survives unload and process restart. The vertical
height remains bounded at 256 blocks.

## Non-negotiable ownership

- `ChunkRepository` remains the only canonical loaded-Chunk, revision, dirty,
  generation-ticket, meshing, stale-result, and unload authority.
- `WorldMutationService` remains the only gameplay voxel mutation path.
- `LogicalWorldItemService` remains the only canonical stable-ID and allocator
  authority for WorldItems.
- `SaveRootProvider`, `SaveGameManifest`, and the existing world UUID directory
  remain the only save-root and world-identity authority.
- `ProductLoop` and the owner-thread session runtime retain lifecycle
  ownership. A streaming controller requests work; it never becomes a second
  World or Chunk store.
- Workers may read, decode, generate, serialize, compress, and build CPU mesh
  data from immutable inputs. The context owner thread alone publishes
  canonical Chunk results and creates, replaces, uploads, registers, or
  destroys GPU resources.
- Unloaded or failed space is `UNKNOWN/UNAVAILABLE`, never implicit AIR.
- Phase 15 does not implement infinite Y, detail voxels, large biome/POI
  content, moving assemblies, structural physics, multiplayer, or terrain LOD.

## 1. Global coordinates and safe envelope

### 1.1 One Chunk key

The existing `ChunkKey(int x, int z)` remains the only Chunk address type.
Negative mapping uses exact floor semantics:

- `chunk = floorDiv(worldBlock, 16)`;
- `local = floorMod(worldBlock, 16)`;
- world block `-1` maps to Chunk `-1`, local `15`;
- every multiple of 16 is tested on both sides of zero.

No parallel long-width key is introduced. The existing Phase 14 codec remains
an `int x/z` wire contract.

### 1.2 Checked coordinate envelope

`MAX_SAFE_CHUNK_COORDINATE` is `134,217,727`; valid Chunk axes are the
inclusive symmetric range `[-134,217,727, +134,217,727]`. The corresponding
Chunk origins remain representable as signed 32-bit block coordinates.

All origin multiplication, neighbor construction, distance, radius, and
priority calculations use checked `long` intermediates. A request outside the
safe envelope fails closed before repository admission, generation, storage,
or rendering. No integer wrap is accepted. Stable ordering is
`priority-class`, squared distance computed in `long`, then `ChunkKey.x`, then
`ChunkKey.z`; hash-map iteration never decides work order.

### 1.3 Canonical and resident positions

Global horizontal position is represented canonically as:

```text
GlobalPosition = ChunkKey + double localX/localZ in [0, 16) + bounded double Y
```

`com.overlord.voxel.GlobalPosition` is introduced at Gate 15D because the
controller consumes this canonical value. It is only an immutable checked
coordinate: safe `ChunkKey`, finite `Y`, and canonical finite local `X/Z` in
`[0,16)`. Simulation/render origins, float conversion, camera policy, physics
migration, and rebasing remain exclusively Gate 15G/Task 10 work.

The resident physics and renderer continue to use small `float` values relative
to an explicit immutable `SimulationOrigin`. The origin is a safe ChunkKey.
Changing it is one owner-thread transaction that rebases the player, active
WorldItem projections, camera, transient presentation, collision queries, and
resident Chunk render transforms together. The transaction does not change
canonical Chunk keys, block coordinates, revisions, raycast targets, save
identity, or generator inputs.

GPU positions are relative to the committed render/simulation origin. No
individual renderer, particle system, or physics body may invent its own
offset. A rebase is permitted only at a fixed-step/frame boundary where no
worker owns mutable runtime objects and no partially updated view can publish.

## 2. Existing ChunkRepository lifecycle

Phase 15 extends the existing repository state machine rather than replacing
it. Request/save coordination metadata may be added around the current states,
but there is one authoritative state per key.

```text
ABSENT
  -> REQUESTED (coalesced request ticket)
  -> LOADING or GENERATING (bounded worker admission)
  -> GENERATED/DIRTY (owner-thread canonical publication)
  -> MESHING (immutable CPU input)
  -> READY_FOR_UPLOAD (stale-safe CPU result)
  -> RENDERABLE (owner-thread GPU publication)
  -> UNLOAD_PENDING
  -> SAVING when modified
  -> completeUnload
  -> ABSENT
```

The repository's generation/unload tickets and revisions are extended with a
streaming request epoch. Duplicate requests for a key coalesce. Every worker
result carries the exact key, request epoch, source kind, base identity, and
expected canonical revision. A canceled, replaced, unloaded, or older result
cannot resurrect a Chunk or overwrite a newer revision.

`UNKNOWN` is a query outcome for absent/unready space, not a stored voxel
value. Existing APIs that return AIR for missing Chunks must not be used by
physics, raycast, generation, or streaming decisions without an explicit
availability check.

## 3. Deterministic on-demand generation

### 3.1 Pure stage contract

The current CPU-only `StagedWorldGenerator` and
`DeterministicCoordinateSampler` remain the foundation. Each stage declares:

- stable `stageId`;
- positive `stageVersion`;
- bounded `haloRadius`;
- immutable input contract.

Generated bytes are a pure function of world seed, manifest
`generatorVersion`, stage ID/version, canonical global coordinates, and stable
salt. Mutable global RNG, request order, executor order, wall time, and loaded
neighbor state are forbidden inputs. Lattice and coordinate mixing use `long`
intermediates throughout the approved Chunk envelope.

### 3.2 Seam and decoration ownership

Terrain, biome, strata, surface, and cave providers sample global coordinates.
A Chunk is only the bounded owner of output cells, not a separate normalized
world. Generation never treats an unloaded neighbor as AIR.

Stages requiring neighborhoods query deterministic world-coordinate providers,
not `ChunkRepository`. Decoration and future structure decisions use stable
region cells derived from seed, generator version, stage version, and signed
region coordinates. One anchor owns each candidate. Every affected Chunk may
recompute the same decision and clips output to its own cells, so borders and
corners neither duplicate nor omit the feature.

`GenerationRegion.sampleLocalOrAir()` remains local-only convenience for
already written cells inside one generation responsibility. Cross-border
generation through that method is prohibited by structural tests.

### 3.3 Compatibility rule

Untouched Chunks may be regenerated only with the manifest's exact generator
and stage compatibility identity. A modified persisted Chunk with an
incompatible generator/base identity returns an explicit closed migration
diagnostic; it is never silently combined with a different base.

Hash fixtures cover first request, request after at least 100 unrelated keys,
reverse order, different executor schedules, unload/regenerate, negative keys,
cardinal borders, diagonal corners, caves, and decoration ownership.

## 4. Streamed persistence

### 4.1 Modified-only policy

Phase 15 uses policy B: deterministic untouched generated Chunks are not
persisted. A generation commit records generator compatibility identity,
canonical base hash, and `modified=false`. Only a successful committed
`WorldMutationService` mutation changes the canonical Chunk to
`modified=true`.

A modified Chunk is stored in a sharded per-Chunk file beneath the existing
world UUID directory. Each file contains:

- world/save identity;
- canonical ChunkKey;
- generator/base compatibility identity;
- canonical voxel bytes;
- revision and persisted revision;
- base hash and modification state;
- a versioned optional-extension table containing the reserved Phase 16
  `detail-blocks` extension point.

The Phase 14 manifest/registry gains a versioned streamed-chunk index. It maps
ChunkKey to validated payload identity without creating a second save root.
One-file-per-modified-Chunk is accepted for the first implementation; region
containers require a separate measured format review.

### 4.2 Atomic publication and unload

One modified-Chunk transaction is:

1. capture exact immutable Chunk and WorldItem hibernation payloads under
   tickets;
2. write a transaction-owned sibling temporary file;
3. force/close and reread-validate identity, revision, size, checksum, and
   extension descriptors;
4. atomically replace the Chunk file, using the Phase 14 reviewed fallback only
   when atomic move is unsupported;
5. publish the validated index entry atomically;
6. revalidate repository and WorldItem tickets;
7. commit WorldItem hibernation and `completeUnload`.

Any failure before step 7 retains the modified Chunk resident and active items
canonical under their existing authorities, or retains the last known-good
persisted file/index. A stale save result cannot overwrite or unload a newer
revision. Shutdown may report a blocking failure; it may not claim success or
discard the resident modified state.

### 4.3 Phase 14 v1 migration

Phase 14 archives do not contain a reliable per-Chunk modified bit. Migration
therefore treats all 81 v1 Chunks as authoritative persisted data. Phase 15
does not guess that a matching current generator hash means a v1 Chunk is safe
to discard.

The v1 archive remains readable. On first successful Phase 15 Save or Save &
Quit, all imported v1 Chunks are written and validated as v2 Chunk payloads,
then the v2 index/manifest is atomically published. The readable v1 archive is
retained as recovery backup until the complete v2 world can be reread. Failure
leaves v1 authoritative and reports a bounded migration diagnostic; no partial
v2 world is exposed. Imported v1 Chunks may only be de-duplicated by a future
explicit migration tool, not by Phase 15 runtime inference.

## 5. LogicalWorldItemService paging migration

> **Project-owner supersession (2026-08-14):** The earlier Phase 15 rule that
> WorldItems have no automatic canonical expiry is revoked. The controlling
> implementation design is now
> `docs/superpowers/specs/2026-08-13-phase-15-worlditem-paging-backend-design.md`.
> It also replaces the abandoned owner-directory, authority-trie, opaque-blob,
> catalog/refcount/GC, overlay, and maintenance designs.

Every item has exactly one lifetime authority: `expiresAtWorldTick`. The
constant `WORLD_ITEM_TTL_TICKS` is exactly `18_000L`; spawn computes
`saturatingAdd(worldTick, 18_000L)`, and the item is expired when
`worldTick >= expiresAtWorldTick`. `worldTick` maps exactly to the existing
nonnegative manifest/session `fixedTick`. Pause and process downtime do not
advance it. Pickup, partial pickup, movement, paging, save, and restart do not
refresh expiry.

`SessionPersistenceClock` alone advances `worldTick` from fixed simulation
steps. `LogicalWorldItemService` only receives monotonic tick values and remains
the only semantic, stable-ID, membership, expiry, reservation, and allocator
authority. It keeps exactly one current-live metadata row per live ID with
intended owner/revision, expiry, residency/pending state, and optional durable
page proof. Clean dormant/evicted rows require proof; active/dirty/pending rows
may lack it and then remain resident/pinned. Active DTO, decoded dormant DTO,
evicted-unexpired metadata, and unique pending rows share one hard cap of 1,024.
Pickup or expiry deletes the row immediately; no expired history is retained.

The durable global checkpoint contains `worldTick`, allocator high-water/next
ID, exhaustion, total live count, and the complete canonical set of at most
1,024 physical page descriptors `{ChunkKey,pageRevision,pageHash,
encodedEntryCount,expectedLiveCountAtCheckpointTick}`. Zero-live stale physical
pages remain describable. A
restart pins one immutable index sequence/checkpoint digest, reads and validates
every described page, rejects global duplicate IDs/count/hash/cap/allocator
disagreement, then publishes the service once. Runtime activation must match
the service's complete metadata; load-order-dependent selection is forbidden.

Chunk-local `GLWP` page format version 1 stores exact
`expiresAtWorldTick`. This is the first streamed page wire format and is
independent of Phase 14 whole-world save v1/v2. Task 4 stores only opaque page
bytes plus a generic inline checkpoint extension. Global extension upsert and
remove are explicit atomic mutations; omission retains prior bytes. Each
extension and all retained extensions together are capped at 1 MiB. The
checkpoint declares a generic dependency on the required page extension and
its exact incrementally maintained physical-page count. Task 4 validates it
without decoding WorldItems or scanning Chunks.

The current-live cap is aggregate:

```text
active DTO + decoded dormant DTO + evicted-unexpired metadata
    + unique pending <= 1,024
```

Clean pages are additionally bounded by page count and decoded bytes. A
deterministic `(expiresAtWorldTick,ID)` heap/index contains the same at-most
1,024 live IDs. On a delivered tick, every due item becomes noninteractive and
noncanonical-live immediately. Page tombstone/rewrite cleanup is delayed and
bounded to 64 intents/64 KiB metadata; overflow drops the rediscoverable intent
instead of creating another backlog. Failed cleanup may leave expired bytes,
never a live item. There is no global scan.

Hibernate/unload ordering is capture -> validate/filter -> encode -> atomically
persist explicit expected-revision/hash page/Chunk mutations plus checkpoint
and dependency count -> verify durable proof -> evict DTO/projection. Item-only
Chunk removal and page-extension-only removal are distinct. The service binds a
service-issued, private-issuer-bound opaque ticket to an engine marker proof
through single-consume `commitPersistence(ticket,proof)`. The backend creates a
private nested proof only after force/reread/reopen, and its private verifier
checks issuer, save/root, checkpoint revision, transaction digest, and current
index sequence. Production composition injects this verifier once; callers
cannot construct/substitute proof or verifier. Proof has no public fields, is
never serialized, and the backend never receives a ticket secret.
Failure before proof commit retains exact in-memory authority. Activation is
validate-first/publish-last and must roll back exact canonical and projection
state on failure. Reentrant mutation on the same service during projection
callbacks fails before canonical mutation. A partial-pickup remainder keeps its
stable ID and original expiry.

Legacy v1 read derives expiry as saturating `spawnTick + 18_000`. A v1 writer
may write only a proven `LEGACY_COMPLETE` snapshot whose every expiry equals
that derivation. Presence of paged/dormant state or expiry mismatch makes v1
encode fail closed. Streamed v2 saves checkpoint, descriptor pages, and generic
dependency count atomically. Missing or corrupt pages never mean empty.

Exact accounting is:

```text
sum(expectedLiveCountAtCheckpointTick)
    == publishedCheckpoint.totalLiveItemCount <= 1,024
currentLiveMetadata.size == expiryIndex.size <= 1,024
physicalDescriptorCount == requiredPageDependencyCount <= 1,024
```

Durable/runtime equality holds only immediately after restart publication or
accepted intended checkpoint. Resident/pinned dirty delta is separately bounded
and metered at 1,024 entries/16 MiB candidate bytes. Zero-live stale descriptors
and pending cleanup/tombstone counts remain separate. A full descriptor table can admit a new page only with the same
transaction's stale-safe removal of a zero-live page; failure keeps active data
resident/pinned.

## 6. ChunkStreamingController

The game/session-owned `ChunkStreamingController` observes authoritative player
physics/global position. It computes immutable sets and proposes admissions; it
does not generate, mutate repository arrays, perform IO, or touch GPU state.

The controller receives one immutable `ChunkStreamingObservation` containing
the currently resident and requested/in-flight Chunk keys. It returns one
immutable `ChunkStreamingDecision` containing desired simulation/render/preload
sets, desired epoch, admissions, cancellations, rejections, and unload
candidates. Neither value owns repository, executor, callback, or mutable
collection state.

An observation may transiently contain a completed key in both sets. For
capacity and cancellation decisions the controller defines outstanding work as
`requested - resident`; completed resident work neither consumes a request slot
nor receives a cancellation. Input order never changes the decision.

Approved default radii, in Chunks:

| Set | Radius | Maximum square footprint |
| --- | ---: | ---: |
| Simulation | 2 | 25 |
| Render/active | 4 | 81 |
| Preload | 5 | 121 |
| Unload threshold | 7 | 225 |

The gap between preload 5 and unload 7 supplies hysteresis. A Chunk does not
thrash when the player moves repeatedly across one border. The defaults live
in one validated immutable policy with
`simulation <= render <= preload < unload`; consumers do not duplicate magic
numbers.

Task 7 eagerly materializes the three desired sets, so validated policy limits
the materialized desired radius to 7 (at most 225 keys per set). Larger custom
radii fail before enumeration. This is the complete Task 7 memory bound; it is
not a general streaming-region or Task 10 origin policy.

Priority is deterministic:

1. simulation set;
2. render set;
3. preload set;
4. squared distance from authoritative player Chunk;
5. stable ChunkKey x/z tie-break.

Fast travel replaces the desired epoch. Work outside the new preload set is
canceled when safe or deterministically deprioritized. A full queue rejects
the farthest lowest-priority admission rather than growing without bound.
The desired epoch advances only when the desired-set identity changes. A
stationary player and any observation change that leaves the desired sets
identical retain the epoch, so resident/request completion does not stale all
otherwise valid work.

## 7. Async pipeline and budgets

The implementation reuses bounded executors; it never creates one thread per
Chunk.

| Pipeline | Queue bound | Active bound |
| --- | ---: | ---: |
| Load + deterministic generation | 32 | 4 |
| CPU mesh build | 32 | 2 |
| Chunk save/serialization | 8 | 1 |

Owner-thread per-frame defaults:

- canonical publications: 2;
- GPU mesh uploads/replacements: 2;
- GPU mesh destructions: 4.

These are structural budgets, not FPS assertions. All queues expose immutable
metrics. Task 8 implements only the load/generate and save lanes. Mesh queue and
GPU budgets remain Task 9 work; Task 8 does not change `ChunkMeshManager`.

Task 9 adds one immutable engine-owned `ChunkMeshBudget`. Production defaults
are exactly `32 accepted / 2 active / 2 owner uploads / 4 owner destructions`.
For CPU meshing, `accepted` includes manager-queued inputs, executor-active
builds, completed-but-owner-undrained results/failures, ready-for-upload data,
and a retained failed-upload payload. Moving work between those states never
releases its token. A token is released only when the exact work becomes stale,
is terminally discarded, publishes successfully (including an empty mesh), or
is explicitly discarded during shutdown. Repository candidates beyond the
accepted bound remain unclaimed; no unbounded executor backlog is created.

GPU uploads/replacements and releases remain owner-thread-only. One normal
frame pump performs at most two current uploads/publications and at most four
GPU release attempts, including unloads, stale returned replacements, empty
rebuild replacements, and old objects displaced by a successful replacement.
Owner-thread callback reentrancy does not create a second budget: nested pump
calls share the outermost pump's remaining upload and destruction allowances,
which are consumed before invoking the backend callback.
Excess release work stays in a bounded resident-derived pending queue for later
frames. Stale CPU results and stale ready-to-upload data are rejected before an
OpenGL/backend call. Shutdown is not a frame: it drains every owned GPU object,
aggregates cleanup failures, and leaves no queued/active/completed mesh state.

`ChunkMeshManager` exposes one immutable metrics snapshot covering accepted,
queued, active, completed, upload-ready/failed, and pending-destruction counts.
Task 9 does not add a second renderer authority, move OpenGL calls to workers,
change `ChunkRenderObject` coordinate/origin semantics, or start Task 10/11.

### 7.1 Exact cancelable unload reservation

`ChunkRepository.prepareStreamingUnload(ChunkKey)` is an owner-thread-only
transaction boundary. It returns an opaque, issuer-bound, single-consume
`ChunkUnloadTicket` and an immutable exact `ChunkSnapshot`. Preparation does
not advance the canonical revision or remove the resident entry. The entry is
pinned against competing unload/removal while the preparation is live.

The ticket binds repository issuer, owner thread, key, entry incarnation,
revision, and observable state/failure identity. A canonical mutation, a
replacement streaming request, or any incompatible state transition makes the
ticket stale. `validateStreamingUnload(ticket)` performs the final fail-closed
check before WorldItem hibernation. A validated live pinned ticket has only two
owner operations: `cancelStreamingUnload`, which restores the exact pre-prepare
observable state, or `commitStreamingUnload`, which deterministically removes
that exact resident entry without IO or worker work. Foreign, stale, replayed,
and wrong-thread tickets never mutate repository state.

Workers receive only the detached `ChunkSnapshot` and immutable save inputs.
They never receive `ChunkRepository`, `ChunkUnloadTicket`, logical/physical
WorldItem authorities, or owner callbacks.

### 7.2 One combined durable candidate

The existing `StreamedWorldItemPageBackend`/Task4 adapter accepts one
`StreamedChunkUnloadPlan` containing an exact detached Chunk capture, an
optional prepared `WorldItemPersistencePlan`, and only the required bounded
session/global checkpoint mutations. It reuses the frozen bounded
prepublication staging path. It does not own selection, lifecycle, IDs,
expiry, or unload policy.

One final semantic root publication makes the following visible together:

- the exact Chunk payload;
- all WorldItem page mutations;
- the WorldItem paging checkpoint;
- every required session/global checkpoint dependency.

No reader can observe only the Chunk side or only the WorldItem side. Failure,
staleness, cancellation, or crash before final publication leaves the prior
root authoritative. Success returns the existing backend-issued durable proof
for the optional WorldItem plan plus the exact committed Chunk result.

Owner ordering is fixed:

```text
prepare exact Chunk unload
prepare WorldItem hibernation/persistence when live items exist
-> worker performs bounded combined persistence
-> one root publication and durable proof
-> owner validates exact live ChunkUnloadTicket
-> existing Task 6D logical/physical hibernation transaction
-> deterministic exact Chunk unload commit
```

Persistence failure cancels both preparations. Physical hibernation failure
uses the frozen Task 6D exact rollback and then cancels the Chunk preparation.
Unexpired items and the Chunk remain resident/pinned. Durable success is never
treated as permission to evict before final owner validation and hibernation.

### 7.3 Bounded lanes and diagnostics

Each lane owns a fixed worker set and a shared accepted-work token pool:

```text
accepted == queued + active + completed-but-not-owner-drained
```

Load/generate accepted work is at most 32 with at most 4 active workers. Save
accepted work is at most 8 with at most 1 active worker. Completion does not
release a token; only queued cancellation removal or owner drain/discard does.
Thus blocked owner publication cannot turn the result queue into unbounded
memory. Cancellation is cooperative before work, between expensive stages, and
before result publication. A late canceled completion carries detached data
only and cannot publish canonical authority.

`WorldLoader.generateDetached(ChunkKey)` is the only Task 8 WorldLoader seam.
It runs the existing deterministic generator without repository mutation and
returns exact base data used for base hashing and load validation. Task 8 does
not add startup authority, source history, or a second Chunk repository.

Diagnostics consist of monotonic counters, an immutable bounded current/per-key
snapshot, and no unbounded throwable/future/history collection. A generation,
decode, or save failure latches one diagnostic for its exact key/work identity
and does not retry every frame. A newer desired identity, changed persisted
revision, or explicit retry may clear that latch.

## 8. UNKNOWN space and gameplay safety

If a Chunk required by the simulation set is not READY, its boundary is locally
unavailable. Physics prevents the player from crossing or falling into it;
raycast does not pass through it; no bedrock, AIR, or permanent voxel is
fabricated. Preloading normally prevents this barrier from becoming visible.

The HUD may display truthful `Streaming terrain...` state, blocked direction,
and queue counts, without a fabricated percentage. Debug noclip or teleport
first requests the destination simulation ring and commits the move only when
it is ready; timeout or admission rejection leaves the player at the prior safe
global position.

WorldItems reaching an unavailable boundary stop at their last valid position
and follow the service-owned dormant/persistence path. A failed Chunk remains
unavailable with a closed diagnostic until a permitted new ticket succeeds.
The game is not globally paused merely because one direction is unavailable.

## 9. Simulation-origin rebase transaction

A rebase is requested when the authoritative player crosses a configured
distance from the current origin. It is committed only on the owner thread at
a fixed-step/frame boundary:

1. freeze one immutable old/new origin pair;
2. stop fixed-step mutation and main-thread publication for the transaction;
3. convert canonical player and active item positions into new small local
   floats with checked finite/range validation;
4. prebuild all replacement transforms and collision positions;
5. atomically publish player, PhysicsWorld bodies, camera, active item
   projections, transients, and resident render transforms;
6. publish the new origin and resume work.

Workers use canonical keys/global values and are unaffected by the local
origin. If every resident consumer cannot prevalidate and commit together, the
rebase fails before publication and Phase 15 stops for design review rather
than introducing partial offsets.

Task 10 implements this as a composable transaction boundary, while Task 11
performs the one production-session wiring. `SimulationOrigin` and
`RenderOrigin` are distinct immutable engine values backed by one safe
`ChunkKey`. They are the only checked conversion path between canonical
`GlobalPosition`/global block coordinates and resident-local `Vector3f`
coordinates. Conversion rejects non-finite, out-of-envelope, or imprecisely
distant local results instead of silently rounding a global position.

`CollisionWorld` and `BlockRaycast` gain origin-aware query methods returning
an explicit `AVAILABLE`, `UNKNOWN`, or `FAILED` outcome plus the first
canonical unavailable `ChunkKey`. Availability is checked before every voxel
sample. The legacy zero-origin methods remain compatible for finite-world
callers, but origin-aware gameplay must not collapse unavailable space into an
empty `Optional` or AIR.

`UnknownSpaceBarrier` is a pure game-owned policy over immutable
`ChunkAvailability` observations. Ordinary movement and WorldItem motion stop
at the last available position. Noclip/teleport checks the complete required
destination ring and remains waiting on `UNKNOWN`; `FAILED` preserves the
failed key for diagnostics. A blocked direction does not pause unrelated
simulation and the barrier performs no IO, request admission, or repository
mutation.

`SimulationOriginCoordinator` is owner-thread-only and reentrancy guarded. A
participant preparation is side-effect-free and returns an immutable prepared
commit whose application is specified as allocation-free, callback-free, and
non-throwing. The coordinator prepares every participant before the first
commit. Preparation failure or invalid conversion publishes nothing. A
successful transaction commits all prepared player/body, camera,
WorldItem-projection, transient/particle, and Chunk-render replacements, then
publishes the new simulation/render origin exactly once. Physics bodies move
current and previous/interpolated positions together; canonical WorldItem
DTOs, Chunk keys/revisions, worker results, velocities, and allocator state do
not change. `ChunkRenderObject` replacement reuses the same GPU mesh and
changes only its origin-relative immutable model/bounds.

Task 10 supplies and directly tests the concrete participant seams. Task 11
must compose those seams at the existing owner fixed-step/frame boundary and
must convert player/save/drop/interaction values through the same committed
origin. Until that composition lands, no non-zero origin is installed into the
production session.

## 10. Metrics and diagnostics

Each frame exposes an immutable `ChunkStreamingMetrics` snapshot containing:

- player global Chunk/local position and current simulation origin;
- simulation, render, preload, resident, and unload-pending counts;
- queued/active load-generation, mesh, and save work;
- publications, uploads, bytes uploaded where available, and destructions this
  frame;
- canceled and stale-result totals;
- modified persisted and modified resident counts;
- active DTO, decoded dormant DTO, evicted-unexpired metadata, pending,
  aggregate current-live, descriptor, expiry-heap, pinned page, paging-ticket,
  tombstone/cleanup-intent, and cleanup queued/written/dropped-byte WorldItem
  metrics;
- bounded load, generation, mesh, save, and restore latency observations;
- blocked UNKNOWN directions and bounded diagnostic codes.

F3 only renders the snapshot. It does not calculate desired sets, own queues,
or trigger retries. Diagnostics use stable codes and bounded messages and do
not expose absolute paths or raw archive content.

## 11. Shutdown and recovery

Session shutdown order is deterministic:

1. stop new streaming admission;
2. freeze the final desired/resident/revision snapshot;
3. cancel discardable untouched-generation and stale mesh work;
4. complete or fail closed all modified-Chunk saves and WorldItem hibernation
   tickets;
5. on the owner thread detach renderer objects and destroy GPU resources within
   a bounded drain policy;
6. close load/generation, mesh, and save executors in dependency order;
7. report primary failure with identity-safe suppressed cleanup failures.

No shutdown timeout converts unresolved modified state into success. Repeated
startup/shutdown leaves no worker, file handle, reservation, projection, or GPU
resource alive.

## 12. Verification gates

### Gate 15A: addressing and lifecycle

- floor mapping/local round trips at positive, negative, exact boundary, and
  safe-envelope coordinates;
- checked origin/neighbor/distance/priority overflow behavior;
- stable deterministic ordering independent of collection iteration;
- coalesced duplicate requests and stale ticket rejection;
- one repository state for each key and no parallel Chunk store.

### Gate 15B: deterministic generation

- byte/hash equality across request order, reverse order, 100 unrelated
  requests, and different worker schedules;
- cardinal/diagonal height, surface, cave, strata, and decoration continuity;
- unique stable region ownership at Chunk borders;
- no mutation events and no repository/unloaded-as-air input during generation.

### Gate 15C: streamed persistence

- untouched generate/unload/regenerate exact hash;
- modified unload/reload and process-style restart exact snapshot;
- negative and safe-large Chunk keys;
- stale save cannot overwrite newer revision;
- failed save retains resident state or last known-good payload;
- same-ID WorldItem hibernate/activate and partial-pickup remainder;
- `expiresAtWorldTick` exact boundary, overflow saturation, pause/no-offline
  advance, all-due immediate semantic expiry, bounded lossy cleanup-intent
  queue, and aggregate current-live metadata at most 1,024;
- SessionPersistenceClock-only advancement and one immutable read-view restart
  validating all descriptors/pages/duplicates/cap/allocator before publication;
- generic dependency count and explicit expected-revision/hash Chunk/page
  upsert/remove, including item-only versus extension-only removal;
- private-issuer-bound opaque ticket plus backend-private marker proof, verified
  against revision/digest/current index sequence, is consumed exactly once
  before eviction; v1 expiry/paged encode fails closed and v2 restart restores
  exact state;
- Phase 14 v1 full authoritative import, failed migration recovery, and
  complete atomic v2 migration.

### Gate 15D: controller

- initial 2/4/5 desired sets and 7-radius unload eligibility;
- east/west/north/south and negative traversal;
- hysteresis without boundary thrash;
- nearest stable priority and bounded fast-travel replacement;
- UNKNOWN movement barrier and destination-ready teleport.

### Gate 15E: pipeline

- queue/admission bounds under rapid travel;
- generation, decode, mesh, save, and publication failure closure;
- unload eligibility during running generation;
- canceled/stale results cannot publish;
- owner-thread assertions for repository publication and every OpenGL action;
- deterministic shutdown with queued work and no leaked threads/handles.

### Gate 15F: precision and soak

The automated/headless soak performs at least 500 Chunk transitions, including
negative travel, direction reversals, modified-Chunk returns, WorldItem
hibernation and expiry, stale work, and origin rebases. It asserts:

- resident count returns to the configured structural bound;
- queues never exceed their bounds and do not grow monotonically with distance;
- stale work is discarded;
- untouched hashes remain deterministic;
- modified bytes and stable IDs reload exactly;
- local render/physics transforms stay small and finite;
- raycast/collision identifies the correct canonical global block;
- active DTO + decoded dormant DTO + evicted-unexpired metadata + unique pending
  WorldItems never exceed 1,024; survivor sum, metadata, and expiry-index agree;
  physical descriptor and dependency counts agree separately;
- due items are immediately absent from live interaction even when bounded
  cleanup fails or its full queue drops the rediscoverable intent;
- revisited expired pages converge through bounded cleanup without a global
  scan or retained expired history;
- retained authority size is bounded by current resident policy rather than
  total traversal distance.

Wall-clock latency and memory observations are recorded separately. CI has no
brittle FPS threshold.

Windows and Apple Silicon macOS each require real create/load, long traversal
beyond the former radius, negative coordinates, visible seam inspection,
return across unload boundaries, distant modification unload/reload, Save &
Quit/relaunch verification, WorldItem boundary behavior, bounded debug metrics,
resize/focus, native OpenGL/OpenAL, installDist, and clean shutdown. Evidence is
recorded as automated, human-reported, pending, or failed without inference or
invented environment/test/duration values.

## 13. Extension contracts

Phase 16 sparse detail state uses the versioned optional extension table inside
the same streamed Chunk payload and follows normal load/save/unload lifecycle.
Phase 15 does not implement 4x4x4 occupancy or tools.

Phase 19 consumes a deterministic region/structure decision input derived from
world seed, generator/stage version, and stable signed region coordinates. One
region authority decides an anchor; neighboring Chunks deterministically
consume and clip the decision. Phase 15 does not add large POIs or biome
content.

Future structural physics consumes canonical Chunk/mutation/detail ownership
events. Phase 15 does not detach terrain or create moving voxel assemblies.

## 14. Stop conditions

Implementation stops for design review if:

- it requires a second ChunkRepository, World store, save root, or WorldItem
  authority;
- the approved `int ChunkKey` envelope cannot support a required operation;
- v1 migration or a modified-Chunk failure can discard the last known-good
  player change;
- WorldItem paging cannot preserve stable IDs, complete current-live
  metadata/descriptors, immutable-view all-page restore, exact expiry,
  durable-before-evict ordering, or the aggregate 1,024 current-live cap;
- WorldItem lifetime would require wall-clock/offline time, a second age field,
  or refresh on pickup/movement/paging;
- expiry cleanup requires a global page scan, owner directory/trie, opaque blob
  graph, catalog/refcount/GC system, database, or maintenance overlay;
- semantic expiry waits for durable cleanup, or cleanup/tombstone work creates
  an unbounded retained backlog;
- restart publishes before all described pages/survivors/duplicates/caps/
  allocator are validated, or runtime activation depends on load order;
- generation depends on request order, thread order, loaded neighbors, or
  unloaded-as-air behavior;
- worker code must call OpenGL/GLFW or mutate context-owned resources;
- queues/admission cannot remain bounded;
- an atomic simulation-origin rebase cannot update every resident
  physics/presentation consumer;
- UNKNOWN space must be treated as AIR to keep simulation running;
- Phase 15 expands into detail voxels, large POIs, infinite Y, LOD, multiplayer,
  or structural physics.

## 15. Deliverables and completion

Phase 15 delivers the implementation plan and task reports, this design,
`docs/architecture/infinite-world-streaming.md`, a lifecycle diagram, addressing
contract, deterministic hash fixtures, streamed persistence and WorldItem
paging policies, budgets/metrics, long-distance soak evidence, platform
acceptance, known limits, and a Phase 15 handoff.

Definition of done requires travel beyond the former finite boundary without
pre-generating explored distance, structurally bounded resident/queue state,
deterministic untouched regeneration, exact modified reload and restart,
negative-coordinate support, hysteresis, stale-safe/cancelable bounded work,
single Chunk and WorldItem authorities, owner-thread-only GPU work, and honest
Windows/macOS status.
