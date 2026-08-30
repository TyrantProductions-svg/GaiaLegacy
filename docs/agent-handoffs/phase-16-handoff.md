# Phase 16 Handoff — Small-Block Voxel Core

## Status

Gates 16A through 16D are controller-accepted. Gate 16E Windows manual acceptance is recorded PASS and final local repository-wide verification is GREEN. Native Apple Silicon runtime remains `NOT RUN`. Phase 16 is ready for the separately authorized integration checkpoint but is not committed, pushed, opened as a PR, merged, tagged, or released.

## Completed work

- Sparse typed FULL/DETAIL parent state with one 4x4x4 scale.
- Chunk-owned DETAIL storage, immutable snapshots, deterministic indexing, 1,024-parent cap, and no canonical empty DETAIL.
- Repository-authoritative DETAIL mutations and atomic FULL/DETAIL transitions.
- Streamed `detail-blocks` codec v1, exact snapshot capture, detail-aware equality, unload/reload, and old-save compatibility.
- DETAIL refinement inside the existing `BlockRaycast` DDA.
- Deterministic quarter-grid collision boxes inside `CollisionWorld`.
- Hybrid geometry in the existing Chunk mesh/claim/GPU lifecycle, including FULL/DETAIL seam coverage, UV, AO, and stale-result rejection.
- 8 MiB per-hybrid-output cap with typed complexity diagnostics and no retry storm.
- 128 MiB all-Chunk CPU mesh lifecycle budget with checked reservation/accounting and typed single-job impossibility.
- Exact-sized builder allocation. Ownership-transfer Steps B/C were measured unnecessary and are not implemented.
- Development-only typed inspection/mutation/fixture controls and bounded diagnostics.
- Deterministic data, codec, collision, raycast, mesh, and mixed-memory measurement fixtures.
- New World startup initializes its first bounded mesh order/readiness set from
  the existing Phase 15 `ChunkStreamingController` decision before scheduling;
  radius-8 generation may retain 289 initial Chunks while the current normal
  render/simulation sets remain controller-derived and bounded.
- Atomic mixed Chunk/WorldItem publication keeps exact captured `detail-blocks`
  authoritative while preserving independently owned durable extensions.
- Expected mesh output/memory policy rejections remain revision-latched
  diagnostics and do not enter the fatal session failure channel.
- Legacy FULL placement now observes the adjacent canonical `ParentCellState`
  before reading FULL material identity, so a ray through a DETAIL gap cannot
  crash or mutate the adjacent DETAIL parent through the byte-only API.

## Core decisions future phases must not break

1. `ChunkRepository` is the only resident Chunk, revision, dirty, unload-ticket, and canonical DETAIL authority.
2. DETAIL table membership is the physical discriminator; its FULL backing byte is AIR and stored occupancy is nonzero.
3. `ChunkSnapshot` is the detached canonical carrier. `ChunkMeshInput` remains exactly nine snapshots and `ChunkMeshingClaim` remains separate capability metadata.
4. Engine storage uses runtime byte IDs; game/save translation uses the existing `BlockRegistry` and `ResourceLocation`.
5. `BlockRaycast`, `CollisionWorld`, and `ChunkMeshManager` remain the sole world traversal, collision, and mesh/GPU authorities.
6. UNKNOWN/FAILED never become AIR.
7. Canonical data survives render complexity or memory rejection unchanged.
8. Count/GPU limits and the 8 MiB/128 MiB policies remain frozen absent new measured controller approval.

## Key modified files

The final tracked diff is `48 files changed, 4,100 insertions(+), 408 deletions(-)`.
The exact tracked inventory is:

- Engine production: `GameConfig.java`, `BlockHitResult.java`,
  `BlockCollisionShapeResolver.java`, `BlockRaycast.java`, `BlockRaycastHit.java`,
  `CollisionWorld.java`, `ChunkRenderBackend.java`, `Mesh.java`, `Renderer.java`,
  `Chunk.java`, `ChunkGenerationData.java`, `ChunkMeshBuilder.java`,
  `ChunkMeshData.java`, `ChunkMeshInput.java`, `ChunkMeshManager.java`,
  `ChunkMesher.java`, `ChunkRepository.java`, `ChunkSnapshot.java`,
  `VoxelAmbientOcclusion.java`, and `World.java`.
- Engine tests: `BlockCollisionShapeTest.java`, `BlockRaycastTest.java`,
  `ChunkGenerationDataTest.java`, `ChunkMeshDataTest.java`,
  `ChunkMeshInputTest.java`, `ChunkMeshLifecycleStructureTest.java`,
  `ChunkMeshManagerTest.java`, `ChunkSnapshotTest.java`, and
  `VoxelAmbientOcclusionTest.java`.
- Game production: `GameBootstrap.java`, `BlockInteractionController.java`,
  `BlockPlacementTransaction.java`, `BlockPlacementWorldView.java`,
  `GaiaBlockRaycastService.java`, `GaiaBlockWorldAccess.java`,
  `StreamedChunkCodec.java`, `StreamedExtensionSupportRegistry.java`,
  `StreamedSessionSaveTarget.java`, `StreamedWorldItemPageBackend.java`, and
  `GameSessionFactory.java`.
