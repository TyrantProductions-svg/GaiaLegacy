# Phase 14E Cross-Platform Acceptance Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Produce fresh automated, packaged, performance, Windows, and Apple Silicon macOS evidence for the exact Phase 14 candidate and close its factual documentation without publishing Git changes.

**Architecture:** Gate 14E adds no gameplay feature. It runs forced verification, measures the representative finite-world save path, performs destructive corruption only on copied test saves, and records each platform as PASS or PENDING from real evidence.

**Tech Stack:** Gradle Wrapper, JUnit reports, development runtime, installDist, Git read-only audits, Windows PowerShell, macOS shell.

## Global Constraints

- Do not change behavior to make acceptance easier; defects use systematic debugging and RED/GREEN fixes in the owning earlier gate.
- Do not corrupt the only copy of a human save; duplicate a test slot first.
- Automated tests do not substitute for actual GLFW/OpenGL/OpenAL/input/filesystem runtime.
- A platform not actually run is `NOT RUN / PENDING`.
- Do not stage, commit, push, create a PR, tag, release, or merge.

---

## File Structure

Only measurement tests and final documentation are expected in Gate 14E. Any production change indicates a discovered defect and must return to the relevant Gate 14A-D RED/GREEN cycle.

### Task 1: Representative size and latency measurement

**Files:**
- Test: `game/src/test/java/com/gaia/save/SavePerformanceMeasurementTest.java`
- Update: `docs/testing/phase-14-save-load-acceptance.md`

**Interfaces:**
- Builds the approved default radius-4, 81-Chunk representative snapshot with mutations, full inventory, and bounded WorldItems.
- Prints archive bytes, capture/encode/write/validate/decode/restore milliseconds separately; only structural size limits are assertions.

- [ ] **Step 1: Write measurement fixture and correctness assertions**

```java
@Test
void measuresRepresentativeFiniteWorldWithoutUsingTimeAsCiAssertion() {
    Measurement result = probe.measure(representative81ChunkWorld());
    assertEquals(81, result.chunkCount());
    assertTrue(result.archiveBytes() <= SaveArchiveLimits.maxArchiveBytes());
    assertEquals(result.capturedSnapshot(), result.restoredSnapshot());
    System.out.println(result.toEvidenceLine());
}
```

Do not assert `elapsedMillis <= 1000`; record the target comparison in documentation.

- [ ] **Step 2: Run fresh measurement three times**

```powershell
.\gradlew.bat :game:test --tests com.gaia.save.SavePerformanceMeasurementTest --rerun-tasks --console=plain --no-daemon
```

Run three times on the Windows reference machine. Record each result and median. Do not average away an outlier without explaining it.

- [ ] **Step 3: Triage against approved targets**

Compare default archive to 16 MiB, save capture/validate/commit to 1.0 s, and decode/canonical restore excluding meshing to 1.0 s. If materially over target, stop for attribution; do not introduce background I/O, pooling, or streaming without a design amendment.

- [ ] **Step 4: Review checkpoint**

Run `git diff --check`. Confirm measurement code does not write into the real user save root. Do not commit.

### Task 2: Fresh focused and full automated verification

**Files:**
- Update only if evidence changes: `docs/testing/phase-14-save-load-acceptance.md`

**Interfaces:**
- Produces exact current test totals and packaged-resource evidence.

- [ ] **Step 1: Run forced focused Gate 14 suites**

```powershell
.\gradlew.bat :engine:test --tests 'com.overlord.voxel.*Persistence*' --tests 'com.overlord.worlditem.*Persistence*' --tests 'com.overlord.core.input.*' --rerun-tasks --console=plain --no-daemon
.\gradlew.bat :game:test --tests 'com.gaia.save.*' --tests 'com.gaia.shell.*Save*' --tests 'com.gaia.shell.world.*' --tests 'com.gaia.session.*Persistence*' --rerun-tasks --console=plain --no-daemon
```

- [ ] **Step 2: Run full module suites**

```powershell
.\gradlew.bat :engine:test --console=plain --no-daemon
.\gradlew.bat :game:test --console=plain --no-daemon
.\gradlew.bat :tools:test --console=plain --no-daemon
```

- [ ] **Step 3: Run clean build and forced resource checks**

```powershell
.\gradlew.bat clean test build --console=plain --no-daemon
.\gradlew.bat :engine:verifyPackagedShaderResources :game:verifyPackagedResources :game:verifyInstalledShaderResources :game:verifyInstalledAudioRuntime :tools:verifyGeneratedUiAssets --rerun-tasks --console=plain --no-daemon
```

- [ ] **Step 4: Extract exact XML totals**

Count engine/game/tools tests, passed, skipped, failures, and errors from fresh XML. Do not reuse Phase 13 totals. Record commands, duration, actionable-task counts, and resource audit output.

- [ ] **Step 5: Automated gate**

If any suite/resource check fails, stop success claims and return to the owning gate with systematic debugging and a focused RED. Otherwise run `git diff --check`.

### Task 3: Windows development and installDist save cycle

**Files:**
- Update: `docs/testing/phase-14-save-load-acceptance.md`

**Interfaces:**
- Human/runtime evidence for actual Windows user path, input, archive persistence, native presentation/audio, and corruption UI.

- [ ] **Step 1: Development runtime clean save cycle**

