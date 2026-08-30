# Phase 16 Small-Block Voxel Core Design

**Status:** Controller-approved architecture; Gates 16A-16D implemented and accepted, Gate 16E closure preparation in progress.

**Baseline:** `feat/small-voxel-core` at `fa852fbef2d2292a5778e385b8775b8c81f70ad1`.

**Scope:** Milestone 2 Phase 16, production DETAIL scale `4 x 4 x 4` only.

## 1. Purpose

Phase 16 adds a sparse small-block representation to explicitly converted parent block cells without replacing the normal `1 x 1 x 1` world. A canonical parent coordinate has exactly one gameplay representation:

- `FULL`, containing one existing runtime byte block ID; or
- `DETAIL`, containing exactly 64 logical quarter-scale subvoxels.

Default world generation remains FULL-only. DETAIL state belongs to the canonical owning `Chunk`, shares its revision, dirty, streaming, mesh, and unload lifecycle, and is absent from ordinary Chunks.

This is not a whole-world microvoxel conversion, a second world, or a second persistence root.

## 2. Non-goals

Phase 16 does not add additional detail scales, world-generation DETAIL cells, survival chiseling or material accounting, fluids in DETAIL cells, moving assemblies, structural collapse, multiplayer, LOD, curved geometry, or one render object/rigid body per subvoxel.

## 3. Governing decisions

1. `ChunkRepository` remains the sole resident Chunk, revision, dirty, stale-result, and unload authority.
2. Sparse DETAIL table membership is the physical FULL/DETAIL discriminator.
3. A DETAIL parent's backing FULL byte is always canonical AIR (`0`).
4. An empty DETAIL state is forbidden. Clearing its final occupied subvoxel atomically produces FULL AIR.
5. Engine storage uses existing runtime byte block IDs. `ResourceLocation` remains a game/save identity and never enters engine Chunk storage.
6. `ChunkSnapshot` is the sole detached canonical DETAIL carrier.
7. `ChunkMeshInput` remains exactly nine `ChunkSnapshot` components. `ChunkMeshingClaim` remains the separate lifecycle capability.
8. The existing `detail-blocks` streamed Chunk extension is used. Presence is optional; interpretation is required when present.
9. `BlockRaycast` remains the sole world DDA. `CollisionWorld` remains the static voxel collision kernel.
10. DETAIL mesh generation uses the existing Chunk mesh claim, worker, completion, upload, and destruction lifecycle.

## 4. Authority and data-flow diagrams

### 4.1 Canonical resident authority

```text
owner/context thread
  |
  v
DetailMutationService (validation/events only)
  |
  v
ChunkRepository atomic CAS + revision reservation + dirty outcome
  |
  v
ChunkRepository.Entry
  |
  +-- Chunk FULL backing bytes
  |     DETAIL parent byte is always 0
  |
  +-- Chunk sparse DetailStorage
        membership is the physical discriminator
        no entry means FULL
        entry means DETAIL
```

There is no detail revision, dirty set, repository, resident map, or unload lifecycle outside the owning `ChunkRepository.Entry`.

### 4.2 Detached mesh flow

```text
ChunkRepository owner thread
  -> capture center + eight immutable ChunkSnapshots
  -> revalidate center revision
  -> ChunkMeshingClaim(claimId, key, revision, ChunkMeshInput)
  -> worker receives ChunkMeshInput only
  -> worker reads FULL + DETAIL from the nine snapshots
  -> detached ChunkMeshData(key, revision, vertices)
  -> owner rejects stale revision or uses existing GPU upload budget
```

### 4.3 Streamed save/unload flow

```text
canonical Chunk
  -> exact ChunkSnapshot(full bytes + ordered DETAIL)
  -> game BlockRegistry maps occupied runtime IDs to ResourceLocations
  -> DetailBlocksCodec v1 produces required-if-present extension bytes
  -> existing StreamedChunkPayload
  -> existing bounded staging and one semantic root publication
  -> exact durable revision proof
  -> owner-thread unload-ticket commit
```

### 4.4 Streamed load flow

```text
existing streamed Chunk root/index
  -> StreamedChunkCodec validation
  -> detail-blocks absent: empty DetailChunkSnapshot
  -> detail-blocks present: required DetailBlocksCodec v1 decode
  -> BlockRegistry ResourceLocation-to-runtime-ID resolution
  -> detached ChunkGenerationData(full bytes + DETAIL)
  -> owner-thread ChunkRepository publication
  -> one canonical resident Chunk
```

Unknown, malformed, unsupported, duplicate, or unresolvable DETAIL data fails closed before repository publication.

### 4.5 Typed read flow

```text
gameplay / raycast / collision / mesh / save capture
  -> typed parent observation
  -> FULL(blockId) or DETAIL(immutable DetailCellState)
  -> no byte-only fallback
```

