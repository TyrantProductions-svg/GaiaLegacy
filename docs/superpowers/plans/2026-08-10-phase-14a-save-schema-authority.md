# Phase 14A Save Schema and Authority Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Establish Save/Load v1 identities, manifest/section contracts, platform paths, immutable canonical snapshots, and fresh-instance restore authority without writing archives or integrating menus.

**Architecture:** The game module owns save format types and path policy. Engine and game domain services expose narrow canonical snapshot/restore transactions that validate complete state and never serialize runtime objects. Gate 14A ends with typed, testable authority boundaries consumed by later gates.

**Tech Stack:** Java 17, JUnit 6.1.1, Gson 2.10.1 already present in `game`, JDK `Path`/`UUID`/`Instant`, Gradle Wrapper.

## Global Constraints

- Work only on `feat/save-load-v1`, based on `origin/main@076f9f490fa97db3ecfc0b7e44ac666c5a61df28`.
- Do not stage, commit, push, create a PR, or merge.
- Do not add a serialization dependency; Gson and JDK streams/compression are the approved tools.
- Preserve Java 17 compatibility and `engine -> game` independence.
- Do not serialize PhysicsBody, renderer/GPU/audio state, transient visuals, workers, or reservations.
- Every restore target must be fresh and unpublished; invalid aggregate state is rejected before mutation.
- Preserve `dist/GaiaLegacy-v0.2.0-alpha.1-windows-x64.zip` as untracked and untouched.

---

## File Structure

Create focused format types under `game/src/main/java/com/gaia/save/format`, path types under `com.gaia.save.path`, and aggregate session types under `com.gaia.save.snapshot`. Keep engine-owned Chunk and WorldItem snapshot types in their engine packages. Keep inventory snapshot types beside `BodyInventoryService` because item-form validation is Gaia-specific.

### Task 1: Save identities, manifest, and section registry

**Files:**
- Create: `game/src/main/java/com/gaia/save/format/SaveGameId.java`
- Create: `game/src/main/java/com/gaia/save/format/SaveFormatVersion.java`
- Create: `game/src/main/java/com/gaia/save/format/SaveSectionId.java`
- Create: `game/src/main/java/com/gaia/save/format/SaveSectionDescriptor.java`
- Create: `game/src/main/java/com/gaia/save/format/SaveGameManifest.java`
- Create: `game/src/main/java/com/gaia/save/format/SaveSectionCodec.java`
- Create: `game/src/main/java/com/gaia/save/format/SaveCodecRegistry.java`
- Create: `game/src/main/java/com/gaia/save/format/SaveNameValidator.java`
- Test: `game/src/test/java/com/gaia/save/format/SaveFormatContractTest.java`
- Test: `game/src/test/java/com/gaia/save/format/SaveCodecRegistryTest.java`
- Test: `game/src/test/java/com/gaia/save/format/SaveNameValidatorTest.java`

**Interfaces:**
- Produces: `SaveGameId.parse(String)`, `SaveFormatVersion.CURRENT`, `SaveSectionId` constants `CHUNKS`, `PLAYER`, `INVENTORY`, `WORLD_ITEMS`, and reserved optional IDs.
- Produces: `SaveSectionCodec<T>.encode(T)` and `.decode(byte[])`; `SaveCodecRegistry.resolve(SaveSectionDescriptor)` returns the exact codec, an empty optional for unknown optional sections, or throws for unsupported required sections.
- Produces: manifest section descriptors with exact size and lowercase 64-character SHA-256.

- [ ] **Step 1: Write constructor and registry RED tests**

```java
@Test
void manifestRejectsDuplicateRequiredSectionIds() {
    SaveSectionDescriptor chunks = descriptor(SaveSectionId.CHUNKS, 1, true);
    assertThrows(IllegalArgumentException.class, () -> manifest(List.of(chunks, chunks)));
}

@Test
void registryRejectsUnknownRequiredButSkipsUnknownOptional() {
    SaveCodecRegistry registry = SaveCodecRegistry.of(List.of(new StubCodec(SaveSectionId.CHUNKS, 1)));
    assertThrows(UnsupportedSaveSectionException.class,
            () -> registry.resolve(new SaveSectionDescriptor(new SaveSectionId("future"), 1, true, 0, SHA)));
    assertTrue(registry.resolve(new SaveSectionDescriptor(new SaveSectionId("future"), 1, false, 0, SHA)).isEmpty());
}
```

