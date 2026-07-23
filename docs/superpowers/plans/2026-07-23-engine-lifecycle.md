# Engine Lifecycle Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Introduce an explicit, deterministic engine/game lifecycle with non-blocking world loading, one input owner, a 60 Hz fixed update, main-thread graphics ownership, resize support, and reverse-order shutdown.

**Architecture:** Reusable clocks, input state, window metrics, thread assertions, and shutdown coordination live in `engine`. Gaia-specific loading, dependency composition, and loop states live in `game`. Worker threads return CPU-only `WorldLoadResult`; the main thread alone performs GLFW/OpenGL work and GPU resource lifecycle operations.

**Tech Stack:** Java 17 source/target, Gradle Wrapper 8.5, JDK 21 build runtime, LWJGL 3.3.3, JOML 1.10.5, JUnit Jupiter 6.1.1.

## Global Constraints

- Work only on `refactor/engine-lifecycle`, based on current `main` commit `ef78647`.
- Independently implement the approved design; do not copy or cherry-pick PR #4.
- Preserve terrain generation, chunk radius, texture atlas, spawn behavior, movement speed, jump, gravity, collisions, and final gameplay appearance.
- Keep Java 17 source compatibility and use the Gradle Wrapper.
- Keep OpenGL 4.1 / GLSL 410 compatibility; do not use compute shaders or OpenGL 4.3+.
- Every GLFW/OpenGL call and GPU create/upload/destroy operation must occur on the context-owning main thread.
- Do not expand `ServiceLocator`.
- Follow strict red-green-refactor: observe each focused test fail before adding its production implementation.
- Do not start GLFW in automated tests or CI.
- Leave implementation changes uncommitted for developer review; provide the suggested commit message at handoff.

---

### Task 1: Enable the JUnit Platform

**Files:**
- Modify: `engine/build.gradle`
- Modify: `game/build.gradle`

**Interfaces:**
- Consumes: Gradle's standard `Test` task.
- Produces: JUnit Jupiter discovery in both modules.

- [ ] **Step 1: Configure the engine test runtime**

Add to `engine/build.gradle`:

```groovy
dependencies {
    // Keep all existing dependencies.
    testImplementation "org.junit.jupiter:junit-jupiter:6.1.1"
    testRuntimeOnly "org.junit.platform:junit-platform-launcher"
}

test {
    useJUnitPlatform()
}
```

- [ ] **Step 2: Configure the game test runtime**

Add the same two test dependencies and `test { useJUnitPlatform() }` to `game/build.gradle`, keeping the existing engine and Gson dependencies.

- [ ] **Step 3: Verify test discovery configuration**

Run:

```powershell
.\gradlew.bat :engine:test :game:test --console=plain
```

Expected: both test tasks succeed with `NO-SOURCE`. This step changes test infrastructure only and is the allowed configuration exception before TDD production work.

### Task 2: Build deterministic clocks with TDD

**Files:**
- Create: `engine/src/test/java/com/overlord/core/time/FrameClockTest.java`
- Create: `engine/src/test/java/com/overlord/core/time/FixedStepClockTest.java`
- Create: `engine/src/main/java/com/overlord/core/time/FrameClock.java`
- Create: `engine/src/main/java/com/overlord/core/time/FixedStepClock.java`

**Interfaces:**
- Produces: `double FrameClock.tick()`.
- Produces: `int FixedStepClock.advance(double)`.
- Produces: `float FixedStepClock.fixedStepSeconds()`.
- Produces: `double FixedStepClock.remainderSeconds()`.

- [ ] **Step 1: Write failing `FrameClockTest`**

