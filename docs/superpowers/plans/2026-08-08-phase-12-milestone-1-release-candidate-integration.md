# Phase 12 Milestone 1 Release Candidate Integration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Converge the merged Milestone 1 systems into a clean-clone, packaged, documented, and truthfully accepted Windows and Apple Silicon macOS release-candidate vertical slice without adding a new gameplay system.

**Architecture:** Preserve `GameBootstrap -> GameContext -> GameLoop` and every existing gameplay authority. Add only two Engine-owned RC defaults—VSync and mouse sensitivity—then audit the existing lifecycle, deterministic demo world, packaging, documentation, and native runtime paths. Treat native GLFW/OpenGL evidence as mandatory after the VSync change and record unavailable platform work as `NOT RUN` or `PENDING`.

**Tech Stack:** Java 17 source/target, checked-in Gradle Wrapper 8.5, JDK 21 build runtime, LWJGL 3.3.3, OpenGL 4.1, GLSL 410, JOML 1.10.5, Gson 2.10.1, JUnit Jupiter 6.1.1, Windows PowerShell, Apple Silicon macOS shell.

## Global Constraints

- Work only on `release/milestone-1-vertical-slice`, based on `origin/main@25d3a78040b08f32d6264a6a7e2a8968eed679f9`.
- Do not stage, commit, push, tag, create a pull request, or merge without later explicit user authorization.
- Execute this plan inline with `superpowers:executing-plans`; do not dispatch subagents unless the user later explicitly requests delegation.
- Preserve Java 17 compatibility and use only the checked-in Gradle Wrapper.
- Keep OpenGL 4.1 and GLSL 410 compatibility; do not add adaptive VSync or interval `-1`.
- Every GLFW/OpenGL call and GPU create/upload/destroy operation remains on the context-owning main thread.
- VSync is presentation-only and must not alter the exact `1.0 / 60.0` simulation step, catch-up cap, input-edge delivery, physics, transactions, or deterministic world generation.
- Preserve `GameBootstrap`, `GameContext`, `GameLoop`, `LogicalWorldItemService`, `BodyInventoryService`, `WorldMutationService`, `ChunkRepository`, `ChunkMeshManager`, and `PlayerController` as the unique authorities described in the approved design.
- Do not add a main menu, settings architecture, persistence, crafting, mobs, small-block editing, moving voxel assemblies, fluids, weather, PBR, dynamic shadows, networking, pooling, canonical world-item expiry, or a new audio system.
- `Gaia.mp3` and `Legacy.mp3` are future Milestone 2 audio assets only; Phase 12 does not package or play files that are not currently in the repository.
- For every newly discovered defect, use `superpowers:systematic-debugging`, reproduce it with a focused RED test, implement the smallest GREEN correction, and run the actual game immediately if the correction touches GLFW, OpenGL, shaders, uniforms, GL state, input, audio, or runtime lifecycle.
- Do not treat source scans, recording backends, Windows automation, or build success as Apple Silicon macOS runtime evidence.
- Reject generated output, logs, screenshots, saves, crash dumps, absolute local paths, IDE metadata, `.class` files, and unrelated experiments from the final inventory.

---

## Planned File Map

| File | Responsibility in Phase 12 |
|---|---|
| `engine/src/main/java/com/overlord/config/GameConfig.java` | Own `Window.VSYNC = true` and `Input.MOUSE_SENSITIVITY = 0.1f`. |
| `engine/src/main/java/com/overlord/core/Window.java` | Map the VSync boolean to interval `1` or `0` and apply it only after the GLFW context is current. |
| `engine/src/main/java/com/overlord/renderer/Camera.java` | Consume the shared mouse-sensitivity default instead of a private literal. |
| `engine/src/test/java/com/overlord/core/WindowVsyncContractTest.java` | Exercise the real context-configuration boundary to test call ordering, interval mapping, and owner-thread rejection without creating a GLFW window. |
| `engine/src/test/java/com/overlord/renderer/CameraPositionTest.java` | Lock the RC sensitivity value and prove Camera consumes the shared constant. |
| `game/src/test/java/com/gaia/world/generation/WorldGenerationConfigTest.java` | Lock seed `12345`, algorithm version `2`, radius `4`, and the resulting 81-Chunk demo area. |
| `game/src/test/java/com/gaia/GameBootstrapStructureTest.java` | Lock Survival startup, 1/60 fixed step, eight-step catch-up, unique composition, and current release-resource tasks. |
| `README.md` | Replace the stale Phase 9A status with clean-clone RC build, run, platform, and demo guidance. |
| `CONTROLS.md` | Define every Milestone 1 input and mode-dependent interaction. |
| `docs/architecture/current-baseline.md` | Describe the merged Milestone 1 ownership graph and RC defaults without stale feature-branch language. |
| `CHANGELOG.md` | Summarize the Milestone 1 vertical slice and explicitly defer Milestone 2 systems. |
| `KNOWN_ISSUES.md` | Record verified limitations and platform gaps without presenting unrun work as passing. |
| `THIRD_PARTY_NOTICES.md` | Record that the named collaborator music assets are future, unintegrated Milestone 2 inputs; do not invent source or license details. |
| `docs/agent-handoffs/phase-12-handoff.md` | Carry exact build totals, native acceptance, performance, inventory, known risks, and suggested release metadata. |

