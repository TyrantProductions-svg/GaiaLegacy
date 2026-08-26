# Phase 15 WorldItem TTL Paging Backend — Simplified Revised Design

**Date:** 2026-08-14
**Status:** Draft for approval
**Scope:** Phase 15 Gate 15C WorldItem lifetime, Chunk-local paging, bounded
resident state, v1/v2 compatibility, projection rollback, and restart
acceptance.

This document supersedes the 2026-08-13 owner-directory/Option A design and,
for Phase 15 and later, explicitly supersedes every earlier statement that
canonical WorldItems never expire automatically. The former authority trie,
single overlay, opaque blob graph, proof catalog, reference counts, GC cursor,
maintenance barrier, and maintenance transactions are removed from the design.

## 1. Approved decision

Every canonical WorldItem has one authoritative absolute expiry:

```java
long expiresAtWorldTick
```

The fixed lifetime is:

```java
WORLD_ITEM_TTL_TICKS = 18_000L
```

At spawn tick `t`, expiry is the saturating sum `t + 18_000`. The canonical
test is `worldTick >= expiresAtWorldTick`. No second age, remaining-duration,
wall-clock deadline, offline timer, loaded-time counter, or projection timer is
allowed.

The canonical `worldTick` is the session's existing fixed simulation tick:

- it advances exactly once per successful fixed simulation step;
- it does not advance while the game is paused;
- it is saved and restored exactly;
- closing the process does not consume lifetime;
- Chunk unload does not pause lifetime while the world continues simulating.

Partial pickup, motion, Chunk crossing, hibernation, activation, and save/reload
retain the same stable ID and the same `expiresAtWorldTick`. They never refresh
TTL. A new drop receives a new allocator ID and a new expiry.

## 2. Ownership and rejected complexity

`LogicalWorldItemService` remains the only WorldItem semantic, stable-ID,
allocator, reservation, ticket, page-membership, and expiry authority.
`PhysicalWorldItemSystem` remains a projection only. Task 4 streamed persistence
stores validated versioned bytes and publishes them atomically, but never
chooses an ID, decides expiry, merges items, or interprets page entries.

The following are explicitly out of scope and must not be reintroduced:

- a second `WorldItemRepository`, entity database, or save root;
- a global `WorldItemId -> ChunkKey` owner directory;
- Merkle owner tries, inline overlays, overlay chains, compaction, or
  maintenance epochs;
- an opaque content-addressed blob graph for WorldItems;
- durable proof catalogs, blob reference counts, orphan GC, directory scans, or
  maintenance schedulers;
- a permanent heap map of dormant DTOs or every historical ID;
- wall-clock/offline expiry or a global all-Chunk expiry sweep.

The allocator high-water is global and durable. Historical IDs are never
reused, including after their items expire. The allocator is restored before
any page is activated and is never reconstructed from loaded pages.

## 3. Interface map

### 3.1 Engine semantic data

`WorldItemRuntimeSnapshot` adds the authoritative field:

```java
public record WorldItemRuntimeSnapshot(
        WorldItemSnapshot item,
        Optional<EntityRef> source,
        long spawnTick,
        long pickupAvailableTick,
        long expiresAtWorldTick) {}
```

Validation requires:

```text
0 <= spawnTick <= pickupAvailableTick <= expiresAtWorldTick
```

The final inequality may be equal for a restored item that is immediately
eligible for lazy expiry. The spawn path computes expiry with saturating
addition; no constructor silently recomputes it.

`WorldItemPageSnapshot` remains a detached Chunk-local page:

```java
public record WorldItemPageSnapshot(
        ChunkKey chunkKey,
        long pageRevision,
        List<WorldItemRestoreEntry> entries) {}
```

Entries are sorted by numeric stable ID and each entry carries its exact
`expiresAtWorldTick` through `WorldItemRuntimeSnapshot`.

`LogicalWorldItemSnapshot` retains explicit completeness:

```java
enum Completeness { LEGACY_COMPLETE, PAGED_PARTIAL }
```

A paged snapshot exposes only active/resident DTOs and cannot masquerade as a
complete world snapshot.

### 3.2 Global v2 checkpoint

