# First-Person Movement Presentation and Held-Item Polish Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add deterministic first-person walking, traversal, jump/landing presentation and correct held/shared-cube face orientation without changing gameplay authority.

**Architecture:** A game-owned fixed-step presentation controller observes immutable player motion samples and publishes previous/current engine visual snapshots interpolated at render alpha. The renderer composes movement before the existing action impulse, while the shared unit-cube vertex layout adopts the canonical world-mesh UV convention for every presentation consumer.

**Tech Stack:** Java 17, JOML matrices/vectors, JUnit 5, Gradle Wrapper, OpenGL 4.1/GLSL 410.

## Global Constraints

- Work only on `feat/physical-world-items` in the current dirty Phase 11 working tree.
- Do not stage, commit, push, create a PR, or merge.
- Do not change player physics authority, raycast authority, collision rules, transactions, inventory semantics, or world mutation.
- Advance movement presentation only from fixed 1/60 samples and interpolate immutable previous/current visuals using render alpha.
- Preserve existing break/place action impulses as a separate later composition layer.
- Keep OpenGL/GPU ownership on the main context thread and retain GLSL 410 compatibility.

---

### Task 1: Deterministic movement presentation core

**Files:**
- Create: `engine/src/main/java/com/overlord/renderer/feedback/FirstPersonMovementVisual.java`
- Create: `game/src/main/java/com/gaia/interaction/feedback/FirstPersonMovementState.java`
- Create: `game/src/main/java/com/gaia/interaction/feedback/FirstPersonMovementPresentation.java`
- Create: `game/src/test/java/com/gaia/interaction/feedback/FirstPersonMovementPresentationTest.java`

**Interfaces:**
- Consumes: `fixedUpdate(FirstPersonMovementState)` at exactly 1/60 cadence.
- Produces: `snapshot(float interpolationAlpha)`, `reset()`, and idempotent `close()`.

- [x] Write RED tests for idle identity, grounded speed-scaled bob, airborne suppression, clean idle recovery, deterministic repeatability, and midpoint render-alpha interpolation.
- [x] Run `:game:test --tests com.gaia.interaction.feedback.FirstPersonMovementPresentationTest` and verify failures are caused by the absent production types.
- [x] Implement finite immutable records plus the minimal deterministic bob accumulator and previous/current visual interpolation.
- [x] Run the focused test and verify GREEN.

### Task 2: Traversal, jump, and impact-scaled landing envelopes

**Files:**
- Modify: `game/src/test/java/com/gaia/interaction/feedback/FirstPersonMovementPresentationTest.java`
- Modify: `game/src/main/java/com/gaia/interaction/feedback/FirstPersonMovementPresentation.java`

**Interfaces:**
- Consumes: consecutive immutable samples.
- Produces: one bounded combined movement visual with mutually exclusive grounded step and airborne jump/land classification.

- [x] Add RED tests proving grounded-to-grounded bounded positive/negative deltas are steps, grounded-to-airborne positive velocity is takeoff, airborne-to-grounded is landing, landing scales with literal impact speeds and never exceeds 0.035, repeated steps remain bounded, and every envelope settles exactly to zero.
- [x] Run the focused class and verify the expected behavioral failures.
- [x] Implement step cancellation/easing, takeoff, impact-scaled landing, bounded restart, and exact-zero recovery without mutating any input sample.
- [x] Re-run the focused class and verify GREEN.

### Task 3: Fixed-loop ownership, immutable capture, and lifecycle

**Files:**
- Modify: `engine/src/main/java/com/overlord/renderer/feedback/InteractionFeedbackFrame.java`
- Modify: `game/src/main/java/com/gaia/interaction/feedback/InteractionFeedbackCoordinator.java`
- Modify: `game/src/main/java/com/gaia/GameLoop.java`
- Modify: `game/src/test/java/com/gaia/interaction/feedback/InteractionFeedbackCoordinatorTest.java`
- Modify: `game/src/test/java/com/gaia/InteractionFeedbackGameLoopTest.java`