Production files outside the first three rows are read-only unless a concrete Gate 12 defect supplies a focused RED reproduction and the user keeps the correction in scope.

---

### Task 1: Lock the Existing Release Defaults

**Files:**
- Modify: `game/src/test/java/com/gaia/world/generation/WorldGenerationConfigTest.java:20-27`
- Modify: `game/src/test/java/com/gaia/GameBootstrapStructureTest.java:111-175`
- Verify: `game/src/test/java/com/gaia/ui/HudPresenterTest.java:45-72`
- Verify: `engine/src/test/java/com/overlord/core/time/FixedStepClockTest.java`

**Interfaces:**
- Consumes: `WorldGenerationConfig.visualRevisionCandidate()`, `GameMode.SURVIVAL`, `GameBootstrap.FIXED_STEP_SECONDS`, `GameBootstrap.MAX_FIXED_STEPS_PER_FRAME`, and the initial `HudPresenter` state.
- Produces: regression coverage for seed `12345L`, algorithm version `2`, Chunk radius `4`, 81 loaded Chunks, Survival startup, debug HUD disabled, exact 1/60 fixed step, and eight-step catch-up.

- [ ] **Step 1: Reverify the execution baseline before production edits**

Run:

```powershell
git fetch origin --prune
git branch --show-current
git rev-parse HEAD
git rev-parse origin/main
git rev-list --left-right --count origin/main...HEAD
git status --short --untracked-files=all
```

Expected: branch `release/milestone-1-vertical-slice`; HEAD and `origin/main` both `25d3a78040b08f32d6264a6a7e2a8968eed679f9`; divergence `0 0`; only the approved Phase 12 spec and plan are untracked. Stop before production edits if ancestry changes or any unknown file appears.

- [ ] **Step 2: Extend the existing world-configuration characterization**

Replace `approvedVisualRevisionUsesAlgorithmVersionTwo()` with:

```java
@Test
void releaseCandidateUsesTheApprovedFiniteDemoWorld() {
    WorldGenerationConfig config =
            WorldGenerationConfig.visualRevisionCandidate();

    assertEquals(12345L, config.seed());
    assertEquals(2, config.algorithmVersion());
    assertEquals(4, config.chunkRadius());
    assertEquals(81, squareChunkCount(config));
    assertEquals(1, WorldGenerationConfig.defaults().algorithmVersion());
}
```

- [ ] **Step 3: Add one bootstrap release-default structure test**

Add to `GameBootstrapStructureTest`:

```java
@Test
void composesReleaseCandidateSimulationAndModeDefaults() throws IOException {
    String source = Files.readString(
            Path.of("src/main/java/com/gaia/GameBootstrap.java"));
    String compact = source.replaceAll("\\s+", "");

    assertTrue(compact.contains(
            "FIXED_STEP_SECONDS=1.0/60.0;"));
    assertTrue(compact.contains(
            "MAX_FIXED_STEPS_PER_FRAME=8;"));
    assertTrue(compact.contains(
            "newGameModeManager(GameMode.SURVIVAL,"));
    assertEquals(1, occurrences(
            compact, "WorldGenerationConfig.visualRevisionCandidate()"));
}
```

- [ ] **Step 4: Run the characterization tests**

Run:

```powershell
.\gradlew.bat :game:test --tests com.gaia.world.generation.WorldGenerationConfigTest --tests com.gaia.GameBootstrapStructureTest --tests com.gaia.ui.HudPresenterTest --console=plain --no-daemon
.\gradlew.bat :engine:test --tests com.overlord.core.time.FixedStepClockTest --console=plain --no-daemon
```

Expected: GREEN. These tests characterize already-approved behavior; a failure is a Gate 12.0 drift finding and must be investigated before changing production.

- [ ] **Step 5: Record the unstaged checkpoint**

Run:

```powershell
git diff --check
git status --short --untracked-files=all
```

Expected: only intentional Phase 12 spec, plan, and test edits appear; nothing is staged.

---

### Task 2: Implement VSync and Shared Mouse Sensitivity with RED/GREEN

**Files:**
- Create: `engine/src/test/java/com/overlord/core/WindowVsyncContractTest.java`
- Modify: `engine/src/test/java/com/overlord/renderer/CameraPositionTest.java`
- Modify: `engine/src/main/java/com/overlord/config/GameConfig.java:5-16,49-76`
- Modify: `engine/src/main/java/com/overlord/core/Window.java:1-45,86-110`
- Modify: `engine/src/main/java/com/overlord/renderer/Camera.java:27-34`

**Interfaces:**
- Produces: `public static final boolean GameConfig.Window.VSYNC` with value `true`.
- Produces: `public static final float GameConfig.Input.MOUSE_SENSITIVITY` with value `0.1f`.
- Produces: package-private `static void Window.configureContext(MainThreadGuard, long, boolean, LongConsumer, IntConsumer)` that asserts ownership, makes the context current, and then applies only interval `1` or `0`.
- Consumes: `glfwMakeContextCurrent(long)` and `glfwSwapInterval(int)` on the existing context-owning thread.

