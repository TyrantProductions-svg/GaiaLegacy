# Resource, Material, and Data-Driven Asset System Design

## Status

Approved in conversation on 2026-07-23.

## Baseline

Phase 2 starts from `main` commit
`bb5368bdcb4e55d0939f4600aad6afef0c0f5f14`
(`Refactor/engine lifecycle (#6)`) on the clean branch
`feat/resource-material-system`.

The baseline passes:

```text
.\gradlew.bat clean test build --console=plain --no-daemon
```

Phase 2 preserves the lifecycle, threading, rendering, input, and shutdown
contracts established in Phase 1.

## Goal

Replace classpath directory scanning and hard-coded block texture selection with
a deterministic, JAR-safe asset pipeline. Blocks, materials, atlas regions, and
future module resources are identified by namespaced resource identifiers and
loaded through explicit indexes.

The result must:

- load the same definitions from IDE classpaths and packaged JARs;
- preserve byte block IDs and the current grass, dirt, and stone appearance;
- remove concrete grass, dirt, and stone knowledge from engine rendering code;
- report invalid resources with their source and field;
- provide visible missing-material and missing-texture behavior;
- keep all OpenGL and GPU work on the OpenGL context thread.

## Non-goals

Phase 2 does not:

- add transparent sorting, render queues, blending pipelines, or new shaders;
- implement item behavior, mouth interactions, or two-handed gameplay;
- add module hot reload, resource packs, resource priority, or override rules;
- change terrain generation, movement, physics, chunk dimensions, or gameplay;
- change the Phase 1 fixed-update rate or lifecycle;
- copy code, textures, models, or other assets from Terasology, Create, or any
  other project;
- introduce OpenGL 4.3+, compute shaders, or platform-specific graphics APIs;
- expand `ServiceLocator`.

## Selected resource-discovery approach

Each contributing module or JAR publishes a classpath discovery file:

```text
META-INF/gaialegacy/resource-indexes.list
```

Each non-empty, non-comment line names a namespace manifest:

```text
assets/gaia/resource-index.json
```

`AssetManager` calls:

```java
classLoader.getResources(
    "META-INF/gaialegacy/resource-indexes.list"
)
```

It reads every returned URL, collects manifest paths, removes identical
discoveries, and resolves every matching manifest URL. The manager does not
convert classpath URLs to files, walk source directories, or inspect JAR
internals directly. This makes IDE directories and JAR entries use the same
stream-based path.

Blank lines and lines beginning with `#` are ignored. Discovery URLs, manifest
paths, and definition paths are sorted before parsing so diagnostics are stable.
Sorting never implements precedence: duplicate logical resources remain errors.

Phase 2 requires a namespace to be owned by exactly one manifest. Multiple
manifests declaring the same namespace are rejected. Resource override and
same-namespace aggregation are intentionally deferred.

## Namespace manifest

The Gaia manifest is:

```json
{
  "namespace": "gaia",
  "blocks": [
    "blocks/air.json",
    "blocks/grass.json",
    "blocks/dirt.json",
    "blocks/stone.json"
  ],
  "materials": [
    "materials/opaque.json",
    "materials/missing.json"
  ],
  "atlases": [
    "atlases/blocks.json"
  ]
}
```

Manifest entries are UTF-8 resource paths relative to
`assets/<namespace>/`. Absolute paths, empty segments, backslashes, `.`, `..`,
and attempts to leave the namespace root are rejected.

Every listed definition must exist. A manifest entry that cannot be opened is a
startup error rather than an optional missing reference.

## Module boundaries

### Engine

The `engine` module owns reusable, game-independent data and access contracts:

```text
com.overlord.assets.ResourceLocation
com.overlord.assets.AssetManager
com.overlord.assets.ResourceIndex
com.overlord.assets.AssetDiagnostic
com.overlord.assets.AssetLoadReport
com.overlord.assets.AssetLoadException
com.overlord.renderer.material.RenderType
com.overlord.renderer.material.MaterialDefinition
com.overlord.renderer.texture.TextureRegion
com.overlord.renderer.texture.TextureAtlasMetadata
com.overlord.voxel.BlockFace
com.overlord.voxel.BlockRenderInfo
com.overlord.voxel.BlockRenderResolver
```

These classes are immutable values or narrow interfaces. The engine does not
depend on Gson or on `game`.

`AssetManager` owns classloader access and resource discovery, but not
Gaia-specific JSON parsing. It is constructed with an explicit `ClassLoader`
for production and test isolation.

### Game

The `game` module owns Gaia domain definitions and Gson parsing:

```text
com.gaia.blocks.BlockDefinition
com.gaia.blocks.ItemFormDefinition
com.gaia.blocks.BlockRegistry
com.gaia.assets.GaiaResourceLoader
com.gaia.assets.GaiaAssetCatalog
```

`GaiaResourceLoader` parses the discovered manifests and definition files,
performs cross-resource validation, builds immutable registries, and returns a
`GaiaAssetCatalog`. The catalog contains the block registry, material registry,
atlas metadata, selected block atlas, and the complete load report.

The existing `Block` and `BlockProperties` APIs may be adapted behind the
registry where existing physics or gameplay code still needs them, but they do
not remain a second authoritative definition source.

## Resource identifiers

`ResourceLocation` is an immutable namespaced identifier:

```text
namespace:path
```

Examples:

```text
gaia:grass
gaia:textures/atlas.png
gaia:grass_top
```

The namespace and path are lowercase. The namespace accepts ASCII letters,
digits, `_`, `-`, and `.`. The path accepts those characters plus `/`. A path
must not begin or end with `/`, contain an empty segment, contain `.` or `..`
segments, or use a platform separator.

Parsing never silently supplies a default namespace. JSON authors must write
complete identifiers, which keeps module ownership explicit.

For raw assets, `gaia:textures/atlas.png` maps to:

```text
assets/gaia/textures/atlas.png
```

When opening that classpath path, zero matching URLs means missing and more than
one matching URL means ambiguous ownership. Both outcomes are reported with the
requested `ResourceLocation`.

## Block IDs and compatibility

Every block definition contains:

- a fixed numeric ID in the inclusive range `0..255`;
- a unique `ResourceLocation` name.

The initial mapping remains:

```text
0 = gaia:air
1 = gaia:grass
2 = gaia:dirt
3 = gaia:stone
```

World and save-facing storage remains byte-based. Registry boundaries use
`Byte.toUnsignedInt(byte)` so IDs `128..255` are not interpreted as negative
registry keys.

Duplicate numeric IDs and duplicate names are startup errors. IDs are never
assigned dynamically, including for definitions whose ID is zero.

Air ID zero remains the world-format empty-block invariant. Its definition has
no item form and resolves to non-renderable `BlockRenderInfo`. No grass, dirt,
or stone constant remains in engine code.

World generation resolves `gaia:grass`, `gaia:dirt`, and `gaia:stone` once
during construction or loading and stores their numeric IDs. It does not perform
string lookups for every generated voxel.

## Block definition

A normal block definition has this form:

```json
{
  "id": 1,
  "name": "gaia:grass",
  "material": "gaia:opaque",
  "textures": {
    "top": "gaia:grass_top",
    "bottom": "gaia:dirt",
    "sides": "gaia:grass_side"
  },
  "hardness": 0.6,
  "structuralIntegrity": 0.7,
  "tolerance": 0.4,
  "gravity": false,
  "flammable": false,
  "blastResistance": 0.5,
  "item": {
    "id": "gaia:grass",
    "maxStackSize": 64,
    "mouthHoldable": false,
    "twoHanded": false
  }
}
```

The authoritative fields are:

- `id`;
- `name`;
- `material`;
- six resolved face textures;
- `hardness`;
- `structuralIntegrity`;
- `tolerance`;
- `gravity`;
- `flammable`;
- `blastResistance`;
- optional `item`.

The item form contains:

- `id`;
- `maxStackSize`, restricted to `1..64`;
- `mouthHoldable`;
- `twoHanded`.

`twoHanded` is parsed, stored, and validated, but has no Phase 2 gameplay
behavior. Air may omit `item`; ordinary block items default to the block name
only when the nested `item` object is present without an explicit item ID.

Physical numeric values must be finite and non-negative. Unknown JSON fields are
rejected so misspelled gameplay properties cannot silently use defaults.

## Face texture expansion

Texture mappings accept these keys:

```text
all
sides
top
bottom
north
south
east
west
up
down
```

Aliases map `top` to `up` and `bottom` to `down`. Expansion applies values in
this order:

```text
all -> sides -> top/bottom -> six explicit directions
```

Later entries override earlier shorthands. After expansion, each renderable
block has exactly one texture-region identifier for every `BlockFace`. Any face
left unresolved receives the explicit missing region and a warning.

## Materials and render types

A material definition has this form:

```json
{
  "id": "gaia:opaque",
  "atlas": "gaia:blocks",
  "renderType": "opaque",
  "alphaCutoff": 0.5,
  "missingRegion": "gaia:missing"
}
```

`RenderType` accepts:

```text
opaque
cutout
transparent
```

All three values are parsed, validated, and exposed to later systems. Current
grass, dirt, and stone definitions use `opaque`.

