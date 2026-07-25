# Phase 04 Deterministic World-Generation Snapshots

## Contract

Phase 04 generation is a pure CPU pipeline. A `StagedWorldGenerator` writes a
detached `GenerationRegion`, freezes it as `ChunkGenerationData`, and the
`WorldLoader` publishes that data through `ChunkRepository.beginGeneration`
and `commitGeneration`. Generation does not use gameplay mutation services,
block-change events, inventories, world items, renderer state, GPU resources,
LWJGL, GLFW, or global random/noise state.

`WorldGenerationHasher` defines the snapshot byte stream. It uses SHA-256 and
lowercase hexadecimal output. Every integer is a signed 32-bit value encoded
most-significant byte first. Every byte sequence is prefixed by its 32-bit
length. Text is UTF-8 (the domain labels are ASCII, which is a UTF-8 subset).

A chunk hash contains, in order:

1. `GaiaLegacy.WorldGeneration.Chunk.v1`;
2. `WorldGenerationConfig.canonicalFingerprintInput()`;
3. chunk X, chunk Z, and world height;
4. the canonical block-array length and bytes.

The canonical block array uses the `ChunkGenerationData` layout: X changes
fastest, then Y, then Z. A region hash uses the
`GaiaLegacy.WorldGeneration.Region.v1` domain, the same canonical config, the
chunk count, then the same chunk payload for every chunk sorted by chunk X and
then chunk Z. Duplicate keys are rejected. Input collection order and worker
completion order therefore do not affect the result.

`WorldLoader` returns this exact canonical region identity through
`WorldGenerationHasher.hashRegion`; it does not maintain a second loader-local
hash format. Public initial load and debug rebuild calls enqueue onto the one
injected world-generation `ExecutorService` owned by `GameBootstrap`. Each
public future is bridged to the exact submitted task, so `cancel(true)` signals
the generation loops and interrupts that task. Initial and rebuild paths check
the signal before every publication/commit boundary. A shared operation gate
then makes cancellation atomic with each repository commit and with successful
state/future completion. If cancellation wins the gate, no later commit or
`SUCCEEDED` transition is allowed. If a commit or success action wins, a
concurrently waiting cancellation attempt returns `false`; rebuild cancellation
between keys still preserves already committed prior-key outcomes. The
synchronous orchestration helpers are not public API.

## Version 1 configuration

- Seed: `12345L`
- Algorithm version: `1`
- Default inclusive chunk radius: `4` (81 chunks, `[-4,4]` on X and Z)
- Canonical configuration SHA-256:
  `e1baa7ef6028c6615ac34e42be19dfdc590d0740321608ab8b0ca6dc3d250ada`
- Canonical configuration input:

```text
seed=12345|algorithmVersion=1|chunkRadius=4|biome.scale=0x1.cac083126e979p-9|biome.transitionSharpness=0x1.0p1|height.detailScale=0x1.eb851eb851eb8p-7|height.minimumSurfaceHeight=8|height.maximumSurfaceHeight=96|height.plainsBase=24|height.plainsVariation=6|height.hillsBase=34|height.hillsVariation=14|height.highlandsBase=50|height.highlandsVariation=28|cave.scale=0x1.70a3d70a3d70ap-5|cave.threshold=0x1.8f5c28f5c28f6p-1|cave.bedrockDepth=2|cave.surfaceBuffer=3|surface.dirtDepth=3|surface.rockyWeightThreshold=0x1.199999999999ap-1|surface.rockySlopeThreshold=0x1.0p1|decoration.chanceDenominator=96|decoration.maximumOutcropHeight=3|spawn.maximumSearchRadiusBlocks=96|spawn.requiredEmptyBlocks=2
```

The approved 81-chunk region hash is:

```text
161f6c10773c8dfd84e6961183e8706d5a0ec00750e727e83c4a08afcfbd5ce8
```

The 2026-07-25 Game-owner correction replaced the biome softmax's
`Math.exp` calls with `StrictMath.exp`. Repeated provider and locked snapshot
tests produced the same block bytes and every hash above, so this
platform-stability correction does not change algorithm version `1` or any
snapshot constant.

Representative chunk snapshots:

| Purpose | Block coordinate | Chunk key | SHA-256 |
| --- | ---: | ---: | --- |
| Origin; nearest rolling-hills dominant sample | `(0, 0)` | `(0, 0)` | `3ffb824a152c4e6f1f3333d1d785bc2645a73a685cfcac6c3b6b964232c8bd73` |
| Nearest plains dominant sample | `(29, -45)` | `(1, -3)` | `56f65cf7d77948b8de20a192dde0a9d31e903b6ec04eb7811afb1ad62e81374a` |
| Nearest rocky-highlands dominant sample | `(0, 44)` | `(0, 2)` | `fa65749a079e1cb8befef4f05b4d2db04a6f59bea28822628dc5d1321aa693f6` |
| Positive chunk boundary and nearest cave cell | `(16, 2, 0)` | `(1, 0)` | `8dfcb80a424ffe535b740be56adcae0e6d6286d5ec5b5c811e99953deb56e9cf` |
| Negative-coordinate coverage | `(-1, -1)` | `(-1, -1)` | `743d49d229d22d7400898f43dae920a9195c3065915f529439861568ea5c9e3c` |

Biome coordinates are selected by squared distance from the origin, then X,
then Z. The cave coordinate uses the same horizontal ordering, then Y. The
cave cell is air inside the configured carve interval, while the protected
bedrock and surface-buffer cells remain solid.

## Visual revision version-1 review candidate (historical)

The terrain/cave visual revision intentionally changes generated bytes but has
the following historical review result before its required algorithm-version
promotion. These values are retained for audit only and are not final
version-2 constants.

- Candidate seed: `12345L`
- Candidate algorithm version: `1` for comparison only; it must become `2`
  before promotion
- Candidate configuration SHA-256:
  `b68aebd22b63bdfb0cb1916f4f7041ad17e2ec896b79d281747620d54eb99a86`
- Candidate aggregate region SHA-256:
  `9974c7cec96ed503d7e1d21527f80eeac24f892471ca6dd358cd440c12d6f329`
- Tree blocks in the fixed region: 376 logs and 3,385 leaves
- Explicit entrance surface cells: 47
- Reachable entrance components: 2
- Largest entrance-reachable component: 2,337 air cells
- Maximum entrance depth: 47 blocks
- Maximum entrance component span: 10 Chunks
- Outcrop columns by dominant biome `(plains, hills, highlands)`:
  `(0, 0, 10)`

Candidate inspection coordinates:

| Purpose | Coordinate |
| --- | ---: |
| Plains tree | `(-63, -61)` |
| Rolling-hills tree | `(-50, -54)` |
| Cave entrances | `(77, 29, -16)` and `(25, 28, -6)` |
| Deep reachable cave | `(14, 7, -56)` |
| Cross-Chunk tunnel air | `(16, 8, -55)` |
| Rocky outcrop | `(-19, 50)` |

This review candidate established the approved visual direction. It could not
be promoted with algorithm version `1` because it intentionally changed
terrain shaping, trees, outcrops, chambers, tunnels, entrances, and resulting
Chunk bytes.

## Final visual revision version 2

The user approved the Phase 4 visual direction with deferred rendering
limitations. Algorithm version `2` is therefore the final revised generation
contract while the original version-1 configuration and hashes above remain
immutable history.

- Seed: `12345L`
- Algorithm version: `2`
- Default inclusive Chunk radius: `4` (81 Chunks)
- Canonical configuration SHA-256:
  `56cb2f243319c7cf275ade89f480f9208ce5c1f85334eb225e6b56ed18e3012a`
- Aggregate region SHA-256:
  `ec2c76a97f36d34b7360ae9abbb0be60fb8790f275fdaf5227a7daeae9754353`
- Tree blocks: 220 logs and 2,089 leaves
- Explicit entrance surface cells: 304
- Entrance-reachable components: 10
- Largest entrance-reachable component: 73,558 air cells
- Maximum entrance depth: 63 blocks
- Maximum entrance component span: 23 Chunks
- Outcrop columns by dominant biome `(plains, hills, highlands)`:
  `(0, 0, 33)`

Version-2 representative Chunk snapshots:

| Purpose | Chunk key | SHA-256 |
| --- | ---: | --- |
| Origin | `(0, 0)` | `be50d65edfef7a20fa20f93e3da65835e05c143600b79d5dcbedad7323debc2e` |
| Plains coverage | `(1, -3)` | `857c9a85799b9dcc7ddf4a2f6a5bee3b58c7e49142a17f6fc8abc46e43c97ea0` |
| Highlands coverage | `(0, 2)` | `fb7ff4753fa1b008a6f2da3add9139e774a500bd43a878f75ad36564e0985b81` |
| Positive Chunk boundary | `(1, 0)` | `843a1f350723c87b2def6ae1cb9f305da12ea607bb6a6a1ce1de8447f3acf923` |
| Negative-coordinate coverage | `(-1, -1)` | `225a5c0b5c00064cf23ffb250b95f153fd8b98e04dfc9b8529958dd88641484a` |

Version-2 inspection coordinates:

| Purpose | Coordinate |
| --- | ---: |
| Plains tree | `(-33, 33)` |
| Rolling-hills tree | `(0, 12)` |
| Surface cave entrances | `(-22, 24, 3)` and `(-6, 23, -21)` |
| Additional entrance sample | `(74, 64, -60)` |
| Deep reachable cave | `(67, 2, -64)` |
| Cross-Chunk tunnel air | `(48, 57, -45)` |
| Rocky outcrop | `(-50, 8)` |

The v1-to-v2 change is intentional: biome shaping, deterministic tree and
outcrop descriptors, noise chambers, continuous tunnels, and explicit surface
entrances all alter canonical Chunk bytes. Because
`DeterministicCoordinateSampler` includes the algorithm version, every
version-2 statistic, feature coordinate, representative hash, and aggregate
hash was regenerated rather than copied from the review candidate.

## Manual review checklist

For an intentional world-generation change, inspect at least:

- origin `(0, 0)`;
- negative block/chunk boundary `(-1, -1)`;
- positive chunk boundary columns X `15` and `16` for Z `0..15`;
- plains `(29, -45)`, rolling hills `(0, 0)`, and rocky highlands `(0, 44)`;
- cave cell `(16, 2, 0)` plus its bedrock and surface-buffer bounds;
- version-1 decoration tops against `maximumOutcropHeight = 3`;
- version-2 decoration tops against `maximumOutcropHeight = 6`;
- forward, reverse, shuffled, and four-worker generation of all 81 chunks;
- a failed stage, confirming that later stages do not run and no chunk data is
  published.
- for version 2, both explicit entrances and at least one connected
  underground tunnel/chamber from the version-2 coordinate table above.

## Reproduction

Windows PowerShell:

```powershell
.\gradlew.bat :game:test `
  --tests com.gaia.world.generation.WorldGenerationDeterminismTest `
  --tests com.gaia.world.generation.WorldGenerationBoundaryTest `
  --tests com.gaia.world.WorldGenerationArchitectureTest `
  --console=plain --no-daemon

.\gradlew.bat :game:test `
  --tests com.gaia.world.generation.WorldGenerationSnapshotTest `
  --rerun-tasks --console=plain --no-daemon

.\gradlew.bat clean :game:test `
  --tests com.gaia.world.generation.WorldGenerationSnapshotTest `
  --console=plain --no-daemon
```

macOS:

```bash
./gradlew :game:test \
  --tests com.gaia.world.generation.WorldGenerationDeterminismTest \
  --tests com.gaia.world.generation.WorldGenerationBoundaryTest \
  --tests com.gaia.world.WorldGenerationArchitectureTest \
  --console=plain --no-daemon

./gradlew :game:test \
  --tests com.gaia.world.generation.WorldGenerationSnapshotTest \
  --rerun-tasks --console=plain --no-daemon

./gradlew clean :game:test \
  --tests com.gaia.world.generation.WorldGenerationSnapshotTest \
  --console=plain --no-daemon
```

Run the snapshot command in two separate Gradle processes and require
byte-for-byte identical results before approving constants.

## Intentional update protocol

Do not edit hashes merely to make a failing test green.

1. Explain the intended terrain/configuration change and review its boundaries,
   scheduling independence, failure behavior, and architecture coupling.
2. Increment `algorithmVersion` for an algorithm change, or make the intended
   configuration change visible in `canonicalFingerprintInput()`. Never hide a
   changed algorithm behind the same version and configuration.
3. Run behavior and architecture tests before generating any new constants.
4. Generate candidate hashes twice in separate Gradle processes, including one
   clean build, and compare the full outputs byte-for-byte.
5. Manually review every coordinate in the checklist and select new
   representative coordinates only by the deterministic ordering above.
6. Update the exact tests and this document in the same reviewed change,
   recording why the old snapshots were intentionally superseded.