The service keeps one metadata row for every currently live ID, including
evicted unexpired items:

```java
public record WorldItemLiveMetadata(
        WorldItemId id,
        ChunkKey intendedChunkKey,
        long intendedPageRevision,
        long expiresAtWorldTick,
        WorldItemLiveState state,
        Optional<WorldItemDurablePageProof> durableProof) {}

public record WorldItemDurablePageProof(
        ChunkKey chunkKey,
        long pageRevision,
        String pageHash) {}

public enum WorldItemLiveState {
    ACTIVE, DECODED_DORMANT, EVICTED_UNEXPIRED, PENDING
}
```

This row contains identity, intended owner/revision, expiry, state, and optional
durable proof only. Intended ownership is never inferred from a prior durable
proof. Clean decoded dormant and evicted-unexpired rows require proof; active,
dirty, or pending rows may lack it and then remain resident/pinned until a
matching proof commits. It contains no stack, transform, velocity, projection,
expired-history, or gameplay DTO. A live ID has exactly one row; pending work
changes the row state rather than adding a second count. Full pickup or expiry
deletes the row immediately.

The only non-Chunk WorldItem durable state is one complete checkpoint:

```java
public record WorldItemPageDescriptor(
        ChunkKey chunkKey,
        long pageRevision,
        String pageHash,
        int encodedEntryCount,
        int expectedLiveCountAtCheckpointTick) {}

public record WorldItemPagingCheckpoint(
        SaveIdentity saveIdentity,
        long checkpointRevision,
        long worldTick,
        long nextItemId,
        boolean itemIdsExhausted,
        int totalLiveItemCount,
        List<WorldItemPageDescriptor> pages) {}
```

Descriptors model bounded physical pages, including stale pages with zero live
survivors. They are in canonical `ChunkKey` order, have unique keys, positive
revision, canonical SHA-256, `encodedEntryCount > 0`, and
`0 <= expectedLiveCountAtCheckpointTick <= encodedEntryCount`. There are at
most 1,024 physical descriptors. The checked sum of expected survivors equals
the published checkpoint's `totalLiveItemCount <= 1,024`. Independently, the
current runtime metadata size equals the expiry-index size and remains at most
1,024. Those durable and runtime counts are required to match only immediately
after restart publication or acceptance of a complete intended checkpoint;
resident/pinned dirty state is bounded separately. The generic dependency
reference count equals physical descriptor count, not survivor count.

Task 4 carries it through a generic bounded global-extension seam:

```java
public record StreamedGlobalExtension(
        SaveSectionId sectionId,
        int codecVersion,
        boolean required,
        Optional<RequiredChunkExtensionDependency> dependency,
        byte[] payloadBytes) {}

public record RequiredChunkExtensionDependency(
        SaveSectionId chunkExtensionId,
        int referenceCount) {}

public sealed interface StreamedGlobalExtensionMutation {
    record Upsert(StreamedGlobalExtension extension)
            implements StreamedGlobalExtensionMutation {}
    record Remove(SaveSectionId sectionId)
            implements StreamedGlobalExtensionMutation {}
}

public record StreamedPersistenceTransaction(
        List<StreamedChunkMutation> chunks,
        List<StreamedGlobalExtensionMutation> globalExtensionMutations,
        BooleanSupplier stillCurrent) {}
```

The streamed index maintains generic required-Chunk-extension reference counts
incrementally as Chunk mutations publish. A global extension may declare one
required dependency ID and its exact post-transaction count. Task 4 compares
the declaration to its generic index count without interpreting payload bytes,
scanning Chunks, or branching on WorldItems.

Each section ID occurs at most once per transaction. `Upsert` replaces that
section atomically; `Remove` deletes it atomically and fails if the same
transaction also upserts it. Omitted section IDs retain their prior bytes. The
canonical encoded bytes of any one extension and of all retained global
extensions together are each bounded to 1 MiB, checked before allocation and
before index publication. The index retains the exact inline bytes. There is no
child graph, external blob file, catalog, or reference count.
`world-item-checkpoint` declares dependency on the required `world-items-page`
Chunk extension. It cannot be removed while the generic reference count is
nonzero. Unknown required versions or count disagreement fail closed.

