# Phase 15 WorldItem TTL Paging Backend Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use
> `superpowers:subagent-driven-development` or `superpowers:executing-plans`.
> Every production change follows tests-only RED, controller approval, minimal
> GREEN, focused verification, and independent review.

**Goal:** Persist, evict, expire, and reactivate WorldItems through bounded
Chunk-local pages while preserving stable IDs, global allocator high-water,
simulation-tick lifetime, atomic unload, and process restart.

**Architecture:** `LogicalWorldItemService` owns semantics, complete bounded
current-live metadata, one deterministic expiry index, and one absolute
`expiresAtWorldTick` per item. Task 4 atomically stores required Chunk page
bytes plus a complete bounded checkpoint through generic mutation/dependency
contracts. There is no global owner directory, opaque blob graph, catalog,
external refcount database, GC, overlay, maintenance protocol, or second
WorldItem authority.

**Tech Stack:** Java 17, JUnit 5, Gradle 8.5 wrapper, existing Phase 14 save
identity/manifest, Task 4 dual-index streamed store, existing owner-thread
physics/render boundaries.

## Global constraints

- `WORLD_ITEM_TTL_TICKS = 18_000L` fixed simulation ticks.
- `expiresAtWorldTick` is the only lifetime authority. Spawn computes a
  saturating sum; pickup, movement, paging, save, and restart never refresh it.
- `SessionPersistenceClock` is the only advancing authority. It supplies the
  existing fixed simulation tick as canonical `worldTick`; the service receives
  monotonic values but cannot advance them. Pause and process downtime do not
  advance it.
- `LogicalWorldItemService` remains the only semantic, stable-ID, allocator,
  ticket, page-membership, and expiry authority.
- Restart pins one index sequence/checkpoint digest, validates every described
  page, duplicate, count, hash, cap, and allocator, then publishes service state
  once. Historical IDs are never reconstructed or reused.
- Task 4 stores generic page/checkpoint bytes only. It never decodes a
  WorldItem, chooses an ID, decides expiry, or resolves item collisions.
- Continue using Phase 14 `SaveRootProvider`, `SaveGameId`, manifest identity,
  seed/generator version, `ChunkKey`, codec registry, and fresh-target restore.
- Hard M2 bounds: active DTO + decoded dormant DTO + evicted-unexpired metadata
  + unique pending total at most 1,024; complete page descriptors at most
  1,024; deterministic expiry heap/index at most 1,024; 32 clean pages; 16 MiB
  decoded page bytes; 64 paging tickets; 64 cleanup intents/64 KiB cleanup
  metadata; 1,024 entries per page operation; one semantic checkpoint at most
  1,024 owner mutations/entries; each invisible physical Task4 staging batch
  at most 64 Chunk mutations/64 MiB encoded bytes and <=256 MiB conservative
  payload-array residency (canonical source + actual encoded batch + six
  maximum-size codec/adapter/reread buffers).
- Missing, corrupt, or unavailable pages are never interpreted as empty.
- Exact equations:
  `sum(expectedLiveCountAtCheckpointTick) ==
  publishedCheckpoint.totalLiveItemCount <= 1,024`,
  `currentLiveMetadata.size == expiryIndex.size <= 1,024`, and
  `physicalDescriptorCount == requiredPageDependencyCount <= 1,024`.
  Durable/runtime equality is required only after restart single-publication or
  accepted intended checkpoint. Resident/pinned dirty delta is separately
  bounded/metred at 1,024 entries/16 MiB candidate bytes. Zero-live physical
  stale pages and bounded cleanup/tombstone work are tracked separately.
- No global page scan, owner trie, overlay, catalog, reference count, GC cursor,
  database, second store, wall-clock expiry, or permanent dormant DTO map.
- GPU/GLFW/OpenGL publication and destruction remain on the context owner
  thread. Workers may only read/encode/decode detached bytes.
- Preserve Java 17 and macOS OpenGL 4.1 compatibility.
- Do not stage, commit, push, create a PR, merge, reset, clean, or modify
  `dist/` unless separately authorized.

---

## Locked interface map

### Engine semantic types

