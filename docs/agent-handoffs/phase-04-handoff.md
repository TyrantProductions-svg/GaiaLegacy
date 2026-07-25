# Phase 4 Handoff: Deterministic World-Generation Pipeline

Branch: `feat/worldgen-pipeline`

Base: `origin/main` at
`f1ca80beb47616025bea21615d7e3fccaa5b31c6`

Reviewed implementation and architecture-guard HEAD:
`349c81c627e9492fae5a60322228c0a81edd77cb`

## Completed work

- Added repository generation transactions with immutable
  `ChunkGenerationData`, repository-owned `ChunkGenerationTicket` values,
  `INITIAL` and revision-guarded `REBUILD` modes, terminal result values, and
  queryable generation status/failure.
- Made each successful commit a per-Chunk atomic publication. INITIAL
  publishes a complete new Chunk; REBUILD atomically replaces the CPU Chunk
  only when its captured base revision remains current.
- Kept Phase 3 revision, dirty propagation, neighbor-edge invalidation, and
  stale mesh/upload rejection as the only mesh invalidation authority.
- Added an immutable deterministic seed/version/config contract, a stateless
  world-coordinate sampler, a bounded detached `GenerationRegion`, immutable
  Stage/result values, and canonical generation data.
- Replaced the legacy monolithic generator with a pure CPU Pipeline of exactly
  six ordered Providers: biome, height, strata/density, cave, surface, and
  decoration.
- Added a default finite load range of 81 Chunks, inclusive X/Z `[-4, 4]`, in
  ascending X-then-Z order.
- Added explicit `IDLE`, `RUNNING`, `SUCCEEDED`, `FAILED`, and `CANCELLED`
  loader states plus structured failure data that retains completed keys,
  optional failed key/Stage, stable code, and exact cause.
- Added deterministic safe-spawn selection over committed Chunks. It requires
  non-air support plus empty feet/head cells and never manufactures fallback
  blocks.
- Added a programmatic debug rebuild lifecycle that sorts keys, continues
  across independent failures, and reports exact repository `COMMITTED`,
  `FAILED`, and `CONFLICT` outcomes.
- Added canonical SHA-256 Chunk/region hashing, scheduling-order determinism
  checks, boundary/provider checks, locked snapshots, source architecture
  guards, and an intentional snapshot-update protocol.
- Made the loader's successful generation hash use the canonical
  `WorldGenerationHasher.hashRegion` contract and added a production-default
  end-to-end test over all 81 committed repository Chunks and safe spawn.
- Injected one world-generation executor into `WorldLoader`. Public
  `loadAsync` and `rebuildRegionAsync` use that executor; `GameBootstrap` owns,
  registers, and shuts the single dedicated executor without nested
  submission or per-call pools.
- Made biome softmax normalization use `StrictMath.exp`. Version-1 block bytes,
  representative hashes, and the aggregate hash remained unchanged, so the
  algorithm version remains `1`.
- Made `StagedWorldGenerator` rethrow `CancellationException`, allowing the
  loader to enter `CANCELLED`, terminally fail the live ticket, publish no
  partial Chunk, and skip every later Stage.
- Preserved Phase 7 gameplay mutation exclusion: generation never uses
  `WorldMutationService`, block-change events, inventory, world items, or
  live-world block mutation.
- Updated the current architecture baseline and recorded this handoff without
  changing production or test code in the documentation task.

## Deterministic snapshot and manual coordinates

- Fixed test seed: `12345L`.
- Algorithm version: `1`.
- Default region: 81 Chunks, X/Z `[-4, 4]`.
- Canonical configuration SHA-256:
  `e1baa7ef6028c6615ac34e42be19dfdc590d0740321608ab8b0ca6dc3d250ada`.
- Aggregate region SHA-256:
  `161f6c10773c8dfd84e6961183e8706d5a0ec00750e727e83c4a08afcfbd5ce8`.
- Plains: block `(29, -45)`, Chunk `(1, -3)`.
- Rolling hills/origin: block `(0, 0)`, Chunk `(0, 0)`.
- Rocky highlands: block `(0, 44)`, Chunk `(0, 2)`.
- Cave and positive Chunk boundary: block `(16, 2, 0)`, Chunk `(1, 0)`.
- Negative-coordinate coverage: block/Chunk `(-1, -1)`.