- [ ] **Step 1: Create the focused VSync RED test**

Create `WindowVsyncContractTest.java`:

```java
package com.overlord.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.overlord.config.GameConfig;
import com.overlord.core.thread.MainThreadGuard;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class WindowVsyncContractTest {
    @Test
    void releaseCandidateMakesContextCurrentBeforeEnablingVsync() {
        List<String> calls = new ArrayList<>();

        Window.configureContext(
                MainThreadGuard.captureCurrentThread(),
                42L,
                GameConfig.Window.VSYNC,
                handle -> calls.add("current:" + handle),
                interval -> calls.add("interval:" + interval));

        assertEquals(List.of("current:42", "interval:1"), calls);
    }

    @Test
    void disabledVsyncUsesExplicitIntervalZero() {
        List<String> calls = new ArrayList<>();

        Window.configureContext(
                MainThreadGuard.captureCurrentThread(),
                7L,
                false,
                handle -> calls.add("current:" + handle),
                interval -> calls.add("interval:" + interval));

        assertEquals(List.of("current:7", "interval:0"), calls);
    }

    @Test
    void contextConfigurationRejectsWorkerBeforeBackendCalls()
            throws InterruptedException {
        MainThreadGuard guard = MainThreadGuard.captureCurrentThread();
        List<String> calls = new ArrayList<>();
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            ExecutionException failure = assertThrows(
                    ExecutionException.class,
                    () -> worker.submit(() -> Window.configureContext(
                            guard,
                            9L,
                            true,
                            handle -> calls.add("current"),
                            interval -> calls.add("interval"))).get());

            assertInstanceOf(IllegalStateException.class, failure.getCause());
            assertTrue(calls.isEmpty());
        } finally {
            worker.shutdownNow();
            assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS));
        }
    }
}
```

- [ ] **Step 2: Extend the Camera RED test**

Add the `GameConfig` import, then add:

```java
@Test
void defaultMouseMovementUsesTheReleaseCandidateSensitivity() {
    assertEquals(0.1f, GameConfig.Input.MOUSE_SENSITIVITY, 0.0f);

    Camera camera = new Camera();
    camera.processMouseMovement(10.0f, 0.0f);

    assertEquals(-89.0f, camera.getYaw(), 1.0e-6f);
}
```

- [ ] **Step 3: Run the focused tests and observe RED**

Run:

```powershell
.\gradlew.bat :engine:test --tests com.overlord.core.WindowVsyncContractTest --tests com.overlord.renderer.CameraPositionTest --console=plain --no-daemon
```

Expected: test compilation fails because `GameConfig.Window.VSYNC`, `GameConfig.Input.MOUSE_SENSITIVITY`, and `Window.configureContext(...)` do not yet exist.

- [ ] **Step 4: Add the two explicit RC defaults**

Add to the existing nested owners in `GameConfig`:

```java
public static final class Window {
    // Keep all existing fields.
    public static final boolean VSYNC = true;
}

public static final class Input {
    // Keep all existing fields.
    public static final float MOUSE_SENSITIVITY = 0.1f;
}
```

- [ ] **Step 5: Apply VSync through the testable owner-thread boundary**

In `Window.init()`, keep the current failure handling and replace the direct context call with:

```java
configureContext(
        mainThreadGuard,
        window,
        GameConfig.Window.VSYNC,
        org.lwjgl.glfw.GLFW::glfwMakeContextCurrent,
        org.lwjgl.glfw.GLFW::glfwSwapInterval);
GL.createCapabilities();
```

Add this package-private production boundary near the other Window helpers:

```java
static void configureContext(
        MainThreadGuard mainThreadGuard,
        long window,
        boolean vsync,
        LongConsumer makeContextCurrent,
        IntConsumer setSwapInterval) {
    Objects.requireNonNull(mainThreadGuard, "mainThreadGuard")
            .assertMainThread("GLFW context activation and VSync");
    Objects.requireNonNull(makeContextCurrent, "makeContextCurrent")
            .accept(window);
    Objects.requireNonNull(setSwapInterval, "setSwapInterval")
            .accept(vsync ? 1 : 0);
}
```

This executes the real policy used by production, proves current-before-interval ordering without a fake Window, and preserves the existing initialization cleanup scope.

- [ ] **Step 6: Replace the Camera literal**

Change only the constructor assignment:

```java
mouseSensitivity = GameConfig.Input.MOUSE_SENSITIVITY;
```

- [ ] **Step 7: Run focused GREEN**

Run:

```powershell
.\gradlew.bat :engine:test --tests com.overlord.core.WindowVsyncContractTest --tests com.overlord.renderer.CameraPositionTest --tests com.overlord.core.thread.MainThreadGuardTest --tests com.overlord.core.time.FixedStepClockTest --console=plain --no-daemon
```

Expected: all selected Engine tests pass; no automated test creates a GLFW window.

- [ ] **Step 8: Run the mandatory immediate Windows development smoke**

Run:

```powershell
.\gradlew.bat :game:run --console=plain --no-daemon
```

With the process running, perform exactly:

1. reach gameplay;
2. move and jump;
3. break one block;
4. place one block;
5. press Q for one item;
6. press Ctrl+Q for the full stack;
7. use Shift+right-click manual pickup;
8. press Escape and confirm clean process exit.

Expected: launch and shutdown succeed with VSync enabled and no GLFW, OpenGL, shader, uniform, GL-state, reservation, or cleanup exception. If mouse interaction cannot be driven by the implementation session, leave the process ready and label the result `USER INTERACTIVE ACTIONS PENDING`; do not infer acceptance from launch alone.

- [ ] **Step 9: Stop on any native failure**

If the smoke fails, record the exact stack and action, invoke `superpowers:systematic-debugging`, add the lowest-layer focused RED reproduction, and do not proceed to broad verification until the native path is GREEN.

- [ ] **Step 10: Record the unstaged checkpoint**

Run:

```powershell
git diff --check
git status --short --untracked-files=all
```

Expected: only Phase 12 files are modified or untracked; nothing is staged.

---

### Task 3: Gate 12A Ownership and Lifecycle Audit

**Files:**
- Read: `game/src/main/java/com/gaia/GameBootstrap.java`
- Read: `game/src/main/java/com/gaia/GameContext.java`
- Read: `game/src/main/java/com/gaia/GameLoop.java`
- Read: `game/src/main/java/com/gaia/GameLoopFrameOrchestrator.java`
- Read: `engine/src/main/java/com/overlord/core/lifecycle/ShutdownCoordinator.java`
- Read: `engine/src/main/java/com/overlord/worlditem/LogicalWorldItemService.java`
- Verify: `game/src/test/java/com/gaia/GameBootstrapTest.java`
- Verify: `game/src/test/java/com/gaia/GameBootstrapStructureTest.java`
- Verify: `game/src/test/java/com/gaia/GameLoopStructureTest.java`
- Verify: `game/src/test/java/com/gaia/RenderArchitectureTest.java`
- Verify: `engine/src/test/java/com/overlord/core/lifecycle/ShutdownCoordinatorTest.java`
- Verify: `engine/src/test/java/com/overlord/worlditem/WorldItemReservationAuditTest.java`
- Verify: `engine/src/test/java/com/overlord/worlditem/LogicalWorldItemServiceTest.java`
- Verify: `game/src/test/java/com/gaia/worlditem/PhysicalWorldItemSystemTest.java`
- Verify: `game/src/test/java/com/gaia/inventory/InventoryDropCommitBarrierTest.java`

**Interfaces:**
- Consumes: the existing production ownership graph and shutdown registrations.
- Produces: a written Gate 12A audit table for the Phase 12 handoff; no production change unless a concrete failing invariant is reproduced.

- [ ] **Step 1: Map unique construction and ownership sites**

Run:

```powershell
rg -n "new (LogicalWorldItemService|BodyInventoryService|WorldMutationService|ChunkRepository|ChunkMeshManager|PlayerController|PhysicsWorld|GameLoop|ParticleSystem)" engine/src/main game/src/main
rg -n "shutdownCoordinator\.register|registerChunkMeshes|registerWorldExecutor|engine::shutdown|::close" game/src/main/java/com/gaia/GameBootstrap.java
```

Expected: one production construction/composition authority for each mutable service, with cleanup registered after successful construction so reverse close order is safe.

- [ ] **Step 2: Trace fixed-step capture and render presentation**

Run:

```powershell
rg -n "beginFrame|consumeFixedInput|heldOnly|runFixedBatch|fixedUpdate|capture.*Frame|render" game/src/main/java/com/gaia/GameLoop.java game/src/main/java/com/gaia/GameLoopFrameOrchestrator.java
```

Expected: one frame snapshot, pressed edges delivered only to the first fixed step, held-only catch-up, immutable presentation capture before render, and no renderer-owned gameplay mutation.

- [ ] **Step 3: Run the focused lifecycle and authority suite**

Run:

```powershell
.\gradlew.bat :engine:test --tests com.overlord.core.lifecycle.ShutdownCoordinatorTest --tests com.overlord.worlditem.WorldItemReservationAuditTest --tests com.overlord.worlditem.LogicalWorldItemServiceTest --console=plain --no-daemon
.\gradlew.bat :game:test --tests com.gaia.GameBootstrapTest --tests com.gaia.GameBootstrapStructureTest --tests com.gaia.GameLoopStructureTest --tests com.gaia.RenderArchitectureTest --tests com.gaia.worlditem.PhysicalWorldItemSystemTest --tests com.gaia.inventory.InventoryDropCommitBarrierTest --console=plain --no-daemon
```

Expected: GREEN, including reverse shutdown, idempotent close, executor termination, reservation resolution, stable-ID preservation, body cleanup, and unique renderer/world authority.

- [ ] **Step 4: Perform a second launch/close cycle**

Run the development launcher twice in separate processes:

```powershell
.\gradlew.bat :game:run --console=plain --no-daemon
```

For each process, reach gameplay and exit with Escape. Expected: both processes terminate cleanly; the second start does not inherit a GLFW callback, reservation, executor, mesh, body, presentation cache, or GPU resource from the first.

- [ ] **Step 5: Write the Gate 12A evidence notes**

