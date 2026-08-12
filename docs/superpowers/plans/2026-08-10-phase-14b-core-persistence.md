# Phase 14B Core Persistence Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Encode and decode all v1 canonical sections deterministically, capture a live paused session, and restore a complete fresh session with exact canonical equality and rebuilt presentation.

**Architecture:** Gate 14B implements pure codecs over Gate 14A snapshots, then adds one session capture/restore coordinator. Disk/archive atomicity remains Gate 14C; product menus remain Gate 14D.

**Tech Stack:** Java 17, Gson 2.10.1, JDK `DataInputStream`/`DataOutputStream`, SHA-256, JUnit 6.1.1.

## Global Constraints

- All Phase 14A constraints remain in force.
- Codecs are deterministic and bounded; do not serialize Java objects with `ObjectOutputStream`.
- Full canonical Chunk bytes are authoritative; do not regenerate and apply deltas.
- Restore creates no GPU state and serializes no PhysicsBody.
- Do not stage, commit, push, create a PR, or merge.

---

## File Structure

Create one codec per section under `com.gaia.save.codec`. Use explicit DTOs for Gson and an explicit binary Chunk layout. `SaveSnapshotCodec` is the only component that assembles all section payloads and hashes.

### Task 1: Deterministic Chunk binary codec

**Files:**
- Create: `game/src/main/java/com/gaia/save/codec/ChunkSectionCodec.java`
- Create: `game/src/main/java/com/gaia/save/codec/SaveCodecException.java`
- Test: `game/src/test/java/com/gaia/save/codec/ChunkSectionCodecTest.java`

**Interfaces:**
- Implements: `SaveSectionCodec<ChunkRepositorySnapshot>` with ID `chunks`, codec version 1, required.
- Binary header: ASCII `GLCH`, codec version, world height, revision high-water, Chunk count; then sorted x, z, revision, block length, block bytes in big-endian order.

- [ ] **Step 1: Write exact-byte and corruption RED tests**

```java
@Test
void shuffledChunkInputEncodesToIdenticalBytes() {
    assertArrayEquals(codec.encode(snapshot(a, b)), codec.encode(snapshot(b, a)));
}

@Test
void decodeRejectsTrailingBytesAndOversizedCountBeforeAllocation() {
    assertThrows(SaveCodecException.class, () -> codec.decode(bytesWithCount(Integer.MAX_VALUE)));
    assertThrows(SaveCodecException.class, () -> codec.decode(concat(validBytes(), new byte[]{1})));
}
```

Cover magic/version, checked length arithmetic, duplicate key, negative revision, high-water below a Chunk revision, truncated bytes, exact defensive copies, and radius-8 supported bounds.

- [ ] **Step 2: Run RED**

```powershell
.\gradlew.bat :game:test --tests com.gaia.save.codec.ChunkSectionCodecTest --console=plain --no-daemon
```

- [ ] **Step 3: Implement minimal explicit binary codec**

Never trust encoded lengths before checking against the supported Chunk count and exact canonical block length. Sort a defensive copy; reject duplicates rather than silently deduplicating.

```java
out.writeBytes("GLCH");
out.writeInt(codecVersion());
out.writeInt(snapshot.worldHeight());
out.writeLong(snapshot.revisionHighWater());
out.writeInt(chunks.size());
for (ChunkSnapshot chunk : chunks) {
    writeChunk(out, chunk);
}
```

- [ ] **Step 4: Run GREEN and deterministic mutation check**

Rerun the focused test. Flip one block byte in the test input and verify encoded bytes and later hash change, then restore the fixture.

- [ ] **Step 5: Review checkpoint**

Run `git diff --check`; confirm no ZIP/filesystem or world-generation code was added. Do not commit.

### Task 2: Player, Inventory, and WorldItem JSON codecs

**Files:**
- Create: `game/src/main/java/com/gaia/save/codec/PlayerSectionCodec.java`
- Create: `game/src/main/java/com/gaia/save/codec/InventorySectionCodec.java`
- Create: `game/src/main/java/com/gaia/save/codec/WorldItemsSectionCodec.java`
- Test: `game/src/test/java/com/gaia/save/codec/PlayerSectionCodecTest.java`
- Test: `game/src/test/java/com/gaia/save/codec/InventorySectionCodecTest.java`
- Test: `game/src/test/java/com/gaia/save/codec/WorldItemsSectionCodecTest.java`

**Interfaces:**
- Each codec implements `SaveSectionCodec<T>`, version 1, required.
- JSON DTO field order is explicit and encoded UTF-8 is canonical for the same snapshot.

- [ ] **Step 1: Write RED for valid round trips and impossible payloads**

Player tests cover exact finite position/velocity, yaw normalization policy, pitch bounds, game mode, and noclip. Inventory tests cover every slot, active slot, revision, two-handed representation, item IDs/counts, duplicate fields, and unknown enum. WorldItem tests cover sorted stable IDs, source, ticks, states, allocator high-water/exhaustion, duplicate IDs, nonfinite motion, and malformed JSON.

