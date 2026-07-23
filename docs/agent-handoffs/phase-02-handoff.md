# Phase 2 Handoff: Resource, Material, and Data-Driven Assets

Phase 2 started from
`bb5368bdcb4e55d0939f4600aad6afef0c0f5f14`
(`Refactor/engine lifecycle (#6)`) and is recorded here at
`91f70c218f34789165ad0b45328535286695e645`
(`test(assets): reject trailing JSON values`) on
`feat/resource-material-system`.

## Completed work

- Added validated, comparable `ResourceLocation` values and deterministic,
  stream-based classpath discovery through
  `META-INF/gaialegacy/resource-indexes.list`. Directory and JAR resources use
  the same `ClassLoader`/stream path. Final review also hardened the namespace
  boundary by rejecting the complete `.` and `..` namespace segments before
  classpath mapping.
- Added immutable asset diagnostics, reports, index/source values, render
  material values, atlas metadata, texture regions, CPU texture images, block
  render values, and the `BlockRenderResolver` engine boundary.
- Added strict Gaia JSON parsing, deterministic multi-pass validation,
  source/field-aware diagnostics, cross-namespace references, guarded missing
  assets, an immutable `BlockRegistry`, and an immutable `GaiaAssetCatalog`.
  The final parser consumes Gson `JsonReader` tokens in strict mode, rejects
  comments, single-quoted or unquoted names, trailing values, and duplicate
  keys before building a JSON tree, and preserves exact fields for unknown,
  missing, wrong-type, and duplicate-field diagnostics.
- Preserved fixed unsigned block IDs `0..255`, including `air=0`, `grass=1`,
  `dirt=2`, and `stone=3`; removed the legacy static registry bridge and the
  obsolete `Block`/`BlockProperties` definition path.
- Added indexed production block, material, atlas, and texture resources.
  Existing grass, dirt, and stone atlas tiles were preserved. The new
  purple-and-black missing tile is original GaiaLegacy project work.
- Made block item forms optional for every block definition, while preserving
  air ID zero as the non-renderable empty-world invariant.
- Aligned missing material and region resolution with the actually selected
  atlas. A missing `material.missingRegion` now warns and resolves to the
  selected atlas checker tile. Missing/undecodable/I/O-failed complete atlas
  images publish matching procedural 2x2 metadata and UVs; ambiguous classpath
  ownership remains fatal.
- Replaced engine block-specific UV selection with constructor-injected,
  six-face `TextureRegion` lookup. World generation resolves Gaia block IDs
  once from the registry. Neighbor occlusion now also uses resolved
  `renderable()` state, so unknown or non-renderable nonzero IDs cannot leave
  holes in adjacent geometry.
- Loads and validates assets before world work, injects CPU-side
  `RenderAssets` into `Engine`/`Renderer`, and uploads the selected texture only
  after the existing main-thread guard succeeds.
- Added real packaged-JAR resource verification to `game:check`.
- Added focused engine/game tests for discovery, diagnostics, validation,
  fallback behavior, immutability, world/mesh integration, bootstrap
  composition, and production resources.
- Added third-party notices and reference/provenance documentation. Phase 2
  copied no Terasology/Create code or art and introduced no other copied
  third-party code or art.

## Incomplete work

- The interactive Windows smoke test `.\gradlew.bat :game` was not run during
  Task 11 because it opens a GLFW window. A developer must manually verify the
  world appearance, movement, mouse capture/focus behavior, resize handling,
  indexed-resource diagnostics, and Escape shutdown.
- macOS automated and interactive verification was not run locally; it
  requires a macOS developer or CI host:

  ```bash
  ./gradlew clean test build
  ./gradlew :game
  ```

- No Phase 2 implementation item is otherwise known to be unfinished. Resource
  priority/override rules, hot reload, transparent sorting, cutout/transparent
  render pipelines, item behavior, and gameplay changes remain explicitly
  deferred non-goals rather than partial Phase 2 implementations.

## Core architecture decisions

- The engine owns reusable resource access, immutable render values, CPU image
  loading, and the narrow `BlockRenderResolver`; Gaia JSON schemas and domain
  definitions remain in `game`. `engine` does not depend on `game` or Gson.
- Discovery is explicit and JAR-safe. Contributing classpath entries publish
  `META-INF/gaialegacy/resource-indexes.list`, which names sorted namespace
  manifests. Resources are opened through streams; production code does not
  walk classpath directories or inspect JAR files directly.
- Namespace/resource duplicates and ambiguous classpath ownership are errors,
  not precedence. Resource pack priority and same-namespace aggregation are
  intentionally undefined.