### 3.3 Service paging seam

The service produces page replacements and the intended checkpoint, then
accepts only an exact durable proof paired with its own opaque ticket:

```java
public record WorldItemPersistencePlan(
        long expectedCheckpointRevision,
        WorldItemPagingCheckpoint intendedCheckpoint,
        List<WorldItemPageMutation> pageMutations,
        String transactionDigest,
        BooleanSupplier stillCurrent) {}

// Engine semantic intent. The game backend translates this to Task 4
// StreamedChunkMutation without introducing an engine -> game dependency.
public sealed interface WorldItemPageMutation {
    record Upsert(
            WorldItemPageSnapshot page,
            Optional<WorldItemPageDescriptor> expectedPrevious)
            implements WorldItemPageMutation {}
    record Remove(WorldItemPageDescriptor expected)
            implements WorldItemPageMutation {}
}

public final class WorldItemPersistenceTicket {
    private final Object issuer;
    private WorldItemPersistenceTicket(Object issuer) { this.issuer = issuer; }
    public static WorldItemPersistenceTicket issuedBy(Object issuer) {
        return new WorldItemPersistenceTicket(Objects.requireNonNull(issuer));
    }
    public boolean belongsTo(Object issuer) { return this.issuer == issuer; }
}

public interface WorldItemDurableProof {}

public interface WorldItemDurabilityVerifier {
    void verify(
            WorldItemPersistenceTicket ticket,
            WorldItemPersistencePlan plan,
            WorldItemDurableProof proof);
}

public interface WorldItemPageReadView extends AutoCloseable {
    long indexSequence();
    String checkpointDigest();
    WorldItemPagingCheckpoint checkpoint();
    WorldItemPageSnapshot read(WorldItemPageDescriptor descriptor);
}

public interface WorldItemPageSource {
    WorldItemPageReadView openReadView();
}
```

The service holds a private issuer object, creates an opaque
`WorldItemPersistenceTicket` through `issuedBy(privateIssuer)`, and stores
ticket identity, provenance, expected checkpoint
revision/digest, and consumption state in the bounded issuer table. It exposes
the public detached plan separately. The backend receives the plan only.
Callers may create tickets for their own issuer objects, but cannot create one
that passes `belongsTo` for the service's private issuer identity.

The game backend has a private nested `StoreProof` implementing the marker. Its
private constructor captures backend issuer identity, save/root identity,
checkpoint revision, transaction digest, and published index sequence only
after write/force/reread and reopen validation succeed. Its private nested
verifier is the sole production `WorldItemDurabilityVerifier`; production
composition injects that one verifier into `LogicalWorldItemService` exactly
once and exposes no caller-supplied verifier/proof constructor.

`commitPersistence(ticket, proof)` requires a ticket from the service's own
issuer table, verifier acceptance of its backend-private proof/issuer/save-root,
exact plan digest/revision, and the backend's current published index sequence,
then consumes the ticket once. Caller-defined marker implementations, foreign
backend proofs, replay, partial, or stale proof fail before mutation. Proof is
never serialized and has no public value fields.

`WorldItemPageReadView` pins one immutable published index sequence and the
checkpoint digest it contains. All descriptor/page reads use that sequence;
close releases the bounded pin. A restart never mixes pages from two index
generations.

## 4. Durable page format

`WorldItemPageCodec` uses literal magic `GLWP` and page format version 1. This
is the first streamed page wire format, so its version number is independent of
the Phase 14 whole-world save v1/v2 generation. It contains:

- save identity;
- canonical `ChunkKey`;
- positive page revision;
- bounded entry count;
- entries in strictly increasing stable-ID order;
- complete item stack, position, velocity, logical revision, source, spawn
  tick, pickup tick, `expiresAtWorldTick`, and physical state;
- checksum over the canonical payload;
- no trailing data.

The page codec rejects duplicate IDs, noncanonical order, wrong save/key,
invalid tick ordering, unsafe coordinates, non-finite values, malformed UTF-8,
truncation, oversize lengths, checksum mismatch, and unknown required version.
Returned values and bytes are detached defensive copies.