- Game tests: `BlockInteractionControllerTest.java`,
  `BlockPlacementTransactionTest.java`, `GaiaBlockRaycastServiceTest.java`,
  `FeedbackTransactionIsolationTest.java`, `StreamedChunkUnloadTransactionTest.java`,
  `WorldItemPagingRestartTest.java`, and `GameSessionEligibilityBoundaryTest.java`.
- Tools build: `tools/build.gradle`.

The 83 untracked Phase 16 source, test, documentation, and tooling files are:

- Documentation: this handoff, `docs/architecture/small-block-voxel-core.md`,
  `docs/architecture/streamed-detail-blocks-format.md`, six Gate 16A-16E
  implementation notes, the Phase 16 plan, and the Phase 16 design spec.
- Engine API/production: `DetailMutationRequest.java`, `DetailMutationResult.java`,
  `DetailMutationService.java`, `DetailToFullRequest.java`, `FullToDetailRequest.java`,
  `DetailCollisionBoxMerger.java`, `DetailRaycastTarget.java`,
  `FullRaycastTarget.java`, `RaycastCellTarget.java`, `ChunkDetailMutation.java`,
  `ChunkDetailMutationOutcome.java`, `ChunkMeshGeometryBounds.java`,
  `ChunkMeshMemoryBudgetExceededException.java`, `ChunkMeshMemoryPlan.java`,
  `ChunkMeshOutputLimitExceededException.java`, `DetailCellState.java`,
  `DetailChunkSnapshot.java`, `DetailStorage.java`, `FullCellState.java`,
  `LocalSubVoxelPosition.java`, `ParentCellObservation.java`,
  `ParentCellObservationResult.java`, `ParentCellState.java`,
  `QuarterVoxelSample.java`, `QuarterVoxelSampler.java`, and `VoxelScale.java`.
- Engine tests: `DetailBlockRaycastTest.java`, `DetailCollisionArchitectureTest.java`,
  `DetailCollisionBoxMergerTest.java`, `DetailCollisionWorldTest.java`,
  `DetailRaycastArchitectureTest.java`, `RaycastHitProvenanceTest.java`,
  `ChunkDetailMutationConcurrencyTest.java`, `ChunkDetailMutationTest.java`,
  `ChunkDetailStorageTest.java`, `ChunkHybridMeshOutputLimitTest.java`,
  `ChunkMeshGeometryBoundsTest.java`, `ChunkMeshMemoryBudgetTest.java`,
  `DetailArchitectureContractTest.java`, `DetailCellStateTest.java`,
  `DetailChunkMeshAdversarialBoundTest.java`, `DetailChunkMeshArchitectureTest.java`,
  `DetailChunkMeshBuilderTest.java`, `DetailChunkMeshLifecycleTest.java`,
  `DetailChunkSnapshotTest.java`, `DetailSnapshotBoundTest.java`,
  `DetailSnapshotPropagationTest.java`, `QuarterVoxelSamplerTest.java`, and
  `TypedParentObservationTest.java`.
- Game production: `DetailDebugInputController.java`, `DetailDebugTools.java`,
  `DetailFixturePattern.java`, `GaiaDetailMutationService.java`,
  `ChunkDetailPersistence.java`, `DetailBlocksCodec.java`, and
  `StreamedChunkCanonicalDecoder.java`.
- Game tests: `DetailChunkMeshCompositionTest.java`,
  `HybridMeshOutputCanonicalSafetyTest.java`, `PhysicsDetailCompositionTest.java`,
  `DetailDebugInputControllerTest.java`, `DetailDebugToolsTest.java`,
  `DetailFixturePatternTest.java`, `DetailTargetingIntegrationTest.java`,
  `GaiaDetailMutationServiceTest.java`, `DetailBlocksCodecTest.java`,
  `StreamedChunkCanonicalDecoderTest.java`, `StreamedDetailPersistenceTest.java`,
  and `NewWorldInitialMeshSchedulingTest.java`.
- Tools: `DetailVoxelPerformanceFixture.java`, `HybridMeshRetentionStressFixture.java`,
  `DetailVoxelPerformanceFixtureTest.java`, and
  `HybridMeshRetentionStressFixtureTest.java`.

The untracked `dist/GaiaLegacy-v0.2.0-alpha.1-windows-x64.zip` is a pre-existing
user artifact and is excluded. The two controller-quarantined tracked files
remain user-owned and must not be included in a Phase 16 commit without separate
approval.

## Verification evidence

Gate-specific focused and proportional matrices through Gate 16D are recorded in the dated implementation notes. Accepted Gate 16E resource evidence is:

- corrected production-equivalent mixed `-Xmx512m` peak: 334,315,448 bytes (62.27%);
- four-run heap envelope: 58.00% to 62.27%;
- 4 collections / 15 ms;
- 32 accepted, 2 active, 5 completed peak, 31 uploads with one deliberate stale result;
- forward progress PASS, starvation not observed;
- current focused debug tooling: 9/9 GREEN;
- current bounded measurement fixture: 4/4 GREEN.