`alphaCutoff` must be finite and in `0..1`. It is retained as material data for
cutout rendering, but Phase 2 does not add shader branches or a cutout pipeline.
Transparent materials similarly do not activate sorting or blending changes in
this phase.

The block definition refers to a material instead of duplicating render state.
Its resolved render type is therefore obtained through the material reference.

## Atlas metadata and UVs

The block atlas metadata has this form:

```json
{
  "id": "gaia:blocks",
  "texture": "gaia:textures/atlas.png",
  "width": 128,
  "height": 64,
  "regions": {
    "gaia:grass_top": {
      "x": 0,
      "y": 0,
      "width": 16,
      "height": 16
    },
    "gaia:grass_side": {
      "x": 16,
      "y": 0,
      "width": 16,
      "height": 16
    },
    "gaia:dirt": {
      "x": 32,
      "y": 0,
      "width": 16,
      "height": 16
    },
    "gaia:stone": {
      "x": 48,
      "y": 0,
      "width": 16,
      "height": 16
    },
    "gaia:missing": {
      "x": 80,
      "y": 0,
      "width": 16,
      "height": 16
    }
  }
}
```

Atlas dimensions and region coordinates are positive integers. Every region
must remain within atlas bounds. Region IDs are unique across the atlas.

`TextureRegion` stores pixel coordinates and dimensions and computes normalized
UV bounds from `TextureAtlasMetadata`. The conversion preserves the current
image-origin and winding convention so grass, dirt, and stone do not flip or
move.

The existing atlas keeps its current used pixels unchanged. Phase 2 adds an
original GaiaLegacy purple-and-black checker tile in an unused atlas region.
No third-party art is used.

## Render lookup

`ChunkMeshBuilder` no longer switches on numeric block types. It receives a
`BlockRenderResolver` through explicit construction. The resolver maps an
unsigned block ID to immutable `BlockRenderInfo`, which contains:

- whether the block produces geometry;
- its resolved material;
- its six resolved texture regions.

Mesh construction asks for the region associated with the face being emitted
and writes that region's normalized UVs. The hot path remains numeric and
allocation-free; namespaced lookups occur while building the resolver, not per
vertex.

This narrow engine interface prevents `engine` from depending on
`BlockDefinition` or on Gaia block names.

## Bootstrap and threading

`GameBootstrap` remains the application composition root. Before constructing
world-loading work it performs:

```text
discover indexes
-> parse manifests
-> parse atlas/material/block definitions
-> validate identifiers, fields, and references
-> build immutable registries and render lookup
-> inject the catalog into Engine, Renderer, and WorldLoader
```

Only after this sequence succeeds may world generation begin.

Classpath reads, UTF-8 decoding, Gson parsing, and validation are CPU
operations. They do not invoke GLFW or OpenGL. Texture GPU creation and upload
remain owned by Renderer and execute on the Phase 1 main/OpenGL thread.

The asset system does not create its own executor. Any later background asset
work must produce CPU-side data and hand it to the main thread for GPU upload.

## Missing assets

Missing references are visible and diagnosable:

- an unknown texture-region reference resolves to `gaia:missing`;
- an unknown material reference resolves to the missing material;
- the normal missing region is the purple-and-black tile in the Gaia atlas.

The loader contains a minimal, non-gameplay `MissingAssets` safety definition
to prevent a recursive fallback failure if the JSON missing material or missing
region is itself damaged. This safety definition is reserved infrastructure;
resource definitions may not replace it.

If the entire atlas image is absent or cannot be decoded, Renderer logs the
asset source and creates a minimal procedural purple-and-black texture on the
main thread. This final GPU fallback does not mask malformed JSON, duplicate
IDs, invalid atlas bounds, or missing manifest entries.

## Error policy

Fatal startup errors include:

- malformed or invalid JSON;
- an unknown JSON field;
- duplicate namespace, numeric ID, resource ID, or atlas region ID;
- invalid `ResourceLocation`;
- numeric ranges or atlas bounds outside their allowed limits;
- a manifest entry whose definition file does not exist;
- a required field that is absent;
- an ambiguous raw classpath resource with multiple owners.

Recoverable reference problems include:

- a block referencing an unknown texture region;
- a block referencing an unknown material;
- a material referencing an unknown missing region;
- an atlas texture image that Renderer cannot load or decode.

Each diagnostic records:

- severity;
- stable error code;
- source manifest or JSON path;
- related `ResourceLocation`, when available;
- JSON field, when available;
- human-readable reason;
- selected fallback, when applicable.

Example:

```text
WARN ASSET_MISSING_REGION
source=assets/gaia/blocks/grass.json
resource=gaia:not_found
field=textures.top
fallback=gaia:missing
```

