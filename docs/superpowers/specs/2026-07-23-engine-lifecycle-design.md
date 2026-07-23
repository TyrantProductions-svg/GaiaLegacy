# Engine Lifecycle and Deterministic Game Loop Design

## Status

Approved in conversation on 2026-07-23.

## Baseline

Phase 1 starts from `main` commit `ef78647` (`Merge pull request #5 from TyrantProductions-svg/chore/repository-baseline`) on the clean branch `refactor/engine-lifecycle`.

The implementation is independent work based on current main. It does not copy or cherry-pick PR #4.

## Goal

Replace the monolithic `GaiaMain` flow with an explicit, deterministic lifecycle that all later systems can rely on:

- a minimal application entry point;
- non-blocking world loading;
- one input owner;
- a 60 Hz fixed update;
- a variable-rate render loop with long-frame protection;
- explicit main-thread and worker-thread boundaries;
- exception-safe reverse shutdown.

## Non-goals

Phase 1 does not:

- change terrain generation, texture assets, material appearance, movement speed, jump velocity, gravity, collision dimensions, or chunk radius;
- import PR #4 source;
- repair unrelated ECS, world-storage, or `TaskScheduler` routing limitations;
- add a scene framework, loading UI, render interpolation, or new gameplay;
- use OpenGL 4.3+, compute shaders, or platform-specific graphics APIs;
- expand `ServiceLocator`.

## Selected approach

Use an explicit `GameContext` and a stateful `GameLoop`.

`engine` supplies reusable lifecycle primitives, clocks, input state, window metrics, thread assertions, and renderer ownership. `game` supplies Gaia-specific dependency composition, world loading, fixed-update orchestration, and application state.

The lifecycle states are:

```text
LOADING -> RUNNING -> STOPPING
```

There is no infinite wait loop or sleep polling.

## Application entry and composition

`GaiaMain` contains only:

```java
public static void main(String[] args) {
    new GameBootstrap().run();
}
```

`GameBootstrap.run()` executes on the main thread and is the only composition root. It:

1. captures the main thread in `MainThreadGuard`;
2. creates `ShutdownCoordinator`;
3. initializes the block registry;
4. constructs and initializes `Engine`;
5. creates `InputManager` and installs the sole input callbacks;
6. creates `PhysicsManager` and `PlayerManager` with explicit dependencies;
7. creates `FrameClock` and `FixedStepClock`;
8. creates a named, lifecycle-owned world worker executor;
9. submits `WorldLoader.load(World)` as a `CompletableFuture<WorldLoadResult>`;
10. builds an immutable `GameContext`;
11. runs `GameLoop`;
12. closes all registered resources in `finally`.

No new object is registered in `ServiceLocator`.

## Component design

### `FrameClock`

Package:

```text
com.overlord.core.time
```

Constructor:

```java
FrameClock(LongSupplier nanoTime, double maxDeltaSeconds)
```

Behavior:

- the first `tick()` returns `0.0`;
- later ticks convert nanoseconds to seconds;
- negative values return `0.0`;
- values above `maxDeltaSeconds` return the maximum;
- production uses `System::nanoTime` and a maximum delta of `0.25` seconds.

### `FixedStepClock`

Package:

```text
com.overlord.core.time
```

Constructor:

```java
FixedStepClock(double fixedStepSeconds, int maxStepsPerFrame)
```

Production values:

```text
fixedStepSeconds = 1.0 / 60.0
maxStepsPerFrame = 5
```

`advance(frameDeltaSeconds)` adds time to the accumulator and returns the number of fixed steps for the current frame. It:

- preserves an accumulator smaller than one step across frames;
- returns at most five steps;
- drops excess whole steps after the cap;
- preserves the remaining fractional time smaller than one fixed step;
- never sleeps and never changes render cadence.

### `MainThreadGuard`

Package:

```text
com.overlord.core.thread
```

`captureCurrentThread()` stores the owner thread. `assertMainThread(operation)` throws `IllegalStateException` with the operation and owner/current thread names when called elsewhere.

The guard runs before every GLFW or OpenGL operation in:

- `Window`;
- `Renderer`;
- `Mesh`;
- `Shader`;
- `Texture`;
- engine shutdown paths that release GPU/window resources.

Worker tasks are never given these GPU-owning objects.

### `InputManager` and `InputSnapshot`

Package:

```text
com.overlord.core.input
```

`InputManager` is an instance owned by `GameContext`. It installs the only GLFW callbacks for:

- keys;
- cursor position;
- window focus.

The unused static `com.overlord.core.Input` class is removed so the engine cannot install a competing key callback.

It stores:

- current key-down state;
- key-press edges latched until a fixed update consumes them;
- accumulated mouse delta for one render frame;
- mouse-baseline state.