- `ResourceLocation` rejects complete namespace segments `.` and `..`; other
  valid dot-containing namespaces remain stable identifiers.
- Gaia JSON is parsed from strict tokens into a tree only after per-object
  duplicate-key checks. Structured field paths are retained as diagnostics
  cross schema and parser boundaries.
- Validated catalogs, registries, diagnostics, atlas metadata, texture images,
  and block render data are published as immutable or copy-owned values.
- World/save storage remains byte-based. Registry boundaries convert stored
  bytes with `Byte.toUnsignedInt`, and numeric IDs are definition data rather
  than dynamically assigned values.
- Renderable blocks resolve one material and exactly six face regions. Air ID
  zero is non-renderable. Missing material/region/image behavior stays visible
  and produces structured diagnostics. Fallback render metadata always matches
  the selected CPU image: real atlas metadata is retained when its canonical
  missing tile exists, otherwise the bound image and metadata both become the
  same procedural 2x2 checker.
- Asset discovery, JSON parsing, validation, world generation, and mesh data
  creation are CPU-only. All GLFW calls, OpenGL calls, and GPU
  create/upload/use/destroy operations remain on the main/context-owning thread.
- Dependencies are passed by constructors or the existing explicit context.
  The asset catalog is not stored in `ServiceLocator`.
- Java 17 compatibility and macOS OpenGL 4.1 / GLSL 410 remain mandatory.

## Modified files

The tracked paths below differ from the Phase 2 base, including this Task 11
handoff finalized by the documentation-only closeout commit. Local ignored
execution records are listed separately and are not part of the Git diff.

### Root and documentation

- `.gitignore`
- `THIRD_PARTY_NOTICES.md`
- `docs/references.md`
- `docs/agent-handoffs/phase-02-handoff.md`
- `docs/superpowers/specs/2026-07-23-resource-material-system-design.md`
- `docs/superpowers/plans/2026-07-23-resource-material-system.md`

### Engine production

- `engine/src/main/java/com/overlord/assets/AssetDiagnostic.java`
- `engine/src/main/java/com/overlord/assets/AssetLoadException.java`
- `engine/src/main/java/com/overlord/assets/AssetLoadReport.java`
- `engine/src/main/java/com/overlord/assets/AssetManager.java`
- `engine/src/main/java/com/overlord/assets/AssetSeverity.java`
- `engine/src/main/java/com/overlord/assets/AssetSource.java`
- `engine/src/main/java/com/overlord/assets/ResourceIndex.java`
- `engine/src/main/java/com/overlord/assets/ResourceLocation.java`
- `engine/src/main/java/com/overlord/config/GameConfig.java`
- `engine/src/main/java/com/overlord/core/Engine.java`
- `engine/src/main/java/com/overlord/renderer/RenderAssets.java`
- `engine/src/main/java/com/overlord/renderer/Renderer.java`
- `engine/src/main/java/com/overlord/renderer/Texture.java`
- `engine/src/main/java/com/overlord/renderer/material/MaterialDefinition.java`
- `engine/src/main/java/com/overlord/renderer/material/RenderType.java`
- `engine/src/main/java/com/overlord/renderer/texture/TextureAtlasMetadata.java`
- `engine/src/main/java/com/overlord/renderer/texture/TextureImage.java`
- `engine/src/main/java/com/overlord/renderer/texture/TextureImageLoader.java`
- `engine/src/main/java/com/overlord/renderer/texture/TextureRegion.java`
- `engine/src/main/java/com/overlord/voxel/BlockFace.java`
- `engine/src/main/java/com/overlord/voxel/BlockRenderInfo.java`
- `engine/src/main/java/com/overlord/voxel/BlockRenderResolver.java`
- `engine/src/main/java/com/overlord/voxel/ChunkMeshBuilder.java`

### Engine tests

- `engine/src/test/java/com/overlord/assets/AssetLoadReportTest.java`
- `engine/src/test/java/com/overlord/assets/AssetManagerTest.java`
- `engine/src/test/java/com/overlord/assets/ResourceLocationTest.java`
- `engine/src/test/java/com/overlord/core/thread/MainThreadGuardTest.java`
- `engine/src/test/java/com/overlord/renderer/RenderAssetsTest.java`
- `engine/src/test/java/com/overlord/renderer/texture/TextureAtlasMetadataTest.java`
- `engine/src/test/java/com/overlord/renderer/texture/TextureImageLoaderTest.java`
- `engine/src/test/java/com/overlord/voxel/ChunkMeshBuilderTest.java`

