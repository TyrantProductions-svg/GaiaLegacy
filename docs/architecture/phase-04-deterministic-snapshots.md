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

## Manual review checklist

For an intentional world-generation change, inspect at least:

- origin `(0, 0)`;
- negative block/chunk boundary `(-1, -1)`;
- positive chunk boundary columns X `15` and `16` for Z `0..15`;
- plains `(29, -45)`, rolling hills `(0, 0)`, and rocky highlands `(0, 44)`;
- cave cell `(16, 2, 0)` plus its bedrock and surface-buffer bounds;
- decoration tops against `maximumOutcropHeight = 3`;
- forward, reverse, shuffled, and four-worker generation of all 81 chunks;
- a failed stage, confirming that later stages do not run and no chunk data is
  published.

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
