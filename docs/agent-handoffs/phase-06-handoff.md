# Phase 6 Handoff: Fixed-Step Physics Foundation

## Completed work

- Built Phase 6 on `refactor/physics-foundation` from `origin/main` at
  `ad02717`; the reviewed implementation HEAD is
  `95d78d0338ecec3603f726af63d0420e639bbe83`.
- Replaced the Camera-owned `PhysicsManager` path with immutable collision
  values, ordered block collision shapes, continuous swept voxel collision,
  bounded sweep-and-slide, deterministic depenetration, and one shared
  shape-aware `BlockRaycast`.
- Added validated `MassProperties`, `ForceAccumulator`, authoritative
  previous/current `PhysicsBody` state, and an insertion-ordered
  `PhysicsWorld` for simple fixed-step bodies colliding with static voxels.
- Added a dedicated `PlayerController` for gravity, terminal velocity, jump,
  ground/wall/ceiling response, one-block step-up, one-block conditional ground
  snap, spawn recovery, and collision-safe noclip.
- Migrated `PlayerManager` to fixed intent delivery, normalized Camera-relative
  horizontal input, and a deterministic 15-fixed-step double-Space noclip
  window. Catch-up steps after the first receive held-only input so one press
  edge is never replayed.
- Composed one shape resolver, `CollisionWorld`, `BlockRaycast`, player body,
  `PlayerController`, and `PhysicsWorld` explicitly in `GameBootstrap` and
  `GameContext`; no physics service was added to `ServiceLocator`.
- Kept production simulation at exactly `1.0 / 60.0` second and raised the
  catch-up limit from five to eight fixed steps, covering the required 10 FPS
  case. The running order is player, generic physics, modules, then events.
- Changed world loading to explicit player-feet coordinates, synchronized both
  authoritative body positions on spawn, required safe penetration recovery,
  and made Camera position one-way interpolated render output.
- Removed the legacy `PhysicsManager` production file and added deterministic
  10/60/144/240 FPS, high-speed tunneling, architecture, and composition
  enforcement tests.
- Engine-owner review approved the handoff with 0 Critical, 0 Important, and
  one nonblocking Minor test-depth finding: add an explicit two-block-high
  obstacle rejection regression. The reviewer found the implementation
  behavior correct.
- Game-owner review approved the handoff with 0 Critical, 0 Important, and
  0 Minor findings. Both owners approved the Phase 6 handoff.

## Incomplete work

- Windows interactive `.\gradlew.bat :game` was **NOT RUN** during Task 12.
  Movement, jump, step-up, ground snap, wall slide, ceiling response, noclip,
  cursor-release resize, Chunk-boundary movement, final visuals, and clean
  Escape shutdown still require a developer smoke test.
- Native macOS `./gradlew clean test build` was **NOT RUN** because no native
  macOS environment was available.
- Interactive macOS `./gradlew :game` was **NOT RUN** because no native macOS
  environment was available.
- The engine-owner Minor for an explicit two-block obstacle step-rejection
  regression remains deferred. Existing implementation bounds step height to
  one block and has low-ceiling/one-block traversal coverage; this is a
  coverage-depth follow-up, not a known production defect.
- Body-body collision, rotation, angular integration, constraints, joints,
  contact manifolds, islands, a full solver, and moving voxel ships remain
  deliberate Phase 11 work.
- Data-driven non-cube block collision shapes, gameplay block interaction,
  automatic world streaming, and physics/ECS integration remain outside
  Phase 6 scope.

## Core architecture decisions

- `PhysicsBody.previousPosition` and current position are the authoritative
  physics transform. `beginStep()` snapshots current to previous once per
  valid fixed update; teleport synchronizes both. Interpolation is a pure read
  and never mutates either state.
- Camera position is a one-way render output derived from interpolated player
  feet plus eye height. Physics, collision, and gameplay ray origins must use
  authoritative body state and must not read interpolated Camera position.
  Camera orientation remains the view/input direction source.
- Production physics receives exactly `1.0 / 60.0` second from `GameLoop`.
  Eight catch-up steps cover six steps at 10 FPS while retaining the existing
  0.25-second frame clamp and a bounded spiral-of-death policy.
- One injected `BlockCollisionShapeResolver` is shared by `CollisionWorld` and
  `BlockRaycast`. `PlayerController` and `PhysicsWorld` both delegate static
  voxel movement to `CollisionWorld`; no second player/body collision loop or
  voxel raycast implementation is permitted.
- Swept contact ties are deterministic: fraction, Y/X/Z axis priority,
  ascending block X/Y/Z, then declared sub-shape order. Shape lists and body
  registration preserve declared/insertion order.
- `PlayerController` owns character policies such as jump, step, snap,
  grounded state, recovery, and noclip. `PhysicsWorld` owns generic-body
  integration. The player body must not be registered in `PhysicsWorld`.