### Game build and production

- `game/build.gradle`
- `game/src/main/java/com/gaia/GameBootstrap.java`
- `game/src/main/java/com/gaia/assets/GaiaAssetCatalog.java`
- `game/src/main/java/com/gaia/assets/GaiaResourceLoader.java`
- `game/src/main/java/com/gaia/assets/StrictJson.java`
- `game/src/main/java/com/gaia/blocks/Block.java` (deleted)
- `game/src/main/java/com/gaia/blocks/BlockDefinition.java`
- `game/src/main/java/com/gaia/blocks/BlockProperties.java` (deleted)
- `game/src/main/java/com/gaia/blocks/BlockRegistry.java`
- `game/src/main/java/com/gaia/blocks/ItemFormDefinition.java`
- `game/src/main/java/com/gaia/world/GaiaWorldGenerator.java`
- `game/src/main/java/com/gaia/world/WorldLoader.java`

### Game resources

- `game/src/main/resources/META-INF/gaialegacy/resource-indexes.list`
- `game/src/main/resources/assets/gaia/resource-index.json`
- `game/src/main/resources/assets/gaia/atlases/blocks.json`
- `game/src/main/resources/assets/gaia/blocks/air.json`
- `game/src/main/resources/assets/gaia/blocks/dirt.json`
- `game/src/main/resources/assets/gaia/blocks/grass.json`
- `game/src/main/resources/assets/gaia/blocks/stone.json`
- `game/src/main/resources/assets/gaia/materials/missing.json`
- `game/src/main/resources/assets/gaia/materials/opaque.json`
- `game/src/main/resources/assets/gaia/textures/atlas.png`
- `game/src/main/resources/blocks/dirt.json` (deleted)
- `game/src/main/resources/blocks/grass.json` (deleted)
- `game/src/main/resources/textures/atlas.png` (deleted/relocated)

### Game tests

- `game/src/test/java/com/gaia/GameBootstrapStructureTest.java`
- `game/src/test/java/com/gaia/GameBootstrapTest.java`
- `game/src/test/java/com/gaia/assets/GaiaProductionAssetsTest.java`
- `game/src/test/java/com/gaia/assets/GaiaResourceLoaderTest.java`
- `game/src/test/java/com/gaia/assets/TestAssetJar.java`
- `game/src/test/java/com/gaia/blocks/BlockRegistryTest.java`
- `game/src/test/java/com/gaia/world/GaiaWorldGeneratorTest.java`
- `game/src/test/java/com/gaia/world/WorldLoaderTest.java`

### Local ignored execution records

- `.superpowers/sdd/progress.md`
- `.superpowers/sdd/task-1-report.md` through
  `.superpowers/sdd/task-11-report.md`
- `.superpowers/sdd/task-1-brief.md` through
  `.superpowers/sdd/task-11-brief.md`
- `.superpowers/sdd/review-*.diff`

## Test commands and results

All results below are from Windows on 2026-07-23. Automated commands did not
launch a GLFW window.

### Dependency resolution

```powershell
.\gradlew.bat :engine:dependencies :game:dependencies --configuration runtimeClasspath --console=plain --no-daemon
.\gradlew.bat :engine:dependencies :game:dependencies --configuration testCompileClasspath --console=plain --no-daemon
.\gradlew.bat :engine:dependencies :game:dependencies --configuration testRuntimeClasspath --console=plain --no-daemon
```

Results: all three commands reported `BUILD SUCCESSFUL in 5s` with two actionable
tasks executed. Gradle resolved LWJGL 3.3.3, JOML 1.10.5, Gson 2.10.1, JUnit
6.1.1, transitive OpenTest4J 1.3.0, and the compile-time JUnit transitives API
Guardian 1.1.2 and JSpecify 1.0.0. These commands were used for documentation
evidence, not as a test substitute.

### Clean automated verification

```powershell
.\gradlew.bat clean test build --console=plain --no-daemon
```

The first restricted invocation could not download/access the Gradle 8.5
Wrapper distribution (`java.net.SocketException: Permission denied:
getsockopt`). The identical command was rerun with approved Wrapper
cache/network access.

Approved rerun result: `BUILD SUCCESSFUL in 13s`; all 16 actionable tasks
executed, including engine/game compilation and tests,
`:game:verifyPackagedResources`, and both module builds. JUnit XML reports
contain 22 suites and 111 tests with 0 failures, 0 errors, and 0 skipped tests.

### Final review-fix verification