Final post-fix repository-wide verification used Microsoft OpenJDK 21.0.11,
Gradle 8.5, the checked-in wrapper, and the already diagnosed process-local TCP
selector fallback. `gradlew.bat clean test build --console=plain --no-daemon`
completed with exit code 0 in 5h 38m 26s (31 actionable tasks: 30 executed and
one up-to-date). Fresh JUnit XML totals were engine 1,492/1,492, game
2,131/2,131, and tools 31 passed plus one skipped test; there were zero failures
and zero errors. The long wall-clock cost was observed in Phase 14 migration,
streamed-store fault, WorldItem paging, and streaming performance tests and is
consistent with the separately classified S2 storage/catalog risk below.

The independently rerun packaged-resource task, engine packaged-shader task,
installed shader/audio verification, and `:game:installDist` all completed
successfully. The generated distribution contains the Windows launchers,
versioned engine/game JARs, required LWJGL Windows natives, packaged audio,
textures, UI assets, and GLSL resources and contains no repository-local
absolute runtime path.

Windows production runtime acceptance is PASS: radius-8 New World creation,
ordinary FULL terrain, DETAIL fixture rendering, quarter-height/half-height/
three-quarter-height collision, staircase traversal, thin-wall collision and
sliding without ordinary-speed tunneling, FULL/DETAIL and DETAIL/DETAIL seams,
real unload-radius departure/return, restored DETAIL render/raycast/collision,
focus loss/recovery and DETAIL retargeting, Save & Quit, fresh-process reload,
and clean exit were all observed. The accepted asymmetric seven-cell fixture
used occupancy `0x0480010040000029`, hash
`2f65a9bce5596a7bc053d701ccff01469fcb6be683fba1ad36ff099b04a0caa7`,
revision `4839`, local raycast `[2,2,3]`, seven collision boxes, and installed
mesh state. No obvious crack or Z-fighting was observed. Native Apple Silicon
interactive runtime remains `NOT RUN` and must not be inferred from Windows or
local Gradle evidence.

## Known risks and unfinished work

- The default Java/Gradle selector path fails before test execution because the
  JDK's Unix-domain `PipeImpl` connection returns `Invalid argument`. Direct
  TCP loopback succeeds. The documented process-local
  `JAVA_TOOL_OPTIONS=-Djdk.net.unixdomain.tmpdir=C:\__codex_missing_unix_socket_dir__`
  forces the JDK TCP fallback and produced the final Gradle 8.5 repository-wide
  PASS without changing repository or machine configuration.
- Native Apple Silicon runtime acceptance is required when hardware is available; otherwise report `NOT RUN`.
- Sparse DETAIL restore creates measured dead allocation churn. Fresh-process
  restoration was exact. Isolated catalog attribution classified the long
  catalog delay as an existing Phase 14/streamed-catalog characteristic, not a
  Phase 16 DETAIL regression: the real six-directory root took 98.135, 94.763,
  and 90.399 seconds per `summaries()` pass; the dominant pre-Phase-16 save has
  289 indexed Chunks, 578 physical payload files, and no `detail-blocks`
  extensions. Catalog listing resolves and validates every indexed payload;
  it retains extension bytes generically but does not invoke the Phase 16
  `DetailBlocksCodec` semantic restore path. This remains a separately scoped
  product-performance risk.
- A complexity failure may leave a last-known-good visual mesh that differs from current canonical raycast/collision state; the failed revision is explicitly diagnosed and never presented as current.
- The 8 MiB and 128 MiB limits are current M2 safety policies, not world-generation density targets or universal hardware guarantees.

## Forward compatibility — revised Phase 19 world generation

Natural generated DETAIL must be deterministic staged world generation using the same `ParentCellState`/`ChunkSnapshot` representation. It must not call gameplay `DetailMutationService`, must not mark deterministic base terrain player-modified, and must derive a `generatedDetailBudgetPerChunk` substantially below 1,024 from Phase 16 measurements. Output must be reproducible from seed, generator version, and world coordinates and must use the existing mesh, raycast, collision, and persistence systems. Phase 16 implements no natural DETAIL generation.

## Forward compatibility — revised Phase 17/18 harvest

Later phases may use existing DETAIL targeting and repository mutation contracts for coarse parent break, precision subvoxel break, tool-gated recovery, and detail-unit material conservation. Canonical DETAIL needs no provenance flag: identical generated and player-placed material follows the same future harvest policy. Phase 16 adds no chisel, drops, inventory economy, or harvest fields.

## Suggested eventual integration text

Suggested commit: `feat(voxel): add sparse 4x4x4 detail-block core`

Suggested PR title: `feat(voxel): establish small-block voxel data mesh raycast and collision`

Suggested PR description: Introduce sparse Chunk-owned DETAIL_4 canonical state, repository-authoritative mutation and streamed persistence, typed raycast/collision integration, hybrid Chunk meshing, measured mesh-memory safety policies, deterministic diagnostics/fixtures, and cross-platform closure evidence without changing normal FULL world authority.
