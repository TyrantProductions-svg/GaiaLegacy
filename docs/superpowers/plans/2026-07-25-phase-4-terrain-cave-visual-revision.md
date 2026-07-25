# Phase 4 Terrain and Cave Visual Revision Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add original oak resources, deterministic world-space trees and
sparse outcrops, distinct biome terrain, and a connected hybrid cave system
without weakening the approved Phase 4 lifecycle.

**Architecture:** Keep the six formal generation stages. Compose pure,
world-space feature descriptor/carver units inside the Cave and Decoration
stages, write only bounded detached `GenerationRegion` data, and preserve the
repository transaction and Phase 3 stale-result authority.

**Tech Stack:** Java 17, Gradle 8.5 Wrapper, JUnit Jupiter, existing
`ResourceLocation`, `DeterministicCoordinateSampler`, `GenerationRegion`,
`ChunkRepository`, PNG/JSON resource pipeline, Java standard-library SHA-256.

## Global Constraints

- Work only on `feat/worldgen-pipeline`; do not push, create a PR, or merge.
- Keep Java 17 compatibility and use the checked-in Gradle Wrapper.
- Keep OpenGL 4.1/GLSL 410 and main-thread GPU ownership unchanged.
- Do not modify `Renderer`, `ChunkMeshManager`, `PlayerController`, or
  `PhysicsManager`.
- Do not add BlockSize, microvoxels, global Random, mutable static noise, or
  call-order RNG.
- Generation must not call `WorldMutationService` or create GPU resources.
- Preserve the six formal Stage IDs and repository generation transaction.
- Do not overwrite formal version-1 snapshot/hash values before visual
  approval.
- Every production behavior follows RED, GREEN, REFACTOR.

---

### Task 1: Original Oak Assets

**Files:**
- Modify: `game/src/main/resources/assets/gaia/textures/atlas.png`
- Modify: `game/src/main/resources/assets/gaia/atlases/blocks.json`
- Modify: `game/src/main/resources/assets/gaia/resource-index.json`
- Create: `game/src/main/resources/assets/gaia/blocks/oak_log.json`
- Create: `game/src/main/resources/assets/gaia/blocks/oak_leaves.json`
- Modify: `game/src/test/java/com/gaia/assets/GaiaProductionAssetsTest.java`
- Modify: `THIRD_PARTY_NOTICES.md`

**Interfaces:**
- Produces stored block IDs 4 and 5 and regions `gaia:oak_log_side`,
  `gaia:oak_log_top`, and `gaia:oak_leaves`.

- [ ] Write tests that load both block definitions, assert face mappings and
  opaque material, assert the three exact new regions, and hash the four
  existing tile pixels unchanged.
- [ ] Run `:game:test --tests com.gaia.assets.GaiaProductionAssetsTest` and
  verify RED because oak resources do not exist.
- [ ] Draw original 16-by-16 pixels into the three approved transparent tail
  cells and add JSON/index definitions with IDs 4 and 5.
- [ ] Re-run the focused test and packaged-resource verification for GREEN.

### Task 2: Candidate Configuration and Shared World-Space Queries

**Files:**
- Modify: `game/src/main/java/com/gaia/world/generation/WorldGenerationConfig.java`
- Create: `game/src/main/java/com/gaia/world/generation/TerrainColumnSampler.java`
- Create: `game/src/main/java/com/gaia/world/generation/FeatureCell.java`
- Modify: matching generation configuration and primitive tests.

**Interfaces:**
- Produces candidate-only terrain/cave/decoration settings and pure
  `TerrainColumnSampler.sample(worldX, worldZ)`.

- [ ] Write validation, fingerprint, negative-cell, and scheduling-independent
  query tests.
- [ ] Verify RED for missing candidate settings/query types.
- [ ] Implement immutable settings and floor-div world-cell mapping.
- [ ] Verify GREEN across primitive generation tests.

### Task 3: Biome-Specific Terrain Shaping

**Files:**
- Modify: `game/src/main/java/com/gaia/world/generation/BlendedHeightProvider.java`
- Modify: `game/src/test/java/com/gaia/world/generation/BlendedHeightProviderTest.java`
- Modify: `game/src/test/java/com/gaia/world/generation/WorldGenerationBoundaryTest.java`

**Interfaces:**
- Preserves `HeightProvider.sampleHeight(...)` while producing statistically
  distinct plains, hills, and highlands.

