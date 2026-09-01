# Phase 10 UI Icon and Font Assets

## Provenance

The final Phase 10 font is **Quiet Rune 5x7 version 1**, independently authored
for GaiaLegacy from blank five-column by seven-row grids. Its explicit `#`/`.`
pattern grammar, rasterizer, atlas, and metadata are repository-owned work. The
icon generator and icon atlas are also original GaiaLegacy work derived only
from the repository's existing canonical block textures. No web artwork,
copied game art, operating-system font, or third-party font file is used.

An earlier candidate contained provenance-ambiguous Mode 13h/Allegro-style
8x8 row bytes. Those bytes were discarded rather than relabelled or licensed,
and are not present in the final source or generated atlas. A regression test
rejects their printable-ASCII SHA-256 fingerprint
`a2b901a9fc90cdfca0ae078ad12747fab8487713a8860d9e6ca0f2b235fd6b5c`.
Allegro is not a GaiaLegacy source or binary dependency.

The assets add no third-party license beyond the dependencies already recorded
in [`THIRD_PARTY_NOTICES.md`](../../THIRD_PARTY_NOTICES.md). The generators do
not alter the shared block atlas or any Phase 2 texture-region coordinates.

## Source and generated files

Project-owned generator sources:

- `tools/src/main/java/com/gaia/tools/ui/GlyphSource.java`;
- `tools/src/main/java/com/gaia/tools/ui/BitmapFontGenerator.java`;
- `tools/src/main/java/com/gaia/tools/ui/BlockIconGenerator.java`;
- `tools/src/main/java/com/gaia/tools/ui/UiAssetGenerator.java`.

Candidate runtime resources intended for the Phase 10 commit:

- `game/src/main/resources/assets/gaia/ui/ui-assets.json`;
- `game/src/main/resources/assets/gaia/ui/ui_font.png`;
- `game/src/main/resources/assets/gaia/ui/ui_font.json`;
- `game/src/main/resources/assets/gaia/ui/ui_icons.png`;
- `game/src/main/resources/assets/gaia/ui/ui_icons.json`.

Runtime loads these through `AssetManager` using JAR-safe identities:

| Identity | Classpath path |
| --- | --- |
| `gaia:ui/ui-assets.json` | `assets/gaia/ui/ui-assets.json` |
| `gaia:ui/ui_font.png` | `assets/gaia/ui/ui_font.png` |
| `gaia:ui/ui_font.json` | `assets/gaia/ui/ui_font.json` |
| `gaia:ui/ui_icons.png` | `assets/gaia/ui/ui_icons.png` |
| `gaia:ui/ui_icons.json` | `assets/gaia/ui/ui_icons.json` |

`game/src/main/resources/assets/gaia/resource-index.json` declares all five UI
resources. The engine UI shaders use `overlord:shaders/ui/ui.vert` and
`overlord:shaders/ui/ui.frag`.

## Deterministic generation commands

Intentional source-resource regeneration:

```powershell
.\gradlew.bat :tools:generateUiAssets --console=plain --no-daemon
```

The task invokes `com.gaia.tools.ui.UiAssetGenerator` with four normalized
relative output paths under `game/src/main/resources/assets/gaia/ui`. It rejects
absolute, non-normalized, duplicate, symlink-escaping, or root-escaping output
paths. Block-backed icons continue to use the canonical block faces. The
standalone `gaia:chisel` icon resolves its explicit `ATLAS_REGION` presentation
through the same canonical item-form owner and block atlas; it does not require
or invent block backing.

Normal verification is compare-only:

```powershell
.\gradlew.bat :tools:verifyGeneratedUiAssets `
  --rerun-tasks --console=plain --no-daemon