```java
package com.overlord.core.time;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;
import org.junit.jupiter.api.Test;

class FrameClockTest {
    @Test
    void firstTickIsZeroAndNormalDeltaIsPreserved() {
        long[] times = {1_000_000_000L, 1_016_000_000L};
        AtomicInteger index = new AtomicInteger();
        FrameClock clock = new FrameClock(() -> times[index.getAndIncrement()], 0.25);

        assertEquals(0.0, clock.tick(), 1.0e-9);
        assertEquals(0.016, clock.tick(), 1.0e-9);
    }

    @Test
    void negativeDeltaIsClampedToZero() {
        LongSupplier time = new LongSupplier() {
            private final long[] values = {2_000_000_000L, 1_000_000_000L};
            private int index;
            public long getAsLong() { return values[index++]; }
        };
        FrameClock clock = new FrameClock(time, 0.25);
        clock.tick();
        assertEquals(0.0, clock.tick(), 1.0e-9);
    }

    @Test
    void longFrameIsClamped() {
        long[] times = {0L, 1_000_000_000L};
        AtomicInteger index = new AtomicInteger();
        FrameClock clock = new FrameClock(() -> times[index.getAndIncrement()], 0.25);
        clock.tick();
        assertEquals(0.25, clock.tick(), 1.0e-9);
    }
}
```

- [ ] **Step 2: Verify the FrameClock red state**

Run:

```powershell
.\gradlew.bat :engine:test --tests com.overlord.core.time.FrameClockTest --console=plain
```

Expected: compilation fails because `FrameClock` does not exist.

- [ ] **Step 3: Implement `FrameClock`**

```java
package com.overlord.core.time;

import java.util.Objects;
import java.util.function.LongSupplier;

public final class FrameClock {
    private final LongSupplier nanoTime;
    private final double maxDeltaSeconds;
    private boolean initialized;
    private long previousNanos;

    public FrameClock(LongSupplier nanoTime, double maxDeltaSeconds) {
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
        if (!Double.isFinite(maxDeltaSeconds) || maxDeltaSeconds <= 0.0) {
            throw new IllegalArgumentException("maxDeltaSeconds must be finite and positive");
        }
        this.maxDeltaSeconds = maxDeltaSeconds;
    }

    public double tick() {
        long currentNanos = nanoTime.getAsLong();
        if (!initialized) {
            initialized = true;
            previousNanos = currentNanos;
            return 0.0;
        }
        long elapsedNanos = currentNanos - previousNanos;
        previousNanos = currentNanos;
        double deltaSeconds = Math.max(0.0, elapsedNanos / 1_000_000_000.0);
        return Math.min(deltaSeconds, maxDeltaSeconds);
    }
}
```

- [ ] **Step 4: Verify FrameClock green**

Run the Step 2 command. Expected: 3 tests pass.

- [ ] **Step 5: Write failing `FixedStepClockTest`**

```java
package com.overlord.core.time;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class FixedStepClockTest {
    private static final double STEP = 1.0 / 60.0;

    @Test
    void accumulatesPartialFramesAndExecutesExactStep() {
        FixedStepClock clock = new FixedStepClock(STEP, 5);
        assertEquals(0, clock.advance(STEP * 0.4));
        assertEquals(1, clock.advance(STEP * 0.6));
        assertEquals(0.0, clock.remainderSeconds(), 1.0e-9);
    }

    @Test
    void capsCatchUpAndKeepsFractionalRemainder() {
        FixedStepClock clock = new FixedStepClock(STEP, 5);
        assertEquals(5, clock.advance(STEP * 12.5));
        assertEquals(STEP * 0.5, clock.remainderSeconds(), 1.0e-9);
    }

    @Test
    void exposesFixedStepAsFloat() {
        FixedStepClock clock = new FixedStepClock(STEP, 5);
        assertEquals((float) STEP, clock.fixedStepSeconds());
    }
}
```

- [ ] **Step 6: Verify the FixedStepClock red state**

Run:

```powershell
.\gradlew.bat :engine:test --tests com.overlord.core.time.FixedStepClockTest --console=plain
```

Expected: compilation fails because `FixedStepClock` does not exist.

- [ ] **Step 7: Implement `FixedStepClock`**

