# Phase 14C Atomic Save and Recovery Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Persist one complete `.glsave` archive without losing the last known-good world, detect corruption safely, and expose catalog/recovery/delete results as immutable diagnostics.

**Architecture:** Pure Gate 14B section bytes are wrapped in one bounded ZIP archive. `AtomicSaveStore` owns temp, force, backup, replace, and cleanup ordering through an injectable filesystem seam. Catalog discovery validates current and backup archives and never treats temp files as saves.

Each direct world directory owns exactly `current.glsave`, optional `backup.glsave`, and task-owned sibling temp files. Readers never infer a save from a temp file, and recovery never promotes `backup.glsave` without an explicit user command.

**Tech Stack:** Java 17 NIO, JDK ZIP/Deflate, Gson manifest codec, SHA-256, JUnit 6.1.1, `@TempDir`.

## Global Constraints

- All Phase 14A/14B constraints remain in force.
- Never load a half-written temp or silently replace corrupt current with backup.
- Reject duplicate/traversal ZIP entries and bounded-expansion violations before domain publication.
- Do not follow symbolic links or junction-like entries outside the configured save root.
- Do not stage, commit, push, create a PR, or merge.

---

## File Structure

Archive classes live under `com.gaia.save.archive`; atomic filesystem ownership lives under `com.gaia.save.store`; catalog adapters remain under the existing `com.gaia.shell.save` read-only seam.

### Task 1: Bounded archive writer and reader

**Files:**
- Create: `game/src/main/java/com/gaia/save/archive/SaveArchiveLimits.java`
- Create: `game/src/main/java/com/gaia/save/archive/SaveManifestCodec.java`
- Create: `game/src/main/java/com/gaia/save/archive/SaveArchiveWriter.java`
- Create: `game/src/main/java/com/gaia/save/archive/SaveArchiveReader.java`
- Create: `game/src/main/java/com/gaia/save/archive/SaveArchiveReadResult.java`
- Create: `game/src/main/java/com/gaia/save/archive/SaveDiagnostic.java`
- Test: `game/src/test/java/com/gaia/save/archive/SaveArchiveRoundTripTest.java`
- Test: `game/src/test/java/com/gaia/save/archive/SaveArchiveCorruptionTest.java`

**Interfaces:**
- `void SaveArchiveWriter.write(Path, EncodedSaveGame)` writes normalized entries in required order.
- `SaveArchiveReadResult SaveArchiveReader.read(Path)` returns `VALID`, `CORRUPT`, or `UNSUPPORTED_VERSION` plus bounded diagnostics and optional decoded snapshot.
- `SaveArchiveLimits` computes supported radius-8 structural limits with checked arithmetic.

- [ ] **Step 1: Write real ZIP round-trip RED**

```java
@Test
void writesManifestFirstAndRequiredSectionsInCanonicalOrder() throws Exception {
    writer.write(file, encodedFixture());
    assertEquals(List.of("manifest.json", "chunks.bin", "player.json",
            "inventory.json", "world-items.json"), zipEntryNames(file));
    assertEquals(snapshot(), reader.read(file).snapshot().orElseThrow());
}
```

- [ ] **Step 2: Write corruption/limit RED**

Create real ZIP fixtures for duplicate names, `../` traversal, missing required entry, unknown required/optional entry, future format, wrong size/hash, truncated archive, trailing section bytes, oversized declared length, oversized expansion, and excessive entry count. Assert stable diagnostic codes and no snapshot.

- [ ] **Step 3: Run RED**

```powershell
.\gradlew.bat :game:test --tests 'com.gaia.save.archive.*' --console=plain --no-daemon
```

- [ ] **Step 4: Implement bounded writer/reader**

Write pre-encoded bounded section bytes, normalize ZIP metadata, and reject duplicates during iteration rather than storing them in a map that overwrites. Count actual decompressed bytes while reading; never allocate solely from an untrusted encoded length.

```java
Set<String> observed = new HashSet<>();
for (ZipEntry entry; (entry = zip.getNextEntry()) != null; ) {
    String name = normalizeEntryName(entry.getName());
    if (!observed.add(name)) {
        return SaveArchiveReadResult.corrupt(DUPLICATE_ENTRY, name);
    }
    sections.put(name, readBounded(zip, limits.maxBytesFor(name)));
}
```

- [ ] **Step 5: Run GREEN and mutation proof**

Rerun focused tests. Mutate one payload byte without updating the manifest and confirm `CHECKSUM_MISMATCH`; restore fixture and rerun PASS.