The final-review fixes used focused RED/GREEN cycles for namespace traversal,
strict/malformed/duplicate JSON, structured diagnostic fields, optional item
forms, atlas/material/region fallback consistency, recoverable texture I/O,
and resolver-based neighbor occlusion. The focused suites passed before the
final clean run.

```powershell
.\gradlew.bat clean test build --console=plain --no-daemon
```

Result on Windows 2026-07-24: `BUILD SUCCESSFUL in 13s`; all 16 actionable
tasks executed. JUnit XML reports contain 22 suites and 128 tests with
0 failures, 0 errors, and 0 skipped tests. This includes
`:game:verifyPackagedResources`.

```powershell
.\gradlew.bat :game:verifyPackagedResources --rerun-tasks --console=plain --no-daemon
```

Result: `BUILD SUCCESSFUL in 7s`; all five actionable tasks executed and the
packaged game JAR contained every required indexed resource.

The trailing-value JSON regression was mutation-verified: removing the
`END_DOCUMENT` guard produced one expected RED failure among the four strict
syntax cases, and restoring it returned all four to GREEN.

### Packaged resource inspection

```powershell
jar tf game/build/libs/game-0.1.0.jar
```

Result: the generated JAR contains all ten required paths:

```text
META-INF/gaialegacy/resource-indexes.list
assets/gaia/resource-index.json
assets/gaia/blocks/air.json
assets/gaia/blocks/grass.json
assets/gaia/blocks/dirt.json
assets/gaia/blocks/stone.json
assets/gaia/materials/opaque.json
assets/gaia/materials/missing.json
assets/gaia/atlases/blocks.json
assets/gaia/textures/atlas.png
```

### Repository policy and architecture checks

```powershell
git diff --check
git ls-files | Select-String -Pattern '(^|/)(bin/|.*\.class$)'
Select-String -Path gradle.properties -Pattern 'org\.gradle\.java\.home|/Library/Java|[A-Za-z]:\\'
rg -n "BlockRegistry\.(AIR|GRASS|DIRT|STONE|init|loadAllFromResources)|BlockProperties|new Block\(" game/src
rg -n -i "\b(grass|dirt|stone)\b|textures/atlas\.png" engine/src/main
rg -n -i "getResources\(.*blocks|walk\(|Files\.list|legacyBlocks|registerFromProperties|nextModId|jsonBlockCount" engine/src/main game/src/main
rg -n "GL4[2-9]|glDispatchCompute|compute shader" engine/src/main game/src/main
```

Results: `git diff --check` printed nothing; no tracked `bin/` or `.class`
path, personal/platform-specific JDK path, legacy block API, concrete Gaia
block/atlas name in engine production code, legacy directory scanner, OpenGL
4.2+ symbol, or compute-shader use was found.

The Task 11 plan's broader search for `case 1|case 2|case 3` does find the
unchanged six-face geometry switch in `ChunkMeshBuilder`; those cases select
face vertex layouts and do not encode grass, dirt, stone, or atlas data.

### Interactive and macOS status

- `.\gradlew.bat :game`: not run during Task 11 because it opens a GLFW
  window; manual Windows verification required.
- `./gradlew clean test build`: not run locally; requires macOS
  developer/CI verification.
- `./gradlew :game`: not run locally; requires interactive macOS developer
  verification.

## Known risks

- Current automated verification proves parsing, IDs, UV coordinates, atlas
  preservation hashes, packaging, composition, and threading guards, but not
  the final on-screen presentation or interactive input/focus/resize behavior.
- Native macOS execution, Retina framebuffer behavior, and macOS native
  selection remain unverified in this Windows-only Task 11 run.
- `GaiaResourceLoader` is intentionally cohesive but large. Future refactoring
  must preserve its deterministic pass boundaries, strict token parsing before
  tree construction, duplicate-key detection, diagnostic field provenance and
  ordering, effective atlas/image alignment, and aggregation behavior.
- `RenderType.CUTOUT` and `RenderType.TRANSPARENT` are validated data values
  only; Phase 2 does not implement matching draw queues, shader branches,
  blending, or sorting.
- Resource override/priority and same-namespace aggregation are deliberately
  unsupported. Duplicate namespaces and ambiguous classpath resource owners
  remain fatal.
- The legacy general-purpose `TaskScheduler` risks recorded in the Phase 0/1
  handoffs are unchanged; Phase 2 does not use it for asset loading or GPU
  work.

## Interfaces the next phase must not break

- `ResourceLocation` remains a complete lowercase `namespace:path` identifier
  with its current validation, equality, ordering, string form, and
  `assets/<namespace>/<path>` classpath mapping.