```java
assertEquals(snapshot, codec.decode(codec.encode(snapshot)));
assertArrayEquals(codec.encode(shuffled), codec.encode(sorted));
assertThrows(SaveCodecException.class, () -> codec.decode(invalidJson));
```

- [ ] **Step 2: Run strict RED**

```powershell
.\gradlew.bat :game:test --tests 'com.gaia.save.codec.*SectionCodecTest' --console=plain --no-daemon
```

- [ ] **Step 3: Implement DTO conversion with domain-constructor validation**

Do not let Gson instantiate authoritative domain records as a validation bypass. Parse DTOs, then construct `ResourceLocation`, `ItemStack`, `EntityRef`, enum, and canonical snapshot values through their public constructors. Wrap bounded parse/validation failures as `SaveCodecException` with section-specific codes.

```java
ItemStack toDomain(StackDocument document) {
    return new ItemStack(
            ResourceLocation.parse(document.itemId()),
            document.count());
}
```

- [ ] **Step 4: Run GREEN and existing item contract tests**

```powershell
.\gradlew.bat :game:test --tests 'com.gaia.save.codec.*' --tests 'com.gaia.inventory.*ContractTest' --console=plain --no-daemon
.\gradlew.bat :engine:test --tests 'com.overlord.worlditem.api.*ContractTest' --console=plain --no-daemon
```

- [ ] **Step 5: Review checkpoint**

Inspect encoded JSON for no class names, absolute paths, runtime handles, or reservation IDs. Run `git diff --check`. Do not commit.

### Task 3: Section assembly, hashes, and deterministic SaveGameSnapshot round trip

**Files:**
- Create: `game/src/main/java/com/gaia/save/codec/EncodedSaveSection.java`
- Create: `game/src/main/java/com/gaia/save/codec/EncodedSaveGame.java`
- Create: `game/src/main/java/com/gaia/save/codec/SaveSnapshotCodec.java`
- Test: `game/src/test/java/com/gaia/save/codec/SaveSnapshotCodecTest.java`

**Interfaces:**
- Produces: `EncodedSaveGame SaveSnapshotCodec.encode(SaveGameSnapshot, Instant modifiedTime)`.
- Produces: `SaveGameSnapshot SaveSnapshotCodec.decode(SaveGameManifest, Map<SaveSectionId, byte[]>)`.
- `EncodedSaveSection` contains descriptor plus a defensive byte array.

- [ ] **Step 1: Write aggregate RED**

```java
@Test
void everyDescriptorMatchesExactPayloadSizeAndSha256() {
    EncodedSaveGame encoded = codec.encode(snapshot, MODIFIED);
    encoded.sections().forEach(section -> {
        assertEquals(section.bytes().length, section.descriptor().uncompressedSize());
        assertEquals(sha256(section.bytes()), section.descriptor().sha256());
    });
}
```

Cover fixed section order, manifest/save-ID agreement, required section absence, checksum mismatch, optional unknown skip diagnostic, unchanged canonical section bytes across repeated save, and modified-time-only manifest difference.

- [ ] **Step 2: Run RED, implement, run GREEN**

```powershell
.\gradlew.bat :game:test --tests com.gaia.save.codec.SaveSnapshotCodecTest --console=plain --no-daemon
```

Pre-encode and bound all domain sections, compute descriptors, then construct the complete manifest. Decode verifies descriptor-to-byte size/hash before delegating to a section codec.

```java
byte[] bytes = codec.encode(sectionValue);
SaveSectionDescriptor descriptor = new SaveSectionDescriptor(
        codec.sectionId(), codec.codecVersion(), codec.required(),
        bytes.length, Sha256.hex(bytes));
```

- [ ] **Step 3: Review checkpoint**

Run all `com.gaia.save.codec.*` tests and `git diff --check`. Confirm the code still performs no filesystem I/O. Do not commit.

### Task 4: Session capture and fresh canonical restore coordinator

**Files:**
- Create: `game/src/main/java/com/gaia/save/session/SessionPersistenceRevision.java`
- Create: `game/src/main/java/com/gaia/save/session/SessionRestoreCoordinator.java`
- Modify: `game/src/main/java/com/gaia/session/GameSession.java`
- Modify: `game/src/main/java/com/gaia/session/GameSessionFactory.java`
- Modify: `engine/src/main/java/com/overlord/physics/PlayerController.java`
- Test: `game/src/test/java/com/gaia/save/session/SessionSaveCaptureTest.java`
- Test: `game/src/test/java/com/gaia/save/session/SessionRestoreCoordinatorTest.java`
- Test: `game/src/test/java/com/gaia/session/GameSessionPersistenceTest.java`