- Input press edges are delivered to only the first fixed step of a catch-up
  frame. Later steps receive `InputSnapshot.heldOnly()`.
- Player, generic physics, module, and event updates remain synchronous on the
  main fixed-update thread. Physics is CPU-only and contains no renderer,
  LWJGL, GLFW, OpenGL, Mesh, Shader, Texture, or GPU-resource work.
- `World.getBlock` remains the collision/raycast read boundary. Missing and
  out-of-height reads remain air and non-allocating; negative and
  Chunk-boundary coordinates preserve the repository's floor-based mapping.
- Phase 6 deliberately provides static-voxel collision only. Phase 11 may add
  body-body and angular behavior without replacing authoritative body state,
  shared static collision, or deterministic ownership contracts.

## Modified files

The reviewed implementation range `origin/main..95d78d0` changes these exact
49 tracked paths:

**Design and plan**

- `docs/superpowers/plans/2026-07-24-physics-foundation.md`
- `docs/superpowers/specs/2026-07-24-physics-foundation-design.md`

**Engine production**

- `engine/src/main/java/com/overlord/config/GameConfig.java`
- `engine/src/main/java/com/overlord/core/PlayerManager.java`
- `engine/src/main/java/com/overlord/core/input/InputSnapshot.java`
- `engine/src/main/java/com/overlord/core/time/FixedStepClock.java`
- `engine/src/main/java/com/overlord/physics/Aabb.java`
- `engine/src/main/java/com/overlord/physics/BlockCollisionShape.java`
- `engine/src/main/java/com/overlord/physics/BlockCollisionShapeResolver.java`
- `engine/src/main/java/com/overlord/physics/BlockRaycast.java`
- `engine/src/main/java/com/overlord/physics/BlockRaycastHit.java`
- `engine/src/main/java/com/overlord/physics/CollisionWorld.java`
- `engine/src/main/java/com/overlord/physics/ForceAccumulator.java`
- `engine/src/main/java/com/overlord/physics/MassProperties.java`
- `engine/src/main/java/com/overlord/physics/MotionResult.java`
- `engine/src/main/java/com/overlord/physics/PhysicsBody.java`
- `engine/src/main/java/com/overlord/physics/PhysicsManager.java` (deleted)
- `engine/src/main/java/com/overlord/physics/PhysicsWorld.java`
- `engine/src/main/java/com/overlord/physics/PlayerController.java`
- `engine/src/main/java/com/overlord/physics/SweepResult.java`
- `engine/src/main/java/com/overlord/renderer/Camera.java`

**Engine tests**

- `engine/src/test/java/com/overlord/core/PlayerManagerTest.java`
- `engine/src/test/java/com/overlord/core/input/InputSnapshotTest.java`
- `engine/src/test/java/com/overlord/core/time/FixedStepClockTest.java`
- `engine/src/test/java/com/overlord/physics/AabbTest.java`
- `engine/src/test/java/com/overlord/physics/BlockCollisionShapeTest.java`
- `engine/src/test/java/com/overlord/physics/BlockRaycastTest.java`
- `engine/src/test/java/com/overlord/physics/CollisionValueTest.java`
- `engine/src/test/java/com/overlord/physics/CollisionWorldMotionTest.java`
- `engine/src/test/java/com/overlord/physics/CollisionWorldSweepTest.java`
- `engine/src/test/java/com/overlord/physics/ForceAccumulatorTest.java`
- `engine/src/test/java/com/overlord/physics/MassPropertiesTest.java`
- `engine/src/test/java/com/overlord/physics/PhysicsArchitectureTest.java`
- `engine/src/test/java/com/overlord/physics/PhysicsBodyTest.java`
- `engine/src/test/java/com/overlord/physics/PhysicsDeterminismTest.java`
- `engine/src/test/java/com/overlord/physics/PhysicsWorldTest.java`
- `engine/src/test/java/com/overlord/physics/PlayerControllerCollisionTest.java`
- `engine/src/test/java/com/overlord/physics/PlayerControllerTraversalTest.java`
- `engine/src/test/java/com/overlord/renderer/CameraPositionTest.java`

**Game production and tests**

- `game/src/main/java/com/gaia/GameBootstrap.java`
- `game/src/main/java/com/gaia/GameContext.java`
- `game/src/main/java/com/gaia/GameLoop.java`
- `game/src/main/java/com/gaia/world/WorldLoadResult.java`
- `game/src/main/java/com/gaia/world/WorldLoader.java`
- `game/src/test/java/com/gaia/GaiaMainStructureTest.java`
- `game/src/test/java/com/gaia/GameBootstrapTest.java`
- `game/src/test/java/com/gaia/GameLoopStructureTest.java`
- `game/src/test/java/com/gaia/PhysicsCompositionStructureTest.java`
- `game/src/test/java/com/gaia/world/WorldLoaderTest.java`