```java
public record WorldItemRuntimeSnapshot(
        WorldItemSnapshot item,
        Optional<EntityRef> source,
        long spawnTick,
        long pickupAvailableTick,
        long expiresAtWorldTick) {}

public record WorldItemPageSnapshot(
        ChunkKey chunkKey,
        long pageRevision,
        List<WorldItemRestoreEntry> entries) {}

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

public record WorldItemPersistencePlan(
        long expectedCheckpointRevision,
        WorldItemPagingCheckpoint intendedCheckpoint,
        List<WorldItemPageMutation> pageMutations,
        String transactionDigest,
        BooleanSupplier stillCurrent) {}

// Engine semantic intent. The game backend translates this to Task 4
// StreamedChunkMutation without creating a second WorldItem authority.
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

### Superseded file classification

After a consumer audit in 6A, delete these abandoned, uncommitted Option A
artifacts if no approved consumer remains:

- `engine/.../worlditem/api/WorldItemOwnerRecord.java`
- `engine/.../worlditem/api/WorldItemOwnerMutation.java`
- `engine/.../worlditem/api/WorldItemAuthoritySnapshot.java`
- `game/.../save/streaming/AuthenticatedOpaqueBlob.java`
- `game/.../save/streaming/StreamedOpaqueBlob.java`
- `game/.../save/streaming/WorldItemAuthorityCodec.java`
- `game/.../save/streaming/WorldItemAuthorityCodecTest.java`

Also delete the abandoned nested/API families and their dedicated assertions:

- `StreamedChunkIndex.OpaqueAuthorityDescriptor`, `opaqueAuthorities()`, and
  `withAuthority(...)`;
- `StreamedPersistenceTransaction.opaqueBlobs`, `authorityUpdates`,
  `authorityCasUpdates`, `OpaqueAuthorityUpdate`, `withAuthorityCas(...)`, and
  `replacements()`;
- `StreamedChunkStore` opaque blob directory/shard/slot paths, candidate
  closure/proof validation, retired roots, authenticated reads, opaque read
  pins, authority-maintenance barrier, GC cursor/result/metrics, orphan scan,
  and every `readOpaque*`/`pinOpaque*`/`collectOpaque*` public seam;
- opaque-authority encoding/decoding in `StreamedChunkIndexCodec`;
- Option A authority/blob/catalog/GC fixtures inside
  `StreamedChunkCodecTest` and `StreamedChunkStoreFaultTest`.

Retain Task 4's core two-index authority/recovery implementation, generic file
guard/fault seams, Chunk codec, and non-opaque crash tests. Rewrite them around
explicit Chunk/extension mutations and generic dependency counts. Defer
`LogicalWorldItemService`, `PhysicalWorldItemSystem`, session orchestration,
whole-world v1 writer, UI, and platform smoke to 6B/6C/6D/6E as assigned. Do
not remove a merged public API without a separate compatibility review.

### Generic Task 4 extension seam

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

public sealed interface StreamedChunkMutation {
    record Upsert(StreamedChunkStore.ExactChunkCapture capture)
            implements StreamedChunkMutation {}
    record Remove(ChunkKey key, long expectedRevision, String expectedHash)
            implements StreamedChunkMutation {}
}

CommitResult commitTransaction(StreamedPersistenceTransaction transaction);
// Package-owned detached generic generation; it exposes no WorldItem semantics.
StreamedChunkStore.PinnedReadView openPinnedReadView();
```

The v3 streamed index stores canonical inline global extensions under the
existing checksummed dual-index envelope. An `Upsert` atomically replaces its
section; a `Remove` atomically removes it; omission retains existing bytes; and
duplicate/conflicting mutations fail before file mutation. Chunk remove is
compare-and-remove against expected revision/hash. One extension and all
retained extension bytes are each capped at 1 MiB, with bounds checked before
collection copy/allocation. The index incrementally maintains required Chunk
extension counts. A global extension declares a dependency ID and exact
post-transaction count; mismatch or checkpoint removal with a nonzero count
fails without a scan or WorldItem branch. Unknown required extensions block
load. The abandoned
`StreamedOpaqueBlob`, child graph, authority descriptor, blob directory, proof
catalog, refcount, and GC APIs are not part of this plan.

### Game codecs and adapter

```java
final class WorldItemPageCodec {
    // First GLWP wire format; independent of whole-world save v1/v2.
    static final int CODEC_VERSION = 1;
    byte[] encode(SaveIdentity save, WorldItemPageSnapshot page);
    WorldItemPageSnapshot decode(
            SaveIdentity expectedSave, ChunkKey expectedKey, byte[] bytes);
}

final class WorldItemPagingCheckpointCodec {
    static final int CODEC_VERSION = 1;
    byte[] encode(WorldItemPagingCheckpoint checkpoint);
    WorldItemPagingCheckpoint decode(
            SaveIdentity expectedSave, byte[] bytes);
}

final class StreamedWorldItemPageBackend implements WorldItemPageSource {
    // Converts the generic pinned generation into the semantic read view.
    WorldItemDurableProof persist(WorldItemPersistencePlan plan);
    WorldItemPageReadView openReadView();

    private final class StoreProof implements WorldItemDurableProof {
        private StoreProof(/* issuer, save/root, revision, digest, sequence */) {}
    }

    private final class StoreDurabilityVerifier
            implements WorldItemDurabilityVerifier {
        private StoreDurabilityVerifier() {}
    }
}
```