- [ ] **Step 6: Review checkpoint**

Run `git diff --check`; verify reader errors do not expose unbounded path/content text. Do not commit.

### Task 2: AtomicSaveStore current/backup transaction

**Files:**
- Create: `game/src/main/java/com/gaia/save/store/SaveFileOperations.java`
- Create: `game/src/main/java/com/gaia/save/store/JdkSaveFileOperations.java`
- Create: `game/src/main/java/com/gaia/save/store/SaveWriteResult.java`
- Create: `game/src/main/java/com/gaia/save/store/AtomicSaveStore.java`
- Test: `game/src/test/java/com/gaia/save/store/AtomicSaveStoreTest.java`
- Test: `game/src/test/java/com/gaia/save/store/AtomicSaveStoreFaultInjectionTest.java`

**Interfaces:**
- `SaveWriteResult AtomicSaveStore.save(SaveGameSnapshot, Instant)`.
- Filesystem seam exposes create-temp, force-file, move-atomic-replace, move-replace fallback, copy, delete-if-exists, and best-effort directory force as separately faultable operations.
- Success returns the exact committed manifest; failure retains a primary Throwable and bounded diagnostic.

- [ ] **Step 1: Write last-known-good RED matrix**

For every injected failure point, begin with known `OLD_CURRENT` and `OLD_BACKUP` bytes and assert the exact surviving validated archive. Include section encode, temp write, force, reread validation, backup move, current move, fallback copy/replace, directory force, and temp cleanup.

```java
assertNotEquals(SUCCESS, result.status());
assertTrue(anyValidArchiveEquals(worldDir, OLD_CURRENT));
assertFalse(sessionCheckpointAdvanced.get());
```

- [ ] **Step 2: Write atomic-unsupported and first-save RED**

Throw `AtomicMoveNotSupportedException` from both current and backup atomic moves. Assert the deterministic fallback creates/validates backup before exposing replacement current. Initial-save failure must leave no catalog-valid slot.

- [ ] **Step 3: Run RED**

```powershell
.\gradlew.bat :game:test --tests 'com.gaia.save.store.AtomicSaveStore*Test' --console=plain --no-daemon
```

- [ ] **Step 4: Implement exact ownership transfer and suppression policy**

Track temp/current/backup ownership explicitly. After a successful move, never delete the transferred path. Cleanup failures are suppressed under the primary failure unless they make last-known-good ownership uncertain; uncertain ownership is returned as a blocking/fatal result, not a retryable UI error. Avoid self-suppression when injected operations throw the same Throwable instance.

```java
Path temp = files.createSiblingTemp(worldDir, "current.glsave");
boolean tempOwned = true;
try {
    archiveWriter.write(temp, encoded);
    files.forceFile(temp);
    requireValid(temp);
    rotateValidatedCurrentToBackup(worldDir);
    files.moveReplacing(temp, currentArchive(worldDir));
    tempOwned = false;
    requireValid(currentArchive(worldDir));
    files.forceDirectoryBestEffort(worldDir);
    return SaveWriteResult.success(encoded.manifest());
} finally {
    if (tempOwned) {
        files.deleteIfExists(temp);
    }
}
```

- [ ] **Step 5: Run GREEN and existing settings atomic regressions**

```powershell
.\gradlew.bat :game:test --tests 'com.gaia.save.store.*' --tests com.gaia.settings.AtomicFileWriterTest --tests com.gaia.settings.SettingsPersistenceFailureIntegrationTest --console=plain --no-daemon
```

- [ ] **Step 6: Review checkpoint**

Inspect every failure branch for one verified current or backup. Run `git diff --check`. Do not commit.

### Task 3: Validated catalog, explicit backup recovery, and safe delete

**Files:**
- Modify: `game/src/main/java/com/gaia/shell/save/SaveCatalog.java`
- Modify: `game/src/main/java/com/gaia/shell/save/SaveSummary.java`
- Replace production use of: `game/src/main/java/com/gaia/shell/save/EmptySaveCatalog.java`
- Create: `game/src/main/java/com/gaia/save/store/FileSaveCatalog.java`
- Create: `game/src/main/java/com/gaia/save/store/SaveRecoveryResult.java`
- Create: `game/src/main/java/com/gaia/save/store/SaveDeleteResult.java`
- Create: `game/src/main/java/com/gaia/save/store/SaveRepository.java`
- Test: `game/src/test/java/com/gaia/save/store/FileSaveCatalogTest.java`
- Test: `game/src/test/java/com/gaia/save/store/SaveRecoveryTest.java`
- Test: `game/src/test/java/com/gaia/save/store/SaveDeleteTest.java`