```powershell
.\gradlew.bat :game:run --console=plain --no-daemon
```

Create a named Unicode world with a chosen seed; record the resolved save root and ID. Break/place blocks, move player, alter all inventory slots, Q/Ctrl+Q drops, manually pick up one item, leave another physical WorldItem, Save, Save & Quit, exit, relaunch, Load, and verify exact world/player/inventory/items.

- [ ] **Step 2: Repeat save and second-session lifecycle**

Save again, Return to Main Menu, load another/new session, return, reload original, and confirm no duplicate WorldItem body, music voice, mesh, or reservation. Verify resize, DPI, Alt+Tab, focus recovery, mouse/keyboard slot navigation, and clean Escape shutdown.

- [ ] **Step 3: Corruption/recovery test on a copy**

Close the game. Copy the tested world directory to a new test ID/name using a test helper or documented safe procedure. Corrupt/truncate only that copied current archive. Relaunch and verify `RECOVERABLE_BACKUP` or `CORRUPT` as appropriate, no crash, Load disabled when required, explicit recovery confirmation, and successful backup restore. Delete the copied test slot through UI.

- [ ] **Step 4: installDist path**

Run the generated Windows launcher from `game/build/install/game/bin`. Repeat create/save/quit/relaunch/load, native audio, gameplay, and clean shutdown. Record actual durations and errors; do not infer PASS from development runtime.

- [ ] **Step 5: Record factual Windows result**

Mark each checklist row PASS/FAIL and include actual save size/timings. Stop on item loss/duplication, wrong block/player state, crash, corrupt silent load, shader/OpenAL error, or non-clean shutdown.

### Task 4: Apple Silicon macOS acceptance

**Files:**
- Update: `docs/testing/phase-14-save-load-acceptance.md`

**Interfaces:**
- Human/native evidence on the exact same candidate SHA used for Windows.

- [ ] **Step 1: Confirm native environment and clean candidate**

Record Mac model, `uname -m`, macOS version, Java version, exact SHA, fresh-clone/clean status, and divergence. Do not invent missing metadata.

- [ ] **Step 2: Run macOS automation**

```bash
./gradlew clean test build --console=plain --no-daemon
./gradlew :engine:verifyPackagedShaderResources :game:verifyPackagedResources :game:verifyInstalledShaderResources :game:verifyInstalledAudioRuntime :tools:verifyGeneratedUiAssets --rerun-tasks --console=plain --no-daemon
```

- [ ] **Step 3: Run native development save cycle**

Repeat the complete Windows create/mutate/save/quit/relaunch/load/resave/corrupt-copy/recover/delete flow using the native macOS save root. Verify Unicode name, case/path separators, Retina, resize, Command+Tab, pointer alignment, Gaia audio continuity, and clean shutdown.

- [ ] **Step 4: Run macOS installDist cycle**

Launch the generated distribution with the native arm64 JVM, repeat save/load and native audio, and record actual duration. A human-reported complete checklist may be recorded as such, but missing raw totals/version/durations remain explicitly unclaimed.

- [ ] **Step 5: Record PASS or PENDING**

If not actually run, mark all macOS runtime rows `NOT RUN / PENDING`; do not downgrade a supplied human-confirmed PASS or manufacture details.

### Task 5: Final documentation, inventory, and handoff

**Files:**
- Update: `README.md`
- Update: `CONTROLS.md`
- Update: `KNOWN_ISSUES.md`
- Update if factual status changed: `CHANGELOG.md`
- Update: `docs/architecture/current-baseline.md`
- Update: `docs/architecture/save-load-v1.md`
- Update: `docs/testing/phase-14-save-load-acceptance.md`
- Create: `docs/agent-handoffs/phase-14-handoff.md`

**Interfaces:**
- Produces the factual Phase 14 closure and explicit Phase 15 and Phase 16 boundaries.

- [ ] **Step 1: Update user documentation**

Document actual world-slot controls, save roots, Save/Save & Quit, recovery/delete, no autosave, archive version, and known limitations. Remove stale disabled-Phase-14 Load wording only after implemented behavior is verified.

- [ ] **Step 2: Update architecture and handoff**

Record completed/unfinished work, full file inventory, ownership, archive/version table, atomic/fallback order, stable-ID/tick semantics, tests, platform evidence, performance measurements, risks, and interfaces Phase 15 and Phase 16 must not break.

- [ ] **Step 3: Final repository audit**

```powershell
git status --short --untracked-files=all
git diff --name-status
git diff --stat
git diff --check
git ls-files --others --exclude-standard
```

Reject build output, local saves/settings, logs, screenshots, crash dumps, IDE files, `.class`, test corruption fixtures outside test resources, and unrelated experiments. Preserve the known untracked Milestone 1 ZIP and exclude it from any later authorized staging.

- [ ] **Step 4: Final report without Git mutation**

Report baseline/branch, exact changed files, fresh test totals, packaged checks, Windows/macOS dev/installDist status, save sizes/timings, known issues, deferred Phase 15/16 work, suggested commit `feat(save): add versioned local world save and load`, and suggested PR title `feat(save): implement GaiaLegacy local world slots and persistence`.

Stop. Do not stage, commit, push, create a PR, tag, release, merge, or start Phase 15 without separate authorization.
