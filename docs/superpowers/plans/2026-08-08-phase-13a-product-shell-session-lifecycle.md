# Phase 13A Product Shell and Session Lifecycle Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a single-loop Main Menu/Pause/Controls shell with deterministic input blocking and a lazily created, fully closeable gameplay session.

**Architecture:** A long-lived `ProductLoop` owns window events, product screens, UI, and zero or one `GameSession`. The session owns the canonical World and all gameplay runtime resources; product screens receive immutable UI input before gameplay, and lifecycle transitions apply a held-input release gate.

**Tech Stack:** Java 17, LWJGL 3.3.3 GLFW/OpenGL 4.1, JOML, existing immutable UI command renderer, JUnit 6.1.1, Gradle Wrapper.

## Global Constraints

- Keep exactly one application loop and one GLFW/OpenGL owner thread.
- Preserve the fixed 1/60 gameplay simulation and existing first-step/`heldOnly()` edge policy.
- Do not let UI types call World, Inventory, WorldItem, Renderer, GLFW, or OpenAL services directly.
- Do not implement SaveGame serialization, loading, autosave, cloud saves, key rebinding, inventory/crafting UI, or new gameplay systems.
- Keep OpenGL 4.1 and GLSL 410 compatibility.
- Run a real Windows runtime smoke immediately after local GREEN for GLFW/input/lifecycle changes.
- Preserve and exclude `dist/GaiaLegacy-v0.2.0-alpha.1-windows-x64.zip`.
- Do not stage, commit, push, create a PR, or merge.

## File Structure

Engine input additions:

- `engine/src/main/java/com/overlord/core/input/UiInputSnapshot.java`: immutable per-product-frame UI input.
- `engine/src/main/java/com/overlord/core/input/InputManager.java`: absolute pointer capture and held-input release gate.
- `engine/src/main/java/com/overlord/core/time/FixedStepClock.java`: explicit remainder discard on lifecycle boundaries.

Game product shell:

- `game/src/main/java/com/gaia/shell/ScreenId.java`: primary route enum.
- `game/src/main/java/com/gaia/shell/ScreenReturnTarget.java`: Main Menu or Paused return target.
- `game/src/main/java/com/gaia/shell/ModalId.java`: top-layer modal enum.
- `game/src/main/java/com/gaia/shell/ScreenCommand.java`: typed UI intent.
- `game/src/main/java/com/gaia/shell/ScreenRouter.java`: legal transition and modal state owner.
- `game/src/main/java/com/gaia/shell/ProductShellSnapshot.java`: immutable render/input projection.
- `game/src/main/java/com/gaia/shell/ProductShellController.java`: translates commands into route/session intent.
- `game/src/main/java/com/gaia/shell/ProductLoop.java`: the only application loop.
- `game/src/main/java/com/gaia/shell/save/SaveCatalog.java`: Phase 14-facing read-only catalog boundary.
- `game/src/main/java/com/gaia/shell/save/SaveSummary.java`: immutable catalog row.
- `game/src/main/java/com/gaia/shell/save/EmptySaveCatalog.java`: Phase 13 production adapter.

Game product UI:

- `game/src/main/java/com/gaia/shell/ui/UiActionId.java`: stable focus/hit identifiers.
- `game/src/main/java/com/gaia/shell/ui/UiHitRegion.java`: logical bounds plus enabled action and finite `contains`/center helpers.
- `game/src/main/java/com/gaia/shell/ui/ProductUiLayout.java`: `UiFrame` and matching hit regions.
- `game/src/main/java/com/gaia/shell/ui/ProductScreenPresenter.java`: Main/Pause/Controls/modal layout.
- `game/src/main/java/com/gaia/shell/ui/ProductScreenInputController.java`: mouse/keyboard navigation to commands.

Session boundary:

- `game/src/main/java/com/gaia/session/GameSession.java`: lifecycle and frame boundary.
- `game/src/main/java/com/gaia/session/GameSessionFactory.java`: creates one isolated session.
- `game/src/main/java/com/gaia/session/GameSessionConfig.java`: immutable next-session configuration.
- `game/src/main/java/com/gaia/session/GameSessionFrame.java`: immutable session render/HUD projection.
- `game/src/main/java/com/gaia/session/GameSessionState.java`: Loading/Ready/Failed/Closed.
- `game/src/main/java/com/gaia/GameLoop.java`: refactor existing gameplay-frame logic so it no longer owns the outer while-loop.
- `game/src/main/java/com/gaia/GameContext.java`: session-only dependency context.
- `game/src/main/java/com/gaia/GameBootstrap.java`: compose product resources and session factory instead of eagerly creating a world.
- `engine/src/main/java/com/overlord/core/Engine.java`: stop constructing/registering a canonical World.

