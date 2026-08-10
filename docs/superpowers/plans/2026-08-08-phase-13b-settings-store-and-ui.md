# Phase 13B Settings Store and Settings UI Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add validated schema-v1 user settings, atomic cross-platform persistence, a draft/apply UI, and explicit hot/next-session application boundaries.

**Architecture:** An immutable `SettingsSnapshot` is loaded through an injected path/store boundary. `SettingsController` validates a draft, applies reversible owner-thread presentation changes, persists atomically, and only then publishes the snapshot; world-affecting defaults are captured by the next `GameSessionConfig`.

**Tech Stack:** Java 17 records/sealed types, Gson 2.10.1, NIO atomic move, LWJGL GLFW/OpenGL owner-thread boundaries, existing product UI, JUnit 6.1.1.

## Global Constraints

- VSync, FOV, sensitivity, invert-Y, and audio settings apply only through public owner boundaries.
- VSync must use interval 1 for true and 0 for false after the context is current; it cannot alter fixed 1/60 simulation.
- Render distance is Chunk radius 2–8 and applies only to the next New World.
- Default game mode applies only to the next New World; debug HUD default applies only to the next GameSession.
- Fullscreen/window mode and additional UI scale remain deferred.
- No absolute personal paths; corrupted settings must not crash startup.
- Do not write settings every frame or persist unapplied drafts.
- Do not stage, commit, push, create a PR, or merge.

## File Structure

- `game/src/main/java/com/gaia/settings/SettingsSnapshot.java`: immutable schema v1.
- `game/src/main/java/com/gaia/settings/SettingsDefaults.java`: approved default values.
- `game/src/main/java/com/gaia/settings/SettingsDocument.java`: nullable schema-v1 JSON transfer object.
- `game/src/main/java/com/gaia/settings/SettingsValidator.java`: clamp/enum/schema validation.
- `game/src/main/java/com/gaia/settings/SettingsDiagnostic.java`: bounded load/save/apply diagnostic.
- `game/src/main/java/com/gaia/settings/SettingsLoadResult.java`: snapshot plus immutable diagnostics.
- `game/src/main/java/com/gaia/settings/SettingsPathProvider.java`: injectable path interface.
- `game/src/main/java/com/gaia/settings/DefaultSettingsPathProvider.java`: Windows/macOS/Linux resolution.
- `game/src/main/java/com/gaia/settings/SettingsStore.java`: load/save boundary.
- `game/src/main/java/com/gaia/settings/JsonSettingsStore.java`: Gson schema and atomic write.
- `game/src/main/java/com/gaia/settings/AtomicFileWriter.java`: same-directory temp/replace.
- `game/src/main/java/com/gaia/settings/SettingsPersistenceException.java`: typed load/save failure.
- `game/src/main/java/com/gaia/settings/SettingsApplier.java`: reversible hot application.
- `game/src/main/java/com/gaia/settings/AudioSettingsPort.java`: Gate 13C adapter seam.
- `game/src/main/java/com/gaia/settings/SettingsController.java`: draft/apply/discard state.
- `game/src/main/java/com/gaia/settings/SettingsDraftSnapshot.java`: immutable Settings screen VM.
- `engine/src/main/java/com/overlord/core/Window.java`: runtime VSync setter.
- `engine/src/main/java/com/overlord/renderer/Renderer.java`: validated FOV setter.
- `engine/src/main/java/com/overlord/renderer/Camera.java`: look sensitivity and invert-Y setter.
- `game/src/main/java/com/gaia/shell/ui/ProductScreenPresenter.java`: Settings rows and dirty modal.
- `game/src/main/java/com/gaia/shell/ui/ProductScreenInputController.java`: Settings commands.
- `game/src/main/java/com/gaia/session/GameSessionConfig.java`: capture next-session values.

---

### Task 1: Immutable schema v1 and validation

**Files:**
- Create: `game/src/main/java/com/gaia/settings/SettingsSnapshot.java`
- Create: `game/src/main/java/com/gaia/settings/SettingsDefaults.java`
- Create: `game/src/main/java/com/gaia/settings/SettingsDocument.java`
- Create: `game/src/main/java/com/gaia/settings/SettingsValidator.java`
- Create: `game/src/main/java/com/gaia/settings/SettingsDiagnostic.java`
- Create: `game/src/main/java/com/gaia/settings/SettingsLoadResult.java`
- Test: `game/src/test/java/com/gaia/settings/SettingsSnapshotTest.java`
- Test: `game/src/test/java/com/gaia/settings/SettingsValidatorTest.java`

