# Cosmic presentation ownership (Phase 17.5)

The accepted direction is A+ / WORLD-FIRST COSMIC SURVIVAL: Gaia terrain is the
emotional foreground, with restrained Legacy cues, a left navigation field,
darker secondary surfaces and a lightweight gameplay HUD. This is presentation,
not a new product/gameplay architecture.

## One runtime path

`GameBootstrap` / `GameSessionFactory` load `GaiaUiAssets` through
`GaiaUiAssetLoader`. One immutable `TypographyCatalog` resolves semantic roles;
one `TextRenderer` emits glyph draw commands; one `UiRenderer` batches them and
owns GPU resources on the GL context thread. The existing product shell/router
and `UiHitRegion` remain input/navigation authority. `GaiaHudScreen` remains
the HUD composition path. Legacy bitmap-font constructors are compatibility
seams for tests, not an additional active Quiet Rune production renderer.

| Resource | Runtime dimensions | RGBA8 bytes | Sampling |
| --- | --- | ---: | --- |
| Pixelify display page | 256x512 | 524,288 | NEAREST |
| Inter functional/body/HUD page | 256x256 | 262,144 | LINEAR |
| Smooth brand emblem | 256x256 | 262,144 | LINEAR |
| Initial dawn hero | 1280x720 | 3,686,400 | LINEAR |

Pixelify Bold 700 serves DISPLAY_TITLE; SemiBold 600 serves HEADING_LARGE.
Inter 400/500/600 serve body/functional/HUD roles. Physical page count is two
fonts, not one page for all UI textures. IBM Plex is specimen-only.

The existing straight-alpha shader and CLAMP_TO_EDGE remain unchanged.
Transparent emblem pixels retain cyan ink RGB with coverage in alpha to avoid
filtering halos. One emblem quad replaces twelve primitive commands; the
main-menu delta is -11 commands and +1 texture batch/bind. One fullscreen hero
quad adds one hero batch/bind. Planet and motifs are baked and add no runtime
geometry or texture memory. Only dawn is decoded/resident, although three
deterministic derivatives are packaged. There is no per-frame decode or upload.

## Build-time ownership and provenance

Build-only tools generate fonts from hash-locked official TTF bytes, and brand
and planet from project-owned analytic geometry. Hero derivatives originate
from the tracked Gaia capture `docs/images/gaialegacy-hero.png`. Independent
generator processes must yield identical bytes and match checked-in PNG/JSON.
See `docs/ui-asset-provenance.md` and machine-readable manifests for separate
source SHA-256, upstream Git identity, and derivative SHA-256 receipts.

Complete Pixelify/Inter notices are copied into the game JAR/installDist from
their canonical build-time sources. No TTF, SVG, tools module, OS-font lookup,
runtime filesystem browsing, second world, or second renderer is needed.

## Layout, input and future boundaries

Logical hit regions remain stable; framebuffer/DPI snapping is presentation.
Functional font metrics drive inventory label separation, including fractional
DPI. Theme tokens are tested after alpha composition over bright/dark scenes.
Motion tokens and pure bounded motion primitives exist, but this integration
does not expand animation behavior or add callbacks/history/another loop.

The phase does not change ChunkRepository, FULL/DETAIL, inventory, WorldItem,
save/load, collision, raycast, generation or simulation ownership. Phase 16's
8 MiB output / 128 MiB CPU mesh budgets, accepted32/active2 and owner-frame GPU
budgets remain frozen. Phase 18/19 must consume these boundaries, not fork them.

## Deferred after Milestone 2 core completion

A dedicated high-quality presentation pass may add slow hero pan, deterministic
crossfade, subtle parallax, screen enter/exit transitions, modal choreography,
focus/selection micro-motion, restrained logo reveal, subtle celestial movement,
HUD transient tuning and final Reduced Motion UX. Optional UI SFX require an
appropriate audio seam. None are implemented here. UI/chisel SFX, CJK/Fusion,
Lucide/Kenney imports and broader migration also remain deferred; Phase 18
progression/assets and Phase 19 world/content require separate authorization.