The page remains one required extension inside the owning
`StreamedChunkPayload`. A Chunk is `persistenceRequired` when it has at least
one unexpired page entry even if its voxels equal generated base. A page that
becomes empty after a successful cleanup commit is removed. Other required
runtime extensions and optional `detail-blocks` remain independent.

Mutation intent is explicit at both boundaries:

```java
public sealed interface StreamedChunkMutation {
    record Upsert(StreamedChunkStore.ExactChunkCapture capture)
            implements StreamedChunkMutation {}
    record Remove(
            ChunkKey chunkKey,
            long expectedRevision,
            String expectedPayloadHash)
            implements StreamedChunkMutation {}
}

```

Every remove is compare-and-remove against expected revision and hash. The
WorldItem adapter translates a page upsert/remove into the owning Chunk's
generic extension mutation. Removing the last WorldItem page from an otherwise
unpersisted generated Chunk removes the complete item-only Chunk entry. If
voxel changes or another required extension remain, it removes only the
`world-items-page` extension and republishes the preserved Chunk payload. A
stale expected revision/hash fails without mutation. Crash recovery exposes
the complete old or complete new Chunk/page/checkpoint/dependency count.

When all 1,024 physical descriptor slots are occupied, persisting a new page is
admitted only in the same transaction as expected-revision/hash removal of a
zero-live stale page. If no such page is available, the active/dirty candidate
remains resident and pinned; it is not evicted or silently omitted. Failure of
the paired transaction keeps the candidate resident/pinned and the old physical
descriptor set authoritative.

## 5. Bounded resident state

One validated `WorldItemPageCachePolicy` owns all limits:

| Resource | M2 hard bound |
|---|---:|
| Current-live metadata (active DTO + decoded dormant DTO + evicted unexpired + unique pending) | 1,024 |
| Clean decoded pages | 32 |
| Clean decoded bytes | 16 MiB |
| Concurrent paging tickets | 64 |
| One semantic WorldItem checkpoint | 1,024 owner mutations / 1,024 entries |
| One physical Task4 staging batch | 64 Chunk mutations / 64 MiB encoded |
| Staging payload-array residency | <=256 MiB conservative structural upper bound |
| Deterministic expiry heap/index entries | 1,024 |
| Resident/pinned dirty delta from accepted checkpoint | 1,024 entries / 16 MiB candidate bytes |
| Delayed cleanup intents | 64 records / 64 KiB metadata |
| Entries validated/filtered per page operation | 1,024 |

The 1,024 cap covers every currently live item, not only resident DTOs. An
evicted unexpired item retains its minimal `WorldItemLiveMetadata`; pending is a
unique state of that same row and is never double-counted. A decoded candidate
is admitted only when the aggregate post-publication set stays within 1,024.
Clean, unpinned DTO pages evict by deterministic LRU with `ChunkKey` tie-break,
but their live metadata stays until pickup or expiry. Dirty canonical DTO state
stays active or pinned until persistence succeeds. If every DTO candidate is
pinned, admission defers or fails without mutation.

The live metadata map and expiry index have identical ID sets. For each clean
dormant/evicted row, durable proof matches its physical descriptor; active,
dirty, or pending rows without proof remain resident/pinned. Intended
owner/revision remains separate from that proof. Pickup and expiry remove live
metadata/index entries immediately. Physical stale-page descriptors and
pending cleanup counts are tracked separately; no expired semantic history or
allocator history is retained in memory.

The durable and runtime invariants are deliberately separate:

```text
sum(descriptor.expectedLiveCountAtCheckpointTick)
    == publishedCheckpoint.totalLiveItemCount <= 1,024

currentLiveMetadata.size == expiryIndex.size <= 1,024

descriptorCount == requiredPageExtensionDependencyCount <= 1,024
physicalStalePageCount == count(descriptor.expectedLiveCountAtCheckpointTick == 0)
cleanupIntentCount <= 64
dirtyDeltaCount <= 1,024
dirtyDeltaCandidateBytes <= 16 MiB
```

