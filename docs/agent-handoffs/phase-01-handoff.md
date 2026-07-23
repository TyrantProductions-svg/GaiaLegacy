# Phase 01 Handoff: Deterministic Engine Lifecycle

## Completed work

- Created `refactor/engine-lifecycle` from the refreshed `origin/main` baseline at
  `ef78647`.
- Added and committed the approved Phase 1 design independently from PR #4. No
  PR #4 code, third-party project code, or third-party assets were copied.
- Reduced `GaiaMain` to a startup entry point that only runs `GameBootstrap`.
- Added explicit `GameBootstrap`, immutable `GameContext`, and `GameLoop`
  composition with `LOADING`, `RUNNING`, and `STOPPING` states.
- Added a 1/60-second fixed update clock, a 0.25-second frame-delta clamp, and a
  maximum of five fixed updates per render frame. Excess whole steps are
  dropped while the fractional remainder is retained.
- Removed asynchronous per-frame `PlayerManager.update` submission. Player,
  physics, module, and event updates now run synchronously in the fixed step.
- Replaced duplicate GLFW input polling with one callback-backed
  `InputManager`. Mouse baseline state resets on focus changes, key press edges
  remain latched until a fixed input snapshot is consumed, and one snapshot is
  shared by all fixed updates in a catch-up frame.
- Added `F1` cursor-capture toggling so the window can be resized during
  testing. Mouse look pauses while released and the baseline resets before
  recapture.
- Added separate logical-window and framebuffer-pixel metrics. Framebuffer
  resize drives viewport/projection updates and zero-sized minimized
  framebuffers are ignored.
- Added `MainThreadGuard` and applied it to GLFW input/window operations,
  renderer entry points, and Mesh/Shader/Texture creation, upload, use, and
  cleanup.
- Moved existing terrain generation, CPU mesh generation, mesh combination,
  spawn scan, and fallback column behavior into a cancellable CPU-only
  `WorldLoader`.
- Kept GPU mesh creation/upload on the context-owning main thread through
  `Renderer.replaceMesh(float[])`.
- Made `Engine.init()` transactional and `Engine.shutdown()` idempotent.
  Cleanup continues after runtime exceptions or errors and suppresses later
  failures on the first.
- Added reverse, idempotent `ShutdownCoordinator` cleanup. Bootstrap cleanup
  preserves the original startup/runtime failure and suppresses cleanup
  failures on it.
- Added a root `game` task alias so the required cross-platform command
  `gradlew :game` delegates to `:game:run`.
- Added JUnit Platform configuration and 29 headless tests covering clocks,
  delta clamping, shutdown order/failure aggregation, main-thread rejection,
  input/focus behavior, window metrics, fixed player input, CPU world loading,
  cancellation, bootstrap failure preservation, and the minimal entry point.

## Unfinished work

- The implementation working tree has not been committed, pushed, or opened as
  a pull request. The two approved design commits are already on the branch.
- Native macOS commands were not run in this Windows session. The existing
  GitHub Actions macOS job remains the pending native verification after push.
- The Windows interactive smoke test confirmed startup, terrain/atlas
  rendering, mouse-look response, Escape shutdown, and no visible OpenGL thread
  assertion. Computer Use was then stopped by the user, so jump, sustained
  movement, focus regain, and framebuffer resize were not fully verified.
- The loading state completed too quickly to directly observe the clear-only
  loading frame during the smoke test.
- `GameLoop` cancellation and catch-up behavior are implemented directly, but
  the GL-owning loop itself does not have a headless integration test. Its
  clocks, input snapshots, worker cancellation, thread guard, and bootstrap
  failure paths are covered independently.

## Core architecture decisions

- Main/context-owning thread:
  - GLFW initialization, callbacks, event polling, close queries, cursor mode,
    resize handling, buffer swap, and destruction;
  - all OpenGL calls;
  - Mesh/Shader/Texture GPU create, upload, use, draw, replacement, and cleanup;
  - render-frame mouse look and deterministic fixed updates.
- Fixed update:
  - exactly `1.0 / 60.0` seconds;
  - up to five steps per render frame;
  - synchronous `PlayerManager`, `PhysicsManager`, `ModuleManager`, and
    `EventBus` processing;
  - one immutable `InputSnapshot` consumed only when at least one fixed step
    runs and shared across that frame's fixed steps.