**Interfaces:**
- Adds to `GameSession`: `com.gaia.save.snapshot.SessionSaveCaptureResult captureSave()` and `void markSaved(SessionPersistenceRevision)`; both owner-thread, READY-only operations.
- Adds a typed load construction path to `GameSessionFactory` that consumes a validated `SaveGameSnapshot` rather than private-field mutation.
- Adds `PlayerController.restoreCanonical(position, velocity, noclip)` with full prevalidation and penetration recovery performed by the coordinator after Chunks exist.

- [ ] **Step 1: Write capture RED**

Create a READY session fixture with mutated world, player, inventory, and WorldItems. Assert exact aggregate capture, fixed tick, seed/config, and monotonically increasing persistence revision. Assert pending inventory/world-item reservations return `PENDING_TRANSACTION` and produce no snapshot.

- [ ] **Step 2: Write restore RED**

Restore into fresh services and assert exact canonical equality. Inject failure in each restore stage and assert the session never reaches READY, no frame is published, and shutdown closes the half-built session once.

- [ ] **Step 3: Run RED**

```powershell
.\gradlew.bat :game:test --tests 'com.gaia.save.session.*' --tests com.gaia.session.GameSessionPersistenceTest --console=plain --no-daemon
```

- [ ] **Step 4: Implement session authority without product-shell integration**

Capture while paused/READY only. Restore order is Chunks, inventory, WorldItems, player/game mode/camera, projection reconciliation, then Chunk mesh readiness. Store the restored fixed tick in the session runtime. Do not call world generation for a full-snapshot load.

```java
chunks.restoreCanonical(snapshot.chunks()).requireRestored();
inventory.restoreCanonical(snapshot.inventory()).requireRestored();
worldItems.restoreCanonical(snapshot.worldItems().logical()).requireRestored();
restorePlayer(snapshot.player());
physicalWorldItems.reconcileRestoredCanonicalState(snapshot.fixedTick());
```

- [ ] **Step 5: Rebuild projections and transient-empty state**

Add the smallest explicit `PhysicalWorldItemSystem` reconciliation seam needed during load. Assert exact body count/identity by stable ID, no duplicate projection, no particles/transient feedback, and no serialized body identity.

```java
public void reconcileRestoredCanonicalState(long tick) {
    assertOpenAndOwnerThread();
    reconcile(tick);
}
```

- [ ] **Step 6: Run GREEN and session/physics regressions**

```powershell
.\gradlew.bat :game:test --tests 'com.gaia.save.session.*' --tests 'com.gaia.session.*' --tests 'com.gaia.worlditem.*' --tests 'com.gaia.physics.*' --console=plain --no-daemon
```

- [ ] **Step 7: Review checkpoint**

Confirm loaded sessions use public restore boundaries only, generation is bypassed for snapshot load, and presentation is rebuilt after canonical state. Run `git diff --check`. Do not commit.

### Task 5: Gate 14B deterministic end-to-end round trip

**Files:**
- Test: `game/src/test/java/com/gaia/save/SaveLoadCanonicalRoundTripTest.java`
- Test: `game/src/test/java/com/gaia/save/WorldItemPersistenceRegressionTest.java`
- Test: `game/src/test/java/com/gaia/save/ChunkPersistenceRegressionTest.java`
- Update: `docs/architecture/save-load-v1.md`

**Interfaces:**
- Consumes all Gate 14A/14B types.
- Produces executable evidence for canonical equality before filesystem/archive work.

- [ ] **Step 1: Add end-to-end RED fixtures**

The fixture must include all 81 default Chunks, at least two block mutations, nonzero player velocity/orientation, every inventory slot, a two-handed variant in a separate case, a partial-pickup remainder, sleeping/grounded/frozen items, and a deleted high stable ID whose allocator cannot be reused.

- [ ] **Step 2: Run RED and correct only contract gaps**

```powershell
.\gradlew.bat :game:test --tests 'com.gaia.save.*RoundTripTest' --tests 'com.gaia.save.*RegressionTest' --console=plain --no-daemon
```

Expected first run may expose missing capture/restore fields. Add only fields required by the approved design; do not add presentation state.

```java
SaveGameSnapshot restored = codecs.decode(encoded.manifest(), encoded.payloads());
assertEquals(captured, restored);
```

- [ ] **Step 3: Run final Gate 14B matrix**

```powershell
.\gradlew.bat :engine:test --tests 'com.overlord.voxel.*' --tests 'com.overlord.worlditem.*' --console=plain --no-daemon
.\gradlew.bat :game:test --tests 'com.gaia.save.*' --tests 'com.gaia.session.*' --tests 'com.gaia.inventory.*' --tests 'com.gaia.worlditem.*' --console=plain --no-daemon
git diff --check
```

- [ ] **Step 4: Document implemented in-memory format and restore order**

Record exact section layouts, version table, stable-ID/tick semantics, restore ordering, and explicit exclusions in `docs/architecture/save-load-v1.md`. Do not claim disk atomicity or menu integration before later gates pass.