The adapter maps `SaveIdentity` exactly to `SaveGameId`, encodes the service
plan, maps page mutations to generic Chunk extension upsert/remove, calls one
Task 4 transaction, then creates its private proof only after force/reread and
reopen validation. The private verifier checks proof class/issuer, save/root,
checkpoint revision, digest, and current published sequence. Production
composition injects that verifier into the service once; callers cannot
instantiate proof/verifier or substitute one. The service alone performs
`commitPersistence(ticket, proof)` and single-consumes its own private-ctor
ticket. Proof is not serialized. The backend has no allocator, item map, expiry
policy, or collision policy.

---

## Task 6A: TTL page format and generic checkpoint seam

**Estimate:** 1.5–3 engineer-days.

**Files:**

- Modify: `engine/src/main/java/com/overlord/worlditem/api/WorldItemRuntimeSnapshot.java`
- Modify: `engine/src/main/java/com/overlord/worlditem/api/WorldItemPageSnapshot.java`
- Create: `engine/src/main/java/com/overlord/worlditem/api/WorldItemPageDescriptor.java`
- Create: `engine/src/main/java/com/overlord/worlditem/api/WorldItemDurablePageProof.java`
- Create: `engine/src/main/java/com/overlord/worlditem/api/WorldItemPagingCheckpoint.java`
- Modify: `engine/src/main/java/com/overlord/worlditem/api/WorldItemPersistencePlan.java`
- Create: `engine/src/main/java/com/overlord/worlditem/api/WorldItemPageMutation.java`
- Delete after consumer audit: `engine/src/main/java/com/overlord/worlditem/api/WorldItemDurableReceipt.java`
- Create: `engine/src/main/java/com/overlord/worlditem/api/WorldItemPersistenceTicket.java`
- Create: `engine/src/main/java/com/overlord/worlditem/api/WorldItemDurableProof.java`
- Create: `engine/src/main/java/com/overlord/worlditem/api/WorldItemDurabilityVerifier.java`
- Modify: `engine/src/main/java/com/overlord/worlditem/api/WorldItemPageSource.java`
- Create: `engine/src/main/java/com/overlord/worlditem/api/WorldItemPageReadView.java`
- Delete after consumer audit: the three abandoned owner/authority DTOs listed
  above
- Modify: `game/src/main/java/com/gaia/save/format/SaveSectionId.java`
- Modify: `game/src/main/java/com/gaia/save/streaming/WorldItemPageCodec.java`
- Create: `game/src/main/java/com/gaia/save/streaming/WorldItemPagingCheckpointCodec.java`
- Create: `game/src/main/java/com/gaia/save/streaming/StreamedGlobalExtension.java`
- Create: `game/src/main/java/com/gaia/save/streaming/RequiredChunkExtensionDependency.java`
- Create: `game/src/main/java/com/gaia/save/streaming/StreamedGlobalExtensionMutation.java`
- Create: `game/src/main/java/com/gaia/save/streaming/StreamedChunkMutation.java`
- Modify: `game/src/main/java/com/gaia/save/streaming/StreamedPersistenceTransaction.java`
- Modify: `game/src/main/java/com/gaia/save/streaming/StreamedChunkIndex.java`
- Modify: `game/src/main/java/com/gaia/save/streaming/StreamedChunkIndexCodec.java`
- Modify: `game/src/main/java/com/gaia/save/streaming/StreamedChunkStore.java`
- Delete after consumer audit: the three abandoned game authority/blob files
  and their dedicated codec test listed above
- Modify test: `game/src/test/java/com/gaia/save/streaming/WorldItemPageCodecTest.java`
- Create test: `game/src/test/java/com/gaia/save/streaming/WorldItemPagingCheckpointCodecTest.java`
- Modify test: `game/src/test/java/com/gaia/save/streaming/StreamedChunkCodecTest.java`
- Modify test: `game/src/test/java/com/gaia/save/streaming/StreamedChunkStoreFaultTest.java`
- Report: `.superpowers/sdd/2026-08-12-phase-15-infinite-world-streaming/task-6a-ttl-report.md`

**Produces:** literal `GLWP` v1 pages carrying exact expiry; checkpoint v1 with
complete descriptors and live count; immutable sequence-pinned read view;
explicit Chunk/page/global upsert/remove; generic dependency counts; and one
atomic Chunk-page-checkpoint transaction.

- [x] **Step 1: Write runtime and literal codec REDs**

  Add literal independent fixtures for `GLWP` and checkpoint bytes. Cover
  `expiresAtWorldTick` at spawn, exact boundary `worldTick == expiry`,
  `Long.MAX_VALUE` saturation, negative/unsafe values, all physical states,
  stable-ID sorting, duplicate/noncanonical entries, wrong save/key, checksum,
  truncation, trailing bytes, oversize counts, and defensive copies. Checkpoint
  fixtures cover canonical descriptors, unique keys, SHA-256, positive
  revision/raw encoded count, zero-to-raw expected survivor count, checked
  survivor sum, <=1,024 physical descriptors/live survivors, and allocator
  bounds.