```java
package com.overlord.core.time;

public final class FixedStepClock {
    private final double fixedStepSeconds;
    private final int maxStepsPerFrame;
    private double accumulator;

    public FixedStepClock(double fixedStepSeconds, int maxStepsPerFrame) {
        if (!Double.isFinite(fixedStepSeconds) || fixedStepSeconds <= 0.0) {
            throw new IllegalArgumentException("fixedStepSeconds must be finite and positive");
        }
        if (maxStepsPerFrame <= 0) {
            throw new IllegalArgumentException("maxStepsPerFrame must be positive");
        }
        this.fixedStepSeconds = fixedStepSeconds;
        this.maxStepsPerFrame = maxStepsPerFrame;
    }

    public int advance(double frameDeltaSeconds) {
        if (Double.isFinite(frameDeltaSeconds) && frameDeltaSeconds > 0.0) {
            accumulator += frameDeltaSeconds;
        }
        int availableSteps = (int) Math.floor((accumulator + 1.0e-12) / fixedStepSeconds);
        int executedSteps = Math.min(availableSteps, maxStepsPerFrame);
        accumulator -= availableSteps * fixedStepSeconds;
        if (accumulator < 0.0 && accumulator > -1.0e-9) {
            accumulator = 0.0;
        }
        return executedSteps;
    }

    public float fixedStepSeconds() {
        return (float) fixedStepSeconds;
    }

    public double remainderSeconds() {
        return accumulator;
    }
}
```

- [ ] **Step 8: Verify both clock suites**

Run:

```powershell
.\gradlew.bat :engine:test --tests "com.overlord.core.time.*" --console=plain
```

Expected: 6 tests pass.

### Task 3: Add reverse shutdown and main-thread assertions with TDD

**Files:**
- Create: `engine/src/test/java/com/overlord/core/lifecycle/ShutdownCoordinatorTest.java`
- Create: `engine/src/main/java/com/overlord/core/lifecycle/ShutdownCoordinator.java`
- Create: `engine/src/test/java/com/overlord/core/thread/MainThreadGuardTest.java`
- Create: `engine/src/main/java/com/overlord/core/thread/MainThreadGuard.java`

**Interfaces:**
- Produces: `ShutdownCoordinator.register(String, Runnable)` and idempotent `close()`.
- Produces: `MainThreadGuard.captureCurrentThread()` and `assertMainThread(String)`.

- [ ] **Step 1: Write the failing shutdown test**

Use an `ArrayList<String>` to register cleanup actions after recording `init-engine`, `init-worker`, and `init-load`. Assert final order:

```text
init-engine, init-worker, init-load, close-load, close-worker, close-engine
```

Add tests that call `close()` twice and that register three failing/recording actions; assert all run and the later failure is suppressed on the first.

- [ ] **Step 2: Verify shutdown test red**

Run:

```powershell
.\gradlew.bat :engine:test --tests com.overlord.core.lifecycle.ShutdownCoordinatorTest --console=plain
```

Expected: compilation fails because `ShutdownCoordinator` does not exist.

- [ ] **Step 3: Implement `ShutdownCoordinator`**

Use a private `ArrayDeque<Registration>`, reject registration after close, mark closed before running actions, remove actions with `removeLast()`, retain the first `RuntimeException`, add later exceptions with `addSuppressed`, and throw the first only after the deque is empty.

The exact public surface is:

```java
public final class ShutdownCoordinator implements AutoCloseable {
    public synchronized void register(String name, Runnable action);
    @Override public synchronized void close();
}
```

- [ ] **Step 4: Verify shutdown test green**

Run Step 2. Expected: all shutdown tests pass.

- [ ] **Step 5: Write the failing thread guard test**

Capture the test thread, assert an owner call passes, then use a single-thread executor to call `assertMainThread("GPU upload")`; assert the `ExecutionException` cause is `IllegalStateException` and names `GPU upload`.