- Worker thread:
  - one named `Gaia-World-Loader` executor;
  - terrain generation, CPU chunk mesh building, mesh combination, and spawn
    calculation only;
  - no Window, Renderer, Mesh, Shader, Texture, GLFW, or OpenGL access.
- Loading:
  - the render loop continues polling events, handling close/resize, clearing,
    and swapping;
  - `CompletableFuture.isDone()`/`join()` replaces the old sleep-wait loop;
  - cancellation transitions to `STOPPING`;
  - successful mesh upload and physics spawn initialization occur on the main
    thread.
- Shutdown:
  - registrations follow initialization order and close in reverse;
  - world future cancellation and executor interruption/termination precede
    engine, GPU, and window cleanup;
  - cleanup is idempotent and later failures are suppressed.
- Engine remains independent from game. New game composition uses explicit
  constructor/context dependencies and does not expand `ServiceLocator`.
- Rendering remains OpenGL 4.1 / GLSL 410 compatible and uses no compute
  shaders or platform-specific graphics APIs.

## Modified files

### Root and module configuration

- `build.gradle`
- `engine/build.gradle`
- `game/build.gradle`

### Engine production code

- `engine/src/main/java/com/overlord/Main.java`
- `engine/src/main/java/com/overlord/core/Engine.java`
- `engine/src/main/java/com/overlord/core/Input.java` (removed)
- `engine/src/main/java/com/overlord/core/PlayerManager.java`
- `engine/src/main/java/com/overlord/core/Window.java`
- `engine/src/main/java/com/overlord/core/WindowMetrics.java`
- `engine/src/main/java/com/overlord/core/input/InputManager.java`
- `engine/src/main/java/com/overlord/core/input/InputSnapshot.java`
- `engine/src/main/java/com/overlord/core/input/MouseDelta.java`
- `engine/src/main/java/com/overlord/core/lifecycle/ShutdownCoordinator.java`
- `engine/src/main/java/com/overlord/core/thread/MainThreadGuard.java`
- `engine/src/main/java/com/overlord/core/time/FixedStepClock.java`
- `engine/src/main/java/com/overlord/core/time/FrameClock.java`
- `engine/src/main/java/com/overlord/renderer/Mesh.java`
- `engine/src/main/java/com/overlord/renderer/Renderer.java`
- `engine/src/main/java/com/overlord/renderer/Shader.java`
- `engine/src/main/java/com/overlord/renderer/Texture.java`

### Engine tests

- `engine/src/test/java/com/overlord/core/PlayerManagerTest.java`
- `engine/src/test/java/com/overlord/core/WindowMetricsTest.java`
- `engine/src/test/java/com/overlord/core/input/InputManagerTest.java`
- `engine/src/test/java/com/overlord/core/lifecycle/ShutdownCoordinatorTest.java`
- `engine/src/test/java/com/overlord/core/thread/MainThreadGuardTest.java`
- `engine/src/test/java/com/overlord/core/time/FixedStepClockTest.java`
- `engine/src/test/java/com/overlord/core/time/FrameClockTest.java`

### Game production code

- `game/src/main/java/com/gaia/GaiaMain.java`
- `game/src/main/java/com/gaia/GameBootstrap.java`
- `game/src/main/java/com/gaia/GameContext.java`
- `game/src/main/java/com/gaia/GameLoop.java`
- `game/src/main/java/com/gaia/world/WorldLoadResult.java`
- `game/src/main/java/com/gaia/world/WorldLoader.java`

### Game tests

- `game/src/test/java/com/gaia/GaiaMainStructureTest.java`
- `game/src/test/java/com/gaia/GameBootstrapTest.java`
- `game/src/test/java/com/gaia/world/WorldLoaderTest.java`

### Documentation

- `docs/superpowers/specs/2026-07-23-engine-lifecycle-design.md`
- `docs/superpowers/plans/2026-07-23-engine-lifecycle.md`
- `docs/agent-handoffs/phase-01-handoff.md`

## Test commands and results

### Final Windows automated verification

```powershell
.\gradlew.bat clean test build --console=plain --no-daemon
```

Result: `BUILD SUCCESSFUL in 12s`; all 15 actionable tasks executed.