Durable survivor count equals runtime metadata/index only immediately after the
single restart publication or after `commitPersistence(ticket,proof)` accepts
the intended checkpoint. Between those synchronization points, new, moved,
picked-up, or expired runtime state forms the separately bounded and metered
resident/pinned dirty delta. Physical descriptors, zero-live stale pages, and
cleanup/tombstone work never enter the runtime semantic equation.

Heap use is bounded by resident policy rather than travel distance. Durable
disk use converges lazily because expired entries disappear whenever their page
is next activated, hibernated, saved, or otherwise encountered. The design does
not promise immediate reclamation of an expired page that is never revisited.

## 6. Lazy expiry semantics

`SessionPersistenceClock` is the only component allowed to advance
`worldTick`. It advances only with the existing fixed simulation clock and
persists the same value to the manifest/checkpoint. The service only receives a
validated monotonic tick; it cannot increment from frame time, wall time,
callbacks, page loads, or restart.

The service maintains one deterministic min-heap/index ordered by
`(expiresAtWorldTick, WorldItemId)` for every live metadata row. Its size is at
most 1,024. On each delivered tick it drains **all** entries with
`expiresAtWorldTick <= worldTick` in that call. Each due item becomes
noninteractive and noncanonical-live immediately: reservations/tickets are
failed or canceled, projection is removed on the owner thread, and metadata
plus expiry-index entry are deleted. The absolute per-tick semantic work bound
is therefore the total live cap, 1,024; expiry is never delayed by a smaller
cleanup budget.

Persistence cleanup has three bounded entry points:

1. Semantic expiry offers the affected page revision/hash to a 64-record,
   64-KiB deterministic cleanup-intent queue. If full, the intent is dropped;
   stale expired bytes are harmless and rediscovered on a later bounded page
   read. No alternate backlog or global scan is created.
2. Activation validates the complete bounded page, filters entries whose
   expiry is at or before the restored/current world tick, and publishes only
   surviving entries. If filtering changes the page, the page becomes dirty
   and must be durably rewritten before its clean cache image may evict.
3. Hibernate/save validates and filters each included page before encoding.
   Expired entries are absent from the candidate bytes.

Cleanup drains under the normal 64-page/64-MiB transaction bound and emits a
page rewrite or expected-revision/hash remove tombstone. Persistence failure
may leave expired page material on disk, but it cannot restore the item to live
metadata, interaction, projection, or allocator state. Expiry never allocates
an ID or changes allocator high-water. An item expiring during a prepared page
transaction makes that ticket stale; the operation retries from a fresh
capture.

## 7. Transaction ordering

### 7.1 Hibernate and unload

```text
capture canonical page at worldTick
-> filter expired entries
-> prepare private ticket, checkpoint descriptors, page mutations, dependency count
-> encode detached page/checkpoint bytes
-> compute canonical transaction digest and pin state
-> Task 4 captures and converges one last-known-good base root
-> generate at most 64 owner payloads / 64 MiB encoded per invisible batch
-> write/force/reread inactive fixed slots; release each completed batch
-> validate all <=1,024 lightweight descriptors plus checkpoint/session root
-> recheck base generation and freshness
-> publish recovery then main index exactly once
-> return proof(checkpoint revision, digest, index sequence)
-> service commitPersistence(ticket, proof) matches and single-consumes ticket
-> only then evict DTOs and projection
```

Any persistence failure retains exact canonical memory/projection state and the
last known-good durable data. A modified or expiry-cleaned page is never
discarded before durable success.

### 7.2 Restart and activation

```text
open immutable read view pinned to one index sequence/checkpoint digest
-> validate manifest identity/worldTick and checkpoint codec/dependency count
-> read every descriptor page from that same view
-> validate every key/revision/hash and raw encodedEntryCount
-> filter each raw page at checkpoint worldTick
-> validate expected survivor count and globally unique surviving IDs
-> validate aggregate equations, cap, allocator, and exact surviving metadata
-> publish checkpoint + complete service metadata once
-> close view
-> runtime activation rereads one descriptor from a pinned view
-> require exact match to service metadata before DTO/projection creation
-> build detached physical projections
-> publish canonical items and projections once
```