- [ ] **Step 6: Verify thread guard red**

Run:

```powershell
.\gradlew.bat :engine:test --tests com.overlord.core.thread.MainThreadGuardTest --console=plain
```

Expected: compilation fails because `MainThreadGuard` does not exist.

- [ ] **Step 7: Implement `MainThreadGuard`**

```java
package com.overlord.core.thread;

import java.util.Objects;

public final class MainThreadGuard {
    private final Thread owner;

    private MainThreadGuard(Thread owner) {
        this.owner = Objects.requireNonNull(owner, "owner");
    }

    public static MainThreadGuard captureCurrentThread() {
        return new MainThreadGuard(Thread.currentThread());
    }

    public void assertMainThread(String operation) {
        Thread current = Thread.currentThread();
        if (current != owner) {
            throw new IllegalStateException(
                operation + " must run on " + owner.getName()
                    + " but ran on " + current.getName()
            );
        }
    }
}
```

- [ ] **Step 8: Verify Task 3 suites**

Run:

```powershell
.\gradlew.bat :engine:test --tests "com.overlord.core.lifecycle.*" --tests "com.overlord.core.thread.*" --console=plain
```

Expected: all lifecycle/thread tests pass.

### Task 4: Unify input and separate window metrics with TDD

**Files:**
- Delete: `engine/src/main/java/com/overlord/core/Input.java`
- Create: `engine/src/main/java/com/overlord/core/input/MouseDelta.java`
- Create: `engine/src/main/java/com/overlord/core/input/InputSnapshot.java`
- Create: `engine/src/main/java/com/overlord/core/input/InputManager.java`
- Create: `engine/src/test/java/com/overlord/core/input/InputManagerTest.java`
- Create: `engine/src/main/java/com/overlord/core/WindowMetrics.java`
- Create: `engine/src/test/java/com/overlord/core/WindowMetricsTest.java`

**Interfaces:**
- Produces: callback-backed `InputManager`.
- Produces: immutable `InputSnapshot`.
- Produces: independently tracked logical/framebuffer sizes.

- [ ] **Step 1: Write `InputManagerTest` before production classes**

Tests call package-visible callback targets directly:

```java
manager.onCursorPosition(100.0, 100.0);
assertEquals(MouseDelta.ZERO, manager.consumeMouseDelta());
manager.onCursorPosition(108.0, 94.0);
assertEquals(new MouseDelta(8.0, 6.0), manager.consumeMouseDelta());
assertEquals(MouseDelta.ZERO, manager.consumeMouseDelta());
```

Add focus-reset, focus-loss-key-clear, and latched-key-edge tests using `GLFW_PRESS` and `GLFW_RELEASE`.

- [ ] **Step 2: Verify input red**

Run:

```powershell
.\gradlew.bat :engine:test --tests com.overlord.core.input.InputManagerTest --console=plain
```

Expected: compilation fails because the input types do not exist.

- [ ] **Step 3: Implement input value types**

```java
public record MouseDelta(double x, double y) {
    public static final MouseDelta ZERO = new MouseDelta(0.0, 0.0);
}
```

`InputSnapshot` copies its down/pressed sets with `Set.copyOf` and exposes:

```java
boolean isKeyDown(int key)
boolean isKeyPressed(int key)
```

- [ ] **Step 4: Implement `InputManager`**

Use boolean arrays sized `GLFW_KEY_LAST + 1`. `install(long window)` installs exactly one key callback, cursor callback, and focus callback. Callback targets update arrays and accumulated mouse state. `consumeFixedInput()` copies down/pressed keys then clears only pressed edges. `onWindowFocus(false)` clears both arrays and resets the mouse baseline; focus regain also keeps the baseline unset.

- [ ] **Step 5: Verify input green, then remove old static Input**

Run Step 2 and expect all input tests to pass. Use `rg "com\\.overlord\\.core\\.Input|Input\\."` to confirm no consumer, then delete the obsolete class.