- [x] **Step 2: Write generic storage REDs**

  Add v3 index literals with one required inline `world-item-checkpoint`
  extension while retaining index v1/v2 readers. Cover page plus optional
  `detail-blocks`, upsert/remove/omission-retains semantics, duplicate or
  conflicting mutation rejection, exact one-extension and aggregate 1 MiB
  boundaries, explicit expected-revision/hash Chunk remove, item-only Chunk
  removal versus extension-only removal, stale remove rejection, incremental
  dependency count, checkpoint removal/count mismatch, unknown required
  extension closure, aggregate size checks before copying lazy tails, and
  absence of WorldItem imports/branches/scans in Task 4 generic production
  files. Fill all 1,024 physical descriptors; prove a new page requires paired
  expected-hash removal of a zero-live stale page and persistence failure keeps
  the active candidate resident/pinned.

- [x] **Step 3: Write crash/freshness REDs**

  Fault each Chunk page write/force/reread and recovery/main index
  write/force/reread. Reopen must select exact old pages+checkpoint or complete
  new pages+checkpoint+dependency count. Pin one immutable read view while a
  newer index publishes and prove every page/checkpoint read remains at the
  original sequence/digest until close. Every freshness callback runs exactly
  once before the first mutation; stale/RuntimeException fails closed and exact
  `Error` escapes.

- [x] **Step 4: Run tests-only RED and stop**

  ```powershell
  .\gradlew.bat :game:test --tests 'com.gaia.save.streaming.WorldItem*CodecTest' --tests 'com.gaia.save.streaming.StreamedChunk*Test' --console=plain --no-daemon
  ```

  Record exact compile/semantic failures and production hashes. Do not begin
  GREEN before controller approval.

- [x] **Step 5: Implement minimal codecs and inline transaction**

  Use bounded data-stream framing, canonical order, exact SHA/checksum,
  saturating-add validation, defensive copies, and the existing guarded file
  operation boundary. Remove obsolete owner/blob/catalog code only after the
  compiler and structural tests prove it has no approved consumer.

- [x] **Step 6: Verify and review 6A**

  Run the Step 4 suite, all streamed codec/store tests, Phase 14 atomic and
  migration groups, `git diff --check`, dependency scans, direct file-mutation
  scans, and generated-output scans. Require independent 0 Critical / 0
  Important before 6B.

---

## Task 6B: Dormant activation, durable eviction, and lazy expiry

**Estimate:** 1.5–2.5 engineer-days.

**Files:**

- Create: `engine/src/main/java/com/overlord/worlditem/api/WorldItemPageCachePolicy.java`
- Create: `engine/src/main/java/com/overlord/worlditem/api/WorldItemPagingMetrics.java`
- Create: `engine/src/main/java/com/overlord/worlditem/api/WorldItemLiveMetadata.java`
- Create: `engine/src/main/java/com/overlord/worlditem/api/WorldItemLiveState.java`
- Create: `engine/src/main/java/com/overlord/worlditem/WorldItemPageCache.java`
- Create: `engine/src/main/java/com/overlord/worlditem/WorldItemPagingState.java`
- Create: `engine/src/main/java/com/overlord/worlditem/WorldItemExpiryIndex.java`
- Modify: `engine/src/main/java/com/overlord/worlditem/LogicalWorldItemService.java`
- Modify: `engine/src/main/java/com/overlord/worlditem/api/LogicalWorldItemSnapshot.java`
- Modify: existing hibernate/activation ticket, payload, and result types
- Modify test: `engine/src/test/java/com/overlord/worlditem/LogicalWorldItemPagingTest.java`
- Modify test: `engine/src/test/java/com/overlord/worlditem/LogicalWorldItemPersistenceTest.java`
- Create test: `engine/src/test/java/com/overlord/worlditem/LogicalWorldItemPageCacheTest.java`
- Create test: `engine/src/test/java/com/overlord/worlditem/LogicalWorldItemExpiryTest.java`
- Create test: `game/src/test/java/com/gaia/save/streaming/WorldItemDurabilityCapabilityTest.java`
- Report: `.superpowers/sdd/2026-08-12-phase-15-infinite-world-streaming/task-6b-report.md`

**Consumes:** 6A page/checkpoint/mutation/ticket/proof types. **Produces:** complete
bounded current-live metadata, expiry heap/index, cleanup-intent queue,
service-owned hibernate/activation plans, and exact ticket+proof single-consume
durable eviction.