Raw FULL bytes remain available only to explicitly paired storage, generation, and codec internals. A raw read of a DETAIL coordinate must throw rather than return AIR.

## 5. Exact engine runtime representation

### 5.1 Public canonical values

The engine adds:

```java
public enum VoxelScale {
    DETAIL_4(4)
}

public record LocalSubVoxelPosition(int x, int y, int z) {
    public int index();
}

public sealed interface ParentCellState
        permits FullCellState, DetailCellState {}

public record FullCellState(byte blockId)
        implements ParentCellState {}

public final class DetailCellState
        implements ParentCellState {
    public static final int CELL_COUNT = 64;
    public long occupancyMask();
    public byte blockId(LocalSubVoxelPosition position);
    public byte[] copyBlockIds();
}
```

`VoxelScale` exposes only `DETAIL_4` in Phase 16. No generalized dynamic scale is accepted by production APIs.

### 5.2 Deterministic indices

Subvoxel coordinates use X-fastest order:

```text
subIndex = subX + 4 * subY + 16 * subZ
subX = subIndex & 3
subY = (subIndex >>> 2) & 3
subZ = (subIndex >>> 4) & 3
```

The parent index matches the existing flat Chunk byte order:

```text
parentIndex = localX
            + localY * 16
            + localZ * 16 * worldHeight
```

For `worldHeight <= 256`, `parentIndex` is in `[0, 65535]` and is stored as an unsigned 16-bit value in compact tables and on disk.

### 5.3 DetailCellState invariant

For every index `i` in `[0, 63]`:

```text
occupied = ((occupancyMask >>> i) & 1L) != 0

occupied     => blockIds[i] != 0
not occupied => blockIds[i] == 0
```

Additional requirements:

- `occupancyMask != 0`;
- `blockIds.length == 64` at construction;
- constructor input and returned arrays are defensively copied;
- no null material values exist;
- equality compares the mask and all 64 IDs;
- hash code includes the mask and all 64 IDs;
- validation rejects a nonzero ID under a clear bit or AIR under a set bit.

The engine can validate nonzero byte structure. The game adapter validates that every nonzero runtime ID exists in the injected `BlockRegistry`.

### 5.4 Sparse Chunk storage

`Chunk` gains an internal optional physical `DetailStorage` reference. The reference is absent when the sparse table has no entries and is never exposed as a canonical nullable parent state. Ordinary FULL-only Chunks therefore allocate no DETAIL object or arrays; every public canonical read still returns a non-null sealed `FullCellState` or `DetailCellState`.

`DetailStorage` uses canonically sorted parallel arrays:

```text
short[] parentIndices       // unsigned, strictly ascending
long[] occupancyMasks       // one per parent
byte[] blockIds             // flattened entryCount * 64
```

Every stored `DetailStorage` has at least one entry and exact-length arrays; there is no stored empty table and no unbounded spare capacity. Mutation under the repository entry lock creates or removes one sorted entry and validates the complete resulting table before publication. Removal of the last entry removes the physical table reference entirely and the parent is canonically FULL AIR.

For a parent coordinate:

```text
detail entry absent:
    canonical state = FULL(backing byte)

detail entry present:
    require backing byte == 0
    canonical state = DETAIL(entry)
```

No public canonical method implements `AIR byte + maybe entry` precedence. It performs one typed lookup and returns the sealed result. Mutable `Chunk` typed access is repository-internal and requires the existing entry lock; public consumers use an atomic repository observation or an immutable `ChunkSnapshot`. This lock discipline makes the two physical field writes one externally atomic canonical publication.

An implementation may expose a shared non-null immutable empty DETAIL *view* to avoid caller-side nulls. Such a view is non-owning and is never installed in a `Chunk`, retained as canonical parent state, serialized as `detail-blocks`, revision-authorized, or interpreted by mesh, raycast, or collision as DETAIL. Absence of a sparse entry always resolves to FULL, including FULL AIR with block ID `0`.

### 5.5 Per-Chunk DETAIL cap and memory proof

Phase 16 v1 sets:

```text
MAX_DETAIL_PARENTS_PER_CHUNK = 1024
```

The cap is derived, not guessed:

1. The exact v1 codec permits a theoretical 13,721 worst-case entries under the existing 1 MiB extension bound; Section 9 proves this.
2. A compact immutable DETAIL snapshot uses exact backing payload:

```text
2 bytes parent index
+ 8 bytes occupancy mask
+ 64 bytes runtime IDs
= 74 bytes per entry
```

3. At 1,024 entries, backing arrays use `75,776` bytes. Reserving `256` bytes for the fixed wrapper and array headers gives a conservative `76,032`-byte snapshot bound.
4. The fixed Phase 15 mesh envelope can hold 32 accepted claims, each with nine snapshots. If all are distinct worst cases, DETAIL backing is bounded at:

```text
76,032 * 9 * 32 = 21,897,216 bytes
```

5. Existing FULL bytes in the same envelope are:

```text
65,536 * 9 * 32 = 18,874,368 bytes
```

6. Combined canonical backing remains bounded at `40,771,584` bytes before fixed object overhead, keeping Phase 16 added detached data in the same order as the existing FULL snapshot envelope.

The 1,024 cap supports the required 1, 64, and 256-parent fixtures while leaving a fourfold margin above the required measured case. Exceeding it returns an explicit `CAPACITY_EXCEEDED` mutation or decode failure. Nothing evicts or drops DETAIL entries to make space.

## 6. ChunkSnapshot contract

`ChunkSnapshot` adds one immutable `DetailChunkSnapshot` field. Its canonical state is:

```text
ChunkSnapshot
  key
  revision
  worldHeight
  fullBlocks[16 * worldHeight * 16]
  DetailChunkSnapshot
    sorted parentIndices
    occupancyMasks
    flattened blockIds
```

Requirements:

- every DETAIL parent has `fullBlocks[parentIndex] == 0`;
- the detail table is ordered, unique, nonempty per entry, and capped at 1,024;
- factories defensively copy full and detail arrays;
- `equals` and `hashCode` include key, revision, height, FULL bytes, and DETAIL;
- `canonicalContentEquals` compares height, FULL bytes, and DETAIL while excluding key/revision;
- `canonicalContentHash` hashes height, FULL bytes, ordered parent indices, masks, and all runtime IDs in a fixed byte order;
- `cellState(localX, y, localZ)` is the canonical typed read;
- `copyFullBlocks()` is explicitly incomplete without `details()` and is restricted to paired generation/codec use;
- byte-only `getBlock()` is compatibility-only and throws if the coordinate is DETAIL.

`ChunkRepository.snapshot`, canonical save capture, unload capture, restore, and meshing claims all use this one type. No detail lifecycle handle, repository reference, claim ID, mutable map, or callback appears in it.

`ChunkMeshInput` remains exactly:

```text
center, north, northEast, east, southEast,
south, southWest, west, northWest
```

all typed as `ChunkSnapshot`.

## 7. FULL/DETAIL conversion state machine

### 7.1 States

```text
FULL AIR
FULL NON_AIR(id)
DETAIL(mask != 0, ids[64])
```

There is no persistent empty DETAIL state.

### 7.2 Transitions

```text
FULL NON_AIR(id)
  -- convertFullToDetail -->
DETAIL(mask = 0xffffffffffffffff, ids[0..63] = id)

FULL AIR
  -- set first subvoxel to id -->
DETAIL(mask = bit(index), ids[index] = id, all others = 0)

DETAIL
  -- set or replace occupied subvoxel -->
DETAIL(updated mask/ids)

DETAIL with more than one occupied cell
  -- clear one occupied subvoxel -->
DETAIL(updated nonzero mask/ids)

DETAIL with exactly one occupied cell
  -- clear final occupied subvoxel -->
FULL AIR

DETAIL(all 64 occupied, one exactly compatible id)
  -- explicit compactDetailToFull -->
FULL NON_AIR(id)
```

Automatic homogeneous compaction is not performed. Only final-clear-to-AIR is automatic because empty DETAIL is forbidden. Non-AIR compaction requires an explicit request and exact compatibility proof.

### 7.3 Atomic physical updates

FULL to DETAIL, DETAIL to FULL AIR, and explicit DETAIL to FULL non-AIR each execute inside one repository entry lock:

1. revalidate entry incarnation, lifecycle, expected Chunk revision, representation, and expected old state;
2. invalidate a live but not final-validated unload preparation;
3. prepare the replacement full byte and replacement detail table off to the side;
4. validate `DETAIL => full byte 0` and all detail invariants;
5. reserve the owning and neighbor revisions;
6. publish both physical structures to the `Chunk` before releasing the lock;
7. set one new owning Chunk revision, `DIRTY`, `voxelModified`, and clear failure;
8. publish the repository-owned dirty-neighbor outcome.

No observer can see both a nonzero FULL byte and a DETAIL entry or neither during conversion.

## 8. Mutation, revision, and dirty transaction

### 8.1 Service boundary

The narrow `DetailMutationService` owns main-thread validation and game-facing requests. It does not own storage, revisions, or dirty state. It injects:

- `MainThreadGuard`;
- a game adapter backed by the existing `BlockRegistry`;
- `ChunkRepository` as the canonical transaction authority;
- existing interaction context/event publication where applicable.

Debug commands and future gameplay call the service only.

### 8.2 Exact request identity

Every request carries:

- `InteractionContext`, including actor/cause and simulation tick;
- canonical parent block X/Y/Z;
- expected owning Chunk revision;
- expected sealed `ParentCellState` captured from a typed observation;
- for a subvoxel edit, exact `LocalSubVoxelPosition`;
- requested replacement runtime block ID, where `0` means clear;
- for explicit compaction, the requested FULL runtime block ID.