Fatal diagnostics are aggregated in deterministic order and thrown as an
`AssetLoadException` after the applicable validation pass. Recoverable
diagnostics remain in `AssetLoadReport`; Bootstrap logs a summary before world
loading starts.

## Tests

### Resource identifiers

Tests cover:

- valid parsing and round trips;
- equality and stable string form;
- missing namespaces;
- uppercase and illegal characters;
- absolute paths, backslashes, empty segments, and traversal attempts.

### Classpath and JAR loading

Tests create temporary classpath directories and temporary JARs with
`JarOutputStream`. A dedicated `URLClassLoader` verifies:

- discovery of `META-INF/gaialegacy/resource-indexes.list`;
- loading the namespace manifest and its definitions;
- identical behavior for directory and JAR URLs;
- multiple discovery files collected in stable order;
- missing and ambiguous raw resources reported clearly.

These tests do not launch the game or create a GLFW window.

### Parsing and validation

Tests cover:

- valid block, material, and atlas parsing;
- all face shorthand and override combinations;
- duplicate numeric and logical block IDs;
- duplicate materials and atlas regions;
- malformed JSON and unknown fields;
- missing definition files;
- non-finite, negative, and out-of-range values;
- out-of-bounds atlas regions;
- optional item forms and the reserved `twoHanded` field;
- missing texture and material substitutions;
- diagnostic source, field, resource ID, and fallback.

### Render mapping

Tests cover:

- pixel-to-normalized UV conversion;
- current grass-top, grass-side, dirt, and stone coordinates;
- six-face resolution through `BlockRenderResolver`;
- non-renderable air;
- `ChunkMeshBuilder` using resolver data instead of a numeric block switch.

No unit test invokes OpenGL. CPU-side missing texture generation is tested
separately from Renderer upload.

### Packaged artifact

The build verifies that the game JAR contains:

```text
META-INF/gaialegacy/resource-indexes.list
assets/gaia/resource-index.json
assets/gaia/blocks/*.json
assets/gaia/materials/*.json
assets/gaia/atlases/*.json
assets/gaia/textures/atlas.png
```

An integration test loads the packaged resource layout through an isolated
classloader. Manual `:game` verification confirms that the current world still
displays and movement remains unchanged.

## Documentation and attribution

Phase 2 creates `THIRD_PARTY_NOTICES.md` with the versions, licenses, and
official sources of dependencies actually used by the repository:

- LWJGL 3.3.3 — BSD 3-Clause:
  `https://github.com/LWJGL/lwjgl3`;
- GLFW distributed through LWJGL — zlib/libpng:
  `https://www.glfw.org/license`;
- stb distributed through LWJGL — public domain or MIT, according to the
  upstream stb components;
- JOML 1.10.5 — MIT:
  `https://central.sonatype.com/artifact/org.joml/joml`;
- Gson 2.10.1 — Apache License 2.0:
  `https://github.com/google/gson`;
- JUnit 6.1.1 — Eclipse Public License 2.0:
  `https://github.com/junit-team/junit-framework`;
- Gradle Wrapper — Apache License 2.0:
  `https://github.com/gradle/gradle`.

`docs/references.md` distinguishes architectural inspiration from included
third-party material. It records that GaiaLegacy considers Terasology's public
modularity and data-driven concepts, without copying its code or assets, and
that it does not copy Create code or art.

The notices explicitly state that Phase 2 adds no third-party code or art.

## Stable outcomes for later phases

Later phases must preserve:

- `ResourceLocation` syntax and equality;
- fixed unsigned block IDs `0..255`;
- the existing IDs `air=0`, `grass=1`, `dirt=2`, and `stone=3`;
- stream-based asset access that works in both directories and JARs;
- discovery through `META-INF/gaialegacy/resource-indexes.list`;
- immutable registry/catalog publication after validation;
- `BlockRenderResolver` as the engine/game rendering boundary;
- six-face `TextureRegion` lookup through atlas metadata;
- explicit missing asset diagnostics and visible fallback;
- main-thread-only OpenGL and GPU resource ownership;
- Java 17 source compatibility and macOS OpenGL 4.1 / GLSL 410;
- constructor or context injection without expanding `ServiceLocator`.

## Verification and handoff

Required Windows verification:

```text
.\gradlew.bat clean test build
.\gradlew.bat :game
```

Required macOS verification:

```text
./gradlew clean test build
./gradlew :game
```

Automated verification must not launch a GLFW window. The interactive `:game`
commands are manual platform checks.

Phase 2 ends with:

```text
docs/agent-handoffs/phase-02-handoff.md
```

The handoff records completed and incomplete work, core architecture decisions,
modified files, test commands and results, known risks, and interfaces the next
phase must not break.