**Interfaces:**
- Produces: `SettingsDefaults.schemaV1()` and `SettingsValidator.validate(SettingsDocument)`.
- Produces: exact approved values for hot and next-session consumers.

- [ ] **Step 1: Write RED default/range tests**

```java
@Test
void schemaV1DefaultsMatchApprovedProductDefaults() {
    SettingsSnapshot defaults = SettingsDefaults.schemaV1();
    assertEquals(1, defaults.schemaVersion());
    assertTrue(defaults.vsync());
    assertEquals(70.0, defaults.fovDegrees());
    assertEquals(0.10, defaults.mouseSensitivity());
    assertFalse(defaults.invertY());
    assertEquals(4, defaults.chunkRadius());
    assertEquals(1.0, defaults.masterVolume());
    assertEquals(0.65, defaults.musicVolume());
    assertEquals(1.0, defaults.sfxVolume());
    assertTrue(defaults.muteWhenUnfocused());
    assertEquals(GameMode.SURVIVAL, defaults.defaultGameMode());
    assertFalse(defaults.debugHudDefault());
}
```

Add parameterized tests for FOV 50/100, sensitivity 0.02/0.50, Chunk radius 2/8, volume 0/1, and values immediately outside each range. Validate non-finite doubles explicitly.

- [ ] **Step 2: Run RED**

Run: `./gradlew.bat :game:test --tests 'com.gaia.settings.Settings*Test' --console=plain --no-daemon`

Expected: FAIL because settings types do not exist.

- [ ] **Step 3: Implement the immutable model**

```java
public record SettingsSnapshot(
        int schemaVersion,
        boolean vsync,
        double fovDegrees,
        double mouseSensitivity,
        boolean invertY,
        int chunkRadius,
        double masterVolume,
        double musicVolume,
        double sfxVolume,
        boolean muteWhenUnfocused,
        GameMode defaultGameMode,
        boolean debugHudDefault) {}
```

The raw Gson DTO uses nullable wrapper fields so the validator can distinguish missing from explicit false/zero. Each invalid or missing field falls back to the approved default and adds one stable-code diagnostic. Unsupported `schemaVersion` falls back to the complete schema-v1 default with one `UNSUPPORTED_SCHEMA` diagnostic.

- [ ] **Step 4: Implement exact validation and run GREEN**

Use inclusive clamping for finite numeric values and default replacement for non-finite/unparseable values. Unknown JSON fields are ignored. Stable diagnostic codes include `INVALID_JSON`, `UNSUPPORTED_SCHEMA`, `NON_FINITE_VALUE`, `CLAMPED_VALUE`, and `INVALID_ENUM`.

Run the Task 1 test command. Expected: PASS.

---

### Task 2: Cross-platform paths and atomic JSON persistence

**Files:**
- Create: `game/src/main/java/com/gaia/settings/SettingsPathProvider.java`
- Create: `game/src/main/java/com/gaia/settings/DefaultSettingsPathProvider.java`
- Create: `game/src/main/java/com/gaia/settings/SettingsStore.java`
- Create: `game/src/main/java/com/gaia/settings/JsonSettingsStore.java`
- Create: `game/src/main/java/com/gaia/settings/AtomicFileWriter.java`
- Create: `game/src/main/java/com/gaia/settings/SettingsPersistenceException.java`
- Test: `game/src/test/java/com/gaia/settings/DefaultSettingsPathProviderTest.java`
- Test: `game/src/test/java/com/gaia/settings/JsonSettingsStoreTest.java`
- Test: `game/src/test/java/com/gaia/settings/AtomicFileWriterTest.java`

**Interfaces:**
- Produces: `SettingsStore.load(): SettingsLoadResult` and `SettingsStore.save(SettingsSnapshot)`.
- Produces: `SettingsPathProvider.settingsFile(): Path`.

- [ ] **Step 1: Write RED path matrix tests**

Inject OS name, user home, and environment map:

```java
@Test
void resolvesApprovedPlatformLocations() {
    assertEquals(Path.of("C:/Users/Test/AppData/Roaming/GaiaLegacy/settings.json"),
            provider("Windows 11", "C:/Users/Test", Map.of("APPDATA", "C:/Users/Test/AppData/Roaming"))
                    .settingsFile());
    assertEquals(Path.of("/Users/test/Library/Application Support/GaiaLegacy/settings.json"),
            provider("Mac OS X", "/Users/test", Map.of()).settingsFile());
    assertEquals(Path.of("/xdg/GaiaLegacy/settings.json"),
            provider("Linux", "/home/test", Map.of("XDG_CONFIG_HOME", "/xdg")).settingsFile());
}
```

Also test Windows APPDATA absence and Linux XDG absence fallback without using the real machine path.

- [ ] **Step 2: Write RED persistence tests**

Use `@TempDir` for missing file defaults, round trip, unknown field, corrupt JSON, per-field fallback, and prior-file preservation on injected move failure.

```java
@Test
void failedReplaceLeavesPreviousValidFileAndDoesNotReportSaveSuccess(@TempDir Path root) {
    Path target = root.resolve("settings.json");
    Files.writeString(target, validJson(defaults));
    AtomicFileWriter writer = new AtomicFileWriter((source, destination, options) -> {
        throw new IOException("injected replace failure");
    });
    assertThrows(SettingsPersistenceException.class,
            () -> store(target, writer).save(changed));
    assertEquals(defaults, store(target).load().snapshot());
}
```

- [ ] **Step 3: Run RED**

Run: `./gradlew.bat :game:test --tests 'com.gaia.settings.DefaultSettingsPathProviderTest' --tests 'com.gaia.settings.JsonSettingsStoreTest' --tests 'com.gaia.settings.AtomicFileWriterTest' --console=plain --no-daemon`

Expected: FAIL because path/store/writer types do not exist.

- [ ] **Step 4: Implement path and atomic writer**

Write UTF-8 to a uniquely named sibling temporary file, flush/close it, attempt `ATOMIC_MOVE, REPLACE_EXISTING`, and only on `AtomicMoveNotSupportedException` retry `REPLACE_EXISTING`. Delete the temporary file in a finally block without deleting the prior target. Do not swallow other I/O failures.

- [ ] **Step 5: Implement Gson store and run GREEN**

Serialize a stable schema-v1 object with no NaN/Infinity. Load reads UTF-8, delegates all raw-field validation to `SettingsValidator`, and returns immutable diagnostics. A missing file returns defaults and an empty diagnostic list.

Run the Task 2 test command. Expected: PASS.

---

### Task 3: Owner-thread hot-application boundaries

**Files:**
- Modify: `engine/src/main/java/com/overlord/core/Window.java:126-143`
- Modify: `engine/src/test/java/com/overlord/core/WindowVsyncContractTest.java`
- Modify: `engine/src/main/java/com/overlord/renderer/Renderer.java:54-573`
- Modify: `engine/src/test/java/com/overlord/renderer/RendererStructureTest.java`
- Create: `engine/src/test/java/com/overlord/renderer/RendererProjectionSettingsTest.java`
- Modify: `engine/src/main/java/com/overlord/renderer/Camera.java:13-146`
- Create: `engine/src/test/java/com/overlord/renderer/CameraLookSettingsTest.java`
- Create: `game/src/main/java/com/gaia/settings/AudioSettingsPort.java`
- Create: `game/src/main/java/com/gaia/settings/SettingsApplier.java`
- Test: `game/src/test/java/com/gaia/settings/SettingsApplierTest.java`

**Interfaces:**
- Produces: `Window.setVsync(boolean)`, `Renderer.setFovDegrees(float)`, `Camera.setLookSettings(float, boolean)`.
- Produces: `AudioSettingsPort.apply(master, music, sfx, muteWhenUnfocused)`.
- Produces: `SettingsApplier.apply(previous, next)` with rollback on partial failure.

- [ ] **Step 1: RED-test runtime VSync and thread ownership**

Refactor the static test seam to accept an interval consumer and assert exact 1/0. Add a wrong-thread test using `MainThreadGuard`:

```java
@Test
void appliesOneForTrueAndZeroForFalse() {
    List<Integer> intervals = new ArrayList<>();
    Window.applySwapInterval(true, intervals::add);
    Window.applySwapInterval(false, intervals::add);
    assertEquals(List.of(1, 0), intervals);
}
```