The normative byte layout, representative Chunk hashes, reproduction commands,
manual checklist, and intentional-update rules are in
`docs/architecture/phase-04-deterministic-snapshots.md`.

## Unfinished work

- Final branch-wide Engine-owner and Game/shared-owner reviews are pending the
  root orchestrator. This handoff records task-level reviews and a
  documentation audit only; it does not claim final cross-owner approval.
- The post-Game-owner-fix clean test/build and standalone packaged-resource
  verification both passed. A root-orchestrated final owner re-review remains
  pending.
- Task 9's proposed new documentation-source assertions and their explicit
  RED run were not added in this documentation-only delegation because
  production and test code were out of scope. The existing Task 8 architecture
  guards remain unchanged.
- Windows interactive `.\gradlew.bat :game` was **NOT RUN** during Phase 4.
  Plains, rolling hills, rocky highlands, caves, boundary continuity, spawn,
  debug rebuild, resize/input, and Escape shutdown were not observed in an
  interactive session.
- Native macOS `./gradlew clean test build` and interactive `./gradlew :game`
  were **NOT RUN** because no native macOS environment was available.
- The debug rebuild is a programmatic API only. There is no key binding,
  console command, UI, save/persistence integration, or automatic streaming.
- Cross-Chunk surface slope uses only data present in the current detached
  region; Chunk-edge columns use the available one-sided slope.
- Automatic streaming, frustum culling, LOD, transparent sorting, persistence,
  and new Phase 8/9 gameplay remain outside Phase 4 scope.

## Core architecture decisions

### Engine-owned generation publication

- `ChunkRepository` remains the sole loaded-Chunk directory and the only owner
  of lifecycle, revisions, dirty propagation, generation publication, and
  unload interaction.
- A live ticket is owned by the exact issuing repository and key incarnation.
  Fabricated, stale, reused, cross-repository, unloaded, or revision-conflicted
  tickets cannot publish.
- INITIAL failure publishes no entry. REBUILD failure preserves the old CPU
  Chunk, revision, lifecycle, and installed render object.
- REBUILD commit compares complete horizontal edges and dirties only actually
  loaded neighbors whose corresponding edge changed. Missing neighbors are not
  allocated.
- Generation/unload cleanup uses attempt identity to avoid ABA removal of a
  replacement incarnation. The established attempt-monitor/entry-monitor order
  must remain acyclic.

### Deterministic CPU Pipeline

- `WorldGenerationConfig` exposes every seed/version/tuning input through one
  canonical, locale-independent fingerprint string.
- `DeterministicCoordinateSampler` derives samples from immutable seed,
  algorithm version, Stage ID, absolute coordinates, and salt. Results do not
  depend on call order, collection order, worker count, or worker completion
  order.
- `GenerationRegion` owns exactly one bounded detached Chunk. Providers write
  only that CPU buffer; `freeze()` creates defensive canonical
  `ChunkGenerationData`.
- `StagedWorldGenerator` runs the six Providers in declaration order and stops
  on the first returned failure, thrown `RuntimeException`, or thrown `Error`.
  Partial data is never returned or committed.
- Hashes are configuration-specific content identities, not save-file formats.
  Region hashing sorts by Chunk X then Z and rejects duplicate keys.

### Loader, spawn, and rebuild

- Initial load is fail-fast. Already committed Chunks remain available for
  diagnosis, and every started load exits `RUNNING` through a terminal state.
- Loader success includes the immutable initial keys, safe player-feet
  coordinates, configuration fingerprint, and aggregate generation hash.
- Safe spawn searches only committed keys and uses squared distance, X, Z,
  then feet Y as its deterministic order. Effective clearance is at least feet
  plus head even if configuration requests only one empty block.
- Debug rebuild is outcome-oriented and continues through independent keys.
  Genuine repository conflicts remain conflicts and are not rewritten as
  failures.
- Public initial load and debug rebuild are asynchronous and use the same
  injected world-generation executor. Their synchronous orchestration is not
  exposed outside the loader package, and `GameBootstrap` remains the
  executor lifecycle owner.
- A committed rebuild is left `DIRTY`; the Phase 3 mesh lifecycle schedules a
  current revision and rejects all stale CPU/upload work.