The game service resolves a requested `ResourceLocation` through the existing `BlockRegistry` before calling the repository. Unknown identities are rejected without mutation.

### 8.3 Rejection statuses

Repository outcomes are explicit:

- `APPLIED`;
- `NO_CHANGE`;
- `OUT_OF_BOUNDS`;
- `UNKNOWN_CHUNK`;
- `FAILED_CHUNK`;
- `STALE_CHUNK_REVISION`;
- `REPRESENTATION_CONFLICT`;
- `EXPECTED_STATE_CONFLICT`;
- `INVALID_BLOCK_ID`;
- `INVALID_COMPACTION`;
- `CAPACITY_EXCEEDED`;
- `UNLOAD_FINALIZED`.

`APPLIED` carries old and new immutable parent states, the resulting owning Chunk revision, and exact `DirtyChunkRevision` values. Every rejection carries the observed revision/state when safe and an empty dirty list.

The owning Chunk revision is the only detail freshness token. A hit or UI snapshot from any earlier Chunk revision is stale even if its targeted local subvoxel happens to be unchanged.

### 8.4 Boundary invalidation

The existing `ChunkDirtyTracker.affectedByBlock` remains the dependency calculator. A DETAIL edit at parent `localX == 0/15` or `localZ == 0/15` dirties the same face and diagonal AO neighbors as a FULL edit. There is no subvoxel mesh owner or subvoxel dirty tracker.

## 9. Exact DetailBlocksCodec v1 layout

### 9.1 Extension descriptor semantics

The enclosing `StreamedChunkPayload.ExtensionDescriptor` is:

```text
sectionId = detail-blocks
codecVersion = 1
required = true
```

The descriptor is absent when the Chunk has no DETAIL entries. A present descriptor with `required == false` is malformed and fails closed. `StreamedExtensionSupportRegistry.productionDefaults()` supports required `detail-blocks` version 1, not optional interpretation.

The outer `StreamedChunkCodec` version does not need to change because its versioned extension table already exists. The extension's own version is never silently reinterpreted.

### 9.2 Canonical payload bytes

All multibyte integers are big-endian.

| Offset | Size | Field | Canonical rule |
|---:|---:|---|---|
| 0 | 4 | magic | ASCII `GLD1` |
| 4 | 1 | scale | unsigned value `4` |
| 5 | 1 | flags | exactly `0` |
| 6 | 2 | palette count | unsigned `1..255` |
| 8 | 4 | detail parent count | unsigned `1..1024` |

Header size is exactly 12 bytes.

Each palette entry is:

```text
u16 resourceLocationUtf8Length
u8[length] canonical ResourceLocation.toString() bytes
```

Palette rules:

- each UTF-8 name is `1..128` bytes;
- names parse through `ResourceLocation.parse`;
- AIR is forbidden;
- entries are strictly ascending by canonical `ResourceLocation` string;
- duplicates are forbidden;
- the game registry must resolve every name to a unique nonzero runtime byte ID;
- wire material codes are one-based palette positions `1..paletteCount`; `0` means AIR.

Each sparse parent entry is exactly 74 bytes:

| Size | Field |
|---:|---|
| 2 | unsigned parent index |
| 8 | occupancy mask |
| 64 | material codes in deterministic subIndex order |

Entry rules:

- parent indices are strictly ascending and within the payload world height;
- duplicate parents fail;
- occupancy mask is nonzero;
- set bit requires material code `1..paletteCount`;
- clear bit requires material code `0`;
- decoded backing FULL byte at that parent must be `0`;
- trailing bytes fail.

Unknown palette names, unsupported scale, nonzero flags, invalid counts, noncanonical order, malformed occupancy, unknown codes, and registry ID `0` all produce bounded diagnostics and no repository publication.

### 9.3 Worst-case size proof

The existing extension limit is:

```text
1 MiB = 1,048,576 bytes
```

Worst-case palette size is 255 entries, each with a 2-byte length and 128 UTF-8 bytes:

```text
255 * (2 + 128) = 33,150 bytes
```

Fixed header plus worst palette:

```text
12 + 33,150 = 33,162 bytes
```

Every DETAIL parent is exactly 74 bytes, so the wire-only theoretical maximum is:

```text
floor((1,048,576 - 33,162) / 74) = 13,721 parents
```

At 13,721 parents:

```text
33,162 + 13,721 * 74 = 1,048,516 bytes
```

The next entry would require `1,048,590` bytes and exceed the bound.

Phase 16 deliberately applies the lower runtime/snapshot cap of 1,024. Its exact worst-case extension is:

```text
33,162 + 1,024 * 74 = 108,938 bytes
```