Focused tests mirror the packages above under `engine/src/test/java` and `game/src/test/java`.

---

### Task 1: Immutable UI input and held-release gate

**Files:**
- Create: `engine/src/main/java/com/overlord/core/input/UiInputSnapshot.java`
- Modify: `engine/src/main/java/com/overlord/core/input/InputManager.java:20-227`
- Modify: `engine/src/testFixtures/java/com/overlord/core/input/InputManagerTestDriver.java`
- Test: `engine/src/test/java/com/overlord/core/input/InputManagerTest.java`
- Test: `engine/src/test/java/com/overlord/core/input/UiInputSnapshotTest.java`

**Interfaces:**
- Produces: `UiInputSnapshot`, `InputManager.captureUiInput(long)`, and `InputManager.invalidateGameplayInput()`.
- Preserves: `InputManager.consumeFixedInput()` and `InputSnapshot.heldOnly()` signatures.

- [ ] **Step 1: Add RED tests for immutable UI capture**

Add tests that drive the real callback-facing test fixture and assert exact pointer, focus, pressed/down, scroll, and sample ID state:

```java
@Test
void captureUiInputUsesAbsolutePointerAndDoesNotConsumeGameplayEdges() {
    driver.cursor(320.0, 180.0);
    driver.key(GLFW_KEY_ENTER, GLFW_PRESS);
    driver.mouse(GLFW_MOUSE_BUTTON_LEFT, GLFW_PRESS);
    driver.scroll(0.0, -2.0);

    UiInputSnapshot ui = input.captureUiInput(7L);

    assertEquals(320.0, ui.pointerX());
    assertEquals(180.0, ui.pointerY());
    assertTrue(ui.isKeyPressed(GLFW_KEY_ENTER));
    assertTrue(ui.isMousePressed(GLFW_MOUSE_BUTTON_LEFT));
    assertEquals(List.of(-2), ui.scrollDeltas());
    assertTrue(input.consumeFixedInput().isKeyPressed(GLFW_KEY_ENTER));
}
```

- [ ] **Step 2: Run the exact RED tests**

Run: `./gradlew.bat :engine:test --tests com.overlord.core.input.UiInputSnapshotTest --tests com.overlord.core.input.InputManagerTest --console=plain --no-daemon`

Expected: FAIL because `UiInputSnapshot`, `captureUiInput`, and absolute-pointer fixture access do not exist.

- [ ] **Step 3: Implement the immutable UI snapshot**

Use defensive set/list copies and finite pointer validation:

```java
public record UiInputSnapshot(
        Set<Integer> downKeys,
        Set<Integer> pressedKeys,
        Set<Integer> downMouseButtons,
        Set<Integer> pressedMouseButtons,
        List<Integer> scrollDeltas,
        double pointerX,
        double pointerY,
        boolean focused,
        long sampleId) {
    public UiInputSnapshot {
        downKeys = Set.copyOf(downKeys);
        pressedKeys = Set.copyOf(pressedKeys);
        downMouseButtons = Set.copyOf(downMouseButtons);
        pressedMouseButtons = Set.copyOf(pressedMouseButtons);
        scrollDeltas = List.copyOf(scrollDeltas);
        if (!Double.isFinite(pointerX) || !Double.isFinite(pointerY) || sampleId < 0) {
            throw new IllegalArgumentException("UI pointer must be finite and sampleId non-negative");
        }
    }
}
```

Capture must not clear gameplay edges. `InputManager` stores the latest absolute callback position separately from accumulated look delta.

- [ ] **Step 4: Add RED tests for held-input suppression**

Cover Q, Ctrl, movement, jump, left/right mouse, and release recovery:

```java
@Test
void invalidationSuppressesHeldGameplayControlsUntilPhysicalRelease() {
    driver.key(GLFW_KEY_Q, GLFW_PRESS);
    driver.mouse(GLFW_MOUSE_BUTTON_RIGHT, GLFW_PRESS);
    input.invalidateGameplayInput();

    assertFalse(input.consumeFixedInput().isKeyDown(GLFW_KEY_Q));
    assertFalse(input.consumeFixedInput().isMouseButtonDown(GLFW_MOUSE_BUTTON_RIGHT));

    driver.key(GLFW_KEY_Q, GLFW_RELEASE);
    driver.mouse(GLFW_MOUSE_BUTTON_RIGHT, GLFW_RELEASE);
    driver.key(GLFW_KEY_Q, GLFW_PRESS);
    driver.mouse(GLFW_MOUSE_BUTTON_RIGHT, GLFW_PRESS);
    InputSnapshot fresh = input.consumeFixedInput();
    assertTrue(fresh.isKeyPressed(GLFW_KEY_Q));
    assertTrue(fresh.isMouseButtonPressed(GLFW_MOUSE_BUTTON_RIGHT));
}
```

- [ ] **Step 5: Implement general suppression and rerun GREEN**

Add `suppressedKeys` alongside `suppressedMouseButtons`. `invalidateGameplayInput()` marks every currently down key/button suppressed, clears pressed arrays and scroll, resets look baseline, and leaves raw callback-owned down state available to UI. Release callbacks clear the corresponding suppression bit. `consumeFixedInput()` removes suppressed keys and buttons from both down and pressed copies.

Run the Task 1 test command again. Expected: PASS.

- [ ] **Step 6: Run engine input regression tests and inventory the tree**

Run: `./gradlew.bat :engine:test --tests 'com.overlord.core.input.*' --console=plain --no-daemon`

Run: `git status --short --untracked-files=all`

Expected: input tests PASS; only intended Task 1 files plus the pre-existing ZIP and approved design/plan documents appear.

---

### Task 2: Screen router, modal ownership, and SaveCatalog seam

**Files:**
- Create: `game/src/main/java/com/gaia/shell/ScreenId.java`
- Create: `game/src/main/java/com/gaia/shell/ScreenReturnTarget.java`
- Create: `game/src/main/java/com/gaia/shell/ModalId.java`
- Create: `game/src/main/java/com/gaia/shell/ScreenCommand.java`
- Create: `game/src/main/java/com/gaia/shell/ScreenRouter.java`
- Create: `game/src/main/java/com/gaia/shell/ProductShellSnapshot.java`
- Create: `game/src/main/java/com/gaia/shell/save/SaveCatalog.java`
- Create: `game/src/main/java/com/gaia/shell/save/SaveSummary.java`
- Create: `game/src/main/java/com/gaia/shell/save/EmptySaveCatalog.java`
- Test: `game/src/test/java/com/gaia/shell/ScreenRouterTest.java`
- Test: `game/src/test/java/com/gaia/shell/save/EmptySaveCatalogTest.java`

**Interfaces:**
- Produces: `ScreenRouter.snapshot()`, `openSettings`, `openControls`, `beginLoading`, `loadingSucceeded`, `pause`, `resume`, `openModal`, `dismissModal`, and `returnedToMainMenu`.
- Produces: `SaveCatalog.summaries(): List<SaveSummary>`.

- [ ] **Step 1: Write the transition-table RED tests**

Use parameterized legal transitions and explicit illegal cases. Include return target and modal priority:

```java
@Test
void settingsReturnsToTheScreenThatOpenedIt() {
    ScreenRouter router = ScreenRouter.mainMenu();
    router.openSettings(ScreenReturnTarget.MAIN_MENU);
    router.back();
    assertEquals(ScreenId.MAIN_MENU, router.snapshot().screen());

    router.beginLoading();
    router.loadingSucceeded();
    router.pause();
    router.openSettings(ScreenReturnTarget.PAUSED);
    router.back();
    assertEquals(ScreenId.PAUSED, router.snapshot().screen());
}

@Test
void modalBlocksUnderlyingTransition() {
    ScreenRouter router = ScreenRouter.mainMenu();
    router.openModal(ModalId.QUIT_CONFIRMATION);
    assertThrows(IllegalStateException.class, router::beginLoading);
    assertEquals(ModalId.QUIT_CONFIRMATION, router.snapshot().modal().orElseThrow());
}
```

- [ ] **Step 2: Run RED**

Run: `./gradlew.bat :game:test --tests 'com.gaia.shell.*' --console=plain --no-daemon`

Expected: FAIL because the shell route types do not exist.

- [ ] **Step 3: Implement the minimal closed transition model**

Use enums and one mutable router with an immutable snapshot:

```java
public record ProductShellSnapshot(
        ScreenId screen,
        Optional<ModalId> modal,
        Optional<ScreenReturnTarget> returnTarget) {}

public sealed interface ScreenCommand permits
        ScreenCommand.NewWorld,
        ScreenCommand.OpenSettings,
        ScreenCommand.OpenControls,
        ScreenCommand.Resume,
        ScreenCommand.ReturnToMainMenu,
        ScreenCommand.Quit,
        ScreenCommand.Back,
        ScreenCommand.Confirm,
        ScreenCommand.Dismiss {
    record NewWorld() implements ScreenCommand {}
    record OpenSettings() implements ScreenCommand {}
    record OpenControls() implements ScreenCommand {}
    record Resume() implements ScreenCommand {}
    record ReturnToMainMenu() implements ScreenCommand {}
    record Quit() implements ScreenCommand {}
    record Back() implements ScreenCommand {}
    record Confirm() implements ScreenCommand {}
    record Dismiss() implements ScreenCommand {}
}
```

Every router method validates its source state before mutation. Repeated modal dismiss and completed close transitions are no-ops only where the approved lifecycle calls for idempotence; unrelated illegal transitions throw.

- [ ] **Step 4: Implement and test the empty save boundary**

```java
public interface SaveCatalog {
    List<SaveSummary> summaries();
}

public final class EmptySaveCatalog implements SaveCatalog {
    @Override public List<SaveSummary> summaries() { return List.of(); }
}
```

Assert the returned list is empty and immutable. No file I/O or SaveGame type is added.

- [ ] **Step 5: Run GREEN and package-boundary checks**

Run: `./gradlew.bat :game:test --tests 'com.gaia.shell.*' --console=plain --no-daemon`

Expected: PASS.

---

### Task 3: Product screen layout, hit testing, and navigation

**Files:**
- Create: `game/src/main/java/com/gaia/shell/ui/UiActionId.java`
- Create: `game/src/main/java/com/gaia/shell/ui/UiHitRegion.java`
- Create: `game/src/main/java/com/gaia/shell/ui/ProductUiLayout.java`
- Create: `game/src/main/java/com/gaia/shell/ui/ProductScreenPresenter.java`
- Create: `game/src/main/java/com/gaia/shell/ui/ProductScreenInputController.java`
- Test: `game/src/test/java/com/gaia/shell/ui/ProductScreenPresenterTest.java`
- Test: `game/src/test/java/com/gaia/shell/ui/ProductScreenInputControllerTest.java`
- Test: `game/src/test/java/com/gaia/shell/ui/ProductUiDpiMatrixTest.java`

**Interfaces:**
- Consumes: `ProductShellSnapshot`, `UiInputSnapshot`, `UiLayoutContext`, `SaveCatalog` summaries.
- Produces: `ProductUiLayout(UiFrame frame, List<UiHitRegion> hitRegions)` and zero or one `ScreenCommand` per input sample.

- [ ] **Step 1: Add RED presentation and asymmetric DPI hit tests**

Assert exact enabled actions for Main and Pause, disabled Load World, matching painted/hit bounds, and logical pointer mapping at content scales 1.0, 1.25, 1.5, and 2.0:

```java
@ParameterizedTest
@ValueSource(floats = {1.0f, 1.25f, 1.5f, 2.0f})
void newWorldPaintAndHitRegionRemainAligned(float scale) {
    RenderSurfaceMetrics surface = new RenderSurfaceMetrics(
            1280, 720, Math.round(1280 * scale), Math.round(720 * scale), scale, scale);
    ProductUiLayout layout = presenter.present(mainMenu, new UiLayoutContext(surface));
    UiHitRegion hit = layout.hitRegions().stream()
            .filter(region -> region.action() == UiActionId.NEW_WORLD)
            .findFirst().orElseThrow();
    assertTrue(hit.contains(hit.centerX(), hit.centerY()));
    assertTrue(layout.frame().commands().stream()
            .anyMatch(command -> command.framebufferBounds().equals(
                    new UiLayoutContext(surface).toFramebuffer(hit.logicalBounds()))));
}
```

- [ ] **Step 2: Run RED**

Run: `./gradlew.bat :game:test --tests 'com.gaia.shell.ui.*' --console=plain --no-daemon`

Expected: FAIL because product UI types do not exist.

- [ ] **Step 3: Implement one layout source for drawing and interaction**