### Ownership and exclusions

- Engine developer ownership covers `engine/**`, including repository
  transactions and Phase 3 mesh compatibility.
- Game developer ownership covers `game/**`, including deterministic
  Providers, loader, spawn, rebuild orchestration, resources, and composition.
- Shared docs and the cross-boundary phase require both owners' awareness and
  final review.
- The CPU generation package remains independent of repository publication,
  rendering, LWJGL/GLFW/OpenGL, events, interaction, inventory, and world-item
  state.
- Gameplay writes remain fixed-update main-thread operations through the
  Phase 7 mutation contract. Generation publication is not a gameplay write.
- Every OpenGL/GLFW and GPU create/upload/draw/release operation remains on the
  context-owning main thread.

## Exact modified files relative to `origin/main`

The final Phase 4 documentation commit changes these exact 73 tracked paths
relative to `origin/main`.

### Shared design, plan, architecture, and handoff

- `docs/agent-handoffs/phase-04-handoff.md`
- `docs/architecture/current-baseline.md`
- `docs/architecture/phase-04-deterministic-snapshots.md`
- `docs/superpowers/plans/2026-07-25-deterministic-worldgen-pipeline.md`
- `docs/superpowers/specs/2026-07-25-deterministic-worldgen-pipeline-design.md`

### Engine production

- `engine/src/main/java/com/overlord/config/GameConfig.java`
- `engine/src/main/java/com/overlord/voxel/Chunk.java`
- `engine/src/main/java/com/overlord/voxel/ChunkGenerationData.java`
- `engine/src/main/java/com/overlord/voxel/ChunkGenerationMode.java`
- `engine/src/main/java/com/overlord/voxel/ChunkGenerationResult.java`
- `engine/src/main/java/com/overlord/voxel/ChunkGenerationStatus.java`
- `engine/src/main/java/com/overlord/voxel/ChunkGenerationTicket.java`
- `engine/src/main/java/com/overlord/voxel/ChunkRepository.java`

### Engine tests

- `engine/src/test/java/com/overlord/voxel/ChunkGenerationDataTest.java`
- `engine/src/test/java/com/overlord/voxel/ChunkMeshManagerTest.java`
- `engine/src/test/java/com/overlord/voxel/ChunkRepositoryGenerationTransactionTest.java`

### Game production and composition

- `game/src/main/java/com/gaia/GameBootstrap.java`
- `game/src/main/java/com/gaia/GameContext.java`
- `game/src/main/java/com/gaia/GameLoop.java`
- `game/src/main/java/com/gaia/world/GaiaWorldGenerator.java`
- `game/src/main/java/com/gaia/world/SafeSpawnSelector.java`
- `game/src/main/java/com/gaia/world/WorldLoadException.java`
- `game/src/main/java/com/gaia/world/WorldLoadFailure.java`
- `game/src/main/java/com/gaia/world/WorldLoadResult.java`
- `game/src/main/java/com/gaia/world/WorldLoadState.java`
- `game/src/main/java/com/gaia/world/WorldLoader.java`
- `game/src/main/java/com/gaia/world/WorldRebuildResult.java`
- `game/src/main/java/com/gaia/world/generation/BiomeProvider.java`
- `game/src/main/java/com/gaia/world/generation/BiomeSample.java`
- `game/src/main/java/com/gaia/world/generation/BiomeType.java`
- `game/src/main/java/com/gaia/world/generation/BlendedHeightProvider.java`
- `game/src/main/java/com/gaia/world/generation/CaveProvider.java`
- `game/src/main/java/com/gaia/world/generation/ContinuousBiomeProvider.java`
- `game/src/main/java/com/gaia/world/generation/DecorationProvider.java`
- `game/src/main/java/com/gaia/world/generation/DefaultStrataDensityProvider.java`
- `game/src/main/java/com/gaia/world/generation/DefaultSurfaceProvider.java`
- `game/src/main/java/com/gaia/world/generation/DeterministicCoordinateSampler.java`
- `game/src/main/java/com/gaia/world/generation/GenerationBlockPalette.java`
- `game/src/main/java/com/gaia/world/generation/GenerationContext.java`
- `game/src/main/java/com/gaia/world/generation/GenerationRegion.java`
- `game/src/main/java/com/gaia/world/generation/GenerationStageResult.java`
- `game/src/main/java/com/gaia/world/generation/HeightProvider.java`
- `game/src/main/java/com/gaia/world/generation/NoiseCaveProvider.java`
- `game/src/main/java/com/gaia/world/generation/StagedWorldGenerator.java`
- `game/src/main/java/com/gaia/world/generation/StoneOutcropDecorationProvider.java`
- `game/src/main/java/com/gaia/world/generation/StrataDensityProvider.java`
- `game/src/main/java/com/gaia/world/generation/SurfaceProvider.java`
- `game/src/main/java/com/gaia/world/generation/WorldGenerationConfig.java`
- `game/src/main/java/com/gaia/world/generation/WorldGenerationHasher.java`
- `game/src/main/java/com/gaia/world/generation/WorldGenerationResult.java`
- `game/src/main/java/com/gaia/world/generation/WorldGenerationStage.java`
- `game/src/main/java/com/gaia/world/generation/WorldGenerator.java`

