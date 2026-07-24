# Phase 3 Handoff: Independent Chunk Mesh Lifecycle

## Completed work

- Replaced string chunk coordinates with immutable `ChunkKey` values and made
  `ChunkRepository` the owner of loaded voxel entries, lifecycle state,
  revisions, failures, generation, mutation, snapshot capture, and unload
  transitions.
- Added immutable center/cardinal snapshot inputs and CPU-only
  `ChunkMeshData`; production meshing no longer reads mutable `World`, `Chunk`,
  or `SubChunk` state.
- Added revision-aware dirty propagation, scheduling, stale-result rejection,
  explicit retry, and neighbor invalidation for generation, edge changes, and
  unload.
- Hardened final revision handling with repository-scoped unique revision
  tokens, a pre-upload readiness check that drops stale work without charging
  the normal budget, post-upload race rejection, and revision-aware cleanup of
  obsolete failed-upload payloads.
- Added independent `ChunkRenderObject` values, a `ChunkRenderBackend`
  boundary, chunk-local model transforms, world bounds, replacement cleanup,
  empty-mesh handling, explicit unload, and idempotent manager close.
- Removed the legacy combined terrain mesh, mutable-world meshing overload,
  renderer `replaceMesh` path, renderer-owned fallback terrain mesh, and
  mutable chunk compatibility access.
- Changed world loading to return the immutable initial key set plus spawn
  position. The game schedules immutable CPU meshing on two named workers,
  processes at most two uploads per frame on the main thread, and enters
  `RUNNING` only when every initial key is `RENDERABLE`.
- Added executor shutdown barriers so world and mesh workers must be confirmed
  stopped before main-thread GPU cleanup and Engine/OpenGL teardown.
- Added focused engine and game coverage for coordinates, lifecycle
  transitions, synchronization races, snapshot ownership, boundary faces,
  scheduling, executor rejection, uploads, replacement, unload, shutdown,
  architecture boundaries, terrain layers, and loading composition.

## Incomplete work

- Phase 3 intentionally does not implement distance-driven automatic
  streaming, frustum culling, LOD, transparent sorting, block interaction UI,
  persistence, new terrain, or new visual effects.
- Windows interactive `.\gradlew.bat :game` was **NOT RUN** from this delegated
  non-interactive shell. A developer must verify unchanged terrain/materials,
  no permanent initial-set seams, movement, cursor capture/release, resize
  behavior, and clean Escape shutdown.
- Native macOS `./gradlew clean test build` and interactive
  `./gradlew :game` were **NOT RUN** because no native macOS environment was
  available. Neither is claimed to pass.
- The two remaining Task 9 Minor test-depth suggestions are deferred:
  source-structure loading-loop checks could be supplemented with injectable
  orchestration tests, and deterministic terrain snapshots could cover more
  than the sampled production column.

## Core architecture decisions

- The repository owns each chunk's mutable storage, lifecycle state, failure,
  and target mesh revision under an entry-local monitor. A repository-scoped
  `AtomicLong` issues globally unique, monotonically increasing tokens whenever
  a revision advances; no per-key unload tombstone map is retained. Reads do
  not create missing chunks.
- CPU meshing consumes owned immutable center and cardinal-neighbor snapshots.
  Entry locks are held only while copying snapshot bytes, never while building
  the mesh.
- Any center mutation or affected horizontal edge change advances the target
  revision token. Rechecks after snapshot capture, CPU completion, before
  budget accounting, immediately before upload, and after upload reject stale
  work without demoting a newer claim or leaking a replacement.
- `ChunkMeshManager` uses a configurable main-thread upload budget; production
  composition sets the required limit of two uploads per frame.
- `Renderer` implements the main-thread-guarded GPU backend. Workers reference
  only repository snapshots, the CPU mesher, and CPU completion/failure queues;
  create/upload/draw/release operations stay on the OpenGL context-owning main
  thread.
- Explicit unload transitions a key through `UNLOADING`, invalidates late
  results, releases its installed render object on the main thread, removes the
  repository entry, and dirties present cardinal neighbors. Phase 3 provides
  no automatic streaming policy.
- An empty CPU mesh still reaches `RENDERABLE` but has no GPU render object.
- The renderer no longer owns one combined terrain mesh. The manager owns one
  independently replaceable `ChunkRenderObject` per non-empty renderable key;
  replacement publishes the new object before releasing the old one.

## Modified files

The Phase 3 implementation range is `origin/main` at `11cc981` through
`66dca43`. The following groups are the exact tracked paths changed in that
range; the final documentation commit additionally adds this handoff and
updates `docs/architecture/current-baseline.md`.

### Design and plan

- `docs/superpowers/specs/2026-07-24-chunk-mesh-lifecycle-design.md`
- `docs/superpowers/plans/2026-07-24-chunk-mesh-lifecycle.md`

### Engine production