**Interfaces:**
- `InteractionFeedbackCoordinator.fixedMovementUpdate(FirstPersonMovementState)` accepts the post-player fixed sample.
- `snapshotPhysical(..., interpolationAlpha, ...)` publishes the interpolated movement visual independently of action impulse.

- [x] Add RED integration tests for fixed sample capture, render-alpha interpolation, action/movement coexistence, lifecycle reset, idempotent close, no resurrection, and unchanged canonical body position.
- [x] Run the two focused integration test classes and verify the expected failures.
- [x] Wire the post-`PlayerManager.fixedUpdate` sample into the coordinator, add frame compatibility constructors, and close/reset the presentation owner.
- [x] Re-run both classes and verify GREEN.

### Task 4: View and held-item composition

**Files:**
- Modify: `engine/src/main/java/com/overlord/renderer/Renderer.java`
- Modify: `engine/src/main/java/com/overlord/renderer/pass/FirstPersonItemVisualPass.java`
- Modify: `engine/src/test/java/com/overlord/renderer/RendererVisualCameraImpulseTest.java`
- Modify: `engine/src/test/java/com/overlord/renderer/pass/FirstPersonItemVisualPassTest.java`

**Interfaces:**
- Renderer applies authoritative view, then `FirstPersonMovementVisual`, then existing `CameraImpulseVisual`.
- Held pass applies local anchor/base orientation, damped movement response, existing action transform, then scale/centering.

- [x] Add RED matrix tests proving identity preservation, both layers contribute without overriding, ordering is deterministic, raycast/canonical inputs are not mutated, and held motion/action both contribute.
- [x] Run the focused engine test classes and verify behavioral failures.
- [x] Implement the minimal composition helpers and approved local tuning constants.
- [x] Re-run both classes and verify GREEN.

### Task 5: Canonical six-face UV correction for every shared-cube consumer

**Files:**
- Modify: `engine/src/main/java/com/overlord/renderer/feedback/OpenGlUnitCubeMesh.java`
- Modify: `engine/src/test/java/com/overlord/renderer/feedback/OpenGlUnitCubeMeshTest.java`
- Modify: `engine/src/test/java/com/overlord/renderer/pass/FirstPersonItemVisualPassTest.java`
- Modify: `engine/src/test/java/com/overlord/renderer/pass/WorldItemVisualPassTest.java`
- Modify: `engine/src/test/java/com/overlord/renderer/pass/TransientBlockVisualPassTest.java`

**Interfaces:**
- Face indices remain `BlockFace.ordinal()` in NORTH, SOUTH, UP, DOWN, WEST, EAST order.
- Local unit-cube UVs match literal `ChunkMeshBuilder` orientation for all six faces.

- [x] Add a RED uploaded-vertex regression with literal asymmetric six-face position/UV expectations and strengthen held, dropped-item, and transient-proxy tests with six distinct regions.
- [x] Run all four focused engine test classes and verify SOUTH/DOWN/WEST/EAST orientation failures.
- [x] Correct only the shared unit-cube face vertex UV convention; do not change world mesh generation or shaders.
- [x] Re-run all four classes and verify GREEN.

### Task 6: Focused and full verification

**Files:**
- Modify: `docs/agent-handoffs/phase-11-progress.md` only if its verification inventory requires the new focused results.

**Interfaces:**
- No new gameplay interface; verification confirms presentation-only boundaries.

- [x] Run focused movement, coordinator, loop, renderer, held-item, unit-cube, dropped-item, transient-proxy, camera impulse, targeting, and traversal tests.
- [x] Run `:engine:test`, `:game:test`, `:tools:test`, and `clean test build` with `--console=plain --no-daemon`.
- [x] Force `:engine:verifyPackagedShaderResources`, `:game:verifyInstalledShaderResources`, and `:game:verifyPackagedResources` with `--rerun-tasks`.
- [x] Run `git diff --check` and `git status --short --untracked-files=all`; do not stage anything.
- [ ] Launch `:game:run --console=plain --no-daemon`, confirm the GLFW window is responding, and leave it ready for user visual acceptance without claiming human approval.