Focus loss clears key state and key edges. Focus regain resets the mouse baseline. The first cursor event after initialization or focus regain records position and returns zero delta.

Consumption is split:

- `consumeMouseDelta()` is called once per render frame;
- `consumeFixedInput()` is called only when at least one fixed update will run, so short key presses are not lost on render frames with zero fixed steps.

`InputSnapshot` is immutable and contains key-down and key-press-edge state. It has no GLFW dependency.

`PlayerManager` no longer stores a window handle, calls GLFW, calls `System.nanoTime()`, or owns mouse-baseline state. It exposes:

```java
void applyLook(MouseDelta delta)
void fixedUpdate(float fixedDeltaSeconds, InputSnapshot input)
```

Movement, jump, and physics use the fixed delta.

### `Window` and `WindowMetrics`

`Window` owns GLFW window callbacks for:

- logical window size;
- framebuffer pixel size.

`WindowMetrics` stores these independently:

```text
logicalWidth
logicalHeight
framebufferWidth
framebufferHeight
pendingFramebufferResize
```

Initial logical dimensions come from `glfwGetWindowSize`. Initial framebuffer dimensions come from `glfwGetFramebufferSize`.

Callbacks only update state. After `pollEvents()`, `GameLoop` consumes a pending framebuffer resize and asks `Renderer` to resize. Projection aspect ratio and `glViewport` always use framebuffer dimensions, so Retina scaling does not overwrite logical window size.

`Window` provides main-thread-guarded methods for:

```java
pollEvents()
swapBuffers()
shouldClose()
destroy()
```

### `Renderer` and GPU ownership

`Renderer` receives `MainThreadGuard`. Its public GPU entry points assert ownership before any OpenGL call:

```java
init(Camera camera, int framebufferWidth, int framebufferHeight)
clear()
resizeFramebuffer(int width, int height)
replaceMesh(float[] vertices)
render()
cleanup()
```

`replaceMesh` makes Renderer the sole owner of the active mesh:

1. assert main thread;
2. create/upload the new mesh;
3. install it;
4. clean the previous mesh exactly once.

`cleanup()` is idempotent and deletes the active mesh, shader, and texture exactly once. Application code never calls `Mesh.cleanup()` directly.

Loading uses `clear()` and buffer swap without drawing the fallback mesh, so no new loading UI or visible gameplay asset is introduced.

### `WorldLoader` and `WorldLoadResult`

Package:

```text
com.gaia
```

`WorldLoader.load(World)` performs the existing behavior without visual or gameplay changes:

- generate the same chunk range;
- build the same CPU mesh arrays;
- combine them in the same order;
- find the same spawn column;
- apply the same fallback spawn column when required;
- return the same spawn offset.

It returns:

```java
record WorldLoadResult(float[] meshData, Vector3f spawnPosition)
```

The worker receives `World`, configuration, and CPU-only generation/meshing code. It does not receive `Window`, `Renderer`, `Mesh`, `Shader`, `Texture`, or `MainThreadGuard`.

No player, physics, rendering, or other thread accesses the mutable World until the future completes. Future completion supplies the happens-before boundary.

### `GameContext`

Package:

```text
com.gaia
```

`GameContext` is immutable and explicitly holds the dependencies required by `GameLoop`:

- `Engine`;
- `InputManager`;
- `PlayerManager`;
- `PhysicsManager`;
- `FrameClock`;
- `FixedStepClock`;
- `CompletableFuture<WorldLoadResult>`;
- `ShutdownCoordinator`.

It does not expose public mutable fields and is not globally registered.

### `GameLoop`

Package:

```text
com.gaia
```

`GameLoop.run()` owns state transitions and the deterministic frame order.

Each frame:

```text
FrameClock.tick
Window.pollEvents
InputManager.consumeMouseDelta
close-request check
state-specific update
Renderer render or clear
Window.swapBuffers
```

In `LOADING`:

1. poll and process close input;
2. if the future is incomplete, clear and swap;
3. if the future fails, propagate the original cause;
4. if it succeeds, call `Renderer.replaceMesh` on the main thread;
5. set camera spawn and pitch to the existing values;
6. initialize the current physics spawn position;
7. transition to `RUNNING`.

In `RUNNING`:

1. apply the frame's mouse delta exactly once;
2. add clamped frame delta to `FixedStepClock`;
3. when at least one step will run, consume one fixed input snapshot;
4. execute zero through five fixed steps in this order:
   - `PlayerManager.fixedUpdate(1.0f / 60.0f, input)`;
   - `ModuleManager.updateAll(1.0f / 60.0f)`;
   - `EventBus.processAll()`;