- `engine/src/main/java/com/overlord/Main.java`
- `engine/src/main/java/com/overlord/renderer/AxisAlignedBounds.java`
- `engine/src/main/java/com/overlord/renderer/ChunkGpuMesh.java`
- `engine/src/main/java/com/overlord/renderer/ChunkRenderBackend.java`
- `engine/src/main/java/com/overlord/renderer/ChunkRenderObject.java`
- `engine/src/main/java/com/overlord/renderer/Mesh.java`
- `engine/src/main/java/com/overlord/renderer/Renderer.java`
- `engine/src/main/java/com/overlord/voxel/Chunk.java`
- `engine/src/main/java/com/overlord/voxel/ChunkDirtyTracker.java`
- `engine/src/main/java/com/overlord/voxel/ChunkKey.java`
- `engine/src/main/java/com/overlord/voxel/ChunkMeshBuilder.java`
- `engine/src/main/java/com/overlord/voxel/ChunkMeshData.java`
- `engine/src/main/java/com/overlord/voxel/ChunkMeshInput.java`
- `engine/src/main/java/com/overlord/voxel/ChunkMeshManager.java`
- `engine/src/main/java/com/overlord/voxel/ChunkMesher.java`
- `engine/src/main/java/com/overlord/voxel/ChunkRepository.java`
- `engine/src/main/java/com/overlord/voxel/ChunkSnapshot.java`
- `engine/src/main/java/com/overlord/voxel/ChunkState.java`
- `engine/src/main/java/com/overlord/voxel/SubChunk.java`
- `engine/src/main/java/com/overlord/voxel/World.java`

### Engine tests

- `engine/src/test/java/com/overlord/core/thread/MainThreadGuardTest.java`
- `engine/src/test/java/com/overlord/renderer/AxisAlignedBoundsTest.java`
- `engine/src/test/java/com/overlord/renderer/ChunkRenderBackendTest.java`
- `engine/src/test/java/com/overlord/renderer/ChunkRenderObjectTest.java`
- `engine/src/test/java/com/overlord/voxel/ChunkDirtyTrackerTest.java`
- `engine/src/test/java/com/overlord/voxel/ChunkKeyTest.java`
- `engine/src/test/java/com/overlord/voxel/ChunkMeshBuilderTest.java`
- `engine/src/test/java/com/overlord/voxel/ChunkMeshDataTest.java`
- `engine/src/test/java/com/overlord/voxel/ChunkMeshLifecycleStructureTest.java`
- `engine/src/test/java/com/overlord/voxel/ChunkMeshManagerTest.java`
- `engine/src/test/java/com/overlord/voxel/ChunkRepositoryTest.java`
- `engine/src/test/java/com/overlord/voxel/ChunkSnapshotTest.java`
- `engine/src/test/java/com/overlord/voxel/WorldTest.java`

### Game production and tests

- `game/src/main/java/com/gaia/GameBootstrap.java`
- `game/src/main/java/com/gaia/GameContext.java`
- `game/src/main/java/com/gaia/GameLoop.java`
- `game/src/main/java/com/gaia/world/GaiaWorldGenerator.java`
- `game/src/main/java/com/gaia/world/WorldLoadResult.java`
- `game/src/main/java/com/gaia/world/WorldLoader.java`
- `game/src/test/java/com/gaia/GameBootstrapStructureTest.java`
- `game/src/test/java/com/gaia/GameBootstrapTest.java`
- `game/src/test/java/com/gaia/GameLoopStructureTest.java`
- `game/src/test/java/com/gaia/world/GaiaWorldGeneratorTest.java`
- `game/src/test/java/com/gaia/world/WorldLoaderTest.java`

## Test commands and results

Fresh Windows automated verification on 2026-07-24:

```powershell
.\gradlew.bat clean test build --console=plain --no-daemon
```

- Exit code: `0`.
- Result: `BUILD SUCCESSFUL in 14s`; `16 actionable tasks: 16 executed`.
- Selected natives: `natives-windows`.
- Engine JUnit XML: 25 suites, 213 tests, 0 failures, 0 errors, 0 skipped.
- Game JUnit XML: 9 suites, 96 tests, 0 failures, 0 errors, 0 skipped.
- Total JUnit XML: 34 suites, 309 tests, 0 failures, 0 errors, 0 skipped.
- The initial restricted invocation failed before configuration because the
  wrapper could not download Gradle 8.5
  (`java.net.SocketException: Permission denied: getsockopt`). This was an
  environment access failure; the exact approved rerun above is the product
  result.

```powershell
.\gradlew.bat :game:verifyPackagedResources --rerun-tasks --console=plain --no-daemon
```

- Exit code: `0`.
- Result: `BUILD SUCCESSFUL in 8s`; `5 actionable tasks: 5 executed`.
- Selected natives: `natives-windows`.
- `:game:verifyPackagedResources` executed successfully.

Repository and architecture scans:

- `git diff --check`: exit `0`, no output.
- `git status --short --branch`: exit `0`;
  `## refactor/chunk-mesh-lifecycle` before Task 11 documentation edits.
- `git diff --stat origin/main..HEAD`: exit `0`; exact implementation stat is
  reproduced in the final phase report below.
- Tracked `bin`/`.class`/`hs_err_pid`/`replay_pid` scan: no matches.
  PowerShell pipeline exit semantics were `0`.