```

This task regenerates all four generated assets into
`tools/build/generated-ui-verification/`, byte-compares each output with the
checked-in resource, and reports the first differing byte. It never rewrites
source resources. `:tools:check` depends on this verification.

## Bitmap font contract

The Quiet Rune font atlas is deterministic RGBA8:

- dimensions: 128 by 64 pixels;
- cells: 16 columns by 8 rows;
- cell and glyph canvas: 8 by 8 pixels;
- authored drawing area: centred columns 1 through 5 and rows 0 through 6;
- advance: 8 pixels for every glyph;
- bearing: X 0, Y 8;
- tintable foreground: white RGBA;
- transparent pixels: white RGB, alpha zero;
- sampling: nearest-neighbour, clamp-to-edge, base/max level zero, no mipmaps.

Exact glyph coverage is 97 code points:

- printable ASCII U+0020 through U+007E in cells 0 through 94, row-major;
- infinity U+221E in cell 95 (`column 15`, `row 5`);
- missing glyph U+FFFD in cell 96 (`column 0`, `row 6`).

All remaining cells are transparent. Unsupported text resolves to U+FFFD and
is diagnosed once per unsupported code point. The font supports the exact
English UI text, quantities, punctuation, and Creative infinity used by Phase
10 without a system-font fallback.

The generated JSON records source name `Quiet Rune 5x7`, algorithm
`quiet-rune-5x7`, version `1`, and printable-ASCII source fingerprint
`d53cb032352c768e6ab17816d34a426ad376c2b08154c41072e404ae3498a662`.
The fingerprint is SHA-256 over the 95 rasterized eight-byte cells in ascending
U+0020..U+007E order. Any intentional source change requires a reviewed
algorithm-version and fingerprint update.

## Icon atlas contract

The icon atlas is deterministic RGBA8:

- dimensions: 128 by 64 pixels;
- grid: 4 columns by 2 rows;
- cell and icon canvas: 32 by 32 pixels;
- sampling: nearest-neighbour, clamp-to-edge, base/max level zero, no mipmaps;
- three-face isometric projection uses canonical `UP`, `NORTH`, and `EAST`
  block texture regions;
- fixed identity-preserving face light is top 100%, north 82%, east 68%.

Exact row-major mapping:

| Cell | Region | Canonical identity | Display name | Role |
| --- | --- | --- | --- | --- |
| `(0,0)` | `0,0,32,32` | `gaia:grass` | Grass | normal |
| `(1,0)` | `32,0,32,32` | `gaia:dirt` | Dirt | normal |
| `(2,0)` | `64,0,32,32` | `gaia:stone` | Stone | normal |
| `(3,0)` | `96,0,32,32` | `gaia:oak_log` | Oak Log | normal |
| `(0,1)` | `0,32,32,32` | `gaia:oak_leaves` | Oak Leaves | normal |
| `(1,1)` | `32,32,32,32` | `gaia:missing` | Missing | explicit fallback |
| `(2,1)` | none | none | none | unassigned |
| `(3,1)` | none | none | none | unassigned |

`UiIconDefinition`, `UiIconAtlas`, and `UiIconResolver` are immutable
presentation metadata keyed by canonical `ResourceLocation`. They do not define
item rules, create stack identity, or form a second item registry. Block-backed
identities and the explicit standalone `gaia:chisel` visual are resolved from
the existing `BlockRegistry` item-form ownership slot. An unknown identity
resolves only to `gaia:missing`, never to another real item, and is diagnosed
once per ID.

## Exact SHA-256 fingerprints

Hashes verified from the current candidate resources:

| File | SHA-256 |
| --- | --- |
| `ui_font.png` | `a6a27be503ff26fd119cfe3ab74375faf7fbc22e13e1ed5670e6f2d56f5fd1ca` |
| `ui_font.json` | `ec98df77b826b03df7fecfa2e77fadf540c47dc6e01e3b2664dd8aff35636ac4` |
| `ui_icons.png` | `732a12f9815adfd3332b67466240a908481b29b845132a58ce2de4966d57fa75` |
| `ui_icons.json` | `91b27613c0c45b523edeabc982a81b30ab8b34729605264aa6ac41af326dedb4` |

PowerShell verification:

```powershell
$uiAssets = @(
  'game/src/main/resources/assets/gaia/ui/ui_font.png'
  'game/src/main/resources/assets/gaia/ui/ui_font.json'
  'game/src/main/resources/assets/gaia/ui/ui_icons.png'
  'game/src/main/resources/assets/gaia/ui/ui_icons.json'
)
Get-FileHash $uiAssets -Algorithm SHA256
```

## Packaging verification

Use all four checks after any UI asset or packaging change:

```powershell
.\gradlew.bat :game:verifyPackagedResources `
  --rerun-tasks --console=plain --no-daemon
.\gradlew.bat :engine:verifyPackagedShaderResources `
  --rerun-tasks --console=plain --no-daemon
.\gradlew.bat :game:verifyInstalledShaderResources `
  --rerun-tasks --console=plain --no-daemon
.\gradlew.bat :tools:verifyGeneratedUiAssets `
  --rerun-tasks --console=plain --no-daemon
```

These verify the game JAR, engine JAR, installDist copies, JAR-safe classpath
loading, and exact generated bytes. The deleted Phase 9B crosshair shaders must
remain absent from source requirements and package inventories.

## Change rules

- Update generator source, generated PNG, metadata, fingerprints, packaging
  tests, and this document in one reviewed change.
- Do not hand-edit generated PNG bytes or silently accept a mismatch.
- Do not substitute operating-system fonts or absolute font paths.
- Do not download or copy icon artwork.
- Do not add a UI item registry or parallel stack representation.
- Do not move shared block-atlas regions to make UI generation easier.
- Keep transparent pixel RGB non-polluting and preserve straight alpha.

See [UI rendering and HUD](ui-rendering-and-hud.md) and
[Astral Membrane style guide](astral-membrane-style-guide.md).