5. process pending framebuffer resize and GPU uploads;
6. render;
7. swap buffers.

`PlayerManager.fixedUpdate` synchronously invokes `PhysicsManager`. It is never submitted once per frame to `TaskScheduler`.

The loop enters `STOPPING` when the window requests close, Escape is down, the engine stops, or loading is cancelled.

### `ShutdownCoordinator`

Package:

```text
com.overlord.core.lifecycle
```

`ShutdownCoordinator` implements `AutoCloseable`. `register(name, Runnable)` is called immediately after each successful initialization.

`close()`:

- runs actions in reverse registration order;
- runs every action at most once;
- continues after individual failures;
- throws the first failure after all actions complete;
- attaches later failures as suppressed exceptions.

`GameBootstrap` registers cancellation and worker shutdown after the future and executor are created, so they run before engine/window/GPU shutdown.

## Engine lifecycle changes

`Engine` receives or creates `MainThreadGuard` and passes it to Window and Renderer.

`Engine.init()` becomes partial-initialization safe:

- resources are first held locally;
- successfully created resources are cleaned in reverse order if a later step fails;
- fields and `running` are published only after initialization succeeds.

`Engine.shutdown()` is idempotent, main-thread guarded, and null-safe. Existing public subsystem getters and `submitToCore(...)` remain available in Phase 1.

## Error and cancellation behavior

- `GameBootstrap.run()` uses `try/finally`; all exits close the coordinator.
- A world loading exception is unwrapped and propagated with its original cause.
- Closing during loading cancels the future and interrupts/shuts down the world executor.
- `InterruptedException` restores the interrupt flag and becomes a startup failure.
- A partial initialization failure closes only resources that were successfully created.
- A shutdown failure does not skip later cleanup actions.
- Repeated close/cleanup calls do not repeat native deletion.

## Test design

Tests use JUnit Jupiter and run without a GLFW window or OpenGL context. Gradle is configured with `useJUnitPlatform()`.

The selected JUnit version, Maven coordinate, upstream source, and license are recorded in the Phase 1 handoff. No third-party source is copied into the repository.

### `FrameClockTest`

- first tick is zero;
- normal delta is preserved;
- negative delta becomes zero;
- a long delta clamps to 0.25 seconds.

### `FixedStepClockTest`

- a partial step does not update;
- partial time accumulates across frames;
- an exact step executes once;
- a long frame executes at most five steps;
- dropped whole steps do not remove the fractional remainder.

### `ShutdownCoordinatorTest`

- initialization/register order is forward and shutdown order is reverse;
- close is idempotent;
- later cleanup runs after an earlier cleanup failure;
- later failures are suppressed on the first failure.

### `MainThreadGuardTest`

- owner-thread calls pass;
- worker-thread calls fail;
- a Renderer GPU entry point invoked from a worker fails at the guard before any OpenGL call.

### `InputManagerTest`

- the first cursor event establishes a baseline;
- mouse delta is consumed once per frame;
- focus regain establishes a new baseline;
- focus loss clears keys;
- key press edges remain latched until fixed-input consumption.

### `WindowMetricsTest`

- logical dimensions update independently;
- framebuffer dimensions update independently;
- a Retina-style framebuffer resize does not change logical dimensions;
- pending framebuffer resize is consumed once.

TDD is mandatory: each production behavior is preceded by a focused test that is run and observed to fail for the expected missing behavior.

## Verification

Automated Windows verification:

```powershell
.\gradlew.bat clean test build
```

Automated macOS verification:

```bash
./gradlew clean test build
```

CI remains headless and must not start GLFW.

Developer smoke tests:

```powershell
.\gradlew.bat :game
```

```bash
./gradlew :game
```

The smoke test confirms:

- the existing terrain and texture atlas render unchanged;
- movement and jump behavior remain recognizable;
- mouse look has one path and does not jump after focus regain;
- resize updates the viewport;
- Retina framebuffer scaling uses pixel dimensions;
- close works during loading and during gameplay.

## Acceptance mapping

- `GaiaMain` only creates and starts `GameBootstrap`.
- `GameLoop` documents and implements one explicit input/update/render order.
- Physics runs synchronously at 60 Hz.
- `InputManager` is the sole GLFW input owner.
- Long frame delta clamps to 0.25 seconds and fixed catch-up caps at five steps.
- World loading is an explicit non-blocking state.
- Worker tasks produce CPU data only.
- Window and framebuffer dimensions remain distinct.
- Shutdown is reverse-order, idempotent, and exception-safe.
- Java remains source-compatible with Java 17.
- OpenGL remains compatible with macOS OpenGL 4.1 / GLSL 410.
- Terrain, materials, and gameplay constants remain unchanged.