- [ ] **Step 2: Run strict RED**

Run:

```powershell
.\gradlew.bat :game:test --tests 'com.gaia.save.format.*' --console=plain --no-daemon
```

Expected: compilation fails because the format types do not exist.

- [ ] **Step 3: Implement immutable format values and validation**

Use records with compact constructors. `SaveGameId` accepts canonical lowercase UUID text only; `SaveNameValidator.validate(String, Collection<String>)` returns a typed result containing the normalized display name or diagnostic. `SaveGameManifest` copies and validates its descriptor list, requires each v1 section exactly once, validates UTC instants and `modified >= created`, and rejects duplicate IDs.

```java
public interface SaveSectionCodec<T> {
    SaveSectionId sectionId();
    int codecVersion();
    boolean required();
    byte[] encode(T value);
    T decode(byte[] bytes);
}
```

- [ ] **Step 4: Run GREEN and mutation checks**

Run the focused command, then temporarily alter one test fixture to use an uppercase/noncanonical UUID and confirm it fails. Restore the test and rerun GREEN.

- [ ] **Step 5: Review checkpoint**

Run `git diff --check` and verify only Task 1 source/tests plus the approved design/plan documents changed. Do not commit.

### Task 2: Cross-platform SaveRootProvider

**Files:**
- Create: `game/src/main/java/com/gaia/save/path/SaveRootProvider.java`
- Create: `game/src/main/java/com/gaia/save/path/DefaultSaveRootProvider.java`
- Test: `game/src/test/java/com/gaia/save/path/DefaultSaveRootProviderTest.java`

**Interfaces:**
- Produces: `Path SaveRootProvider.saveRoot()`.
- Consumes: injected OS name, user home, and environment map using the same testable pattern as `DefaultSettingsPathProvider`.

- [ ] **Step 1: Write platform matrix RED tests**

```java
@ParameterizedTest
@MethodSource("platforms")
void resolvesPlatformDataRoot(String os, String home, Map<String,String> env, Path expected) {
    assertEquals(expected, new DefaultSaveRootProvider(os, home, env).saveRoot());
}
```

Include Windows APPDATA/fallback, macOS/Darwin, Linux XDG_DATA_HOME/fallback, blank env values, and no personal absolute path in production source.

- [ ] **Step 2: Run RED, implement minimal policy, run GREEN**

Run:

```powershell
.\gradlew.bat :game:test --tests com.gaia.save.path.DefaultSaveRootProviderTest --console=plain --no-daemon
```

Expected RED: missing types. Implement the approved paths exactly, then rerun to PASS.

```java
@FunctionalInterface
public interface SaveRootProvider {
    Path saveRoot();
}

// Linux fallback branch:
return Path.of(environmentValueOrDefault(
        "XDG_DATA_HOME", Path.of(userHome, ".local", "share").toString()),
        "GaiaLegacy", "saves");
```

- [ ] **Step 3: Review checkpoint**