- [ ] Add fixed-seed literal statistical assertions for mean height, height
  range, slope, bounds, and boundary continuity.
- [ ] Verify RED against the current shared smooth field.
- [ ] Implement long-wave flattened plains, medium rolling hills, and bounded
  ridged highlands using absolute coordinates and `StrictMath`.
- [ ] Verify focused and boundary tests GREEN.

### Task 4: Deterministic Trees and Sparse Outcrops

**Files:**
- Create: `game/src/main/java/com/gaia/world/generation/CompositeDecorationProvider.java`
- Create: `game/src/main/java/com/gaia/world/generation/TreeDecorationProvider.java`
- Modify: `game/src/main/java/com/gaia/world/generation/StoneOutcropDecorationProvider.java`
- Modify: `game/src/main/java/com/gaia/world/GaiaWorldGenerator.java`
- Add focused tree, outcrop, cross-Chunk, and distribution tests.

**Interfaces:**
- `CompositeDecorationProvider` remains the single `gaia:decoration` Stage.
- Feature descriptors are pure values regenerated from nearby world cells.

- [ ] Add tests proving every accepted tree has log/leaves, is grounded,
  respects the origin reserve, and reproduces crossing parts in forward and
  reverse Chunk order.
- [ ] Add fixed-seed tests bounding biome tree density and reducing outcrop
  roots by at least 75 percent while retaining highland clusters.
- [ ] Verify RED for missing composite/tree behavior and current 233-column
  outcrop distribution.
- [ ] Implement cell descriptors, neighbor-priority spacing, bounded tree
  intersection writes, cluster outcrops, and deterministic conflict priority.
- [ ] Verify GREEN for decoration, boundary, and scheduling tests.

### Task 5: Hybrid Cave Provider

**Files:**
- Create: `game/src/main/java/com/gaia/world/generation/HybridCaveProvider.java`
- Create: `game/src/main/java/com/gaia/world/generation/NoiseChamberCarver.java`
- Create: `game/src/main/java/com/gaia/world/generation/DeterministicTunnelCarver.java`
- Create: `game/src/main/java/com/gaia/world/generation/SurfaceEntranceCarver.java`
- Modify: `game/src/main/java/com/gaia/world/GaiaWorldGenerator.java`
- Add focused chamber, tunnel, entrance, and BFS tests.

**Interfaces:**
- `HybridCaveProvider.id()` remains `gaia:cave`.
- Internal carvers consume context/target region and return deterministic
  sample/write statistics.

- [ ] Add tests for chamber threshold, non-entry surface protection, sloped
  entrance geometry, x/z/negative/diagonal Chunk continuity, and scheduling
  equality.
- [ ] Add a fixed-seed BFS test requiring at least two entrances, one descent
  over ten blocks, one minimum-size reachable component, and one component
  spanning multiple Chunks.
- [ ] Verify RED against `NoiseCaveProvider`.
- [ ] Implement world-space chamber fields, bounded 3D-cell tunnel
  descriptors, and entrance branches that connect to their tunnel systems.
- [ ] Verify GREEN for all cave and generation boundary tests.

### Task 6: Candidate Hash and Lifecycle Regression

**Files:**
- Modify: `game/src/test/java/com/gaia/world/generation/WorldGenerationSnapshotTest.java`
- Modify: determinism, loader, cancellation, and architecture tests.

**Interfaces:**
- Preserves `VERSION_ONE_REGION_HASH`.
- Adds a non-normative candidate aggregate-hash output and representative
  feature coordinate fixture.

- [ ] Add tests proving forward, reverse, shuffled, and concurrent candidate
  generation hashes match while v1 remains locked.
- [ ] Add structure tests prohibiting graphics/gameplay dependencies and
  retaining exact Stage count.
- [ ] Verify RED before adding candidate snapshot helpers.
- [ ] Implement only the minimal candidate hash/report path and verify GREEN.

### Task 7: Documentation, Review, and Verification

**Files:**
- Modify: `docs/architecture/current-baseline.md`
- Modify: `docs/architecture/phase-04-deterministic-snapshots.md`
- Modify: `docs/agent-handoffs/phase-04-handoff.md`
- Modify: this design and plan with final evidence.

- [ ] Run focused suites, `clean test build`, packaged-resource verification,
  `git diff --check`, hygiene scans, and branch-wide Engine/Game owner review.
- [ ] Count exact Engine/Game tests and record candidate hash, tree/outcrop/
  entrance statistics, and manual coordinates.