Prepare these exact rows for `phase-12-handoff.md`, each with `PASS`, `FAIL`, or `NOT RUN`, a command/test name, and observed evidence:

```text
Unique mutable-service owners
Fixed-step and immutable-presentation order
Reverse and idempotent shutdown
Reservation terminal/release state
GPU cleanup on context owner thread
World/mesh executor termination
Runtime body and presentation-cache cleanup
Repeated development launch and close
```

If any row is `FAIL`, stop Gate 12 and debug it; do not convert an audit finding into an untested architecture refactor.

---

### Task 4: Gate 12B Deterministic Demo World and Gameplay Loop

**Files:**
- Verify: `game/src/test/java/com/gaia/world/generation/WorldGenerationConfigTest.java`
- Verify: `game/src/test/java/com/gaia/world/generation/VisualRevisionWorldGenerationTest.java`
- Verify: `game/src/test/java/com/gaia/world/WorldLoaderTest.java`
- Verify: `game/src/test/java/com/gaia/world/SafeSpawnSelectorTest.java`
- Record later: `README.md`
- Record later: `docs/agent-handoffs/phase-12-handoff.md`

**Interfaces:**
- Consumes: seed `12345`, algorithm version `2`, radius `4`, `SafeSpawnSelector`, the F3 debug HUD, and the production gameplay controls.
- Produces: deterministic coordinate evidence and one human 20–30 minute loop record; no demo-only code branch or hard-coded spawn.

- [ ] **Step 1: Re-run deterministic world tests**

Run:

```powershell
.\gradlew.bat :game:test --tests com.gaia.world.generation.WorldGenerationConfigTest --tests com.gaia.world.generation.VisualRevisionWorldGenerationTest --tests com.gaia.world.WorldLoaderTest --tests com.gaia.world.SafeSpawnSelectorTest --console=plain --no-daemon
```

Expected: GREEN for the fixed seed/version/radius, representative features, safe spawn, deterministic generation, and 81-Chunk finite load.

- [ ] **Step 2: Verify the approved inspection coordinates in production gameplay**

Launch the game, enable F3, and record whether each coordinate remains representative:

```text
Plains tree:             (-33, 33)
Rolling-hills tree:      (0, 12)
Rocky outcrop:           (-50, 8)
Surface cave entrance A: (-22, 24, 3)
Surface cave entrance B: (-6, 23, -21)
Deep reachable cave:     (67, 2, -64)
Cross-Chunk tunnel air:  (48, 57, -45)
```

Do not teleport, write world data, or add a demo route to production. If a coordinate is no longer representative despite deterministic tests, record the observed replacement from the same seed and update documentation only after confirming it twice.

- [ ] **Step 3: Record the production safe spawn**

At the first normal post-load frame, enable F3 and record the displayed player feet X/Y/Z. Label it as the observed safe spawn for `seed=12345`, `algorithm=2`; do not hard-code it into `WorldGenerationConfig` or `GameBootstrap`.

- [ ] **Step 4: Execute the 20–30 minute gameplay loop**

Perform in one uninterrupted session:

```text
spawn -> move -> jump -> select slots 1/2/3
-> Survival break -> canonical block drop -> physical motion
-> Shift+right-click manual pickup -> placement
-> Q single-item drop -> Ctrl+Q full-stack drop
-> F4 Creative -> F4 Survival -> Escape clean exit
```

Watch for item loss/duplication, auto-pickup, stable-ID duplication, duplicate bodies/meshes, frozen queues, reservation leaks, shader/uniform failures, and non-clean shutdown. Any occurrence is a release stop.

- [ ] **Step 5: Record truthfully scoped evidence**

For every route coordinate and gameplay action, record `PASS`, `FAIL`, or `NOT RUN`, duration, platform, JVM architecture, and whether the evidence was human-observed. Do not turn automated world tests into a 20–30 minute gameplay PASS.

---

### Task 5: Gate 12C Release Documentation and Configuration Narrative

**Files:**
- Modify: `README.md`
- Create: `CONTROLS.md`
- Modify: `docs/architecture/current-baseline.md`
- Create: `CHANGELOG.md`
- Create: `KNOWN_ISSUES.md`
- Modify: `THIRD_PARTY_NOTICES.md`

**Interfaces:**
- Consumes: Tasks 1–4 verified defaults, ownership, coordinates, and native evidence.
- Produces: clean-clone and collaborator-facing release documentation with no stale Phase 9A/Phase 11 CURRENT claims.

- [ ] **Step 1: Rewrite README around the Milestone 1 RC**

Use this section order and populate it only with verified values:

```markdown
# GaiaLegacy

## Milestone 1 vertical slice
## Requirements
## Clean-clone build
## Development run
## installDist run
## Release defaults
## Demo world and route
## Controls
## Platform acceptance
## Architecture map
## Known issues and Milestone 2
## License and provenance
```

The commands must be:

```powershell
.\gradlew.bat clean test build --console=plain --no-daemon
.\gradlew.bat :game:run --console=plain --no-daemon
.\gradlew.bat :game:installDist --console=plain --no-daemon
.\game\build\install\game\bin\game.bat
```

and:

```bash
./gradlew clean test build --console=plain --no-daemon
./gradlew :game:run --console=plain --no-daemon
./gradlew :game:installDist --console=plain --no-daemon
./game/build/install/game/bin/game
```

State Java 17 source compatibility, a JDK 21-capable build runtime, OpenGL 4.1, GLSL 410, seed `12345`, algorithm `2`, finite radius `4`, 81 loaded Chunks, FOV `70.0`, sensitivity `0.1`, VSync enabled, debug HUD disabled, and Survival startup. Describe radius as finite demo loading, not streaming render distance.

- [ ] **Step 2: Create the complete controls reference**

`CONTROLS.md` must contain this exact control inventory with mode caveats derived from production:

```text
W/A/S/D              move
Space                jump
Mouse                look
Left mouse           Survival timed break / Creative interaction policy
Right mouse          place selected block
Shift + right mouse  manual world-item pickup when eligible
1 / 2 / 3            select left hand / right hand / mouth slot
Q                    drop one selected item
Ctrl + Q             drop the full selected stack
F1                   toggle cursor capture
F2                   toggle HUD
F3                   toggle debug HUD
F4                   toggle Survival / Creative
Escape               clean exit
```

Inspect `GameConfig.Input`, `InputManager`, `WorldInteractionInputRouter`, `InventoryDropInput`, and `GameModeManager` before final wording. Include any existing production noclip control only if its actual input path is confirmed; do not infer it from `NOCLIP_SPEED` alone. Keep F5–F8 explicitly labeled developer-only if documented.

- [ ] **Step 3: Update the architecture baseline**

Replace the stale feature-branch snapshot with:

```text
Phase 12 release-candidate integration on release/milestone-1-vertical-slice,
based on origin/main@25d3a78040b08f32d6264a6a7e2a8968eed679f9.
```

Add concise sections for the protected ownership graph, exact 1/60 simulation, immutable presentation, VSync-after-current-context ordering, default table, deterministic demo world, reverse shutdown, packaged resources, and native acceptance. Preserve the Phase 11 rule that canonical world-item automatic expiry is deferred.

- [ ] **Step 4: Create the changelog**

Create `CHANGELOG.md` with `## Milestone 1 release candidate - 2026-08-08` and grouped `Added`, `Changed`, `Verified`, and `Deferred` sections. Summarize existing Milestone 1 systems; do not present them as all authored in Phase 12. Include the VSync and sensitivity default reconciliation under `Changed`.

- [ ] **Step 5: Create known issues**

Create `KNOWN_ISSUES.md` with:

```markdown
# Known Issues

## Release-candidate limitations
## Platform acceptance
## Performance observations
## Deferred to Milestone 2
```

Record only reproduced limitations or explicit scope deferrals. If Apple Silicon macOS has not actually run, state `NOT RUN / PENDING`; do not call that a product defect. Include no-save persistence, no settings UI, no crafting/mobs, no automatic world-item expiry, and future audio integration as scope limitations.

- [ ] **Step 6: Update provenance without inventing collaborator metadata**

Append this scoped note to `THIRD_PARTY_NOTICES.md`:

```markdown
## Future collaborator music assets

`Gaia.mp3` and `Legacy.mp3` are named as prospective Milestone 2 audio assets.
They are not present in, packaged by, or played by the Milestone 1 release
candidate. Source, authorship, license, and distribution permission must be
recorded before either asset is introduced into the repository.
```

- [ ] **Step 7: Scan documentation for stale or unsafe claims**

Run:

```powershell
rg -n "Phase 9A candidate|feat/physical-world-items|CURRENT verification|CURRENT working-tree|macOS.*PASS|adaptive VSync|render distance" README.md CONTROLS.md CHANGELOG.md KNOWN_ISSUES.md THIRD_PARTY_NOTICES.md docs/architecture/current-baseline.md
rg -n "(?i)([a-z]:\\\\users\\\\|/users/[^/]+/|/home/[^/]+/)" README.md CONTROLS.md CHANGELOG.md KNOWN_ISSUES.md THIRD_PARTY_NOTICES.md docs
git diff --check
```

Expected: no stale CURRENT totals/inventory, no false macOS PASS, no adaptive VSync, no dynamic render-distance claim, no personal absolute path, and no whitespace errors.

---

### Task 6: Gate 12C Clean-Clone and Packaged Resource Verification

**Files:**
- Verify: `engine/build.gradle`
- Verify: `game/build.gradle`
- Verify: `game/src/test/java/com/gaia/GameBootstrapStructureTest.java`
- Verify: `game/src/test/java/com/gaia/ui/UiPackagedResourceContractTest.java`
- Verify: `engine/src/test/java/com/overlord/renderer/shader/ShaderResourceLoaderTest.java`
- Verify: `engine/src/test/java/com/overlord/renderer/feedback/OpenGlFeedbackResourceStructureTest.java`
- Verify: `game/src/test/java/com/gaia/assets/GaiaResourceLoaderTest.java`

**Interfaces:**
- Consumes: existing `verifyPackagedResources`, `verifyPackagedShaderResources`, `verifyInstalledShaderResources`, UI generation, `jar`, and `installDist` tasks.
- Produces: forced, non-up-to-date packaging evidence and an install tree that can launch without repository-source resource fallback.