- [x] **Step 1: Write allocator and expiry REDs**

  Restore checkpoint `(worldTick=100, nextItemId=101)` before any page. Spawn at
  tick 100 and assert expiry 18,100. Advance to 18,099/18,100, pause without tick
  movement, exercise `Long.MAX_VALUE` saturation, and prove expired IDs are
  never reused. Partial pickup and movement retain original ID/expiry. Assert
  the service rejects self-advancement and accepts only monotonic delivered
  `worldTick` values.

- [x] **Step 2: Write bounded active-expiry REDs**

  Fill the service with 1,024 live IDs across active, decoded dormant, evicted
  unexpired, and pending states. The expiry index has the exact same IDs ordered
  by `(expiresAtWorldTick,id)`. Deliver one tick at which all are due and assert
  every item becomes noninteractive/noncanonical-live in that call, with
  metadata/index deleted and reservations/tickets canceled or stale. No
  smaller cleanup budget may delay semantic expiry.

- [x] **Step 3: Write hibernate and durable-eviction REDs**

  Exercise capture -> expiry filter -> opaque service ticket/public plan ->
  failed proof/cancel exact retention -> successful
  `commitPersistence(ticket,proof)` -> DTO/projection eviction. Moving an item
  across a Chunk boundary rewrites old/new pages together. Save failure retains
  the exact canonical state and last known-good page. Proof tests vary
  checkpoint revision, digest, and published index sequence independently;
  ticket tests use foreign/replayed identity/provenance. The backend never sees
  or echoes ticket secrets. Prove ticket constructor, backend `StoreProof`, and
  verifier constructors are inaccessible; caller-defined marker proof and
  substituted verifier cannot authorize commit; proof appears in no codec.
  Exact verifier match plus ticket single consumption is required.

- [x] **Step 4: Write activation REDs**

  Validate one pinned-view checkpoint/page first; filter expired entries before
  capacity or projection publication. Require every ID/key/expiry/page
  revision/hash/state to match service current-live metadata. Cover wrong
  save/key, stale revision/hash, missing metadata, active/cache collision,
  foreign/replayed ticket, all-pinned cache, load-order-dependent rejection,
  projection preparation failure, and page dirtied by expiry cleanup.

- [x] **Step 5: Write cache-bound REDs**

  Use a two-page/three-live-entry/512-byte policy across 100 historical pages.
  Assert active DTO + decoded dormant DTO + evicted-unexpired metadata + unique
  pending never exceeds the single cap; metadata separates intended owner/
  revision from optional durable proof; clean dormant/evicted requires proof;
  unproved active/dirty/pending remains resident/pinned; pickup/expiry deletes
  it immediately; deterministic LRU+ChunkKey
  tie-break holds; pins prevent DTO eviction; and no physics/render/game object
  is reachable from metadata/cache values. Fill the 64-intent/64-KiB cleanup
  queue, prove overflow drops the intent without an alternate backlog, then
  reread the page and rediscover/rewrite or expected-hash remove it.

- [x] **Step 6: Run tests-only RED and stop**

  ```powershell
  .\gradlew.bat :engine:test --tests 'com.overlord.worlditem.LogicalWorldItem*Test' --console=plain --no-daemon
  ```

- [x] **Step 7: Implement one bounded service aggregate**

  Store active DTOs, decoded DTO cache, the complete <=1,024 metadata map,
  identical-ID expiry heap/index, allocator, received world tick, bounded
  cleanup intents, pins, reservations, private tickets, and checkpoint revision
  in `WorldItemPagingState`. Do not retain expired history, permanent dormant
  DTOs, a second owner map, or a cleanup backlog.

- [x] **Step 8: Verify and review 6B**

  Run all engine WorldItem, inventory, interaction, physics-adjacent, cache,
  expiry, and snapshot tests. Audit collections and weak references for bounded
  retention. Require independent review before 6C.

---

## Task 6C: v1 fail-closed, v2 checkpoint, and process restart

**Estimate:** 1.5–2 engineer-days.

**Files:**

