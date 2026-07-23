# PR #4 Salvage Report

## Scope and evidence

This report compares the fetched `origin/main` at `1f260e1` with `origin/build/add-windows-wrapper` at `2689ad5` and GitHub pull request [#4](https://github.com/TyrantProductions-svg/GaiaLegacy/pull/4). No PR commit or file was merged or cherry-picked.

At inspection time, PR #4 was open, not merged, not a draft, and reported as not mergeable. GitHub reported 8 commits, 50 changed files, 1,258 additions, and 275 deletions. The branch had diverged from current main. Its `game/src/main/java/com/gaia/GaiaMain.java` contains unresolved `<<<<<<<`, `=======`, and `>>>>>>>` merge-conflict markers, so the PR head is not a valid build candidate.

## Source concepts already present in main

The following source files or concepts appear in both the PR branch and current main. They do not justify merging PR #4:

- `GameConfig` and its nested window, player, physics, chunk, rendering, world-generation, core, and input constants;
- `ModuleManager`, `ServiceLocator`, and `TaskScheduler`;
- `Event`, `EventHandler`, and `EventBus`;
- the corresponding `Engine` integration for configuration, scheduling, events, modules, and service registration;
- current player, physics, camera, voxel, world-generation, and rendering classes, although several PR versions have divergent edits;
- the complete Gradle Wrapper, Windows launcher, Java 17 compatibility, OS/architecture-based LWJGL native selection, and macOS-only `-XstartOnFirstThread` handling.

Current main also contains six ECS source files that are absent from the PR head:

- `Component.java`
- `ComponentPool.java`
- `Entity.java`
- `EntityManager.java`
- `ParallelSystem.java`
- `System.java`

The PR branch must not replace main's source tree because doing so would remove the ECS package and regress later main fixes.

## Source that exists only in PR #4

Two Java files exist only at the PR head:

- `game/src/main/java/com/gaia/GameBootstrap.java`
- `game/src/main/java/com/gaia/GameLoop.java`

PR #4 also carries divergent edits to existing files, including `Engine`, `PlayerManager`, `TaskScheduler`, `EventBus`, `PhysicsManager`, `Camera`, voxel classes, `GaiaMain`, and `GaiaWorldGenerator`. These are deltas, not independent missing modules. They should be evaluated as design notes only because the branch is behind current main and has unresolved conflicts.

## Ideas worth reimplementing later

The following ideas are useful if independently reimplemented and tested:

- Split application composition/startup from the steady-state game loop.
- Generate terrain and mesh vertex arrays as CPU work off the main thread.
- Transfer immutable CPU mesh data back to the main thread before constructing or replacing `Mesh`.
- Delegate mouse handling to the player/input boundary rather than duplicating camera math in the composition root.
- Pump queued events at an explicit, documented point in the main loop.
- Give scheduled tasks priorities and explicit execution targets, provided the scheduler semantics are real and tested.

Some ideas already have partial equivalents in main. Phase 1 should evolve current main instead of importing the PR versions.

## Content that must not be retained

### Generated output

PR #4 adds 25 compiled `.class` files under `engine/bin/main/**`. These are build products, not source. The entire `engine/bin/` tree must remain ignored and untracked.

### Broken or platform-specific configuration

- PR `engine/build.gradle` hard-codes `natives-macos-arm64`, breaking Windows, Linux, and Intel macOS.
- PR `game/build.gradle` applies `-XstartOnFirstThread` on every platform rather than macOS only.
- The PR's wrapper/config changes are superseded by current main's complete cross-platform Wrapper and native-selection logic.
- Removing `org.gradle.java.home` from `gradle.properties` is correct, but Phase 0 applies that one-line cleanup directly rather than merging the PR.

### Unresolved or incomplete source

- `GaiaMain.java` contains unresolved merge-conflict markers.
- PR `TaskScheduler.submit(...)` returns `null` despite declaring `Future<?>`.
- `findLeastLoadedCore()` always returns `0`.
- `targetCore` is stored in each task but ignored by the dispatch loop, which still lets any dispatcher consume any task.
- The PR adds a global `ServiceLocator`/singleton-heavy composition path; future work must not expand that dependency style.

No third-party source code or assets are salvaged from PR #4.

## GameBootstrap and GameLoop decision for Phase 1

The separation is worth reimplementing in Phase 1, but the PR files are not suitable for cherry-picking.

A Phase 1 implementation should:

- keep `Engine.init()`, GLFW callbacks/polling, `Mesh` construction/upload/cleanup, rendering, buffer swaps, and engine/window teardown on the main thread that owns the OpenGL context;
- expose an immutable or tightly encapsulated explicit game context, passed by constructor rather than resolved through new global services;
- allow workers to return CPU-only world/mesh results, with a clearly owned main-thread handoff;
- replace the sleep-based startup spin loop with an interrupt-aware future or completion primitive that propagates worker failures;
- calculate delta time from a monotonic clock instead of hard-coding `1.0f / 60.0f`;
- use `try/finally` lifecycle ownership so partial startup and shutdown clean resources exactly once;
- define whether the renderer or application owns replaced meshes to eliminate double deletion;
- add focused tests for scheduler routing, startup failure, shutdown order, and CPU-data handoff without requiring a GLFW window in CI.

This recommendation preserves the public architectural idea—small composition and loop units—without preserving PR #4's conflict state, placeholder scheduler logic, global coupling, or platform regressions.