- [ ] **Step 6: Write failing `WindowMetricsTest`**

Construct `new WindowMetrics(1280, 720, 2560, 1440)`. Update logical size and assert framebuffer size stays 2560x1440. Update framebuffer to 3000x1800, consume one pending `FramebufferSize`, and assert the second consume is empty.

- [ ] **Step 7: Verify metrics red**

Run:

```powershell
.\gradlew.bat :engine:test --tests com.overlord.core.WindowMetricsTest --console=plain
```

Expected: compilation fails because `WindowMetrics` does not exist.

- [ ] **Step 8: Implement `WindowMetrics`**

Use a `record FramebufferSize(int width, int height)` and mutable logical/framebuffer integer fields. Reject negative dimensions. `updateFramebufferSize` sets the pending value; `consumeFramebufferResize()` returns `Optional<FramebufferSize>` and clears it.

- [ ] **Step 9: Verify Task 4 suites**

Run:

```powershell
.\gradlew.bat :engine:test --tests "com.overlord.core.input.*" --tests com.overlord.core.WindowMetricsTest --console=plain
```

Expected: all input and metrics tests pass.

### Task 5: Enforce graphics ownership and resize behavior

**Files:**
- Modify: `engine/src/main/java/com/overlord/core/Window.java`
- Modify: `engine/src/main/java/com/overlord/renderer/Renderer.java`
- Modify: `engine/src/main/java/com/overlord/renderer/Mesh.java`
- Modify: `engine/src/main/java/com/overlord/renderer/Shader.java`
- Modify: `engine/src/main/java/com/overlord/renderer/Texture.java`
- Modify: `engine/src/test/java/com/overlord/core/thread/MainThreadGuardTest.java`

**Interfaces:**
- Consumes: `MainThreadGuard`, `WindowMetrics`.
- Produces: separate logical/framebuffer sizing and guarded GPU entry points.

- [ ] **Step 1: Add a failing Renderer worker-thread test**

Construct `Renderer renderer = new Renderer(MainThreadGuard.captureCurrentThread())`, invoke `renderer.resizeFramebuffer(800, 600)` through an executor, and assert it fails with `IllegalStateException`. No OpenGL Context exists; the test proves the guard executes before `glViewport`.

- [ ] **Step 2: Verify the new test fails**

Run the thread guard test. Expected: compilation fails because the guarded Renderer constructor/resize method do not exist.

- [ ] **Step 3: Refactor `Window`**

Add a guarded constructor while retaining a default compatibility constructor:

```java
public Window() {
    this(MainThreadGuard.captureCurrentThread());
}

public Window(MainThreadGuard mainThreadGuard) { ... }
```

After context creation, query `glfwGetWindowSize` and `glfwGetFramebufferSize` through `MemoryStack`, initialize `WindowMetrics`, and install separate window/framebuffer callbacks. Add guarded `pollEvents`, `swapBuffers`, `shouldClose`, `destroy`, logical getters, framebuffer getters, and `consumeFramebufferResize`.

- [ ] **Step 4: Refactor Renderer and resource wrappers**

Add `MainThreadGuard` constructor arguments to Renderer, Mesh, Shader, and Texture. Assert before each GL call. Add:

```java
void clear()
void resizeFramebuffer(int width, int height)
void replaceMesh(float[] vertices)
```

Ignore zero-sized framebuffer resize while minimized. Rebuild projection from framebuffer aspect. Preserve GLSL `#version 410 core`.

Renderer keeps fallback mesh ownership. Replacing an active non-fallback mesh cleans the previous mesh. Cleanup deletes the active terrain mesh, fallback mesh, shader, and texture once, nulls references, and becomes idempotent.

- [ ] **Step 5: Verify worker rejection and engine compilation**

Run:

```powershell
.\gradlew.bat :engine:test :engine:compileJava --console=plain
```

Expected: worker GPU entry test passes and engine compiles.