- Create: `game/src/main/java/com/gaia/save/streaming/StreamedWorldItemPageBackend.java`
- Modify: `game/src/main/java/com/gaia/save/snapshot/WorldItemsSaveSnapshot.java`
- Modify: `game/src/main/java/com/gaia/save/codec/WorldItemsSectionCodec.java`
- Modify: `game/src/main/java/com/gaia/save/streaming/Phase14SaveMigrator.java`
- Modify: `game/src/main/java/com/gaia/save/streaming/Phase14MigrationResult.java`
- Modify: `game/src/main/java/com/gaia/save/store/SaveRepository.java`
- Modify: `game/src/main/java/com/gaia/save/archive/SaveArchiveReader.java`
- Modify: `game/src/main/java/com/gaia/save/session/SessionRestoreCoordinator.java`
- Modify: `game/src/main/java/com/gaia/session/SessionPersistenceClock.java`
- Modify: `game/src/main/java/com/gaia/session/GameSessionFactory.java`
- Modify test: `game/src/test/java/com/gaia/save/codec/WorldItemsSectionCodecTest.java`
- Modify test: `game/src/test/java/com/gaia/save/streaming/Phase14SaveMigrationTest.java`
- Create test: `game/src/test/java/com/gaia/save/streaming/WorldItemPagingRestartTest.java`
- Modify test: `game/src/test/java/com/gaia/save/SaveFailureRecoveryIntegrationTest.java`
- Modify test: `game/src/test/java/com/gaia/save/session/SessionRestoreCoordinatorTest.java`
- Create test: `game/src/test/java/com/gaia/session/SessionPersistenceClockTest.java`
- Modify test: `game/src/test/java/com/gaia/session/GameSessionPersistenceTest.java`
- Report: `.superpowers/sdd/2026-08-12-phase-15-infinite-world-streaming/task-6c-report.md`

**Produces:** exact v1 compatibility gate, atomic v1->v2 TTL migration, and
worldTick/allocator-first restart.

- [x] **Step 1: Write v1 compatibility REDs**

  Decode exact legacy bytes and derive each expiry with saturating
  `spawnTick + 18,000`. Preserve byte-identical v1 write only for
  `LEGACY_COMPLETE` when every expiry equals the saturating derivation. Require
  expiry mismatch or `world-items-v1.paged-state-unsupported` before any
  temp/current/backup/checkpoint/session mutation when paged state, a v2
  checkpoint, evicted page, or prepared paging ticket exists.

- [x] **Step 2: Write migration REDs**

  Use the real 81-Chunk fixture. Bucket all entries by checked position/key,
  derive expiry once, and atomically publish all pages plus checkpoint using
  source manifest fixed tick and saved allocator. Fault every page/checkpoint/
  index/marker boundary and require exact v1 or complete v2 with the original
  v1 backup intact.

- [x] **Step 3: Write process restart REDs**

  Persist pages, dispose every service/backend/store, reopen one immutable
  `(indexSequence,checkpointDigest)` read view, then read every canonical
  descriptor/page from that view. Validate identities, revisions, hashes,
  raw encoded counts, checkpoint-tick expected survivor counts, global survivor
  duplicate IDs, total <=1,024, and allocator high-water before
  publishing checkpoint plus complete current-live metadata exactly once.
  Repeat forward/reverse/shuffled and while a newer index publishes. Assert
  identical state, no load-order selection, unchanged expiry, no offline tick
  advance, expired-page filtering, and no physical body before logical
  publication.

  Prove `SessionPersistenceClock` alone advances from fixed simulation steps,
  pause delivers no advancement, save/restart preserves its exact value, and
  neither `LogicalWorldItemService` nor backend/page callbacks can increment it.
  `SessionRestoreCoordinator` consumes and restores only the existing
  `com.gaia.session.SessionPersistenceClock`; no parallel clock is created.

- [x] **Step 4: Write save/shutdown REDs**

  Dirty active items and expiry-cleaned pages must be in one checkpoint plan.
  Save & Quit cannot advance checkpoint or close before
  `commitPersistence(ticket,proof)` validates. Failure keeps
  active/projection state and old checkpoint.

- [x] **Step 4A: Prove bounded prepublication staging**

  Stage legal 65- and 1,024-owner candidates through one or more physical
  batches, each <=64 payloads and <=64 MiB. Readers keep the last-known-good
  root until complete candidate validation publishes one recovery/main index
  generation containing page, WorldItem checkpoint, and session checkpoint.
  Test total candidate bytes >64 MiB, first/middle/final batch failure,
  pre/post-publication crash, stale/cancel, late payload mismatch, inactive-slot
  restart, and unchanged legacy single-batch behavior. Reuse only fixed A/B
  payload slots; add no catalog, WAL, GC, or temporary-name authority.

- [x] **Step 5: Run tests-only RED and stop**

  ```powershell
  .\gradlew.bat :game:test --tests 'com.gaia.save.streaming.WorldItemPagingRestartTest' --tests 'com.gaia.save.streaming.Phase14SaveMigrationTest' --tests 'com.gaia.save.codec.WorldItemsSectionCodecTest' --tests 'com.gaia.save.session.SessionRestoreCoordinatorTest' --tests 'com.gaia.session.GameSessionPersistenceTest' --console=plain --no-daemon
  ```

- [x] **Step 6: Implement backend, compatibility gate, and restore order**

  The adapter encodes exactly the public service plan and returns only durable
  proof; it never receives the opaque ticket. Migration publishes
  page/checkpoint/dependency bytes atomically.
  Restore validates manifest world tick equals checkpoint world tick, opens one
  pinned read view, validates all pages/duplicates/caps/allocator into detached
  bounded candidates, then constructs and publishes a fresh service once.
  WorldItem save produces physical payloads incrementally, retains at most one
  <=64-mutation batch, validates the full <=1,024 descriptor candidate, and
  invokes the Task4 final root publication once.