- Discovery remains stream-based through
  `META-INF/gaialegacy/resource-indexes.list` and works from both classpath
  directories and JARs without production directory walking.
- Block IDs remain fixed unsigned values in `0..255`; world storage stays
  byte-based; `air=0`, `grass=1`, `dirt=2`, and `stone=3`.
- `BlockRegistry` and `GaiaAssetCatalog` remain immutable after validation.
  `BlockRenderResolver` remains the narrow engine/game rendering boundary.
- Renderable block lookup remains six-face `TextureRegion` atlas lookup with
  the existing face order and UV/image-origin convention. Air remains
  non-renderable.
- Missing material, region, and image paths remain visible and produce
  structured diagnostics without masking fatal schema, duplicate, bounds, or
  ambiguity errors.
- `GameBootstrap.run()` remains the composition/lifecycle boundary. Assets are
  validated before world work and are injected without expanding
  `ServiceLocator`.
- `WorldLoader.load(World)` remains CPU-only and cancellable and returns
  `WorldLoadResult(float[], Vector3f)` without GPU objects.
- `GameLoop` preserves `LOADING -> RUNNING -> STOPPING`, event polling during
  loading, one mouse delta per render frame, and synchronous fixed-update
  ordering for player, modules, and events.
- Fixed updates remain 1/60 second with a 0.25-second frame clamp and at most
  five catch-up steps. `InputManager` remains the sole GLFW
  key/cursor/focus-callback owner and preserves immutable snapshot/latched-edge
  behavior.
- `Window` keeps logical and framebuffer sizes separate. `Renderer` keeps
  guarded mesh ownership/replacement/cleanup. Mesh, Shader, and Texture require
  an explicit `MainThreadGuard`.
- Every GLFW operation, OpenGL call, and GPU resource
  create/upload/use/destroy action remains on the main/context-owning thread.
- `Engine()`, `Engine(MainThreadGuard)`, and
  `Engine(MainThreadGuard, RenderAssets)` preserve transactional initialization,
  idempotent main-thread shutdown, current getters, and scheduler compatibility
  APIs.
- Engine remains independent from game; new dependencies use constructor or
  explicit-context injection; Java 17 and macOS OpenGL 4.1 / GLSL 410 remain
  supported.

## Final phase report

Committed diff from the Phase 2 base through the final reviewed implementation
head `91f70c2`:

```text
70 files changed, 10424 insertions(+), 485 deletions(-)
```

Local `.superpowers/sdd` briefs, reports, review packages, and progress records
remain intentionally ignored. The tracked handoff update follows
`91f70c2` as the documentation-only closeout commit.

Including this tracked handoff closeout, the prepared final branch diff is:

```text
70 files changed, 10493 insertions(+), 485 deletions(-)
```

Final review-fix commits:

```text
d15c531 fix(assets): harden resource parsing and fallbacks
e25303f fix(rendering): treat non-renderable neighbors as empty
91f70c2 test(assets): reject trailing JSON values
```

Suggested documentation closeout commit:

```text
docs: update Phase 2 review-fix handoff
```

Suggested squashed Phase 2 commit:

```text
feat(assets): add data-driven block and material resource system
```

Suggested pull request title:

```text
feat(assets): add data-driven block and material resource system
```

Suggested pull request description:

```markdown
## Summary

- add deterministic JAR-safe namespaced asset discovery and strict Gaia JSON
  validation
- publish immutable block/material/atlas catalogs with visible missing-asset
  diagnostics and fallbacks
- resolve chunk UVs from injected block metadata and upload CPU-loaded atlas
  data only on the OpenGL owner thread
- package indexed Gaia resources and document dependency/source provenance

## Verification

- `.\gradlew.bat clean test build --console=plain --no-daemon`
  - BUILD SUCCESSFUL; 16/16 tasks executed
  - 128 tests in 22 suites; 0 failures/errors/skips
  - packaged-resource verification passed
- `.\gradlew.bat :game:verifyPackagedResources --rerun-tasks --console=plain --no-daemon`
  - BUILD SUCCESSFUL; 5/5 tasks executed
- repository hygiene and architecture searches passed
- Windows interactive `.\gradlew.bat :game` remains a developer smoke test
- macOS automated and interactive commands require developer/CI verification

## Provenance

No Terasology/Create code or art, or other third-party source/art, was copied.
The new purple-and-black missing tile is original GaiaLegacy project work.
```

Do not merge this branch without the required owner review and outstanding
manual/platform verification.