### Task 6: Make player updates fixed and input-driven

**Files:**
- Create: `engine/src/test/java/com/overlord/core/PlayerManagerTest.java`
- Modify: `engine/src/main/java/com/overlord/core/PlayerManager.java`
- Modify: `engine/src/main/java/com/overlord/Main.java`

**Interfaces:**
- Consumes: `MouseDelta`, `InputSnapshot`, explicit fixed delta.
- Removes: window handle, GLFW polling, internal frame clock, duplicate mouse state.

- [ ] **Step 1: Write failing PlayerManager tests**

Create Camera, World, PhysicsManager, and PlayerManager without a window. Test:

- `applyLook(new MouseDelta(10, 0))` changes yaw by the existing mouse sensitivity;
- an input snapshot with forward down moves horizontal position by `MOVEMENT_SPEED * fixedDelta`;
- a pressed jump key calls fixed physics behavior once without querying GLFW.

- [ ] **Step 2: Verify PlayerManager red**

Run:

```powershell
.\gradlew.bat :engine:test --tests com.overlord.core.PlayerManagerTest --console=plain
```

Expected: compilation fails because the new constructor/methods do not exist.

- [ ] **Step 3: Refactor PlayerManager**

Use:

```java
public PlayerManager(Camera camera, PhysicsManager physicsManager)
public void applyLook(MouseDelta delta)
public void fixedUpdate(float fixedDeltaSeconds, InputSnapshot input)
```

Remove all GLFW imports, window fields, `System.nanoTime`, `firstMouse`, and `shouldClose`. Preserve movement normalization, speeds, key mappings, jump edge behavior, and synchronous `PhysicsManager.update`.

- [ ] **Step 4: Simplify engine sample Main**

Keep the sample compiling without a second input path: initialize Engine, poll Window, render, swap, and close in `finally`. Do not create PlayerManager in the engine sample.

- [ ] **Step 5: Verify PlayerManager and module tests**

Run:

```powershell
.\gradlew.bat :engine:test :engine:compileJava --console=plain
```

Expected: PlayerManager tests pass; no `glfwGetKey` or `System.nanoTime` remains in PlayerManager.

### Task 7: Move existing world startup into a CPU-only loader

**Files:**
- Create: `game/src/main/java/com/gaia/world/WorldLoadResult.java`
- Create: `game/src/main/java/com/gaia/world/WorldLoader.java`
- Create: `game/src/test/java/com/gaia/world/WorldLoaderTest.java`

**Interfaces:**
- Consumes: current World, GaiaWorldGenerator, ChunkMeshBuilder, BlockRegistry constants.
- Produces: `WorldLoadResult(float[] meshData, Vector3f spawnPosition)`.

- [ ] **Step 1: Write failing WorldLoader test**

Run `WorldLoader.load(new World())` inside a named single-thread executor. Assert:

- worker thread differs from the test thread;
- mesh data is non-empty and divisible by five floats per vertex;
- spawn x/z are `0.5`;
- the block below spawn is non-air;
- the result contains no Renderer/Mesh/GPU object.

Add a cancellation test that enters the loader with the worker thread already interrupted and asserts `CancellationException`.

- [ ] **Step 2: Verify world loader red**

Run:

```powershell
.\gradlew.bat :game:test --tests com.gaia.world.WorldLoaderTest --console=plain
```

Expected: compilation fails because loader/result types do not exist.

- [ ] **Step 3: Implement `WorldLoadResult`**

```java
package com.gaia.world;

import java.util.Objects;
import org.joml.Vector3f;

public record WorldLoadResult(float[] meshData, Vector3f spawnPosition) {
    public WorldLoadResult {
        meshData = Objects.requireNonNull(meshData, "meshData");
        spawnPosition = new Vector3f(Objects.requireNonNull(spawnPosition, "spawnPosition"));
    }
}
```

- [ ] **Step 4: Implement `WorldLoader` by extracting current behavior**