Restart may read sequentially but must validate the complete physical
descriptor set before the single service publication. Raw pages may contain
expired stale material; only checkpoint-tick survivors contribute to global ID
uniqueness and live equations. Descriptor count and surviving live entries are
each at most 1,024. Runtime activation succeeds only when surviving page state
matches service metadata and durable proof; any mismatch is blocking.

Failure before publication changes nothing. Callback or projection failure
rolls back to exact metadata/page-owned state and removes every partial
body/renderer registration. Reentrant mutation on the same service fails
before any canonical mutation.

### 7.3 Movement and partial pickup

Moving across a Chunk boundary rewrites the old and new pages in one Task 4
transaction. Full pickup removes the entry; partial pickup keeps the same ID,
logical revision, and expiry. No operation refreshes TTL.

Duplicate live IDs among active, cached, transaction candidates, or checkpoint
survivors fail closed before publication. Restart validates all surviving IDs
under one view; runtime page/metadata mismatch is blocking. Valid data remains
order-independent because IDs come only from the persisted global allocator.

## 8. v1/v2 compatibility

| Operation | Contract |
|---|---|
| Legacy v1 read | Supported. Each entry receives `expiresAtWorldTick = saturatingAdd(spawnTick, 18_000)` and the snapshot is `LEGACY_COMPLETE`. |
| Legacy v1 write from `LEGACY_COMPLETE` with no paging state | Supported only when every item has `expiresAtWorldTick == saturatingAdd(spawnTick, 18_000)`. Any mismatch fails before encoding; accepted output keeps the existing byte format. |
| v1 write with any paged/dormant state, page ticket, or v2 checkpoint | Fail closed with `world-items-v1.paged-state-unsupported` before any filesystem mutation or session close. |
| v1 to streamed v2 migration | Bucket entries by checked ChunkKey, derive expiry once, persist pages plus checkpoint containing source fixed tick and allocator high-water, retain exact v1 backup. |
| Failed migration | Exact v1 remains loadable; partial v2 state is not exposed. |
| Streamed v2 restart | Pin one index sequence/checkpoint digest, validate all described pages, duplicates, cap, hashes, counts, and allocator before one service publication. Offline time does not advance tick. |
| Unknown required page/checkpoint codec | Unsupported/corrupt and blocking; never downgrade to v1. |
| v2 to v1 downgrade | Unsupported. |

The checkpoint world tick must equal the canonical v2 manifest/session fixed
tick. A mismatch is corruption, not a choice of clocks.

## 9. Failure semantics

| Failure | Required result |
|---|---|
| Page/checkpoint encode validation | No callback, write, eviction, or projection change. |
| Chunk/checkpoint write, force, or reread | Old authority and active state remain. |
| Recovery/main index interruption | Reopen selects exact old or complete new pages plus checkpoint. |
| Any first/middle/final staging-batch failure | Old index remains authoritative; inactive-slot remnants are ignored. |
| Staging cancel or stale base generation | No index publication; a later exact-slot overwrite reclaims remnants without a scan. |
| Stale checkpoint/page revision | Fail before durable mutation; retry from fresh capture. |
| Save failure after semantic expiry | Item stays absent from live metadata and interaction; only harmless expired page bytes remain for later bounded rediscovery/cleanup. |
| Cleanup queue full | Drop the cleanup intent; retain no alternate backlog and rediscover stale bytes on a later page read. |
| Read-view index/checkpoint changes concurrently | Pinned view remains on its original sequence; a new view is required after close. |
| Proof replay/foreign digest or sequence | Fail before mutation; the matching opaque ticket remains single-consume. |
| Activation page corruption or collision | No partial activation; page remains unavailable/corrupt. |
| Projection callback failure | Exact logical rollback; no body or renderer leak. |
| Exact `Error` | Escape unchanged after required rollback. |
| Tick/expiry overflow | Saturate expiry at `Long.MAX_VALUE`; never wrap negative. |

## 10. Acceptance matrix

### 6A — TTL page seam

- literal `GLWP` v1 bytes include exact expiry;
- expiry tick ordering, saturation, checksum, bounds, wrong save/key, duplicate,
  truncation, and trailing bytes fail closed;