This leaves `939,638` bytes of the existing extension limit unused and does not require raising any Phase 15 payload or staging bound.

### 9.4 No-op and durable revision semantics

Canonical persistence equality includes:

- world height;
- all backing FULL bytes;
- canonical DETAIL extension presence and bytes;
- other required streamed extensions under their existing rules.

A save/unload may report a no-op only when the durable authority already carries the exact same Chunk revision and canonical content. A higher resident revision with byte-identical content is not durable merely because its payload bytes match an older revision.

Rules:

1. If resident revision equals the already durable revision and canonical FULL+DETAIL content matches, perform no Chunk write and return no new persisted-revision acknowledgement.
2. If resident revision is greater, an upsert or required root/index mutation must durably publish that exact revision before it can be acknowledged.
3. Removing an obsolete modified payload because the Chunk returned to generated FULL-only state is a real root/index transaction, not a no-op.
4. `StreamedChunkUnloadResult.persistedChunkRevision` is present only when the current operation actually durably published that exact revision.
5. An empty persisted-revision result never advances `ChunkRepository.persistedRevision`.

These rules preserve the PR #27 correction.

### 9.5 Backward compatibility

- Phase 14 archives migrate to streamed FULL bytes with no DETAIL extension.
- Existing streamed v2/v3 payloads without `detail-blocks` decode as FULL-only.
- Existing optional test fixtures using `detail-blocks` bytes are not production canonical detail and must be updated; production optional interpretation is no longer accepted.
- No second save root or standalone Phase 14 DETAIL section is introduced.

## 10. Mesh FULL/DETAIL boundary algorithm

### 10.1 Canonical quarter sampler

The mesher defines one immutable sampler over `ChunkMeshInput`:

```text
sample(parentX, parentY, parentZ, subX, subY, subZ)
    FULL AIR       -> empty
    FULL non-AIR   -> occupied by the FULL block ID for all 64 samples
    DETAIL         -> occupancy/material at subIndex
```

Subcoordinates leaving `[0,3]` wrap into the adjacent parent coordinate. Horizontal parent coordinates crossing a Chunk boundary resolve exclusively through the corresponding neighbor `ChunkSnapshot` already in `ChunkMeshInput`. Vertical coordinates stay in the center snapshot; outside world height is empty.

The sampler never calls a raw byte API for a DETAIL parent.

### 10.2 Face ownership

For every oriented quarter-grid interface, derive `surfaceSolid` as `occupied && renderResolver.resolve(unsignedId).renderable()`. This preserves the existing FULL mesher's visibility rule for non-renderable IDs. Face ownership is then:

| Current surface | Neighbor surface | Result |
|---|---|---|
| not solid | not solid | no face |
| solid | solid | no face, regardless of material |
| solid | not solid | current sample owns exactly one outward facelet |
| not solid | solid | no current face; the neighbor owns it when processed |

This single-sided rule prevents coplanar duplicate faces and Z-fighting.

### 10.3 Parent boundary cases

**FULL to FULL:** Existing behavior remains: occupied/occupied hides the face; occupied/AIR emits the existing full quad.

**FULL to DETAIL:** Evaluate the shared face as a deterministic `4 x 4` mask. The FULL block emits facelets only where the adjacent DETAIL boundary sample is empty. Occupied DETAIL samples suppress the corresponding FULL face region. The DETAIL block emits nothing into occupied FULL regions.

**DETAIL to DETAIL:** Compare matching quarter samples. Only occupied-to-empty interfaces emit.

**DETAIL to AIR:** Every occupied boundary sample emits; empty DETAIL gaps remain open.

**Cross-Chunk DETAIL:** The same logic uses the immutable neighbor snapshot. No repository or World read occurs on the worker.

### 10.4 Geometry and UV rules

- All coordinates are exact multiples of `0.25f` produced from integer quarter coordinates.
- DETAIL facelets use the owning subvoxel material and map its complete block-face atlas region onto the quarter face, treating each occupied subvoxel as a small block.
- A split FULL face retains the FULL block's texture ownership. Its quarter facelets crop the original parent-face UV region by quarter coordinates, so the visible fragments reconstruct the unchanged FULL texture.
- Faces between two occupied materials are hidden because they are internal geometry.
- FULL face masks may be greedily merged in row-major order only when face, material, cropped UV continuity, and four AO values are compatible.
- DETAIL facelets are not merged across subvoxels in v1 because full-tile-per-subvoxel UV ownership must not be changed. Hidden-face elimination supplies the required bounded local optimization.

Worst-case visible DETAIL geometry is bounded by 64 occupied cells times six faces. No face is dropped to meet a vertex budget.

### 10.5 AO at quarter boundaries

FULL-only neighborhoods retain the existing parent-grid AO path and its current vertex output.