Use radius 2, the current nested chunk order, current mesh combine order, highest-block scan from 255 to 0, the same fallback column, and `GameConfig.Player.HEIGHT` for spawn offset. Check interruption before each generation and mesh-build iteration and throw `CancellationException`. Do not reference LWJGL, Window, Renderer, Mesh, Shader, or Texture.

- [ ] **Step 5: Verify world loader green**

Run Step 2. Expected: test passes without GLFW initialization.

### Task 8: Make Engine initialization and shutdown exception-safe

**Files:**
- Modify: `engine/src/main/java/com/overlord/core/Engine.java`

**Interfaces:**
- Consumes: `MainThreadGuard`, guarded Window/Renderer.
- Preserves: current Engine getters and `submitToCore(...)`.

- [ ] **Step 1: Refactor Engine construction**

Keep `Engine()` and add:

```java
public Engine(MainThreadGuard mainThreadGuard)
public MainThreadGuard getMainThreadGuard()
```

The default constructor captures the current thread.

- [ ] **Step 2: Make `init()` transactional**

Construct Window, Camera, Renderer, and World into locals. Initialize Renderer with framebuffer width/height. Register services and publish fields only after success. If a later operation fails, clean initialized Renderer then Window on the owner thread and rethrow.

- [ ] **Step 3: Make `shutdown()` idempotent**

Use an atomic closed guard, stop modules/scheduler, clean Renderer, destroy Window, and clear ServiceLocator/EventBus once. Null-check partial fields and assert main thread before native cleanup.

- [ ] **Step 4: Verify Engine compilation and all engine tests**

Run:

```powershell
.\gradlew.bat :engine:test :engine:build --console=plain
```

Expected: all engine tests pass and the Java 17 engine artifact builds.

### Task 9: Compose loading and deterministic running states

**Files:**
- Create: `game/src/main/java/com/gaia/GameContext.java`
- Create: `game/src/main/java/com/gaia/GameLoop.java`
- Create: `game/src/main/java/com/gaia/GameBootstrap.java`
- Modify: `game/src/main/java/com/gaia/GaiaMain.java`
- Create: `game/src/test/java/com/gaia/GaiaMainStructureTest.java`

**Interfaces:**
- Consumes: all earlier Task interfaces.
- Produces: `LOADING -> RUNNING -> STOPPING`.

- [ ] **Step 1: Write a failing entry-point structure test**

Read `game/src/main/java/com/gaia/GaiaMain.java` and assert its body contains `new GameBootstrap().run()` and does not contain imports/references to Engine, World, Mesh, GLFW, PhysicsManager, or PlayerManager.

This source-structure test directly protects the acceptance criterion that GaiaMain remains a minimal entry point.

- [ ] **Step 2: Verify entry-point test red**

Run:

```powershell
.\gradlew.bat :game:test --tests com.gaia.GaiaMainStructureTest --console=plain
```

Expected: test fails because current GaiaMain still contains the monolithic lifecycle.

- [ ] **Step 3: Create immutable `GameContext`**

Create a record with non-null validation for:

```java
Engine engine
InputManager inputManager
PlayerManager playerManager
PhysicsManager physicsManager
FrameClock frameClock
FixedStepClock fixedStepClock
CompletableFuture<WorldLoadResult> worldLoad
ShutdownCoordinator shutdownCoordinator
```

- [ ] **Step 4: Implement `GameLoop`**

Use a private enum `LOADING`, `RUNNING`, `STOPPING`. Per frame:

1. get clamped delta;
2. `window.pollEvents()`;
3. consume mouse delta;
4. stop on window close, Escape, or stopped engine;
5. apply pending framebuffer resize;
6. in loading, inspect the Future without sleep;
7. on successful load, call `renderer.replaceMesh`, set current spawn/pitch, initialize physics, transition to running;
8. in running, apply look once, advance fixed clock, consume fixed input only if steps > 0, synchronously update PlayerManager/ModuleManager/EventBus for each step;
9. clear while loading or render while running;
10. swap buffers.