- required page plus optional detail extension coexist;
- generic inline checkpoint/page/dependency transaction is atomic and Task 4
  never decodes a WorldItem;
- generic Chunk upsert/remove and item-page upsert/remove honor expected
  revision/hash, distinguish item-only Chunk removal from extension-only
  removal, and recover old-or-complete-new at every crash point;
- global dependency count updates incrementally with page mutations and rejects
  checkpoint removal/count mismatch without a WorldItem branch or Chunk scan;
- zero-live physical pages remain describable; raw encoded count and expected
  survivor count validate independently; a full descriptor table admits a new
  page only with same-transaction removal of a proven zero-live stale page.

### 6B — dormant activation and eviction

- unload -> durable page -> eviction -> reload keeps stable ID and expiry;
- save failure occurs before eviction;
- active DTO + decoded dormant DTO + evicted unexpired metadata + unique pending
  never exceeds 1,024 across large historical churn;
- metadata separates intended owner/revision from optional durable proof; clean
  dormant/evicted requires proof while unproved active/dirty/pending stays
  resident/pinned; pickup/expiry deletes metadata immediately;
- the <=1,024 deterministic expiry heap removes all due items semantically on
  the exact tick; bounded tombstone/rewrite cleanup may lag;
- full cleanup queue drops rediscoverable intents without an alternate backlog;
- partial pickup and Chunk crossing keep ID/expiry.

### 6C — v2 and restart

- `SessionPersistenceClock` is the only tick-advancing authority;
- up to 1,024 owner payload replacements stage through <=64-Chunk/<=64-MiB
  physical batches and publish one page/checkpoint/session index generation;
- total candidate bytes may exceed 64 MiB while retained staging payload memory
  remains within the enforced <=256 MiB payload-array upper bound and readers
  see only the previous root until publication;
- new owners count both pre-publication A/B fixed slots, so every physical step
  touches <=64 distinct payload blobs as well as <=64 MiB;
- all handles for one save-root share a fail-fast writer capability; a lazy
  bounded read generation retains slot metadata, never all payload bodies, and
  pins only the exact slots needed to preserve old-view validity;
- one immutable index-sequence/checkpoint-digest view reads and validates every
  descriptor/page, duplicate, total cap, hash, count, and allocator before one
  service publication;
- runtime activation must match complete service-owned current-live metadata;
- pause and process downtime do not consume TTL;
- v1 legacy read/derive remains compatible;
- v1 write also rejects any expiry not exactly derived from spawn+18,000 and
  paged-state writes fail before mutation;
- process-style v2 restart filters items expired at restored/current tick.

### 6D — projection rollback

- callback mutation is guard-first and non-reentrant;
- activation/expiry/hibernate projection failures roll back exactly;
- no worker performs GPU work.

### 6E — acceptance

- forward/reverse page orders produce identical valid state and allocator;
- traversal across at least 500 page transitions keeps heap metrics bounded;
- metrics expose live-metadata, physical descriptor, zero-live stale page,
  expiry-heap, unproved pinned, tombstone/cleanup-intent, and cleanup bytes
  queued/written/dropped counts separately;
- expiry cleanup converges on revisited pages without global scanning;
- corruption, persistence faults, shutdown, and repeated restart leak no ticket,
  pin, worker, file handle, body, or renderer object;
- Windows and macOS results are recorded only when actually observed.

## 11. Stop conditions

Stop for design review if implementation requires:

- a second semantic service, allocator, save root, or entity store;
- reconstructing allocator high-water from loaded pages;
- publishing restart state before every descriptor/page and duplicate has been
  validated through one immutable read view;
- load-order-dependent duplicate handling or runtime activation that disagrees
  with service-owned current-live metadata;
- treating a missing/corrupt page as empty;
- eviction before durable page/checkpoint success;
- wall-clock/offline lifetime or a second expiry counter;
- delaying semantic expiry until page rewrite/durable cleanup succeeds;
- a global page scan, catalog, refcount, database, owner trie, or maintenance
  overlay;
- an unbounded resident map, queue, ticket ledger, or callback batch;
- OpenGL/GLFW work outside the context owner thread;
- silent v1 omission/downgrade.