### Game tests

- `game/src/test/java/com/gaia/GameBootstrapStructureTest.java`
- `game/src/test/java/com/gaia/GameBootstrapTest.java`
- `game/src/test/java/com/gaia/GameLoopStructureTest.java`
- `game/src/test/java/com/gaia/world/GaiaWorldGeneratorTest.java`
- `game/src/test/java/com/gaia/world/SafeSpawnSelectorTest.java`
- `game/src/test/java/com/gaia/world/WorldGenerationArchitectureTest.java`
- `game/src/test/java/com/gaia/world/WorldLoaderTest.java`
- `game/src/test/java/com/gaia/world/generation/BlendedHeightProviderTest.java`
- `game/src/test/java/com/gaia/world/generation/ContinuousBiomeProviderTest.java`
- `game/src/test/java/com/gaia/world/generation/DefaultStrataDensityProviderTest.java`
- `game/src/test/java/com/gaia/world/generation/DefaultSurfaceProviderTest.java`
- `game/src/test/java/com/gaia/world/generation/DeterministicCoordinateSamplerTest.java`
- `game/src/test/java/com/gaia/world/generation/GenerationContractTest.java`
- `game/src/test/java/com/gaia/world/generation/GenerationRegionTest.java`
- `game/src/test/java/com/gaia/world/generation/NoiseCaveProviderTest.java`
- `game/src/test/java/com/gaia/world/generation/StagedWorldGeneratorTest.java`
- `game/src/test/java/com/gaia/world/generation/StoneOutcropDecorationProviderTest.java`
- `game/src/test/java/com/gaia/world/generation/WorldGenerationBoundaryTest.java`
- `game/src/test/java/com/gaia/world/generation/WorldGenerationConfigTest.java`
- `game/src/test/java/com/gaia/world/generation/WorldGenerationDeterminismTest.java`
- `game/src/test/java/com/gaia/world/generation/WorldGenerationSnapshotTest.java`

Ignored `.superpowers/sdd` briefs and reports are coordination records, not
tracked branch changes.

## Test commands and results

The final Game-owner fix verification was run on Windows against implementation
commit `349c81c627e9492fae5a60322228c0a81edd77cb`.

Game-owner fix integration:

```powershell
.\gradlew.bat :game:test --rerun-tasks --console=plain --no-daemon
```

- Passed: 27 suites, 220 tests, 0 failures, 0 errors, 0 skipped.
- Includes the canonical 81-Chunk default loader, async load/rebuild executor,
  exact exceptional/rejection behavior, `StrictMath.exp`, unchanged locked
  snapshots, and Stage-cancellation propagation.

Focused determinism, boundary, and architecture:

```powershell
.\gradlew.bat :game:test `
  --tests com.gaia.world.generation.WorldGenerationDeterminismTest `
  --tests com.gaia.world.generation.WorldGenerationBoundaryTest `
  --tests com.gaia.world.WorldGenerationArchitectureTest `
  --console=plain --no-daemon
```

- Passed: 17 tests, 0 failures.

Locked snapshots:

```powershell
.\gradlew.bat :game:test `
  --tests com.gaia.world.generation.WorldGenerationSnapshotTest `
  --rerun-tasks --console=plain --no-daemon
```