- [ ] **Step 1: Run focused resource and shader tests**

Run:

```powershell
.\gradlew.bat :engine:test --tests com.overlord.renderer.shader.ShaderResourceLoaderTest --tests com.overlord.renderer.feedback.OpenGlFeedbackResourceStructureTest --console=plain --no-daemon
.\gradlew.bat :game:test --tests com.gaia.GameBootstrapStructureTest --tests com.gaia.ui.UiPackagedResourceContractTest --tests com.gaia.assets.GaiaResourceLoaderTest --console=plain --no-daemon
```

Expected: GREEN.

- [ ] **Step 2: Force every cumulative resource verification task**

Run:

```powershell
.\gradlew.bat :engine:verifyPackagedShaderResources --rerun-tasks --console=plain --no-daemon
.\gradlew.bat :game:verifyPackagedResources --rerun-tasks --console=plain --no-daemon
.\gradlew.bat :game:verifyInstalledShaderResources --rerun-tasks --console=plain --no-daemon
```

Expected: each task executes rather than reports `UP-TO-DATE`, and every required Engine shader, game asset index, atlas, feedback texture, UI atlas, and installed JAR resource is present.

- [ ] **Step 3: Inspect the install distribution inventory**

Run:

```powershell
Get-ChildItem -Recurse game/build/install/game | Select-Object FullName,Length
```

Expected: launchers and dependency JARs are present under `game/build/install/game`; no launcher embeds a personal JDK or repository path.

- [ ] **Step 4: Verify tracked-file hygiene**

Run:

```powershell
git ls-files | rg "(^|/)(build|bin)/|\.class$|hs_err|crash|screenshot|\.idea/|\.vscode/"
git ls-files --others --exclude-standard
```

Expected: the tracked-output scan returns no generated artifacts. The untracked list contains only intentional Phase 12 source/document files, never `build/` output or runtime evidence.

---

### Task 7: Gate 12D Windows and Apple Silicon Acceptance, Performance, and Soak

**Files:**
- Record later: `docs/agent-handoffs/phase-12-handoff.md`
- Record later: `KNOWN_ISSUES.md`
- Do not write runtime logs or screenshots into the repository.

**Interfaces:**
- Consumes: development launcher, installDist launcher, F1–F4 controls, debug HUD counts, `RenderMetricsConsoleReporter`, JVM GC logging, and the Gate 12B gameplay route.
- Produces: platform-specific automated, interactive, performance, and soak evidence with explicit human/agent attribution.

- [ ] **Step 1: Run the fresh Windows module suites**

Run:

```powershell
.\gradlew.bat :engine:test --console=plain --no-daemon
.\gradlew.bat :game:test --console=plain --no-daemon
.\gradlew.bat :tools:test --console=plain --no-daemon
```

Expected: all suites pass except documented intentional skips. Derive totals from the fresh JUnit XML, not Gradle task count or historical handoffs.

- [ ] **Step 2: Run the fresh Windows aggregate build**

Run:

```powershell
.\gradlew.bat clean test build --console=plain --no-daemon
```

Expected: BUILD SUCCESSFUL, with resource checks included. If the sandbox cannot write the Gradle cache before configuration, rerun the identical command with approved external execution and distinguish environment failure from product failure.

- [ ] **Step 3: Run the Windows development acceptance**

Launch:

```powershell
.\gradlew.bat :game:run --console=plain --no-daemon
```

Human-check development gameplay, window resize, DPI behavior, Alt+Tab, F1/F2/F3/F4, the complete Gate 12B loop, VSync-enabled presentation, and Escape shutdown. Record each action separately; launch alone is not interactive acceptance.

- [ ] **Step 4: Run the Windows installDist acceptance with metrics**

Build and launch:

```powershell
.\gradlew.bat :game:installDist --console=plain --no-daemon
$env:JAVA_OPTS='-Dgaia.renderMetrics=true -Xlog:gc'
.\game\build\install\game\bin\game.bat
Remove-Item Env:JAVA_OPTS
```

Run the same complete loop from the packaged launcher. Preserve console evidence outside the repository conversation/log stream only; do not redirect it into a workspace file.

- [ ] **Step 5: Record representative performance and soak observations**

During a 20–30 minute packaged session, sample at idle, traversal, block interaction, and world-item/particle activity:

```text
FPS and frameMs from RenderMetrics
visibleChunks, drawCalls, triangles, meshQueue from RenderMetrics
loaded Chunks, physics bodies, logical WorldItems, block visuals,
world-item visuals, and particles from F3
fixed-step catch-up warnings or visible stalls
JVM memory/GC collection count, pause observations, and growth trend
```

Record representative ranges and the machine/JVM context. Do not introduce pooling unless evidence attributes an unacceptable allocation source.

- [ ] **Step 6: Run Apple Silicon automation only on an actual native arm64 Mac**

On that platform, verify `java -version` and `uname -m`, then run:

```bash
./gradlew clean test build --console=plain --no-daemon
./gradlew :engine:verifyPackagedShaderResources --rerun-tasks --console=plain --no-daemon
./gradlew :game:verifyPackagedResources --rerun-tasks --console=plain --no-daemon
./gradlew :game:verifyInstalledShaderResources --rerun-tasks --console=plain --no-daemon
```