- [ ] **Step 2: RED-test FOV and look settings**

Assert FOV rejects 49.99, 100.01, and non-finite values; the package-private pure `Renderer.projectionFor(RenderSurfaceMetrics, float)` matrix changes for 70 to 90 while `Camera.getForward()` is bitwise unchanged. Assert sensitivity scaling and invert-Y affect only pitch direction. The test must not require a live GL context.

- [ ] **Step 3: Run engine RED**

Run: `./gradlew.bat :engine:test --tests com.overlord.core.WindowVsyncContractTest --tests com.overlord.renderer.RendererProjectionSettingsTest --tests com.overlord.renderer.CameraLookSettingsTest --console=plain --no-daemon`

Expected: FAIL because runtime setters do not exist.

- [ ] **Step 4: Implement minimal engine setters**

`Window.setVsync` asserts main thread and calls `glfwSwapInterval(vsync ? 1 : 0)` without touching clocks. Renderer stores validated `fovDegrees`, recomputes projection using current surface metrics, and does not modify Camera orientation. Camera stores validated sensitivity/invert-Y and applies invert only to Y mouse delta.

- [ ] **Step 5: RED-test reversible aggregate apply**

Use recording ports and inject a failure on the third boundary. Assert previously changed boundaries receive the old values in reverse order, and the current applied snapshot remains unchanged.

- [ ] **Step 6: Implement SettingsApplier and run GREEN**

`SettingsApplier` calls Window, Renderer, Camera, and `AudioSettingsPort` only for changed hot fields. On failure it rolls back already-applied ports in reverse order and rethrows with rollback failures suppressed.

Run the Task 3 engine command and `./gradlew.bat :game:test --tests com.gaia.settings.SettingsApplierTest --console=plain --no-daemon`.

Expected: PASS.

---

### Task 4: Transactional SettingsController and Settings UI

**Files:**
- Create: `game/src/main/java/com/gaia/settings/SettingsController.java`
- Create: `game/src/main/java/com/gaia/settings/SettingsDraftSnapshot.java`
- Modify: `game/src/main/java/com/gaia/shell/ScreenCommand.java`
- Modify: `game/src/main/java/com/gaia/shell/ProductShellController.java`
- Modify: `game/src/main/java/com/gaia/shell/ui/UiActionId.java`
- Modify: `game/src/main/java/com/gaia/shell/ui/ProductScreenPresenter.java`
- Modify: `game/src/main/java/com/gaia/shell/ui/ProductScreenInputController.java`
- Modify: `game/src/main/java/com/gaia/session/GameSessionConfig.java`
- Modify: `game/src/main/java/com/gaia/GameBootstrap.java`
- Test: `game/src/test/java/com/gaia/settings/SettingsControllerTest.java`
- Test: `game/src/test/java/com/gaia/shell/ui/SettingsScreenPresenterTest.java`
- Test: `game/src/test/java/com/gaia/shell/SettingsShellIntegrationTest.java`

**Interfaces:**
- Consumes: Tasks 1–3 settings/store/applier APIs.
- Produces: immutable draft snapshot, typed adjustment commands, Apply/Discard/Cancel, and `GameSessionConfig.from(SettingsSnapshot)`.

- [ ] **Step 1: Write controller transaction RED tests**

Cover successful apply order, validation, persistence failure rollback, application failure, discard, dirty back modal, and no write for unchanged draft:

```java
@Test
void persistenceFailureRollsBackHotSettingsAndDoesNotPublishDraft() {
    controller.adjustFov(90.0);
    store.failNextSave();
    assertThrows(SettingsPersistenceException.class, controller::apply);
    assertEquals(SettingsDefaults.schemaV1(), controller.applied());
    assertEquals(70.0f, rendererPort.fov());
}
```

- [ ] **Step 2: Run controller RED**

Run: `./gradlew.bat :game:test --tests com.gaia.settings.SettingsControllerTest --console=plain --no-daemon`

Expected: FAIL because controller/draft types do not exist.

- [ ] **Step 3: Implement transactional apply**

Apply order is validate draft, hot-apply previous to next, persist next, publish next. If persistence fails, hot-apply next to previous and keep applied unchanged. If rollback fails, attach it as suppressed and surface a blocking diagnostic because runtime and persisted settings can no longer be proven coherent.