Any face involving DETAIL uses the existing three-sample voxel AO rule on the quarter grid. At each facelet vertex, two side samples and the diagonal corner sample are resolved through the canonical quarter sampler. A FULL sample occludes all corresponding quarter positions only when the existing resolver says it is renderable and its material render type is not `TRANSPARENT`; a DETAIL sample applies that same rule to its occupied runtime ID. Material identity otherwise does not affect occlusion in v1.

This produces deterministic AO across FULL/DETAIL and Chunk boundaries without mutable World reads. Adjacent facelets share exact positions; different AO values may create an intentional shading edge but cannot create a geometric crack.

## 11. Raycast parent refinement algorithm

`BlockRaycast` retains its current coarse DDA, event comparison, checked global coordinate conversion, and Y/X/Z tie priority.

For each coarse parent candidate:

1. obtain one atomic typed parent observation containing canonical state and owning Chunk revision;
2. map `UNKNOWN` or `FAILED` to the existing typed `SpatialQueryResult` and stop fail-closed;
3. for FULL AIR, produce no candidate;
4. for FULL non-AIR, execute existing full-block shape intersection;
5. for DETAIL, iterate occupied subindices in ascending deterministic order;
6. build exact local bounds `[sub/4, (sub+1)/4]` for each occupied cell;
7. intersect with the existing slab algorithm and tie rules;
8. retain the nearest candidate within the current DDA event interval;
9. if no occupied subvoxel intersects, continue the same coarse DDA to the next parent;
10. at an edge/corner DDA event, preserve existing neighbor candidate enumeration and deterministic comparison.

The DETAIL hit provenance contains:

- canonical parent block X/Y/Z;
- `LocalSubVoxelPosition`;
- hit face/normal;
- canonical world hit point;
- distance;
- runtime block ID/material identity;
- owning Chunk revision;
- representation kind `DETAIL_4`.

FULL hits retain source compatibility through a discriminated hit target. Gameplay mutation uses the hit's revision and typed observed parent state; any later Chunk revision rejects the request as stale.

Simulation-origin rebasing changes resident-local ray origin but not canonical hit identity. Render-rate tests sample the same fixed simulation state and therefore must produce identical hits at 10/60/144/240 Hz.

## 12. Collision merge algorithm and bound

### 12.1 Source and ownership

DETAIL collision is derived only from an immutable `DetailCellState` obtained through the typed parent observation boundary. `CollisionWorld` remains the broad phase, sweep, slide, overlap, and depenetration authority.

### 12.2 Deterministic greedy merge

Given a 64-bit occupancy mask:

1. copy it into a local `remaining` mask;
2. find the lowest occupied subIndex using X-fastest order;
3. grow a contiguous X run from the seed as far as all cells remain occupied;
4. grow that rectangle in positive Y while each complete X row remains occupied;
5. grow that box in positive Z while each complete X-by-Y layer remains occupied;
6. emit one AABB with quarter-unit min/max coordinates;
7. clear every covered bit from `remaining`;
8. repeat until `remaining == 0`.

Material is irrelevant to static collision merging; adjacent occupied cells of different materials may share a collision box.

### 12.3 Deterministic ordering and bound

Boxes are ordered by the ascending seed subIndex that created them. Growth priority is X, then Y, then Z. The algorithm emits at least one consumed cell per box, so:

```text
1 <= boxCount <= occupiedCellCount <= 64
```

The absolute allocation and publication bound is 64 boxes. A face-isolated 3D checkerboard contains 32 occupied cells and emits 32 boxes; other adversarial patterns may fragment differently, but the loop invariant proves `boxCount <= occupiedCellCount <= 64` without relying on a claimed exact reachable maximum. Every emitted box is passed to `CollisionWorld`; none is silently discarded. FULL remains zero boxes for AIR or one full-cube box for non-AIR.

Origin-aware collision preserves existing `UNKNOWN`/`FAILED` propagation and therefore remains fail-closed.

## 13. Legacy byte API audit

The following authoritative paths must become typed before DETAIL can ship:

| Path | Current byte seam | Required Phase 16 boundary |
|---|---|---|
| Gameplay read | `GaiaBlockWorldAccess.blockAt -> World.getBlock` | atomic typed parent observation; DETAIL cannot map to AIR |
| Mutation | `World.compareAndSetBlock` | FULL mutation rejects DETAIL; DETAIL service delegates exact repository CAS |
| Raycast | `BlockRaycast.hitInBlock -> World.getBlock` | typed refinement with revision/provenance |
| Collision | `CollisionWorld -> World.getBlock -> shapeFor(byte)` | coordinate-aware typed shape resolution |
| Mesh | `ChunkMeshBuilder` and AO byte sampling | `ChunkSnapshot.cellState` plus quarter sampler |
| Save capture | `ChunkSnapshot.copyBlocks` comparisons | FULL bytes plus canonical DETAIL bytes |
| Streamed restore | payload voxels to `ChunkGenerationData` | decode absent/required DETAIL into detached generation data |