`ProductScreenPresenter` appends existing solid/font commands and creates hit regions from the same logical rectangles. Main Menu actions are New World, disabled Load World, Settings, Controls, Quit. Pause actions are Resume, Settings, Controls, Return to Main Menu. Controls is read-only. Modal regions are returned instead of underlying regions when a modal is present.

- [ ] **Step 4: Add RED keyboard/pointer exclusivity tests**

Cover hover/click, disabled click, deterministic focus order, Tab/arrow, Enter/Space, Escape, one command per sample ID, and top-modal exclusivity:

```java
@Test
void confirmationModalOwnsPointerAndKeyboardInput() {
    ProductUiLayout layout = presenter.present(quitModal, context);
    UiHitRegion confirm = layout.region(UiActionId.CONFIRM);
    UiInputSnapshot click = inputAt(confirm.centerX(), confirm.centerY(), 12L);
    assertEquals(new ScreenCommand.Confirm(), controller.route(click, layout).orElseThrow());
    assertFalse(layout.hitRegions().stream().anyMatch(r -> r.action() == UiActionId.NEW_WORLD));
}
```

- [ ] **Step 5: Implement navigation and run GREEN**

Track only presentation focus and last processed sample ID. Never store domain services. Convert framebuffer cursor coordinates to logical coordinates exactly once using content scale, clamp outside-window input to no hit, and ignore disabled regions.

Run the Task 3 test command. Expected: PASS.

---

### Task 4: Fixed-clock boundary and true GameSession ownership

**Files:**
- Modify: `engine/src/main/java/com/overlord/core/time/FixedStepClock.java:1-39`
- Modify: `engine/src/test/java/com/overlord/core/time/FixedStepClockTest.java`
- Modify: `engine/src/main/java/com/overlord/core/Engine.java:20-181`
- Create: `engine/src/test/java/com/overlord/core/EngineLifecycleTest.java`
- Create: `game/src/main/java/com/gaia/session/GameSession.java`
- Create: `game/src/main/java/com/gaia/session/GameSessionFactory.java`
- Create: `game/src/main/java/com/gaia/session/GameSessionConfig.java`
- Create: `game/src/main/java/com/gaia/session/GameSessionFrame.java`
- Create: `game/src/main/java/com/gaia/session/GameSessionState.java`
- Modify: `game/src/main/java/com/gaia/GameContext.java`
- Modify: `game/src/main/java/com/gaia/GameBootstrap.java:96-431`
- Modify: `game/src/main/java/com/gaia/GameLoop.java:39-433`
- Test: `game/src/test/java/com/gaia/session/GameSessionLifecycleTest.java`
- Test: `game/src/test/java/com/gaia/session/GameSessionFactoryTest.java`
- Modify: `game/src/test/java/com/gaia/GameBootstrapStructureTest.java`
- Modify: `game/src/test/java/com/gaia/GameLoopStructureTest.java`

**Interfaces:**
- Produces: `FixedStepClock.discardRemainder()`.
- Produces: `GameSession.state()`, `pollLoad()`, `advancePlaying(...)`, `capturePaused(...)`, `discardFixedTime()`, and `close()`.
- Consumes: `GameSessionConfig(seed, chunkRadius, defaultGameMode, debugHudDefault)`.

- [ ] **Step 1: RED-test fixed-time discard**

```java
@Test
void discardRemainderPreventsPausedTimeFromCompletingAnOldStep() {
    FixedStepClock clock = new FixedStepClock(1.0 / 60.0, 8);
    assertEquals(0, clock.advance(0.010));
    clock.discardRemainder();
    assertEquals(0.0, clock.remainderSeconds());
    assertEquals(0, clock.advance(0.0067));
}
```

Run: `./gradlew.bat :engine:test --tests com.overlord.core.time.FixedStepClockTest --console=plain --no-daemon`

Expected: FAIL because `discardRemainder()` does not exist.

- [ ] **Step 2: Implement and GREEN the clock boundary**

Add an owner-agnostic `public void discardRemainder() { accumulatorSeconds = 0.0; }` and rerun the focused test.

- [ ] **Step 3: Add RED session-lifecycle tests with recording resources**

Tests must prove lazy construction, Loading to Ready, cancel/failure cleanup, reverse close, idempotence, and fresh World identity:

```java
@Test
void returningToMainClosesOneSessionAndNextNewWorldOwnsAFreshWorld() {
    GameSession first = factory.create(config);
    World firstWorld = fixture.worldOwnedBy(first);
    first.close();
    first.close();

    GameSession second = factory.create(config);
    assertNotSame(firstWorld, fixture.worldOwnedBy(second));
    assertEquals(List.of("mesh", "world-load", "gameplay"), fixture.firstCloseOrder());
}
```

- [ ] **Step 4: Run session RED**

Run: `./gradlew.bat :game:test --tests 'com.gaia.session.*' --console=plain --no-daemon`

Expected: FAIL because the session boundary does not exist and bootstrap still owns an eager world load.

- [ ] **Step 5: Extract existing gameplay composition into GameSessionFactory**

Move, rather than duplicate, the existing world/player/physics/inventory/interaction/WorldItem/chunk construction from `GameBootstrap` into `GameSessionFactory.create(GameSessionConfig)`. Each creation uses a fresh `World` and a session-local `ShutdownCoordinator`. Keep exact existing gameplay constructors and shutdown order.

Use this lifecycle surface:

```java
public interface GameSession extends AutoCloseable {
    GameSessionState state();
    void pollLoad();
    GameSessionFrame advancePlaying(double frameDeltaSeconds, MouseDelta look, boolean focused);
    GameSessionFrame capturePaused();
    void discardFixedTime();
    @Override void close();
}
```

`advancePlaying` contains the fixed-step and capture logic currently inside `GameLoop`; `capturePaused` performs no canonical updates and returns a copy of the last immutable frame with product UI supplied separately.

- [ ] **Step 6: Remove World ownership from Engine**

Delete `Engine.world`, its construction, ServiceLocator registration, accessor, and shutdown references. Update game composition to pass the session-owned World directly. Add a structure test that `Engine.java` contains no `new World`, `World world`, or `services.register(World.class`.

- [ ] **Step 7: Run session and ownership GREEN**

Run: `./gradlew.bat :engine:test --tests 'com.overlord.core.*' --console=plain --no-daemon`

Run: `./gradlew.bat :game:test --tests 'com.gaia.session.*' --tests com.gaia.GameBootstrapStructureTest --tests com.gaia.GameLoopStructureTest --console=plain --no-daemon`

Expected: PASS with one session-owned World and no eager `worldLoader.loadAsync` in `GameBootstrap`.

---

### Task 5: ProductLoop, hard pause, focus loss, and no edge replay

**Files:**
- Create: `game/src/main/java/com/gaia/shell/ProductShellController.java`
- Create: `game/src/main/java/com/gaia/shell/ProductLoop.java`
- Modify: `game/src/main/java/com/gaia/GameBootstrap.java:96-431`
- Modify: `game/src/main/java/com/gaia/GameLoop.java`
- Modify: `game/src/main/java/com/gaia/ui/HudFrameCoordinator.java:20-88`
- Test: `game/src/test/java/com/gaia/shell/ProductShellControllerTest.java`
- Test: `game/src/test/java/com/gaia/shell/ProductLoopTest.java`
- Modify: `game/src/test/java/com/gaia/UiGameLoopIntegrationTest.java`
- Modify: `game/src/test/java/com/gaia/InteractionFeedbackGameLoopTest.java`

**Interfaces:**
- Consumes: Task 1 input APIs, Task 2 router, Task 3 layout/controller, Task 4 session factory.
- Produces: the only outer `ProductLoop.run()` and a testable `runFrame(double)` seam.

- [ ] **Step 1: Add RED controller tests for approved menu semantics**

Cover New World, disabled Load World, return warning, quit confirmation, dirty-settings placeholder command routing, and repeated confirmation idempotence. Assert controller outputs typed lifecycle intent and never accesses a domain service.

- [ ] **Step 2: Add RED loop tests for hard pause and focus loss**

Use a recording `GameSession` and `InputManagerTestDriver`:

```java
@Test
void focusLossHardPausesAndResumeCannotReplayHeldQOrRightClick() {
    fixture.enterPlaying();
    fixture.pressKey(GLFW_KEY_Q);
    fixture.pressMouse(GLFW_MOUSE_BUTTON_RIGHT);
    fixture.loseFocus();
    fixture.frame();
    assertEquals(ScreenId.PAUSED, fixture.shell().screen());
    assertEquals(0, fixture.session().fixedAdvancesAfterPause());

    fixture.restoreFocus();
    fixture.resume();
    fixture.frame();
    assertFalse(fixture.session().lastInput().isKeyDown(GLFW_KEY_Q));
    assertFalse(fixture.session().lastInput().isMouseButtonDown(GLFW_MOUSE_BUTTON_RIGHT));
}
```

