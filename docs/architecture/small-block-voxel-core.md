# Small-Block Voxel Core

## Status and scope

Phase 16 implements one production DETAIL scale: `4 x 4 x 4`. A subvoxel is exactly `0.25 x 0.25 x 0.25` parent-block units. Ordinary generation remains FULL-only; DETAIL exists only in explicitly converted or edited parent cells. Natural DETAIL generation, survival chisels, material-unit accounting, drops, LOD, additional scales, fluids, and moving assemblies are not part of Phase 16.

## Canonical authority

`ChunkRepository` is the only resident Chunk, revision, dirty, unload-ticket, and stale-publication authority. A parent coordinate has one typed state:

```text
ParentCellState
  FULL   -> FullCellState(runtime byte block ID)
  DETAIL -> DetailCellState(occupancy mask + 64 runtime byte block IDs)
```

Sparse DETAIL membership is the physical discriminator:

- no DETAIL entry means FULL, including FULL AIR (`block ID 0`);
- a DETAIL entry requires backing FULL byte `0`;
- a stored DETAIL entry always has `occupancyMask != 0`;
- an occupied bit requires a non-AIR runtime ID;
- an unoccupied slot requires runtime ID `0`;
- clearing the final occupied slot atomically converts DETAIL to FULL AIR;
- placing the first subvoxel into FULL AIR atomically creates DETAIL;
- the shared empty DETAIL snapshot is a non-owning API value only. It is never stored, serialized, revision-authorized, meshed, raycast, or collision-authorized.

DETAIL data is sparse and Chunk-owned. A FULL-only Chunk allocates no `DetailStorage`. The hard canonical limit is 1,024 DETAIL parents per Chunk; exceeding it rejects the mutation or restore without partial publication.

## Deterministic coordinates and identity

The only Phase 16 scale is `VoxelScale.DETAIL_4`. Local indexing is X-fastest:

```text
index = x + 4*y + 16*z
x,y,z in [0,3]
```

Engine storage uses the same runtime byte block IDs as FULL storage. Game and persistence code translate those IDs through the existing `BlockRegistry` to canonical `ResourceLocation` identities. Engine Chunk storage has no game-layer resource dependency and no second material registry.

Canonical parent identity uses global integer voxel coordinates and `ChunkKey`. `SimulationOrigin` translates presentation/simulation-local coordinates without changing canonical identity.

## Mutation transaction

`GaiaDetailMutationService` is a game adapter over the engine `DetailMutationService`; the actual compare-and-set and publication occur in `ChunkRepository`.

```text
typed request
  -> validate Chunk key, expected Chunk revision, expected parent state,
     selected subvoxel old occupancy/material, and requested material
  -> atomically mutate FULL bytes + sparse DETAIL entry
  -> allocate one canonical Chunk revision
  -> mark the ordinary voxel-modified/dirty authority
  -> invalidate owner and the existing face/AO neighbors
```

Rejected requests make no storage, revision, dirty, neighbor, ticket, or persistence change. There is no DETAIL revision or DETAIL dirty tracker.

FULL-to-DETAIL conversion is one repository mutation: the original FULL ID becomes all 64 occupied DETAIL IDs while the backing byte becomes AIR. DETAIL-to-FULL AIR after the final clear is likewise one mutation. Phase 16 does not opportunistically compact a uniform DETAIL parent to FULL.

## Immutable snapshots and worker ownership

`ChunkSnapshot` is the sole detached canonical carrier. It contains exact FULL bytes and a canonically ordered immutable `DetailChunkSnapshot`. Equality and canonical persistence comparisons include both.

`ChunkMeshInput` remains exactly nine `ChunkSnapshot` components: center and the eight horizontal neighbors. `ChunkMeshingClaim` remains the separate `claimId/key/revision/input` capability. Workers receive immutable snapshots only; they do not read mutable `World`, `ChunkRepository`, or `Chunk`, publish repository state, or call OpenGL.

## Raycast

`BlockRaycast` remains the only world-level DDA. Each parent step observes `AVAILABLE`, `UNKNOWN`, or `FAILED` typed state:

- FULL uses the existing block collision/raycast shape;
- DETAIL refines the segment through that parent against at most 64 exact quarter AABBs;
- a DETAIL gap continues the same parent DDA;
- UNKNOWN and FAILED propagate and never become AIR or a miss.

A DETAIL hit records canonical parent coordinates, local subvoxel coordinates, face, canonical world point, distance, runtime material identity, owning Chunk revision, and DETAIL provenance. It grants no mutation authority; Gate 16B compare-and-set revalidates every later edit. Legacy normal break/place explicitly leaves DETAIL non-mutating.

## Collision

`CollisionWorld` remains the static sweep, move-and-slide, overlap, and depenetration kernel. The typed parent observation seam resolves DETAIL into deterministic AABBs using lowest-index scan and fixed expansion order. Quarter coordinates are converted directly from integers, so boundaries are exactly `0`, `0.25`, `0.5`, `0.75`, and `1`.

The union covers every occupied cell exactly once, fabricates no empty geometry, and has an absolute bound of 64 boxes per parent. No rigid body or persistent/global collision cache is created. UNKNOWN remains fail-closed.