Task 12 additionally creates
`docs/agent-handoffs/phase-06-handoff.md` and modifies
`docs/architecture/current-baseline.md`. Ignored `.superpowers/sdd` briefs and
reports are local coordination records and are not part of the tracked diff.

## Test commands and results

Fresh Windows automated verification on 2026-07-24:

```powershell
.\gradlew.bat clean test build --console=plain --no-daemon
```

- The first restricted invocation failed before project configuration because
  the sandbox denied the Gradle Wrapper 8.5 download
  (`java.net.SocketException: Permission denied: getsockopt`). This was an
  environment access failure, not a product test result.
- The approved identical rerun exited `0`: `BUILD SUCCESSFUL in 15s`;
  `16 actionable tasks: 16 executed`.
- Gradle selected `natives-windows`.
- Engine JUnit XML: 41 suites, 358 tests, 0 failures, 0 errors, 0 skipped.
- Game JUnit XML: 10 suites, 102 tests, 0 failures, 0 errors, 0 skipped.
- Total JUnit XML: 51 suites, 460 tests, 0 failures, 0 errors, 0 skipped.
- The build compiled, tested, packaged, produced distributions, and executed
  `:game:verifyPackagedResources`.

Independent packaged-resource rerun:

```powershell
.\gradlew.bat :game:verifyPackagedResources --rerun-tasks --console=plain --no-daemon
```

- Exit `0`: `BUILD SUCCESSFUL in 7s`;
  `5 actionable tasks: 5 executed`.
- Gradle selected `natives-windows`; packaged-resource verification executed.

Repository and architecture checks:

```powershell
git diff --check origin/main..HEAD
git status --short --branch
git diff --stat origin/main..HEAD
git ls-files | Select-String -Pattern '(^|/)bin(/|$)|\.class($|[^/]*$)|hs_err_pid|replay_pid'
Select-String -Path gradle.properties -Pattern 'org\.gradle\.java\.home|/Library/Java|[A-Za-z]:\\'
rg -n "PhysicsManager|Camera" engine/src/main/java/com/overlord/physics
rg -n "org\.lwjgl|glGen|glBind|glBuffer|glDelete|glDraw|new Mesh" engine/src/main/java/com/overlord/physics
rg -n "#version 4(2|3|4|5|6)|glDispatchCompute" engine/src game/src
```

- `git diff --check origin/main..HEAD`: exit `0`, no output.
- Before Task 12 documentation edits, status was
  `refactor/physics-foundation...origin/main [ahead 20]` with no path entries.
- Tracked generated-artifact and absolute-JDK-path scans had no matches.
- Physics legacy/Camera, graphics/GPU, and OpenGL 4.2+/compute scans had no
  matches; each no-match `rg` exited `1` as expected.
- Final local Task 12 documentation diff checks are recorded in the final
  phase report.

Interactive/platform status:

- Windows `.\gradlew.bat :game`: **NOT RUN**.
- macOS `./gradlew clean test build`: **NOT RUN**.
- macOS `./gradlew :game`: **NOT RUN**.

## Known risks

- The explicit two-block-high obstacle step-rejection regression requested as
  an engine-owner Minor remains absent. The one-block bound and current
  traversal behavior were reviewed as correct; this is a nonblocking coverage
  gap, not evidence of a runtime defect.
- Architecture/composition tests enforce several boundaries by scanning raw
  Java source strings. They catch the current prohibited imports,
  constructions, and ordering fragments, but formatting/refactoring can make
  them brittle and semantically equivalent violations can evade naive string
  checks. Future structural tests should retain behavioral coverage and avoid
  over-interpreting these scans as a complete dependency proof.
- Collision broad phases, overlap, and depenetration enumerate voxel ranges
  directly. Extremely large displacements or colliders can be expensive even
  though production player/body steps are bounded and correct.
- Synchronous `BlockRaycast` deliberately rejects distances above 4096 blocks
  and fails explicitly if an adjacent placement coordinate overflows the
  signed integer domain. Callers must handle those documented limits.
- The default shape resolver treats every non-air block as a full cube until
  data-driven block collision shapes are introduced.
- Full one-block step and ground snap are game-like policies that still need
  interactive feel/visual tuning. Automated tests prove bounded behavior, not
  presentation quality.
- The eight-step catch-up limit covers 10 FPS, but frames longer than roughly
  133 ms can still drop excess whole simulation time by design.
- Angular velocity and torque are reserved state only; torque is cleared but
  does not rotate bodies. Body-body collision is intentionally absent.
- Automated Windows tests do not prove GLFW/OpenGL visuals, native input,
  cursor/focus/resize behavior, clean interactive shutdown, or native macOS
  behavior.

## Interfaces the next phase must not break