Also assert paused frames call `capturePaused` but never `advancePlaying`, repeated pause/resume calls `discardFixedTime`, Escape/F1 share the transition, and F2/F3/F4 never reach a product screen.

- [ ] **Step 3: Run ProductLoop RED**

Run: `./gradlew.bat :game:test --tests 'com.gaia.shell.Product*' --tests com.gaia.UiGameLoopIntegrationTest --tests com.gaia.InteractionFeedbackGameLoopTest --console=plain --no-daemon`

Expected: FAIL because ProductLoop/controller integration does not exist.

- [ ] **Step 4: Implement the single frame order**

`ProductLoop.runFrame` performs exactly: poll surface/focus state already captured by callbacks, capture UI input, route modal/screen, apply lifecycle transition, update active session only in Playing, capture immutable shell/session presentation, render world/HUD then product UI, and swap in `run()`.

Use one command per UI sample and call `inputManager.invalidateGameplayInput()`, `session.discardFixedTime()`, `blockInteraction.cancel()`, and feedback clearing on every Playing boundary.

- [ ] **Step 5: Recompose GameBootstrap**

Bootstrap creates engine/window/renderer/UI input, product presenter/controller/router, `EmptySaveCatalog`, and `GameSessionFactory`, then calls one `new ProductLoop(...).run()`. It must not call `loadAsync` or construct session gameplay services before New World.

- [ ] **Step 6: Run focused GREEN**

Run the Task 5 test command. Expected: PASS.

Run: `./gradlew.bat :game:test --tests com.gaia.GameBootstrapTest --tests com.gaia.GameBootstrapStructureTest --tests com.gaia.GameLoopStructureTest --console=plain --no-daemon`

Expected: PASS.

- [ ] **Step 7: Run Gate 13A regression suite**

Run: `./gradlew.bat :engine:test --tests 'com.overlord.core.input.*' --tests 'com.overlord.core.time.*' --console=plain --no-daemon`

Run: `./gradlew.bat :game:test --tests 'com.gaia.shell.*' --tests 'com.gaia.session.*' --tests com.gaia.UiGameLoopIntegrationTest --tests com.gaia.InteractionFeedbackGameLoopTest --console=plain --no-daemon`

Expected: all focused tests PASS.

---

### Task 6: Gate 13A runtime checkpoint and factual handoff entry

**Files:**
- Create or update after actual evidence: `docs/agent-handoffs/phase-13-handoff.md`
- Update only if controls changed: `CONTROLS.md`

**Interfaces:**
- Consumes: completed Gate 13A runtime.
- Produces: an evidence record; no production behavior.

- [ ] **Step 1: Run whitespace and inventory checks before runtime**

Run: `git diff --check`

Run: `git status --short --untracked-files=all`

Expected: no whitespace errors; no build output, logs, screenshots, saves, crash dumps, IDE files, or unrelated artifacts are newly tracked/untracked.

- [ ] **Step 2: Launch the real Windows game**

Run: `./gradlew.bat :game:run --console=plain --no-daemon`

Required interactive path:

1. launch to Main Menu with normal cursor;
2. keyboard and mouse activate Controls and return;
3. New World enters Loading then Playing;
4. Escape pauses and releases cursor;
5. F1 resumes/pauses consistently;
6. Alt+Tab from Playing returns in Paused state;
7. resume while Q, right click, or jump was previously held and verify no action replay;
8. Return to Main Menu shows unsaved warning and closes the session;
9. start a second New World and verify it is a fresh session;
10. quit through confirmation with clean shutdown.

- [ ] **Step 3: Record only observed results**

Add Gate 13A commands, test names, runtime PASS/FAIL, platform, and any known issue to the Phase 13 handoff. If mouse interaction cannot be performed by the agent, keep the process ready and record `WINDOWS INTERACTIVE RETEST REQUIRED BY USER` rather than claiming PASS.

- [ ] **Step 4: Stop on runtime defects**

If GLFW/input/cursor/session cleanup fails, apply `superpowers:systematic-debugging` and `superpowers:test-driven-development` before changing production. Rerun the exact focused regression and this runtime path immediately after GREEN.

Phase 13B may start only when Gate 13A automated tests are green and the Windows runtime path has no unresolved blocking failure.