Safe compatibility uses are limited to:

- FULL-only world generation before publication;
- explicitly named `copyFullBlocks()` paired with `details()` in canonical codecs;
- legacy tests whose fixtures contain no DETAIL;
- internal invariant validation of the DETAIL backing byte.

`Chunk.getBlock`, `ChunkSnapshot.getBlock`, `ChunkMeshInput.getBlock`, `World.getBlock`, and `ChunkRepository.getBlock` must not be used by DETAIL-aware production paths. If retained for source compatibility, they throw on a DETAIL coordinate.

Architecture tests scan production sources and enforce:

- raycast, collision, gameplay access, meshing, AO, save capture, and restore use typed APIs;
- no game dependency appears in engine DETAIL storage;
- no second detail map/repository/revision/dirty/upload queue exists;
- `ChunkMeshInput` still has exactly nine `ChunkSnapshot` components;
- workers do not receive `World`, `ChunkRepository`, mutable maps, tickets, or OpenGL capabilities.

## 14. Thread and lifecycle rules

- Detail gameplay mutation and canonical repository publication run on the owner/context thread.
- Workers receive only immutable `ChunkSnapshot`, codec input bytes, or detached collision/mesh values.
- Workers never read mutable `World`, publish to `ChunkRepository`, or call OpenGL.
- DETAIL mesh completion uses the existing claim revision and stale-result rejection.
- DETAIL upload/destruction uses the existing limits: accepted 32, active 2, uploads 2/frame, destructions 4/frame, and aggregate Chunk publication plus mesh upload 2/frame.
- Chunk unload removes its one canonical entry, which releases FULL, DETAIL, collision-derived transient data, and the existing mesh lifecycle together.

## 15. Diagnostics and fail-closed behavior

Detail codec diagnostics use bounded stable codes, including:

- `detail-blocks.unsupported-version`;
- `detail-blocks.invalid-required-flag`;
- `detail-blocks.invalid-magic`;
- `detail-blocks.unsupported-scale`;
- `detail-blocks.invalid-flags`;
- `detail-blocks.palette-bound`;
- `detail-blocks.noncanonical-palette`;
- `detail-blocks.unknown-material`;
- `detail-blocks.parent-count-bound`;
- `detail-blocks.duplicate-parent`;
- `detail-blocks.parent-out-of-range`;
- `detail-blocks.empty-detail`;
- `detail-blocks.invalid-occupancy-material`;
- `detail-blocks.full-backing-conflict`;
- `detail-blocks.trailing-bytes`.

Decode failures retain the last known-good root and prevent the affected Chunk from publishing. Mutation capacity or stale failures do not partially change state.

## 16. Gate sequencing

### Gate 16A: Canonical data model

Engine API work:

- scale, local position, sealed parent state, immutable detail state;
- sparse bounded Chunk storage and typed observation;
- immutable DetailChunkSnapshot and ChunkSnapshot integration;
- exact FULL backing invariant and legacy byte API guards;
- unchanged nine-component ChunkMeshInput/claim capability.

No game integration is required until the engine types and snapshot bounds pass focused tests.

### Gate 16B: Mutation, revision, dirty, and streamed save

Engine API work:

- atomic repository conversions/detail CAS;
- exact stale outcomes and existing neighbor dirty revisions;
- narrow service contract.

Game integration work:

- BlockRegistry validation/translation;
- `DetailBlocksCodec` v1;
- streamed capture, load, restore, migration, and no-op equality;
- PR #27 durable acknowledgement regression tests.

### Gate 16C1: Detail-aware raycast

Engine API work:

- typed parent refinement in the existing DDA;
- discriminated FULL/DETAIL hit provenance.

Game integration work:

- resource-identity mapping and exact detail selection for interaction/debug tools.

### Gate 16C2: Detail collision

Engine API work:

- deterministic greedy AABB derivation;
- typed CollisionWorld resolution with at most 64 boxes.

Game integration is limited to composing the typed resolver; no new physics authority is added.

### Gate 16D: Detail mesh/render

Engine API work:

- quarter sampler, hidden-face elimination, FULL/DETAIL masks, quarter AO, deterministic geometry/hash;
- existing claim/revision and upload lifecycle regression coverage.

Game integration work:

- existing BlockRegistry material/UV resolver composition only.

### Gate 16E: Debug, fixtures, measurements, and acceptance

Game/tooling work:

- owner-thread debug commands through `DetailMutationService`;
- deterministic stair, hollow opening, thin wall, checkerboard, and asymmetric fixtures;
- 1/64/256/cap-bound measurements;
- architecture and persistence documentation;
- Windows and Apple Silicon acceptance evidence;
- Phase 16 handoff.

## 17. Test policy