Run `git diff --check`; inspect source for `D:\`, `C:\Users`, and repository-relative save roots. Do not commit.

### Task 3: Chunk aggregate snapshot and fresh restore

**Files:**
- Modify: `engine/src/main/java/com/overlord/voxel/ChunkSnapshot.java`
- Create: `engine/src/main/java/com/overlord/voxel/ChunkRepositorySnapshot.java`
- Create: `engine/src/main/java/com/overlord/voxel/ChunkRepositoryRestoreResult.java`
- Modify: `engine/src/main/java/com/overlord/voxel/ChunkRepository.java`
- Test: `engine/src/test/java/com/overlord/voxel/ChunkRepositoryPersistenceTest.java`

**Interfaces:**
- Produces: `ChunkRepositorySnapshot ChunkRepository.canonicalSnapshot()`.
- Produces: `ChunkRepositoryRestoreResult ChunkRepository.restoreCanonical(ChunkRepositorySnapshot)`.
- Produces: `byte[] ChunkSnapshot.copyBlocks()`.

- [ ] **Step 1: Write RED tests for deterministic capture and all-or-nothing restore**

```java
@Test
void restorePublishesAllChunksAndAdvancesRevisionAboveSavedHighWater() {
    ChunkRepository target = new ChunkRepository();
    ChunkRepositorySnapshot saved = snapshot(91L, chunk(1, -2, 77L), chunk(-1, 3, 80L));
    assertEquals(RESTORED, target.restoreCanonical(saved).status());
    assertEquals(List.of(new ChunkKey(-1, 3), new ChunkKey(1, -2)), sorted(target.keys()));
    assertTrue(target.setBlock(0, 1, 0, (byte) 2));
    assertTrue(target.revision(new ChunkKey(0, 0)) > 91L);
}
```

Also cover shuffled keys, duplicate keys, invalid high-water, nonempty target, active generation, byte-array defensive copies, exact revisions, and every restored Chunk being a meshing candidate.

- [ ] **Step 2: Run RED**

```powershell
.\gradlew.bat :engine:test --tests com.overlord.voxel.ChunkRepositoryPersistenceTest --console=plain --no-daemon
```

Expected: missing snapshot/restore APIs.

- [ ] **Step 3: Implement aggregate capture and validated publication**

Capture keys sorted by `(x,z)`, reject active generation attempts, copy every block array, and include the repository revision high-water. Restore validates the complete snapshot before inserting any entry, restores exact revisions, marks entries dirty for meshing, and sets the revision sequence to at least the saved high-water. It must not create mesh/GPU state.

```java
public ChunkRepositoryRestoreResult restoreCanonical(ChunkRepositorySnapshot snapshot) {
    assertRestoreTargetEmptyAndIdle();
    List<ChunkSnapshot> validated = validateCompleteSnapshot(snapshot);
    publishRestoredEntries(validated);
    revisionSequence.set(snapshot.revisionHighWater());
    return ChunkRepositoryRestoreResult.restored(validated.size());
}
```

- [ ] **Step 4: Run focused and existing Chunk regressions**

```powershell
.\gradlew.bat :engine:test --tests 'com.overlord.voxel.ChunkRepository*Test' --tests com.overlord.voxel.WorldTest --console=plain --no-daemon
```

Expected: PASS.

- [ ] **Step 5: Review checkpoint**

Confirm a failed restore leaves `keys()` empty and revision sequence unchanged. Run `git diff --check`. Do not commit.

### Task 4: Inventory and WorldItem canonical restore boundaries

**Files:**
- Create: `game/src/main/java/com/gaia/inventory/BodyInventoryCanonicalSnapshot.java`
- Create: `game/src/main/java/com/gaia/inventory/BodyInventoryRestoreResult.java`
- Modify: `game/src/main/java/com/gaia/inventory/BodyInventoryService.java`
- Create: `engine/src/main/java/com/overlord/worlditem/api/LogicalWorldItemSnapshot.java`
- Create: `engine/src/main/java/com/overlord/worlditem/api/WorldItemRestoreEntry.java`
- Create: `engine/src/main/java/com/overlord/worlditem/api/WorldItemRestoreResult.java`
- Modify: `engine/src/main/java/com/overlord/worlditem/LogicalWorldItemService.java`
- Test: `game/src/test/java/com/gaia/inventory/BodyInventoryPersistenceTest.java`
- Test: `engine/src/test/java/com/overlord/worlditem/LogicalWorldItemPersistenceTest.java`

**Interfaces:**
- Produces: `BodyInventoryCanonicalSnapshot canonicalSnapshot(EntityRef)` and `BodyInventoryRestoreResult restoreCanonical(EntityRef, BodyInventoryCanonicalSnapshot)` on a fresh service.
- Produces: `LogicalWorldItemSnapshot canonicalSnapshot()` and `restoreCanonical(LogicalWorldItemSnapshot)`.
- `LogicalWorldItemSnapshot` contains sorted entries plus `nextItemId` and `itemIdsExhausted`; reservation allocators are intentionally absent.

- [ ] **Step 1: Write inventory RED**

Cover exact direct slots, active slot, two-handed occupancy, revision, unknown item, overstack, duplicate hand representation, nonempty target, and pending reservation rejection.

```java
assertEquals(snapshot, restored.canonicalSnapshot(owner));
assertEquals(INVALID_SNAPSHOT, fresh.restoreCanonical(invalidTwoHanded).status());
assertTrue(fresh.canonicalSnapshot(owner).stacks().isEmpty());
```

- [ ] **Step 2: Write WorldItem RED**

Cover stable ordering, exact runtime/source/ticks/state, duplicate IDs, capacity, next-ID high-water, `Long.MAX_VALUE` exhaustion, pending spawn/extraction rejection, and no stable-ID reuse after restoring a snapshot whose highest historical ID is no longer live.

- [ ] **Step 3: Run RED commands**

```powershell
.\gradlew.bat :engine:test --tests com.overlord.worlditem.LogicalWorldItemPersistenceTest --console=plain --no-daemon
.\gradlew.bat :game:test --tests com.gaia.inventory.BodyInventoryPersistenceTest --console=plain --no-daemon
```

- [ ] **Step 4: Implement minimal validated restore transactions**

Both services validate full input before mutation, require the owner thread, require fresh state, and leave no partial state on rejection. Inventory uses the existing item-form lookup. WorldItem restores only live logical items and physical enum state; it creates no PhysicsBody and no reservation history.

```java
public WorldItemRestoreResult restoreCanonical(LogicalWorldItemSnapshot snapshot) {
    assertMainThread("world item canonical restore");
    ValidatedWorldItems validated = validateRestore(snapshot);
    if (!items.isEmpty() || hasPendingReservations()) {
        return WorldItemRestoreResult.targetNotFresh();
    }
    publish(validated);
    return WorldItemRestoreResult.restored(items.size());
}
```

- [ ] **Step 5: Run GREEN and related transaction suites**

```powershell
.\gradlew.bat :engine:test --tests 'com.overlord.worlditem.*' --console=plain --no-daemon
.\gradlew.bat :game:test --tests 'com.gaia.inventory.*' --tests 'com.gaia.worlditem.*' --console=plain --no-daemon
```

- [ ] **Step 6: Review checkpoint**

Verify exact state equality, no events during restore, no body creation, and no pending reservation serialization. Run `git diff --check`. Do not commit.

### Task 5: Immutable aggregate SaveGameSnapshot authority

**Files:**
- Create: `game/src/main/java/com/gaia/save/snapshot/PlayerSaveSnapshot.java`
- Create: `game/src/main/java/com/gaia/save/snapshot/InventorySaveSnapshot.java`
- Create: `game/src/main/java/com/gaia/save/snapshot/WorldItemsSaveSnapshot.java`
- Create: `game/src/main/java/com/gaia/save/snapshot/SaveGameSnapshot.java`
- Create: `game/src/main/java/com/gaia/save/snapshot/SessionSaveCaptureResult.java`
- Test: `game/src/test/java/com/gaia/save/snapshot/SaveGameSnapshotTest.java`
- Test: `game/src/test/java/com/gaia/save/snapshot/SessionSaveCaptureContractTest.java`

**Interfaces:**
- Produces immutable aggregate values consumed by Gate 14B codecs.
- `SessionSaveCaptureResult` has `CAPTURED`, `PENDING_TRANSACTION`, or `INCONSISTENT_REVISION` and an optional immutable snapshot plus captured revision value.

- [ ] **Step 1: Write RED for defensive copies and impossible shapes**

```java
@Test
void aggregateDoesNotRetainMutableVectorsArraysOrLists() {
    SaveGameSnapshot snapshot = fixture();
    mutateAllOriginalInputs();
    assertEquals(expectedFixture(), snapshot);
}
```

Reject nonfinite player values, invalid pitch, negative fixed tick/revisions, aggregate metadata/save-ID mismatch, inconsistent WorldItem tick, and section snapshots belonging to another owner. The aggregate carries created/static metadata; Gate 14B supplies modified time when it constructs the manifest.

- [ ] **Step 2: Run RED, implement values, run GREEN**

```powershell
.\gradlew.bat :game:test --tests 'com.gaia.save.snapshot.*' --console=plain --no-daemon
```

```java
public record SessionSaveCaptureResult(
        Status status,
        Optional<SaveGameSnapshot> snapshot,
        OptionalLong capturedRevision) {
    public enum Status { CAPTURED, PENDING_TRANSACTION, INCONSISTENT_REVISION }
}
```

- [ ] **Step 3: Gate 14A regression and inventory audit**

```powershell
.\gradlew.bat :engine:test --tests 'com.overlord.voxel.*' --tests 'com.overlord.worlditem.*' --console=plain --no-daemon
.\gradlew.bat :game:test --tests 'com.gaia.save.*' --tests 'com.gaia.inventory.*' --console=plain --no-daemon
git diff --check
git status --short --untracked-files=all
```

Expected: all Gate 14A tests pass; no production integration, archive writer, menu behavior, or Git mutation exists yet.