Unwrap `CompletionException`; rethrow a RuntimeException cause directly or wrap a checked cause with `"World loading failed"`.

- [ ] **Step 5: Implement `GameBootstrap`**

Capture the main thread, create ShutdownCoordinator, initialize blocks and Engine, install InputManager, create physics/player, call `ModuleManager.getInstance().initAll()`, create clocks, create a named one-thread world executor, submit `CompletableFuture.supplyAsync`, register cancellation and executor shutdown after creation, create context, and run GameLoop in `try/finally`.

Executor shutdown performs `shutdownNow()`, waits up to five seconds, restores interruption, and throws `IllegalStateException` on interruption.

- [ ] **Step 6: Reduce GaiaMain**

Replace it with:

```java
package com.gaia;

public final class GaiaMain {
    private GaiaMain() {
    }

    public static void main(String[] args) {
        new GameBootstrap().run();
    }
}
```

- [ ] **Step 7: Verify game tests and static ownership**

Run:

```powershell
.\gradlew.bat :game:test :game:build --console=plain
rg -n "glfwGetKey|System\\.nanoTime|Thread\\.sleep|new Mesh" game/src/main/java engine/src/main/java/com/overlord/core/PlayerManager.java
```

Expected:

- game tests/build pass;
- no sleep loop;
- no GLFW polling or internal clock in PlayerManager;
- no Mesh creation in game code;
- only FrameClock uses `System.nanoTime` through its injected production supplier.

### Task 10: Full verification, manual smoke test, and handoff

**Files:**
- Create: `docs/agent-handoffs/phase-01-handoff.md`

**Interfaces:**
- Produces: Phase 1 evidence and constraints for Phase 2.

- [ ] **Step 1: Run all automated verification**

```powershell
.\gradlew.bat clean test build --console=plain --no-daemon
```

Expected: build succeeds with all new tests passing and no GLFW window.

- [ ] **Step 2: Run policy and architecture checks**

Run:

```powershell
git diff --check
git status --short --branch
git ls-files | Select-String -Pattern '(^|/)bin(/|$)|\.class($|[^/]*$)'
rg -n "#version 4(2|3|4|5|6)|glDispatchCompute" engine/src game/src
rg -n "glfwGetKey|glfwSetCursorPosCallback|glfwSetKeyCallback|glfwSetWindowFocusCallback" engine/src/main game/src/main
```

Expected:

- no whitespace errors or tracked compiled artifacts;
- no OpenGL 4.2+ shader or compute use;
- input callbacks exist only in InputManager;
- PlayerManager and game lifecycle contain no direct GLFW key polling.

- [ ] **Step 3: Perform the interactive Windows smoke test**

Run:

```powershell
.\gradlew.bat :game
```

Verify:

- loading window remains responsive and clears without a new UI;
- existing terrain/atlas render;
- movement, jump, mouse look, focus regain, resize, and close work;
- no OpenGL thread assertion fires.

Stop the game normally after verification. If the environment cannot perform interactive control, record the test as not run rather than claiming success.

- [ ] **Step 4: Write Phase 1 handoff**

Include:

- completed and unfinished work;
- architecture decisions and exact thread ownership;
- modified files;
- test commands/results, including test count;
- JUnit `org.junit.jupiter:junit-jupiter:6.1.1`, upstream `https://github.com/junit-team/junit-framework`, EPL-2.0 license, and the two Gradle files that introduce it;
- known risks;
- interfaces Phase 2 must not break;
- raw `git diff --stat`;
- suggested commit `refactor(core): introduce deterministic engine lifecycle and game loop`;
- suggested PR title and description.

- [ ] **Step 5: Final review checkpoint**

Re-read the approved design and map every acceptance criterion to code/tests/handoff evidence. Leave the branch uncommitted and unpushed for developer review. Do not merge a PR.