If no native Apple Silicon environment is available, do not run substitutes; record every macOS automated row as `NOT RUN / PENDING`.

- [ ] **Step 7: Run Apple Silicon interactive acceptance only on that Mac**

Run development and installDist launchers, Retina/resize, Command+Tab, function-key behavior, the full gameplay loop, and clean shutdown. If this was not human-run on Apple Silicon, record `NOT RUN / PENDING`; Windows success does not change the result.

- [ ] **Step 8: Apply release stop conditions**

Stop and report immediately on item duplication/loss, crash, non-clean shutdown, unexplained platform build failure, shader/uniform/GL-state error, GPU cleanup error, locked reservation, duplicate stable ID/body/mesh, unbounded queue, or any proposed fix that disables transaction/thread/revision/state protection.

---

### Task 8: Gate 12E Handoff and Final Unstaged Audit

**Files:**
- Create: `docs/agent-handoffs/phase-12-handoff.md`
- Finalize: `README.md`
- Finalize: `docs/architecture/current-baseline.md`
- Finalize: `CHANGELOG.md`
- Finalize: `KNOWN_ISSUES.md`

**Interfaces:**
- Consumes: exact evidence from Tasks 1–7.
- Produces: the final Phase 12 RC handoff, release/PR description text, and a clean unstaged inventory; it performs no Git publication action.

- [ ] **Step 1: Create the Phase 12 handoff structure**

Use exactly these top-level sections:

```markdown
# Phase 12 Milestone 1 Release Candidate Handoff

## Baseline and branch
## Completed work
## Unfinished work
## Protected architecture decisions
## Exact modified and untracked files
## Automated verification
## Windows development acceptance
## Windows installDist acceptance
## Apple Silicon macOS acceptance
## Deterministic demo world
## Performance and soak observations
## Known risks and issues
## Milestone 2 deferrals
## Interfaces the next phase must not break
## Suggested release metadata
```

- [ ] **Step 2: Populate exact automated totals from fresh XML**

Use PowerShell to sum `tests`, `failures`, `errors`, and `skipped` attributes beneath:

```text
engine/build/test-results/test/*.xml
game/build/test-results/test/*.xml
tools/build/test-results/test/*.xml
```

Record per-module and total results. Never retain baseline 947/825/27 figures as CURRENT unless the fresh XML independently matches them; historical values may remain only when labeled historical.

- [ ] **Step 3: Record platform status without inference**

For both Windows and Apple Silicon macOS, separate:

```text
Automated build
Development launch
Development complete gameplay loop
installDist launch
installDist complete gameplay loop
Window-system checks
Clean exit
Human observer
```

Every unperformed row is `NOT RUN / PENDING`, never omitted and never inferred from another row.

- [ ] **Step 4: Reconcile all release documents to final evidence**

Update README platform status, current-baseline status, changelog verification, and known issues so they agree exactly with the handoff. Remove stale CURRENT totals and stale CURRENT working-tree counts before deriving final values.

- [ ] **Step 5: Run final fresh verification**

Run:

```powershell
.\gradlew.bat clean test build --console=plain --no-daemon
.\gradlew.bat :engine:verifyPackagedShaderResources --rerun-tasks --console=plain --no-daemon
.\gradlew.bat :game:verifyPackagedResources --rerun-tasks --console=plain --no-daemon
.\gradlew.bat :game:verifyInstalledShaderResources --rerun-tasks --console=plain --no-daemon
git diff --check
```

Expected: all build/resource tasks pass and `git diff --check` emits no output.

- [ ] **Step 6: Perform the mandatory final file audit**

Run:

```powershell
git branch --show-current
git rev-parse HEAD
git rev-parse origin/main
git rev-list --left-right --count origin/main...HEAD
git status --short --untracked-files=all
git diff --name-status
git diff --stat
git ls-files --others --exclude-standard
```

Expected: branch remains `release/milestone-1-vertical-slice`; no generated or unrelated file appears; all changes remain unstaged. Record exact modified/untracked counts from the final status rather than copying an earlier inventory.

- [ ] **Step 7: Write release metadata without executing it**

Record these suggestions in the handoff:

```text
Suggested commit:
release: integrate milestone 1 physical sandbox vertical slice

Suggested PR title:
release: GaiaLegacy Milestone 1 vertical slice
```

The suggested PR description must summarize scope, protected authorities, exact verification, Windows/macOS truth table, demo route, performance, known issues, and Milestone 2 deferrals. Do not stage, commit, push, tag, create the PR, or merge.

---

## Execution Checkpoints

Pause for user review after:

1. Task 2 focused GREEN and the mandatory immediate Windows smoke;
2. Task 3 Gate 12A lifecycle audit;
3. Task 4 deterministic demo and 20–30 minute loop;
4. Task 6 forced package verification;
5. Task 7 platform/soak evidence;
6. Task 8 final unstaged audit.

At every checkpoint report exact commands, results, newly modified files, and any human action still required. Do not describe the RC as complete while a required Windows interactive row is pending, and do not describe Apple Silicon macOS as passing without native evidence.