- [ ] **Step 4: Write Settings screen RED tests**

Assert every approved row and value/range, no Fullscreen/UI Scale row, enabled Apply only when dirty, next-session labels on render distance/game mode/debug, and dirty Back opens exactly Apply/Discard/Cancel.

- [ ] **Step 5: Implement typed settings commands and layout**

Add commands for boolean toggle, bounded decrement/increment, Apply, Discard, and Cancel. Use discrete UI steps: FOV 1 degree, sensitivity 0.01, Chunk radius 1, volume 5 percentage points. The controller clamps at approved limits; holding a control does not repeat unless a new pressed sample arrives.

- [ ] **Step 6: Capture next-session settings**

```java
public static GameSessionConfig from(SettingsSnapshot settings) {
    return new GameSessionConfig(
            12345L,
            settings.chunkRadius(),
            settings.defaultGameMode(),
            settings.debugHudDefault());
}
```

World generation uses this Chunk radius only when a new session is created. Existing sessions remain unchanged.

- [ ] **Step 7: Run focused GREEN**

Run: `./gradlew.bat :game:test --tests 'com.gaia.settings.*' --tests com.gaia.shell.ui.SettingsScreenPresenterTest --tests com.gaia.shell.SettingsShellIntegrationTest --console=plain --no-daemon`

Expected: PASS.

---

### Task 5: Gate 13B integration, persistence smoke, and documentation

**Files:**
- Modify: `game/src/main/java/com/gaia/shell/ProductLoop.java`
- Modify: `game/src/main/java/com/gaia/GameBootstrap.java`
- Update after actual evidence: `docs/agent-handoffs/phase-13-handoff.md`
- Update factual controls only: `CONTROLS.md`

**Interfaces:**
- Consumes: validated loaded settings before Engine/window/session creation and the Settings UI transaction.
- Produces: runtime-applied settings and factual Gate 13B evidence.

- [ ] **Step 1: Integrate startup load and close policy**

Resolve the settings path and load schema v1 before product defaults are applied. Construct Engine/window with the loaded VSync, then apply FOV/look settings after renderer/camera creation. Product close rewrites only the current applied snapshot and never an open draft.

- [ ] **Step 2: Run Gate 13B focused regression**

Run: `./gradlew.bat :engine:test --tests com.overlord.core.WindowVsyncContractTest --tests com.overlord.renderer.RendererProjectionSettingsTest --tests com.overlord.renderer.CameraLookSettingsTest --console=plain --no-daemon`

Run: `./gradlew.bat :game:test --tests 'com.gaia.settings.*' --tests 'com.gaia.shell.*Settings*' --console=plain --no-daemon`

Expected: PASS.

- [ ] **Step 3: Verify presentation settings do not alter fixed simulation**

Run: `./gradlew.bat :engine:test --tests com.overlord.core.time.FixedStepClockTest --console=plain --no-daemon`

Run: `./gradlew.bat :game:test --tests 'com.gaia.physics.*' --tests 'com.gaia.worlditem.*' --console=plain --no-daemon`

Expected: PASS; no gameplay test depends on VSync, FOV, sensitivity, or audio values.

- [ ] **Step 4: Run real Windows persistence smoke**

Run: `./gradlew.bat :game:run --console=plain --no-daemon`

Interactively verify: change FOV, sensitivity, invert-Y, VSync, volumes, Chunk radius, default mode, and debug default; Apply; exit cleanly; relaunch; confirm values persisted; confirm hot settings applied; confirm next-session labels behave only after New World; resize and inspect DPI hit alignment.

- [ ] **Step 5: Inspect the actual settings file safely**

Use `SettingsPathProvider` output reported by a focused diagnostic/test helper, not a hard-coded personal path. Confirm `schemaVersion: 1`, finite values, and no temp file left beside it. Do not add the user settings file to the repository.

- [ ] **Step 6: Record evidence and audit**

Update the Phase 13 handoff with exact focused commands and actual runtime status. Run:

`git diff --check`

`git status --short --untracked-files=all`

If any real GLFW/context/settings application defect occurs, stop, use systematic debugging plus TDD, rerun focused GREEN, and immediately repeat the runtime smoke.