- [x] **Step 7: Verify and review 6C**

  Run Step 5 plus Phase 14 archive/store/security/settings, Task 4/5 bounded
  groups, and save/session integration. Audit no allocator recompute, no silent
  v1 downgrade, and no wall-clock use.

  Correction-round evidence after the first final review: 82/82 GREEN across
  16 staging adversarial cases, 24 Task4 TTL/store cases, 16 Task6C restart
  cases, and 26 expanded Phase14 persistence/fault cases. Final independent
  rereview: 0 Critical / 0 Important / 0 Minor — READY. Task 6C is CLOSED;
  STOP before Task 6D.

---

## Task 6D: Projection reentrancy and exact rollback

**Estimate:** 0.5–1 engineer-day.

**Files:**

- Modify: `engine/src/main/java/com/overlord/worlditem/LogicalWorldItemService.java`
- Modify: `game/src/main/java/com/gaia/worlditem/PhysicalWorldItemSystem.java`
- Modify test: `engine/src/test/java/com/overlord/worlditem/LogicalWorldItemPagingTest.java`
- Modify test: `engine/src/test/java/com/overlord/worlditem/LogicalWorldItemExpiryTest.java`
- Modify test: `game/src/test/java/com/gaia/worlditem/PhysicalWorldItemPagingTest.java`
- Modify test: `game/src/test/java/com/gaia/worlditem/PhysicalWorldItemSystemTest.java`
- Report: `.superpowers/sdd/2026-08-12-phase-15-infinite-world-streaming/task-6d-report.md`

**Produces:** owner-thread detached projection batches and exact rollback for
activation, hibernate, and active expiry.

- [x] **Step 1: Write guard-first reentrancy REDs**

  During callbacks invoke spawn, pickup, motion, restore, paging, and expiry
  mutators. Every call fails before allocator, tick, cursor, reservation, item,
  cache, ticket, or projection mutation. Reads remain valid.

- [x] **Step 2: Write rollback REDs**

  Fail before first body, after each partial body, after renderer registration,
  and during expiry removal. Assert exact prior canonical/page-owned state, no
  body/renderer leak, consumed ticket behavior, retry success, suppressed
  cleanup ordering, and exact `Error` transparency.

- [x] **Step 3: Run tests-only RED and stop**

  ```powershell
  .\gradlew.bat :engine:test --tests 'com.overlord.worlditem.LogicalWorldItem*Test' :game:test --tests 'com.gaia.worlditem.PhysicalWorldItemPagingTest' --tests 'com.gaia.worlditem.PhysicalWorldItemSystemTest' --console=plain --no-daemon
  ```

- [x] **Step 4: Implement detached projection transaction**

  Check the service callback guard first in every mutator. Build detached
  bodies without registration, publish logical/projection state once, and on
  failure restore exact state and clean every partial projection.

- [x] **Step 5: Verify and review 6D**

  Run engine/game WorldItem, physics, inventory, interaction, session restore,
  renderer-structure, and owner-thread tests. Audit every public mutator and
  projection path.

  Closure evidence: focused 152/152 GREEN (engine 96, game 56), affected game
  proportional 438/438 GREEN, frozen Task 6C proportional subset 32/32 GREEN,
  and final independent review 0 Critical / 0 Important / 0 Minor — READY.
  The affected engine proportional matrix passed 397/399; its two unrelated
  `BlockRaycastTest` fixture failures occur at the pre-existing safe-coordinate
  boundary before reaching a Task 6D path and remain outside this scope.
  Task 6D is CLOSED / READY; STOP before Task 6E.

---

## Task 6E: Restart, order, memory, expiry, and corruption acceptance

**Estimate:** 1–1.5 engineer-days plus platform smoke.

**Files:**

- Create: `game/src/test/java/com/gaia/save/streaming/WorldItemPagingAcceptanceTest.java`
- Create: `game/src/test/java/com/gaia/save/streaming/WorldItemPagingCorruptionTest.java`
- Create: `game/src/test/java/com/gaia/world/streaming/WorldItemPagingSoakTest.java`
- Create: `game/src/test/java/com/gaia/world/streaming/WorldItemPagingMetricsTest.java`
- Modify: `docs/superpowers/specs/2026-08-12-phase-15-infinite-world-streaming-design.md`
- Modify: `docs/superpowers/plans/2026-08-12-phase-15-infinite-world-streaming.md`
- Modify: `.superpowers/sdd/2026-08-12-phase-15-infinite-world-streaming/progress.md`
- Report: `.superpowers/sdd/2026-08-12-phase-15-infinite-world-streaming/task-6e-report.md`