- Passed: 2 tests, 0 failures.
- Candidate hashes were also reproduced byte-for-byte in a second separate
  clean Gradle process before constants were locked.

Full Windows automated verification:

```powershell
.\gradlew.bat clean test build --console=plain --no-daemon
```

- Passed: `BUILD SUCCESSFUL`; all 18 Gradle tasks passed.
- Engine JUnit XML: 50 suites, 526 tests, 0 failures, 0 errors, 0 skipped.
- Game JUnit XML: 27 suites, 220 tests, 0 failures, 0 errors, 0 skipped.
- Total: 77 suites, 746 tests, 0 failures, 0 errors, 0 skipped.
- The clean build included packaged-resource verification.

Standalone packaged-resource verification:

```powershell
.\gradlew.bat :game:verifyPackagedResources `
  --rerun-tasks --console=plain --no-daemon
```

- Passed: `BUILD SUCCESSFUL`; all five selected tasks executed.
- `git diff --check`, tracked-generated-output, absolute-JDK, generation
  graphics, and generation gameplay-coupling scans produced no prohibited
  match. The protected-name diff match is the intentional engine test
  `ChunkMeshManagerTest.java`; no protected production file changed.

Task 9 documentation/inventory checks are recorded in the ignored task report.
No interactive or native macOS result is inferred from automated tests.

## Owner-review findings and resolutions

Task-level reviews before the final phase review produced the following
material findings:

- Engine generation transaction review found ticket/unload lifecycle,
  cross-repository ownership, metadata cleanup, and unload-cleanup ABA issues.
  They were fixed in `950e1ad` and `1a399a5` with focused RED/GREEN repository
  transaction regressions.
- The revision-safe REBUILD review returned 0 Critical, 0 Important, and
  0 Minor findings.
- A Game review found an Important source-compatibility regression when
  `GenerationRegion.setBlock` was renamed. `623f3b7` restored it as a bounded
  compatibility alias while Providers continue to use `writeBlock`.
- Pipeline review found an Important malformed-output failure-accounting gap.
  `267bd35` validates key and world height before commit so the live ticket
  records the exact failure.
- Loader/spawn reviews found Important extreme-coordinate radius overflow,
  cancellation-after-hash, and one-cell head-clearance gaps. `f3022ff` and
  `1a0cd7e` contain focused regressions and fixes.
- Snapshot/architecture review found two Important and two Minor coverage
  gaps, followed by qualified renderer/event/static-noise matcher gaps. The
  final Task 8 commits use a shared generator across workers, strengthen
  boundary/material assertions and synthetic hash cases, and harden source
  guards. Re-review reported no remaining Task 8 finding.
- The final branch-wide Game-owner review found four Important issues:
  loader-local noncanonical hashing, caller-thread public rebuild, non-strict
  biome exponentiation, and Stage cancellation converted into failure.
  `349c81c` resolved all four through separate RED/GREEN regressions. The
  resulting default aggregate is still
  `161f6c10773c8dfd84e6961183e8706d5a0ec00750e727e83c4a08afcfbd5ce8`.

Final branch-wide Engine-owner and Game/shared-owner review verdict:
**PENDING ROOT ORCHESTRATION**. No final phase approval is claimed here.

## Known risks

- Raw-source architecture guards are useful regression checks but can match
  comments/strings and cannot prove the absence of semantically hidden
  coupling.
- A very large configured radius can make finite generation, spawn search, and
  aggregate hashing expensive even though overflow and determinism are tested.
- Generation failures leave already committed earlier Chunks available for
  diagnosis; there is no repository-wide all-or-nothing rollback.
- REBUILD processes keys independently. Callers must interpret mixed outcomes
  and cannot treat partial success as an atomic region replacement.
- Surface slope has no cross-region height halo, so edge columns use one-sided
  information. Boundary height/surface continuity is tested for the current
  algorithm, but future Providers must preserve scheduling independence.
- Snapshot constants protect the approved algorithm/configuration, not visual
  quality. An intentional terrain change requires version/config visibility,
  two-process hash reproduction, and manual coordinate review.
- Real GLFW/OpenGL visuals, terrain feel, resize/input, Escape shutdown, and
  native macOS behavior remain unverified for Phase 4.

## Interfaces Phase 5, Phase 8, Phase 9, and later world work must not break

- Preserve Java 17, checked-in Gradle Wrapper use, engine-to-game dependency
  direction, OpenGL 4.1/GLSL 410, and main/context-thread GPU ownership.
- Preserve `ChunkRepository` as the only loaded-Chunk directory and owner of
  generation tickets, publication, revisions, dirty propagation, failure,
  stale-result rejection, and unload interaction.
- Preserve exact repository ticket ownership, per-Chunk atomic publication,
  INITIAL partial-data exclusion, REBUILD base-revision guard, old-state
  preservation on failure/conflict, and loaded-neighbor edge invalidation.
- Preserve the Phase 3 mesh lifecycle. A successful REBUILD must leave the
  target `DIRTY`; later code must not directly replace render objects or invent
  a second revision/dirty authority.
- Preserve the deterministic seed/version/config contract and make every
  algorithm/config change visible. Do not add global random/noise state,
  call-order dependence, or worker-order dependence.
- Preserve the six ordered Providers and detached bounded
  `GenerationRegion`; stages must stop on first failure and publish no partial
  `ChunkGenerationData`.
- Preserve the default 81-Chunk finite range and explicit failure/cancellation
  states unless a deliberate, tested configuration or streaming decision
  replaces them.
- Preserve safe-spawn support, feet/head clearance, committed-key/radius
  bounds, deterministic tie order, and no-fallback-block behavior.
- Preserve the debug rebuild lifecycle and exact per-key
  `COMMITTED`/`FAILED`/`CONFLICT` meaning.
- Preserve the injected single-executor `loadAsync`/`rebuildRegionAsync`
  boundary. Do not restore public caller-thread generation, nested submission,
  or per-call world-generation pools.
- Preserve `CancellationException` as cancellation across Stage, loader,
  future, ticket, and no-publication boundaries.
- Preserve Phase 7 gameplay mutation exclusion. Generation must not emit
  gameplay block-change events, consume inventory/world-item services, or
  bypass the repository with live-world block writes.
- Preserve canonical hash domains, byte ordering, sort order, duplicate-key
  rejection, locked fixed seed/hash/coordinates, and the intentional snapshot
  update protocol.
- Preserve Phase 6 authoritative player feet/body transforms and Camera as
  one-way interpolated render output. Loader spawn remains explicit player-feet
  coordinates.
- Keep generation CPU-only. Renderer, Mesh, `ChunkMeshManager`, LWJGL, GLFW,
  OpenGL, GPU resource lifecycle, player-controller behavior, and Phase 8/9
  gameplay must not enter the generation package.

## Final phase report

Final `git diff --stat origin/main` including the Game-owner fixes and updated
handoff:

```text
73 files changed, 11580 insertions(+), 264 deletions(-)
```

Suggested overall commit message:

```text
feat(worldgen): add deterministic staged generation pipeline
```

Task 9 documentation commit:

```text
docs(worldgen): record deterministic pipeline handoff
```

Suggested pull request title:

```text
Phase 4: add deterministic staged world generation
```

Suggested pull request description:

```markdown
## Summary

- add repository-owned INITIAL and revision-safe REBUILD generation
  transactions with per-Chunk atomic publication
- generate finite Gaia terrain through six deterministic detached CPU stages
- expose explicit loader failure/cancellation, safe spawn, and programmatic
  debug rebuild outcomes
- lock configuration-specific SHA-256 snapshots and scheduling/boundary
  architecture guards
- preserve Phase 3 mesh revision authority and Phase 7 gameplay mutation
  exclusion

## Verification

- focused determinism/boundary/architecture suite: 17/17
- locked snapshot suite: 2/2 in repeated separate Gradle processes
- Windows clean test/build at Game-owner fix HEAD: 746/746, zero
  failure/error/skip
- standalone packaged-resource verification passed
- final branch-wide owner re-review remains pending root orchestration

## Manual follow-up

- Windows plains/hills/highlands/cave/boundary/spawn/rebuild,
  resize/input, and Escape-shutdown smoke
- native macOS clean build, interactive smoke, and aggregate-hash comparison
```

No push, pull request, merge, force-push, or modification of `main` was
performed by the Task 9 documentation delegate.