## Mesh and GPU lifecycle

One owning Chunk produces one CPU mesh and one existing GPU lifecycle:

```text
ChunkRepository claim
  -> ChunkMeshingClaim
  -> nine-snapshot ChunkMeshInput
  -> existing CPU worker
  -> detached ChunkMeshData
  -> owner claim/revision validation
  -> existing upload/replacement/destruction path
```

FULL-only inputs preserve the legacy fast path. Hybrid inputs sample only canonical quarter regions affected by DETAIL. Internal occupied DETAIL faces are removed. FULL/DETAIL seams use complementary deterministic 4x4 coverage: occupied-to-occupied is hidden, and visible empty quarters receive exactly one surface. DETAIL/DETAIL and cross-Chunk seams use the same immutable sampler. FULL clipped patches retain parent-face UV subregions; DETAIL faces use their material's full face region. AO follows the existing two-side-plus-corner convention and incompatible AO signatures do not merge.

No DETAIL mesh manager, queue, upload lane, render object per parent, revision, or GPU authority exists.

## Resource policies

Canonical data and renderability are distinct. Legal canonical data is never deleted or rewritten because rendering is too expensive.

Current Phase 16 limits:

- DETAIL parents per Chunk: 1,024;
- hybrid detached output: 8,388,608 bytes;
- all-Chunk CPU mesh lifecycle budget: 134,217,728 bytes;
- accepted meshes: at most 32;
- active mesh workers: at most 2;
- uploads: at most 2 per owner frame;
- GPU destructions: at most 4 per owner frame;
- aggregate Chunk publication plus mesh upload: at most 2 per owner frame.

The current non-indexed format is 40 bytes per vertex and six vertices per facelet. The largest complete hybrid output under the byte cap is 34,952 facelets, 209,712 vertices, and 8,388,480 bytes. Checked preflight stops before the next allocation/emission and reports `MESH_OUTPUT_LIMIT_EXCEEDED`; it never publishes an empty AIR mesh. A deterministic failure is latched per revision to prevent retry storms. A newer revision or explicit retry may rebuild. Existing last-known-good presentation may remain, but is not reported current for the failed revision.

Every FULL, DETAIL, and hybrid job also reserves its complete active-build heap requirement before entering ACTIVE. Global pressure leaves a job QUEUED. A single job that cannot fit the 128 MiB budget reports distinct `MESH_MEMORY_BUDGET_EXCEEDED`. The manager accounts active reservation, completed retained output, upload heap scratch, and direct upload bytes separately, releases every transition exactly once, and preserves the existing count and priority semantics.

Both resource-limit outcomes remain repository-latched mesh diagnostics for the exact canonical revision; they do not enter the manager's fatal worker-failure channel or terminate the gameplay session. Unexpected worker, upload, and lifecycle failures retain the existing fatal reporting behavior.

Production preflight determines the exact float count. The builder allocates one exact-sized backing `float[]`, checks its cursor, then retains the existing `toArray()` and public `ChunkMeshData` defensive-copy semantics. Ownership-transfer Steps B and C were measured unnecessary and are not implemented.

## Development tooling

Development shortcuts are enabled only through the existing debug-shortcut configuration:

- `F9`: inspect target;
- `F10`: convert targeted non-AIR FULL parent to uniform DETAIL;
- `F11`: fill the exact targeted DETAIL subvoxel with stone;
- `F12`: clear the exact targeted DETAIL subvoxel;
- `9`: cycle the bounded fixture selection;
- `0`: apply the selected one-parent fixture.

`Ctrl+F9` and `Ctrl+F10` remain compatibility aliases. The dedicated number-row
keys are the production acceptance controls because Windows recording overlays
intercepted `Alt+F9`, while synthesized modifier chords did not remain held until
the fixed-step input snapshot.

Fixtures are single quarter, quarter slab, thin wall, staircase, hollow opening, asymmetric, checkerboard, uniform full, and mixed material. The adapter calls typed targeting and canonical mutation services only. Output includes parent, Chunk key/revision, local coordinates, occupancy, material, hash, collision-box count, mesh phase, last-known-good presence, and a bounded current diagnostic. Heavy multi-parent stress patterns remain test/tool-only.

## Known measured observations and deferrals

The accepted production-equivalent `-Xmx512m` mixed stress peaked at 334,315,448 bytes (62.27%), four collections/15 ms, with a four-run heap envelope of 58.00% to 62.27%. These are M2 resource observations, not universal hardware guarantees.

Repeated sparse DETAIL insertion during canonical restore creates substantial temporary allocation churn. Attribution showed it dead before the warmed mesh lifecycle baseline and did not prove unacceptable user-facing load behavior. Restore construction remains unchanged pending process-restart evidence.

Future deterministic natural DETAIL generation belongs to revised Phase 19 and must use the same canonical representation, a generated budget substantially below 1,024, seed/version/coordinate reproducibility, and base-generation publication rather than gameplay mutation. Revised Phase 17/18 may build coarse-parent and precision-subvoxel harvest semantics on existing hit/mutation contracts; no provenance or harvest field belongs in Phase 16 state.