- engine: 24 tests, 0 failures, 0 errors, 0 skipped;
- game: 5 tests, 0 failures, 0 errors, 0 skipped;
- total: 29 tests, all passing;
- engine and game compiled, tested, packaged, and produced distributions;
- Gradle selected `natives-windows`.

### Focused verification performed during TDD

```powershell
.\gradlew.bat :engine:test --tests com.overlord.core.time.FrameClockTest
.\gradlew.bat :engine:test --tests com.overlord.core.time.FixedStepClockTest
.\gradlew.bat :engine:test --tests com.overlord.core.lifecycle.ShutdownCoordinatorTest
.\gradlew.bat :engine:test --tests com.overlord.core.thread.MainThreadGuardTest
.\gradlew.bat :engine:test --tests com.overlord.core.input.InputManagerTest
.\gradlew.bat :engine:test --tests com.overlord.core.WindowMetricsTest
.\gradlew.bat :engine:test --tests com.overlord.core.PlayerManagerTest
.\gradlew.bat :game:test --tests com.gaia.world.WorldLoaderTest
.\gradlew.bat :game:test --tests com.gaia.GaiaMainStructureTest
.\gradlew.bat :game:test --tests com.gaia.GameBootstrapTest
```

Each test was first observed failing for the missing behavior/type, then passed
after the minimal implementation.

### Repository policy and architecture checks

```powershell
git diff --check
git ls-files | Select-String -Pattern '(^|/)bin(/|$)|\.class($|[^/]*$)'
rg -n "#version 4(2|3|4|5|6)|glDispatchCompute" engine/src game/src
rg -n "glfwGetKey|glfwSetCursorPosCallback|glfwSetKeyCallback|glfwSetWindowFocusCallback" engine/src/main game/src/main
rg -n "Thread\.sleep|new Mesh|playerManager::update|submitToCore\([^\r\n]*player" game/src/main engine/src/main/java/com/overlord/core/PlayerManager.java
```

Results:

- `git diff --check` passed;
- no tracked `bin` or `.class` artifacts;
- no GLSL version above 410 and no compute dispatch;
- input callbacks exist only in `InputManager`;
- no direct GLFW key polling;
- no sleep-wait loading loop;
- game code does not create Mesh objects;
- no asynchronous per-frame player submission.

### Windows interactive smoke test

```powershell
.\gradlew.bat :game
```

Result: `BUILD SUCCESSFUL in 3m 32s` after Escape closed the game normally.

Confirmed:

- the required `:game` command launches the application;
- existing terrain and texture atlas render;
- mouse input changes the camera view;
- Escape stops the loop and completes Gradle successfully;
- no OpenGL main-thread assertion appeared.

Not fully verified because Computer Use was stopped:

- sustained WASD movement;
- jumping;
- focus loss/regain baseline reset;
- interactive framebuffer resize;
- directly observing the short clear-only loading state.

### macOS commands not run locally

```bash
./gradlew clean test build
./gradlew :game
```

These require native macOS. The application build retains the existing
macOS-only `-XstartOnFirstThread`, platform native selection, OpenGL 4.1, and
GLSL 410 constraints.

## Third-party dependency provenance

No third-party source code or assets were copied into the repository.

The only new dependency declaration is the test framework:

- coordinate: `org.junit.jupiter:junit-jupiter:6.1.1`;
- runtime launcher: `org.junit.platform:junit-platform-launcher`;
- upstream: `https://github.com/junit-team/junit-framework`;
- license: Eclipse Public License 2.0 (EPL-2.0);
- declaration files: `engine/build.gradle` and `game/build.gradle`;
- affected repository files: Gradle dependency declarations and Phase 1 test
  sources only.

## Known risks

- Native macOS runtime behavior remains pending CI/developer verification.
- The incomplete interactive checks listed above must be repeated before a
  release, especially focus regain and Retina framebuffer resize.
- `GameLoop` owns GLFW/OpenGL dependencies and is not instantiated in a
  headless state-transition integration test. Supporting pure units cover its
  clocks, input, cancellation source, thread boundary, shutdown order, and
  bootstrap failure semantics.
- Worker cancellation is bounded by one chunk generation or mesh-build
  operation. `CompletableFuture.cancel(true)` is followed by
  `ExecutorService.shutdownNow()` and a five-second termination wait.
- The legacy general-purpose `TaskScheduler` API is preserved for
  compatibility, including risks already documented in the Phase 0 baseline.
  Phase 1 no longer uses it for player updates or world startup.