- Preserve authoritative previous/current `PhysicsBody` position ownership.
  `beginStep`, teleport synchronization, defensive vector copying, and
  interpolation-as-pure-read must remain intact.
- Preserve Camera as one-way interpolated render output. Do not make Camera
  position authoritative for collision, physics, spawn, or gameplay raycast
  origins.
- Preserve production `1.0 / 60.0` physics and the eight-step catch-up limit
  required for 10 FPS unless a deliberate, tested timing decision replaces
  them. Preserve first-step press edges and held-only later catch-up input.
- Preserve the fixed running order:
  `PlayerManager.fixedUpdate`, `PhysicsWorld.step`,
  `ModuleManager.updateAll`, `EventBus.processAll`.
- Preserve one injected `BlockCollisionShapeResolver` and the shared
  `CollisionWorld` static-voxel kernel for player and generic bodies.
  `BlockRaycast` must remain the single shared shape-aware voxel raycast for
  later interaction phases.
- Preserve swept-contact ordering: fraction, Y/X/Z axis priority, ascending
  block X/Y/Z, then declared sub-shape order.
- Preserve `World.getBlock` non-allocating missing reads, floor-based negative
  coordinate behavior, Chunk-boundary correctness, byte block IDs, and ordered
  shape definitions.
- Preserve `PlayerController` as the sole player-body integrator; never also
  register its body in `PhysicsWorld`.
- Preserve validated mass/inverse-mass pairing, force/impulse accumulation,
  static/inactive/sleeping behavior, accumulator clearing, and insertion-order
  generic body stepping.
- Preserve main-thread fixed simulation and GPU-free `engine.physics`.
  Renderer, Camera-position reads, LWJGL, GLFW, OpenGL, Mesh, Shader, Texture,
  and GPU lifecycle work must not enter the physics package.
- Preserve explicit constructor/`GameContext` composition and do not expand
  `ServiceLocator`.
- Preserve Java 17, the checked-in Gradle Wrapper, OpenGL 4.1, GLSL 410,
  main/context-thread GPU ownership, Phase 3 chunk lifecycle/revision
  contracts, and the initial renderable-chunk readiness gate.
- Phase 11 may add rotation and body-body solving, but must extend rather than
  replace Phase 6 authoritative transforms, shared static collision, and
  deterministic ownership.

## Final phase report

The final branch diff at documentation commit `7d738b2` is:

```text
51 files changed, 9215 insertions(+), 317 deletions(-)
```

Engine-owner review: 0 Critical, 0 Important, 1 nonblocking Minor coverage
suggestion; approved. Game-owner review: 0 Critical, 0 Important, 0 Minor;
approved. No Critical or Important finding remains.

Suggested overall Phase 6 commit:

```text
refactor(physics): add fixed-step collision and rigid-body foundation
```

Suggested Task 12 documentation commit:

```text
docs: record Phase 6 physics foundation handoff
```

Suggested pull request title:

```text
refactor(physics): add fixed-step collision and rigid-body foundation
```

Suggested pull request description:

```markdown
**Summary**

- replace Camera-owned player collision with deterministic swept static-voxel
  collision, a dedicated player controller, and authoritative body transforms
- add shared shape-aware block raycasting plus a minimal fixed-step generic
  rigid-body foundation
- compose physics explicitly, preserve pressed input edges across catch-up,
  and render from one-way Camera interpolation
- remove the legacy PhysicsManager and enforce timing, determinism, graphics,
  and composition boundaries with headless tests

**Verification**

- `.\gradlew.bat clean test build --console=plain --no-daemon`
  - BUILD SUCCESSFUL; 16/16 actionable tasks executed
  - 460 tests in 51 suites; 0 failures, 0 errors, 0 skipped
  - LWJGL selected `natives-windows`
- `.\gradlew.bat :game:verifyPackagedResources --rerun-tasks --console=plain --no-daemon`
  - BUILD SUCCESSFUL; 5/5 actionable tasks executed
- `git diff --check origin/main..HEAD`
- generated-artifact, JDK-path, legacy/Camera, graphics/GPU, and
  OpenGL 4.2+/compute policy scans returned no matches

**Owner review**

- Engine: approved; 0 Critical, 0 Important, 1 deferred nonblocking Minor for
  explicit two-block step-rejection coverage
- Game: approved; 0 Critical, 0 Important, 0 Minor

**Manual follow-up**

- Windows interactive movement/jump/step/snap/wall/ceiling/noclip,
  cursor-resize, Chunk-boundary, visuals, and Escape shutdown smoke
- native macOS clean build and interactive smoke

**Scope**

- no body-body solver, rotation, block interaction, automatic streaming,
  copied third-party code/assets, push, or merge
```

The Task 12 documentation was prepared after the engine and game owner reviews
and is intended for the separate documentation commit listed above. No push,
pull request creation, merge, force-push, or interactive game launch was
performed.