Every Task follows RED, minimal GREEN, then adjacent regression. Gate verification is focused; repository-wide verification waits for the final candidate.

Required focused matrices include:

- all representation, ordering, occupancy, zero-backing, cap, defensive-copy, equality, and hash invariants;
- atomic conversions and exact rejection statuses;
- FULL/DETAIL save/load/reload and no-op durable revision semantics;
- six ray faces, edge/corner ties, gaps, later parent continuation, negative/large coordinates, parent/Chunk boundaries, origin rebase, and render-rate independence;
- collision standing, thin walls, step behavior, supported-speed sweep, deterministic ordering, and proof/enforcement of the absolute 64-box bound;
- FULL/FULL, FULL/DETAIL, DETAIL/DETAIL, DETAIL/AIR, mixed material, Chunk edge, AO, UV, crack, Z-fighting, deterministic mesh hash, and stale claim rejection.

Final candidate verification remains:

```powershell
.\gradlew.bat :engine:test
.\gradlew.bat :game:test
.\gradlew.bat :tools:test
.\gradlew.bat clean test build
git diff --check
```

The interactive `:game` smoke test is run on Windows and Apple Silicon macOS, not CI. PR acceptance still requires two consecutive Windows/Ubuntu/macOS 3/3 GREEN matrices, controller-approved merge, and post-merge main 3/3 GREEN.

## 18. Worktree quarantine

The design/spec turn observed pre-existing user-owned changes:

- `game/src/main/java/com/gaia/world/streaming/ChunkStreamingMetricsRecorder.java`;
- `game/src/test/java/com/gaia/session/ChunkStreamingSessionIntegrationTest.java`;
- untracked `dist/`.

The tracked-file SHA-256 quarantine baseline captured during this design review is:

```text
ChunkStreamingMetricsRecorder.java
  EBBAAD69942CE0F8504BD37DA1B2AF7878BCC19EA38D21A27C788B4C1F7ABB51
ChunkStreamingSessionIntegrationTest.java
  D8C6D9DCC01707809C6414AFA9F11C4D2501CD30748A145897E8F65BA594782C
```

Before Gate 16A implementation:

1. record `git status --short`, path-scoped diffs, and working-file hashes for the two tracked paths;
2. mark them excluded from all Phase 16 edits, diffs, staging, and commits;
3. create new Phase 16 test files rather than modifying `ChunkStreamingSessionIntegrationTest.java`;
4. do not inspect, modify, delete, package, or stage `dist/`;
5. if Phase 16 cannot proceed without editing either tracked quarantined path, stop for controller resolution.

No staging or commit is authorized by this design.

## 19. Acceptance criteria

Phase 16 is acceptable only when:

- every canonical parent is unambiguously FULL or DETAIL;
- DETAIL membership implies backing byte zero and nonzero occupancy;
- every authoritative path is typed and UNKNOWN remains fail-closed;
- one Chunk revision/dirty/save/mesh/unload authority covers both representations;
- exact DETAIL survives unload, save, quit, and reload;
- ordinary FULL rendering, collision, raycast, generation, and persistence remain behaviorally unchanged;
- memory, codec, collision, meshing, raycast, and scheduler work remain within the explicit bounds above;
- no second registry, save root, raycaster, collision world, mesh queue, GPU authority, or global detail map exists.

## 20. Design self-review

The completed design was reviewed against the controller's three required failure classes:

**Authority duplication:** All resident state, CAS, revision allocation, dirty propagation, unload invalidation, and publication terminate at `ChunkRepository`. DETAIL adds no global map, revision, dirty tracker, raycaster, collision world, persistence root, mesh queue, or GPU path. The game service validates and translates identity but cannot publish state.

**Stale assumptions:** The design retains the audited Phase 15 seams: nine-component `ChunkMeshInput`, separate `ChunkMeshingClaim`, typed `AVAILABLE/UNKNOWN/FAILED`, `ChunkDirtyTracker.affectedByBlock`, streamed required extensions, exact unload tickets, and the PR #27 durable acknowledgement rule. Byte-only methods are explicitly treated as unsafe compatibility seams, not assumed to remain authoritative.

**Unbounded memory/work:** FULL-only resident Chunks have no stored DETAIL object or arrays; stored DETAIL tables are nonempty, limited to 1,024 parents per Chunk, and use exact-length compact arrays. The design has a 76,032-byte conservative immutable-detail snapshot bound, uses the existing 32-claim/nine-snapshot envelope, emits at most 64 collision boxes per parent, emits at most 384 unmerged facelets per DETAIL parent, and keeps the existing scheduler/publication/GPU limits. All capacity violations fail explicitly; no data or geometry is discarded.

No stop condition is required by this design. Any implementation discovery that invalidates one of these audited seams, requires a higher cap or queue limit, or prevents the quarantined files from remaining separate returns to controller review before production code continues.