- The standard unstaged `git diff --stat` excludes new untracked files. Review
  it together with `git status --short` and the complete modified-file list
  above.

## Interfaces Phase 2 must not break

- `GaiaMain` remains a minimal entry point invoking `new GameBootstrap().run()`.
- `GameBootstrap.run()` remains the composition/lifecycle boundary.
- `GameContext` explicitly carries Engine, input, player, physics, clocks,
  world future, and shutdown coordinator.
- `GameLoop` preserves `LOADING -> RUNNING -> STOPPING`, event polling while
  loading, one mouse delta per frame, and the fixed update order:
  `PlayerManager`, `ModuleManager`, `EventBus`.
- Fixed update remains 1/60 second with a 0.25-second frame clamp and maximum
  five catch-up steps unless deliberately changed with corresponding tests and
  handoff.
- `FrameClock.tick()`, `FixedStepClock.advance()`,
  `FixedStepClock.fixedStepSeconds()`, and fractional remainder behavior.
- `InputManager` is the sole GLFW key/cursor/focus callback owner. Its focus
  reset, latched edges, per-shortcut edge consumption, mouse consumption, and
  immutable `InputSnapshot` behavior must remain.
- `PlayerManager(Camera, PhysicsManager)`, `applyLook(MouseDelta)`, and
  `fixedUpdate(float, InputSnapshot)` remain GLFW-free and synchronous.
- `Window` keeps logical and framebuffer sizes separate and exposes pending
  framebuffer resize consumption.
- `Renderer` owns active/fallback meshes and provides guarded `clear`,
  `resizeFramebuffer`, `replaceMesh`, `render`, and idempotent `cleanup`.
- Mesh, Shader, and Texture require an explicit `MainThreadGuard`.
- `WorldLoader.load(World)` remains CPU-only and cancellable and returns
  `WorldLoadResult(float[], Vector3f)` without GPU objects.
- `Engine()` and `Engine(MainThreadGuard)` preserve transactional init,
  idempotent main-thread shutdown, existing getters, `isRunning()`,
  `submitToCore(...)`, and `getMainThreadGuard()`.
- `ShutdownCoordinator.register(...)` and reverse idempotent `close()`.
- Root `gradlew :game` remains a cross-platform alias for `:game:run`.
- Engine must not depend on game, ServiceLocator use must not expand, Java 17
  source compatibility remains, and all graphics code stays within macOS
  OpenGL 4.1 / GLSL 410.

## Diff summary

Latest standard unstaged tracked-file output:

```text
13 files changed, 627 insertions(+), 554 deletions(-)
```

This excludes the new untracked Phase 1 source, test, plan, and handoff files
until they are staged. The branch also contains two committed design commits:

```text
630e321 docs: clarify lifecycle cancellation and module init
a54d4af docs: design deterministic engine lifecycle
```

## Suggested commit and pull request

Suggested implementation commit:

```text
refactor(core): introduce deterministic engine lifecycle and game loop
```

Suggested PR title:

```text
refactor(core): introduce deterministic engine lifecycle and game loop
```

Suggested PR description:

```markdown
## Summary

- split Gaia startup into explicit bootstrap, context, loading, fixed-update,
  rendering, and reverse-shutdown phases
- add a deterministic 1/60 fixed loop with delta clamping and bounded catch-up
- unify callback input/focus handling and separate logical/framebuffer sizes
- enforce main-thread ownership for GLFW, OpenGL, and GPU resources
- move terrain and CPU meshing to a cancellable worker with main-thread upload
- add transactional Engine initialization and 29 headless lifecycle tests

## Verification

- `.\gradlew.bat clean test build --console=plain --no-daemon`
- 29 tests passed (24 engine, 5 game)
- `.\gradlew.bat :game`
- `git diff --check`
- no tracked class/bin artifacts
- no OpenGL 4.2+, compute shaders, duplicate input callbacks, sleep-wait loading,
  game-side Mesh creation, or asynchronous player update submission

## Notes

- implemented independently from PR #4
- no third-party source code or assets copied
- macOS native runtime verification remains pending CI
- Windows smoke confirmed terrain/atlas, mouse look, Escape shutdown, and no
  OpenGL thread assertion; movement/jump/focus/resize need a follow-up manual
  pass
```

Do not merge the pull request automatically.