- `gradle.properties` absolute-JDK-path scan: no matches. PowerShell
  `Select-String` exit semantics were `0`.
- Combined-mesh compatibility `rg`: no matches, exit `1`.
- The exact broad worker-GPU `rg` exited `0` only for
  `new MeshingCompletion` and `new MeshingFailure`, substring false positives
  for `new Mesh`.
- The word-bounded GPU follow-up found no worker-path GPU operation or
  `new Mesh` construction, exit `1`.
- The full word-bounded production GPU inventory found real calls only in
  `engine/src/main/java/com/overlord/renderer/{Mesh,Renderer,Shader,Texture}`.
- OpenGL 4.2+/compute dispatch `rg`: no matches, exit `1`.
- Engine-to-game dependency `rg`: no matches, exit `1`.

Interactive/platform status:

- Windows `.\gradlew.bat :game`: **NOT RUN** in the delegated non-interactive
  shell.
- macOS `./gradlew clean test build`: **NOT RUN**; no native macOS environment.
- macOS `./gradlew :game`: **NOT RUN**; no native macOS environment.

## Known risks

- A narrow race remains between the upload loop's outer readiness/budget check
  and its inner pre-backend check: a concurrent mutation in that window can
  consume one frame-budget slot without making a GPU call. Correctness,
  revision rejection, and resource ownership remain intact.
- Automatic streaming and culling remain absent by design. Callers must invoke
  explicit unload from the main thread and currently render every installed
  object.
- Real OpenGL creation, drawing, visual seams, focus/cursor behavior, resize,
  and native macOS behavior remain outside automated coverage.
- The Task 4 Minor about retaining the mutable-world meshing overload was
  deliberately deferred and is resolved: Task 10 removed that overload and
  final structure tests enforce the snapshot-only surface.
- Task 6 originally deferred explicit executor-rejection coverage. That gap is
  no longer present: final manager tests cover rejection while open and
  rejection after a reentrant close.
- The remaining Task 9 Minors are test-depth concerns, not known runtime
  defects: loading-loop behavior relies partly on source-structure assertions,
  and terrain equivalence tests sample production layers/columns rather than a
  broad deterministic snapshot.
- Final cross-owner review found no remaining Critical or Important issue. The
  three engine-owner Important findings and original unload-revision Minor were
  resolved by `66dca43`; the branch-wide whitespace Minor was resolved by
  `fcccf86`.

## Interfaces the next phase must not break

- Java 17 source/target compatibility, Gradle Wrapper use, OpenGL 4.1, and GLSL
  410.
- `engine` must remain independent of `game`; Gaia-specific generation,
  resources, and composition stay under `game`.
- Every GLFW/OpenGL and GPU resource create/upload/draw/release operation must
  stay on the captured main/context-owning thread and retain
  `MainThreadGuard` enforcement.
- `ChunkRepository` remains the sole loaded-chunk directory and state/revision
  transition owner. Missing reads remain non-allocating and mutations retain
  floor-based negative coordinate behavior.
- CPU meshing must consume immutable `ChunkMeshInput` snapshots only. Edge
  invalidation, revision checks, stale-result rejection, and one claimed task
  per target revision must remain intact.
- Empty `RENDERABLE` chunks must not allocate GPU objects. Replacement,
  explicit unload, late-result rejection, and close must release each installed
  object exactly once.
- The production upload budget remains two per frame unless a deliberate,
  tested architecture change replaces it.
- Initial loading must not render partial terrain and must enter `RUNNING` only
  after all initial keys are renderable.
- Shutdown must confirm world and mesh worker termination before manager GPU
  cleanup and Engine/OpenGL teardown.
- Preserve byte block IDs, data-driven `BlockRenderResolver` behavior, atlas
  pixels/material appearance, terrain seed/noise/height/layering, spawn
  behavior, and Phase 1 fixed-step input/update/event ordering.

## Final phase report

The exact final branch diff summary, including the Phase 3 documentation
commit, is:

```text
48 files changed, 8976 insertions(+), 328 deletions(-)
```

Suggested overall commit:

```text
refactor(voxel): add independent chunk mesh lifecycle
```

Suggested pull request title:

```text
refactor(voxel): add independent chunk mesh lifecycle
```

Suggested pull request description:

```markdown
### Summary

- make ChunkRepository own value-keyed chunk state, revisions, immutable
  snapshots, dirty propagation, and explicit unload
- mesh chunks independently on CPU workers and reject stale revision results
- upload at most two chunk meshes per frame and render/release independent GPU
  objects only on the OpenGL main thread
- compose initial per-key loading and shutdown barriers without adding
  automatic streaming or culling

### Verification

- `.\gradlew.bat clean test build --console=plain --no-daemon`
- `.\gradlew.bat :game:verifyPackagedResources --rerun-tasks --console=plain --no-daemon`
- 309 JUnit tests, 0 failures, 0 errors, 0 skipped
- repository, compatibility, GPU-boundary, GLSL/compute, and engine-to-game
  scans recorded in the Phase 3 handoff

### Manual follow-up

- Windows interactive terrain/seams/movement/cursor-resize/Escape smoke
- native macOS build and interactive smoke
```