**Interfaces:**
- `SaveCatalog.summaries()` returns an immutable snapshot sorted by modified descending, ID ascending.
- `SaveSummary` contains ID, name, created/modified, seed, format version, and health `VALID`, `RECOVERABLE_BACKUP`, `CORRUPT`, `UNSUPPORTED_VERSION`.
- `SaveRepository.recoverBackup(SaveGameId)` and `.delete(SaveGameId)` return closed typed results.

- [ ] **Step 1: Write catalog RED**

Build temp roots containing valid current, corrupt current/valid backup, both corrupt, future version, temp-only, unknown directory, and symlink/junction fixture where supported. Assert status, enablement data, deterministic order, bounded diagnostics, and temp exclusion.

- [ ] **Step 2: Write recovery RED**

Assert catalog scan never silently promotes backup. Explicit recovery validates backup, preserves corrupt current for diagnostics until commit, installs backup as current, and yields `VALID` only after reread.

- [ ] **Step 3: Write delete RED**

Assert non-descendant, root itself, nested unexpected path, symlink, unknown ID, repeated delete, move-to-trash failure, and cleanup failure. Successful delete moves only the direct ID directory under root-local `.trash` before the row disappears.

- [ ] **Step 4: Run RED, implement, run GREEN**

```powershell
.\gradlew.bat :game:test --tests 'com.gaia.save.store.*CatalogTest' --tests 'com.gaia.save.store.*RecoveryTest' --tests 'com.gaia.save.store.*DeleteTest' --console=plain --no-daemon
```

```java
return scanDirectSaveDirectories(saveRoot).stream()
        .map(this::validateCurrentAndBackup)
        .sorted(Comparator.comparing(SaveSummary::modifiedTime).reversed()
                .thenComparing(summary -> summary.id().value()))
        .toList();
```

- [ ] **Step 5: Replace EmptySaveCatalog only at composition seam**

Construct `FileSaveCatalog`/`SaveRepository` from `DefaultSaveRootProvider` in `GameBootstrap`, but do not enable Load UI or session persistence yet. Update structure tests to assert one catalog owner and no filesystem access in presenters/controllers.

```java
Path saveRoot = new DefaultSaveRootProvider(environment).saveRoot();
SaveRepository saves = SaveRepository.open(saveRoot, archiveReader, atomicStore);
SaveCatalog catalog = new FileSaveCatalog(saves);
ProductScreenPresenter presenter = new ProductScreenPresenter(catalog);
```

- [ ] **Step 6: Review checkpoint**

Run catalog/store tests, `GameBootstrapStructureTest`, and `git diff --check`. Confirm no blind recursive delete exists.

### Task 4: Complete Gate 14C fault and recovery matrix

**Files:**
- Test: `game/src/test/java/com/gaia/save/SaveFailureRecoveryIntegrationTest.java`
- Test: `game/src/test/java/com/gaia/save/SaveArchiveSecurityTest.java`
- Update: `docs/architecture/save-load-v1.md`

**Interfaces:**
- Consumes production codecs, archive reader/writer, atomic store, catalog, and repository.
- Produces integrated evidence before UI wiring.

- [ ] **Step 1: Add integration RED for all required fault sources**

Inject manifest, world section, inventory codec, world-item codec, rename, replace, force, and cleanup failures. Include crash-like temp on next launch, missing optional section, repeated save, future version, and current/backup combinations.

- [ ] **Step 2: Run RED and fix only missing transactional behavior**

```powershell
.\gradlew.bat :game:test --tests com.gaia.save.SaveFailureRecoveryIntegrationTest --tests com.gaia.save.SaveArchiveSecurityTest --console=plain --no-daemon
```

- [ ] **Step 3: Run full Gate 14C GREEN**

```powershell
.\gradlew.bat :game:test --tests 'com.gaia.save.*' --tests 'com.gaia.shell.save.*' --console=plain --no-daemon
git diff --check
git status --short --untracked-files=all
```

- [ ] **Step 4: Document exact archive/recovery table**

Update `docs/architecture/save-load-v1.md` with entry order, limits, commit/fallback call order, catalog states, recovery/delete behavior, fsync claims, and diagnostic codes. Do not claim product-shell behavior until Gate 14D.