**Produces:** Gate 15C acceptance evidence consumed by Tasks 7, 8, 11, and 12.

- [x] **Step 1: Build real process-style fixture**

  Use one save root, Task 4 store, checkpoint/page codecs, adapter, service, and
  physical system. Persist, dispose every object, reopen one immutable index
  sequence/checkpoint digest view, validate the full descriptor/page set, then
  publish and activate through the owner-thread path.

- [x] **Step 2: Test order, stable ID, and TTL**

  Use at least six pages across negative/origin/positive keys with interleaved
  IDs and expiries. Run forward/reverse/shuffled activation, pause spans,
  unload spans, restart, partial/full pickup, boundary movement, and exact
  expiry ticks. Assert valid data produces byte-identical canonical outcomes,
  complete metadata, and allocator state before/after DTO eviction.

- [x] **Step 3: Test corruption and last-known-good behavior**

  Cover duplicate within/across checkpoint survivor sets, metadata/page proof
  mismatch, raw-count/survivor-count/descriptor/dependency mismatch, zero-live
  stale pages, full-descriptor paired removal, collision with active/cache
  state, wrong save/key, corrupt page/checkpoint, missing required page, stale
  expected revision/hash, foreign/replayed ticket, proof revision/digest/
  index-sequence mismatch and replay, concurrent index publication under a read
  view, and every
  dual-index crash boundary. Require fail closed, exact old/new state, active
  retention before eviction, expired items remaining dead after cleanup
  failure, and no partial restart/activation.

- [x] **Step 4: Run 500-transition structural soak**

  Mix drops, expiry, partial/full pickup, reversals, unload/reload, persistence
  faults, pause, cleanup-queue saturation, and restart. After every epoch assert
  active/decoded/evicted/pending/current-live metadata, descriptor, expiry-heap,
  page/byte, ticket/pin/callback, dirty-delta entries/bytes,
  tombstone/cleanup-intent, and cleanup
  queued/written/dropped byte metrics against hard bounds. Revisited expired
  pages converge without a global scan; total-distance history retains no
  expired metadata, DTOs, bodies, renderer objects, or cleanup backlog.

- [x] **Step 5: Test shutdown and repeated sessions**

  Assert no worker, file handle, reservation, ticket, pin, body, renderer, or
  cache entry survives close, and primary/suppressed close order remains exact.

- [x] **Step 6: Run acceptance matrix**

  ```powershell
  .\gradlew.bat :game:test --tests 'com.gaia.save.streaming.WorldItemPagingAcceptanceTest' --tests 'com.gaia.save.streaming.WorldItemPagingCorruptionTest' --tests 'com.gaia.world.streaming.WorldItemPagingSoakTest' --tests 'com.gaia.world.streaming.WorldItemPagingMetricsTest' --console=plain --no-daemon
  ```

  Then run 6A–6D focused suites, Task 4/5 groups, engine/game WorldItem,
  inventory, interaction, physics, save/session, and clean `test build` when no
  long matrix is active.

- [x] **Step 7: Final audits and platform acceptance**

  Run `git diff --check`; scan for second authority, allocator derivation,
  permanent dormant maps, wall-clock expiry, missing-page-as-empty, v1 silent
  omission, global scans/catalog/refcounts/GC/maintenance, direct unguarded file
  mutation, worker GPU calls, generated files, and absolute JDK paths. Record
  Windows/macOS results only when actually observed.

## Plan self-review

- **TTL authority:** Every lifetime path uses only absolute
  `expiresAtWorldTick` and canonical simulation `worldTick`.
- **Single authority:** Only `LogicalWorldItemService` allocates IDs or decides
  item/page/expiry semantics.
- **Bounded memory:** Complete current-live metadata/descriptors/expiry index
  are <=1,024; decoded DTOs/bytes, tickets, callbacks, cleanup intents/bytes,
  and transaction candidates have separate hard bounds.
- **Atomicity:** Expected-revision/hash page/Chunk mutations, generic dependency
  count, and checkpoint publish in one Task 4 dual-index transaction; eviction
  follows exact single-consume `commitPersistence(ticket,proof)` and the backend
  never receives/echoes ticket secrets.
- **Compatibility:** Legacy v1 read derives fixed expiry; v1 write additionally
  verifies exact derivation and rejects paged state; v2 validates every page
  under one read view before publishing tick/allocator/metadata once.
- **Simplification:** No owner directory, opaque blob graph, catalog, refcount,
  GC, overlay, maintenance protocol, database, or global sweep remains.
- **Task reset:** 6A TTL seam; 6B dormant activation/eviction; 6C v2/restart;
  6D projection rollback; 6E acceptance.
- **Estimate:** 6–10 engineer-days plus Windows and Apple Silicon macOS smoke.