- [ ] Run Windows `:game` interactively and record exit code and the five
  requested screenshot categories; do not claim visual approval without the
  user's confirmation.
- [ ] Leave formal v1 hashes untouched until explicit visual approval.
- [ ] Report final HEAD, diff stat, unfinished macOS/manual work, suggested
  commit message, and PR text without publishing.

### Task 8: Approved Version-2 Lock and Delivery

**Files:**
- Modify:
  `game/src/main/java/com/gaia/world/generation/WorldGenerationConfig.java`
- Modify:
  `game/src/test/java/com/gaia/world/generation/WorldGenerationConfigTest.java`
- Modify:
  `game/src/test/java/com/gaia/world/generation/WorldGenerationSnapshotTest.java`
- Modify:
  `game/src/test/java/com/gaia/world/generation/VisualRevisionWorldGenerationTest.java`
- Modify: `docs/architecture/phase-04-deterministic-snapshots.md`
- Modify: `docs/architecture/current-baseline.md`
- Modify: `docs/agent-handoffs/phase-04-handoff.md`
- Modify: this plan and its paired design.

**Interfaces:**
- Preserves `WorldGenerationConfig.defaults()` and all normative v1 hashes as
  immutable history.
- Promotes `visualRevisionCandidate()` to algorithm version `2` and locks its
  fingerprint, representative Chunk hashes, aggregate hash, statistics, and
  inspection coordinates.

- [x] Change the candidate-version assertion to `2`, add intentionally
  incorrect v2 snapshot constants, and run the focused tests to verify RED
  from the version/hash mismatch.
- [x] Set only the visual revision config's algorithm version to `2`; keep
  `defaults()` at version `1`.
- [x] Run the 81-Chunk report and replace only v2 placeholder constants with
  the emitted fingerprint, representative hashes, aggregate hash, statistics,
  and coordinates; re-run twice in separate Gradle processes for GREEN.
- [x] Audit the atlas stone tile's pixel luminance without changing the PNG
  and record whether Phase 5A albedo work, Phase 5B lighting/AO work, or both
  are required.
- [x] Run Windows `:game`, inspect the approved surface biomes/decorations,
  exercise movement/input/resize/Alt+Tab/Escape, and record the actual exit
  code. The user found an entrance and waived further agent entrance
  inspection. Three of five requested categories were captured outside the
  repository; cave entrance and underground images remain absent.
- [x] Run full build/resource/hygiene checks, stage all 17 tracked and 9
  original untracked candidate files with `git add -A`, inspect
  `git diff --cached --check` and `git diff --cached --stat`, and reject any
  generated/local/coordination artifact.
- [x] Obtain fresh Engine-owner and Game/shared-owner approval on the staged
  version-2 tree.
- [ ] Create the requested local commit and verify the worktree is clean
  without pushing, creating a PR, or merging as the final delivery step.

---

## Execution reconciliation

Status on 2026-07-25:

- [x] Original oak atlas tiles, block definitions, resource index, provenance,
  production asset tests, and packaged-resource verification are implemented.
- [x] Candidate configuration is explicit and fingerprinted. Pure absolute
  coordinate sampling remains in the existing
  `DeterministicCoordinateSampler`; no parallel query framework was needed.
- [x] Biome-specific terrain shaping is implemented in the candidate-only
  `BiomeShapedHeightProvider`, preserving formal v1 `BlendedHeightProvider`.
- [x] Trees and sparse outcrop clusters are composed inside the single
  `gaia:decoration` stage. The candidate avoids changing the formal v1
  `StoneOutcropDecorationProvider`.
- [x] Chambers, tunnels, and surface entrances are implemented as pure private
  operations inside `HybridCaveProvider`; separate public carver classes were
  unnecessary for the tested contract.
- [x] Candidate hash, scheduling equality, feature statistics, cave BFS,
  safe-spawn regression, formal v1 preservation, and architecture boundaries
  have focused tests.
- [x] Windows surface visual review, F1, resize, and clean Escape shutdown are
  recorded.
- [ ] Cave entrance and underground tunnel/chamber game screenshots remain
  outstanding.
- [x] Explicit visual approval, version-2 promotion, and two-process v2
  snapshot locking are complete. Native macOS verification remains
  outstanding.
- [x] Final Windows clean build, standalone packaged-resource verification,
  diff check, and Engine-owner review were refreshed after hardening.
- [x] Final staged Game/shared-owner and Engine-owner re-reviews are complete
  before the local commit.
