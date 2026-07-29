# Phase 10 Handoff: Initial HUD and UI

Date: 2026-07-30

Branch: `feat/initial-hud-ui`

Baseline and current HEAD: `250c3d628f82998eacf73b6a4cf2f8d16b17c7b8`

Status: **READY FOR HUMAN REVIEW; AUTOMATION COMPLETE; WINDOWS 150% DPI
ACCEPTANCE PARTIAL; macOS NOT RUN**

Nothing is staged, committed, pushed, merged, or placed in a pull request.

## Completed work

- Added a generic engine UI layer with immutable draw commands, ordered
  batching, text layout, textures, shader, UI pass, and complete GL-state
  restoration.
- Added game-owned immutable HUD presentation and compact Astral Membrane /
  Quiet Membrane widgets for left hand, right hand, mouth, active/empty state,
  truthful two-handed Shared Core, detached Creative selection, item icons,
  quantities, names, mode, failure, break progress, and DebugHud.
- Added framebuffer/content-scale layout and pixel snapping across the approved
  resolution, aspect-ratio, and scale matrices.
- Integrated exactly one UI frame capture per rendered frame, including
  zero-fixed-step lifecycle frames; F2/F3 use first-step press edges without
  changing Phase 8/9 gameplay input semantics.
- Migrated the Phase 9B crosshair into the UI pass and removed the former
  production crosshair pass and shaders.
- Added the independently authored, deterministic Quiet Rune 5x7 version 1
  bitmap font and canonical block-item icon generation, strict JAR-safe
  loading, explicit fallbacks, resource indexing, and compare-only package
  verification.
- Preserved Java 17 source compatibility, OpenGL 4.1, GLSL 410, main-thread GL
  ownership, engine-to-game dependency direction, and Phase 8/9 mutation and
  transaction boundaries.
- Completed scoped implementation reviews through Task 12 with 0 Critical,
  0 Important, and 0 Minor findings after focused corrections.

## Unfinished work

- Native macOS/Apple Silicon/Retina acceptance; all items are currently
  **NOT RUN**.
- Windows 100%, 125%, 800x600, 1920x1080, repeated freeform resize, two-handed,
  missing-icon, and live break-progress screenshot fixtures were not exercised
  in the available 150% DPI session; their immutable layout/behavior matrices
  remain automated-test covered.
- Final staging, commit, push, PR, and merge remain outside this task and are
  not authorized.

## Core architecture decisions

### Read-only flow

```text
immutable Phase 8/9 views + previous metrics + authoritative feet
    -> HudPresenter
    -> immutable HudPresentationSnapshot
    -> GaiaHudScreen/widgets
    -> UiDrawList.seal()
    -> immutable UiFrame
    -> RenderFrameInput / RenderContext
    -> UiRenderPass / UiRenderer
```

The UI cannot reach inventory mutation, `WorldMutationService`,
`WorldItemService`, interaction controller, Raycast, Chunk repository, or
mutable World/Physics state. UI failures never retry or roll back gameplay.

### Fixed and render-frame timing

- A fixed batch consumes one `InputSnapshot`; step zero sees the full sample and
  later catch-up steps see `heldOnly()`.
- Other fixed systems continue on every catch-up step.
- HUD capture happens once after the batch. A zero-step frame uses neutral input
  and still evaluates focus, F1 cursor capture, loading, shutdown, and blocking
  state immediately.
- F2/F3 are shared `GameConfig.Input` press-edge bindings. A pending edge is not
  consumed by a zero-step render frame.
- Debug metrics are from the previous completed render frame. Player feet come
  from authoritative `PhysicsBody.position()`, not camera interpolation.

### Visual truth

- Hands are 46 by 46 logical pixels, mouth 38 by 38, bottom margin 12.
- Shared Core draws one two-handed icon/count and one locked companion.
- Creative is a detached infinite selection; Survival slots remain preserved.
- `CrosshairWidget` is the only production crosshair authority: span 16,
  thickness 2, gap 4 framebuffer pixels.
- The break bar is 28 by 2 framebuffer pixels, 7 pixels below the crosshair,
  with a white completion segment and a white 22% alpha track.
- Theme and animation tokens are locked in `GaiaUiTheme`; continuous shimmer
  and breathing are disabled.
- Inventory bitmap text is independently quantized per axis to a half or
  integer source-pixel scale. At Windows 150% DPI and above it uses at least
  one framebuffer pixel per font-atlas source pixel, eliminating the observed
  0.75-scale blur without enlarging the slot geometry.

### Rendering and GPU lifetime

- Final pass IDs are `sky`, `world`, `block-damage`, `world-items`, `particles`,
  `debug`, `ui`.
- UI uses straight alpha (`SRC_ALPHA` / `ONE_MINUS_SRC_ALPHA`, alpha source
  `ONE`) and explicit shader sRGB decode/linear multiply/encode while
  `GL_FRAMEBUFFER_SRGB` is disabled.
- Normal and exceptional paths restore program, VAO/VBO/EBO, active texture and
  unit-zero binding, blend factors/equations, depth state, cull, polygon offset,
  viewport, scissor, and draw/read framebuffer bindings.
- Renderer installs immutable CPU assets through a generic API; all GPU
  lifecycle operations are context-owner-thread guarded. Partial creation and
  shutdown clean in reverse order and are idempotent.

## Resource provenance and fingerprints

Quiet Rune 5x7 version 1 was independently authored from blank five-by-seven
grids and rasterized by the project Java tool. Its source algorithm ID is
`quiet-rune-5x7`; printable-ASCII fingerprint is
`d53cb032352c768e6ab17816d34a426ad376c2b08154c41072e404ae3498a662`.
The final source excludes the discarded provenance-ambiguous Mode 13h/Allegro
candidate bytes. No web artwork or system font is used. Canonical icon identity
comes from the Phase 2 block/item-form catalog; UI metadata is not a second
registry.

| Resource | SHA-256 |
| --- | --- |
| `ui_font.png` | `a6a27be503ff26fd119cfe3ab74375faf7fbc22e13e1ed5670e6f2d56f5fd1ca` |
| `ui_font.json` | `ec98df77b826b03df7fecfa2e77fadf540c47dc6e01e3b2664dd8aff35636ac4` |
| `ui_icons.png` | `f2748a3ba40e426c67855fa420f50607a6e7bc94c9415e22636d398cdfed8c41` |
| `ui_icons.json` | `5cefdab102a062ebbf3fbd8a3bb1785bd22fcf8202c9eb2fd0f3ac49533015c5` |

The regenerated font atlas was inspected over the Phase 10 dark background at
native 128x64 resolution and at 8x nearest-neighbour scale. Mixed-case ASCII,
digits, punctuation, infinity, and fallback remained distinguishable with no
cell clipping. The deliberately narrow five-pixel forms remain a manual
readability risk at the smallest HUD scales and require Task 15 acceptance.

See [UI asset provenance](../architecture/ui-icon-and-font-assets.md),
[UI architecture](../architecture/ui-rendering-and-hud.md), and the
[Astral Membrane style guide](../architecture/astral-membrane-style-guide.md).

## Final automated evidence

Fresh verification after the Windows 150% DPI font correction and the
framebuffer-sRGB state-restoration correction:

- `.\gradlew.bat :engine:test --console=plain --no-daemon`: exit `0`;
- `.\gradlew.bat :game:test --console=plain --no-daemon`: exit `0`;
- `.\gradlew.bat :tools:test --console=plain --no-daemon`: exit `0`;
- `.\gradlew.bat clean test build --console=plain --no-daemon`: exit `0`, all
  `29/29` actionable tasks executed;
- engine: `95` suites / `883` tests / `0` failures / `0` errors / `0` skipped;
- game: `70` suites / `612` tests / `0` failures / `0` errors / `0` skipped;
- tools: `4` suites / `26` tests / `0` failures / `0` errors / `1` expected
  Windows symlink-capability skip;
- total: `1,521` tests / `1,520` passed / `1` skipped / `0` failed;
- combined rerun of `:game:verifyPackagedResources`,
  `:engine:verifyPackagedShaderResources`,
  `:game:verifyInstalledShaderResources`, and
  `:tools:verifyGeneratedUiAssets`: exit `0`, `14/14` tasks executed;
- `git diff --check`: exit `0`;
- tracked build/bin/class/IDE/crash output: `0`;
- platform-absolute JDK paths: `0`;
- OpenGL 4.2+/compute/SSBO production matches: `0`;
- engine-to-game dependency matches: `0`;
- UI-to-gameplay mutation/service matches: `0`;
- game-owned UI OpenGL calls: `0`.
- final development `.\gradlew.bat :game --console=plain --no-daemon` smoke:
  exit `0`; the user closed the running window with physical Escape and Gradle
  reported `BUILD SUCCESSFUL`.

The focused font regression suite first failed on fractional source scales and
an active/empty overlap, then passed `29/29` after the pixel-grid-safe scale and
line-spacing corrections. The complete `com.gaia.ui.*` suite also passed.

The first independent final review reported `0 Critical / 2 Important / 1
Minor`. Resolution before rerun:

- the valid 144-logical-pixel item-name finding was reproduced RED, corrected
  from 160 to 144, and returned GREEN in both exact-order and truncation tests;
- the two-handed finding was a real design/plan contradiction. The documents
  now match the already tested active-hand presentation anchor and deterministic
  left anchor when mouth is active;
- the proposed `HudItemSnapshot` correction was rejected because it would
  create the alternate stack type forbidden by Phase 7/8. The design and plan
  now explicitly state that defensive copies use the sole canonical immutable
  `ItemStack` type and that no alternate HUD stack type, registry, or store is
  allowed;
- Windows status was corrected from complete to partial acceptance.

The next independent rereview reported `0 Critical / 1 Important / 0 Minor`:
the UI disabled `GL_FRAMEBUFFER_SRGB` but the generic state snapshot did not
capture or restore that enable bit. Four existing complete-order tests failed
RED when the missing capture/restore calls were required. `RenderStateSnapshot`
and `OpenGlRenderStateBackend` now capture and restore framebuffer-sRGB on both
normal and exceptional scope exits; the focused backend suite and the broader
state/UI/pass suite returned GREEN.

The fresh Engine/UI owner rereview then reported `0 Critical / 0 Important / 0
Minor`. A fresh independent branch-wide review reported `0 Critical / 0
Important / 2 Minor`: one same-instance cleanup exception could replace the
primary UI installation failure through Java self-suppression, and the Escape
manual-evidence row contradicted the narrative. A focused regression failed RED
with the self-suppression exception, then returned GREEN after the installation
path reused the existing identity-safe cleanup-failure aggregator. The Windows
matrix now records the physical Escape key and Gradle exit `0` consistently.
The focused Engine/UI owner rereview and independent branch-wide rereview of
these corrections both returned `0 Critical / 0 Important / 0 Minor` and
`READY`.

## Windows manual acceptance matrix

Executed on Windows at the host's 150% content scale. Screenshots were saved
outside the repository under the Codex visualization workspace; none are
candidate repository files.

| Check | Development build | installDist |
| --- | --- | --- |
| Launch, shader/resource initialization, clean exit code | **PASS**, Gradle `:game` exit `0` | **PASS**, script exit `0` |
| 150% DPI window and maximize/restore | **PASS** | **PASS launch**, resize not repeated |
| 100%, 125%, 800x600, 1920x1080 | **NOT RUN** | **NOT RUN** |
| Compact three-slot readability and active truth | **PASS** after font correction | **PASS empty-state render** |
| Mouth-active fixture | **PASS** | **NOT RUN** |
| Shared Core two-handed and missing-icon fixtures | **NOT RUN** | **NOT RUN** |
| Detached Creative infinity and Survival restoration | **PASS** | **NOT RUN** |
| Icon distinction, quantities, and temporary names | **PASS** | **NOT RUN** |
| Single centred crosshair | **PASS** | **PASS** |
| Live short break-progress fixture | **NOT RUN** | **NOT RUN** |
| F1, F3, F4, Alt+Tab and focus recovery | **PASS** | **NOT RUN** |
| F2 and Escape key paths | F2 automated; Escape **PASS** with physical key and Gradle exit `0` | **NOT RUN** |
| DebugHud readability and previous-frame metrics | **PASS** | **NOT RUN** |
| No blur, jitter, clipping, state leak, or black screen | **PASS at 150% DPI** | **PASS smoke** |

### Screenshot checklist

- Survival HUD: **CAPTURED at 150% DPI**
- Creative HUD: **CAPTURED at 150% DPI**
- two-handed Shared Core: **NOT CAPTURED**
- mouth active: **CAPTURED at 150% DPI**
- missing icon: **NOT CAPTURED**
- DebugHud: **CAPTURED at 150% DPI**
- F1-hidden HUD: **CAPTURED at 150% DPI**
- break progress: **NOT CAPTURED**
- installDist Survival/empty-state HUD: **CAPTURED at 150% DPI**
- 100%/150% comparison: **150% CAPTURED; 100% NOT CAPTURED**

Screenshots must remain untracked unless the user explicitly requests them in
project documentation.

## macOS/Retina status

All native macOS items are **NOT RUN**: Apple Silicon clean build, native
launch, Retina framebuffer, resize, window move, fullscreen/windowed, content
scale, crosshair centre, HUD layout, font/icon clarity, F1/focus, UI state
restoration, and shutdown. Java/OpenGL architecture tests do not substitute for
native acceptance.

## Complete changed-file inventory

This inventory is from the final pre-review
`git status --short --untracked-files=all` snapshot.
Status markers are `M` modified, `D` deleted, and `??` untracked. It includes
all 134 candidate paths and intentionally excludes ignored `.superpowers/sdd`
coordination files.

### Build and root (5)

- `M engine/build.gradle`
- `M game/build.gradle`
- `M settings.gradle`
- `M THIRD_PARTY_NOTICES.md`
- `?? tools/build.gradle`

### Engine production (38)

- `M engine/src/main/java/com/overlord/config/GameConfig.java`
- `M engine/src/main/java/com/overlord/renderer/RenderFrameInput.java`
- `M engine/src/main/java/com/overlord/renderer/Renderer.java`
- `M engine/src/main/java/com/overlord/renderer/feedback/InteractionFeedbackAssets.java`
- `D engine/src/main/java/com/overlord/renderer/pass/CrosshairRenderPass.java`
- `M engine/src/main/java/com/overlord/renderer/pass/RenderContext.java`
- `M engine/src/main/java/com/overlord/renderer/state/LwjglOpenGlRenderStateApi.java`
- `M engine/src/main/java/com/overlord/renderer/state/OpenGlRenderStateApi.java`
- `M engine/src/main/java/com/overlord/renderer/state/OpenGlRenderStateBackend.java`
- `M engine/src/main/java/com/overlord/renderer/state/RenderStateBackend.java`
- `M engine/src/main/java/com/overlord/renderer/state/RenderStateSnapshot.java`
- `M engine/src/main/java/com/overlord/renderer/state/RenderStateSpec.java`
- `?? engine/src/main/java/com/overlord/renderer/pass/UiRenderPass.java`
- `?? engine/src/main/java/com/overlord/renderer/state/ScissorBox.java`
- `?? engine/src/main/java/com/overlord/renderer/ui/BitmapFont.java`
- `?? engine/src/main/java/com/overlord/renderer/ui/BitmapGlyph.java`
- `?? engine/src/main/java/com/overlord/renderer/ui/HudScreen.java`
- `?? engine/src/main/java/com/overlord/renderer/ui/OpenGlUiGpuBackend.java`
- `?? engine/src/main/java/com/overlord/renderer/ui/TextRenderer.java`
- `?? engine/src/main/java/com/overlord/renderer/ui/UiAssetBundle.java`
- `?? engine/src/main/java/com/overlord/renderer/ui/UiBatch.java`
- `?? engine/src/main/java/com/overlord/renderer/ui/UiBatchPlanner.java`
- `?? engine/src/main/java/com/overlord/renderer/ui/UiBatchRun.java`
- `?? engine/src/main/java/com/overlord/renderer/ui/UiColor.java`
- `?? engine/src/main/java/com/overlord/renderer/ui/UiDrawCommand.java`
- `?? engine/src/main/java/com/overlord/renderer/ui/UiDrawList.java`
- `?? engine/src/main/java/com/overlord/renderer/ui/UiFrame.java`
- `?? engine/src/main/java/com/overlord/renderer/ui/UiGpuBackend.java`
- `?? engine/src/main/java/com/overlord/renderer/ui/UiInitializationException.java`
- `?? engine/src/main/java/com/overlord/renderer/ui/UiLayoutContext.java`
- `?? engine/src/main/java/com/overlord/renderer/ui/UiRect.java`
- `?? engine/src/main/java/com/overlord/renderer/ui/UiRenderer.java`
- `?? engine/src/main/java/com/overlord/renderer/ui/UiShader.java`
- `?? engine/src/main/java/com/overlord/renderer/ui/UiTexture.java`
- `?? engine/src/main/java/com/overlord/renderer/ui/UiTextureData.java`
- `?? engine/src/main/java/com/overlord/renderer/ui/UiTextureId.java`
- `?? engine/src/main/java/com/overlord/renderer/ui/UiUvRect.java`
- `?? engine/src/main/java/com/overlord/renderer/ui/Widget.java`

### Engine resources (4)

- `D engine/src/main/resources/assets/overlord/shaders/feedback/crosshair.frag`
- `D engine/src/main/resources/assets/overlord/shaders/feedback/crosshair.vert`
- `?? engine/src/main/resources/assets/overlord/shaders/ui/ui.frag`
- `?? engine/src/main/resources/assets/overlord/shaders/ui/ui.vert`

### Engine tests (19)

- `M engine/src/test/java/com/overlord/renderer/InteractionFeedbackRendererLifecycleTest.java`
- `M engine/src/test/java/com/overlord/renderer/RenderAssetsTest.java`
- `M engine/src/test/java/com/overlord/renderer/RenderPipelineArchitectureTest.java`
- `M engine/src/test/java/com/overlord/renderer/feedback/CrosshairGeometryTest.java`
- `D engine/src/test/java/com/overlord/renderer/pass/CrosshairRenderPassTest.java`
- `M engine/src/test/java/com/overlord/renderer/shader/ShaderResourceLoaderTest.java`
- `M engine/src/test/java/com/overlord/renderer/state/OpenGlRenderStateBackendTest.java`
- `M engine/src/test/java/com/overlord/renderer/state/RenderStateScopeTest.java`
- `?? engine/src/test/java/com/overlord/renderer/RendererUiInstallationLifecycleTest.java`
- `?? engine/src/test/java/com/overlord/renderer/SingleCrosshairAuthorityTest.java`
- `?? engine/src/test/java/com/overlord/renderer/pass/UiRenderPassTest.java`
- `?? engine/src/test/java/com/overlord/renderer/ui/HudScreenTest.java`
- `?? engine/src/test/java/com/overlord/renderer/ui/OpenGlUiResourceLifecycleTest.java`
- `?? engine/src/test/java/com/overlord/renderer/ui/TextRendererTest.java`
- `?? engine/src/test/java/com/overlord/renderer/ui/UiAssetBundleTest.java`
- `?? engine/src/test/java/com/overlord/renderer/ui/UiBatchPlannerTest.java`
- `?? engine/src/test/java/com/overlord/renderer/ui/UiFrameTest.java`
- `?? engine/src/test/java/com/overlord/renderer/ui/UiLayoutContextTest.java`
- `?? engine/src/test/java/com/overlord/renderer/ui/UiRendererTest.java`

### Game production (27)

- `M game/src/main/java/com/gaia/GameBootstrap.java`
- `M game/src/main/java/com/gaia/GameContext.java`
- `M game/src/main/java/com/gaia/GameLoop.java`
- `M game/src/main/java/com/gaia/assets/GaiaResourceLoader.java`
- `?? game/src/main/java/com/gaia/FrameDebugInputCapture.java`
- `?? game/src/main/java/com/gaia/GameLoopFrameOrchestrator.java`
- `?? game/src/main/java/com/gaia/assets/StrictJsonDocument.java`
- `?? game/src/main/java/com/gaia/ui/GaiaHudScreen.java`
- `?? game/src/main/java/com/gaia/ui/GaiaUiAssetLoadException.java`
- `?? game/src/main/java/com/gaia/ui/GaiaUiAssetLoader.java`
- `?? game/src/main/java/com/gaia/ui/GaiaUiAssets.java`
- `?? game/src/main/java/com/gaia/ui/GaiaUiTheme.java`
- `?? game/src/main/java/com/gaia/ui/HudDebugSnapshot.java`
- `?? game/src/main/java/com/gaia/ui/HudFrameCoordinator.java`
- `?? game/src/main/java/com/gaia/ui/HudPresentationSnapshot.java`
- `?? game/src/main/java/com/gaia/ui/HudPresenter.java`
- `?? game/src/main/java/com/gaia/ui/HudSlotSnapshot.java`
- `?? game/src/main/java/com/gaia/ui/HudVisibility.java`
- `?? game/src/main/java/com/gaia/ui/UiIconAtlas.java`
- `?? game/src/main/java/com/gaia/ui/UiIconDefinition.java`
- `?? game/src/main/java/com/gaia/ui/UiIconResolver.java`
- `?? game/src/main/java/com/gaia/ui/widget/BodyInventoryHud.java`
- `?? game/src/main/java/com/gaia/ui/widget/BreakProgressWidget.java`
- `?? game/src/main/java/com/gaia/ui/widget/CrosshairWidget.java`
- `?? game/src/main/java/com/gaia/ui/widget/DebugHud.java`
- `?? game/src/main/java/com/gaia/ui/widget/GameModeWidget.java`
- `?? game/src/main/java/com/gaia/ui/widget/InteractionFailureWidget.java`

### Game resources (6)

- `M game/src/main/resources/assets/gaia/resource-index.json`
- `?? game/src/main/resources/assets/gaia/ui/ui-assets.json`
- `?? game/src/main/resources/assets/gaia/ui/ui_font.json`
- `?? game/src/main/resources/assets/gaia/ui/ui_font.png`
- `?? game/src/main/resources/assets/gaia/ui/ui_icons.json`
- `?? game/src/main/resources/assets/gaia/ui/ui_icons.png`

### Game tests (21)

- `M game/src/test/java/com/gaia/GameLoopStructureTest.java`
- `M game/src/test/java/com/gaia/RenderArchitectureTest.java`
- `M game/src/test/java/com/gaia/assets/GaiaResourceLoaderTest.java`
- `?? game/src/test/java/com/gaia/HudDebugInputCaptureTest.java`
- `?? game/src/test/java/com/gaia/UiGameLoopIntegrationTest.java`
- `?? game/src/test/java/com/gaia/ui/GaiaHudLayoutMatrixTest.java`
- `?? game/src/test/java/com/gaia/ui/GaiaHudScreenIntegrationTest.java`
- `?? game/src/test/java/com/gaia/ui/GaiaUiAssetLoaderTest.java`
- `?? game/src/test/java/com/gaia/ui/GaiaUiThemeTest.java`
- `?? game/src/test/java/com/gaia/ui/HudPresentationSnapshotTest.java`
- `?? game/src/test/java/com/gaia/ui/HudPresenterTest.java`
- `?? game/src/test/java/com/gaia/ui/UiIconResolverTest.java`
- `?? game/src/test/java/com/gaia/ui/UiPackagedResourceContractTest.java`
- `?? game/src/test/java/com/gaia/ui/widget/BodyInventoryHudTest.java`
- `?? game/src/test/java/com/gaia/ui/widget/BreakProgressWidgetTest.java`
- `?? game/src/test/java/com/gaia/ui/widget/CrosshairWidgetTest.java`
- `?? game/src/test/java/com/gaia/ui/widget/DebugHudTest.java`
- `?? game/src/test/java/com/gaia/ui/widget/GameModeWidgetTest.java`
- `?? game/src/test/java/com/gaia/ui/widget/InteractionFailureWidgetTest.java`
- `?? game/src/test/java/com/gaia/ui/widget/Task10WidgetTestSupport.java`
- `?? game/src/test/java/com/gaia/ui/widget/WidgetTestSnapshots.java`

### Tools production and tests (8)

- `?? tools/src/main/java/com/gaia/tools/ui/BitmapFontGenerator.java`
- `?? tools/src/main/java/com/gaia/tools/ui/BlockIconGenerator.java`
- `?? tools/src/main/java/com/gaia/tools/ui/GlyphSource.java`
- `?? tools/src/main/java/com/gaia/tools/ui/UiAssetGenerator.java`
- `?? tools/src/test/java/com/gaia/tools/ui/BitmapFontGeneratorTest.java`
- `?? tools/src/test/java/com/gaia/tools/ui/BlockIconGeneratorTest.java`
- `?? tools/src/test/java/com/gaia/tools/ui/GlyphSourceProvenanceTest.java`
- `?? tools/src/test/java/com/gaia/tools/ui/UiAssetGeneratorTest.java`

### Documentation (6)

- `?? docs/superpowers/plans/2026-07-28-phase-10-initial-hud-ui.md`
- `?? docs/superpowers/specs/2026-07-28-phase-10-initial-hud-ui-design.md`
- `?? docs/architecture/ui-rendering-and-hud.md`
- `?? docs/architecture/astral-membrane-style-guide.md`
- `?? docs/architecture/ui-icon-and-font-assets.md`
- `?? docs/agent-handoffs/phase-10-handoff.md`

## Diff and worktree snapshot

The current tracked `git diff --stat` reports:

```text
34 files changed, 1167 insertions(+), 674 deletions(-)
```

The final pre-review status contains 134 candidate paths: 34 tracked paths (30
modified, 4 deleted) and 100 untracked paths, including two PNG files. The
tracked numstat is 1,167 insertions and 674 deletions. The 98 untracked text
files contain 14,884 lines and the other two untracked files are PNGs, giving a
complete unstaged candidate accounting of 134 paths, 16,051 text additions,
674 deletions, and two binary additions. `git diff --stat` does not include
untracked files. The index remains empty.

## Known risks

- Windows 150% DPI visual quality and actual driver interaction are accepted.
  The original inventory-font blur was traced to 0.625/0.75 source-pixel
  resampling and corrected with focused RED/GREEN coverage. Native 100%/125%
  comparison and additional resolution screenshots remain unrun.
- macOS/Retina behavior is architecture- and test-covered only; it is not
  natively accepted.
- The compact HUD deliberately throws a diagnostic exception when the
  framebuffer-derived logical surface cannot fit its minimum geometry; manual
  acceptance must confirm all required window sizes remain above that boundary.
- DebugHud intentionally shows previous-frame render metrics; users may
  interpret the diagnostic one-frame delay as latency unless documented.
- Icons currently cover five canonical block item forms plus fallback. New item
  forms must update the generator, metadata, tests, hashes, and provenance.
- The UI uses only English bitmap glyphs, printable ASCII, infinity, and
  fallback; full localization and accessibility settings are outside Phase 10.
- Automated review cannot certify the subjective 80/20 restraint, icon
  recognition, or no-jitter visual result.

## Interfaces the next phase must not break

- Canonical `ResourceLocation`, Phase 7 `ItemStack`, Phase 2
  `ItemFormDefinition`, and Phase 8 inventory/reservation contracts.
- `BodyInventoryService` as the only body-inventory mutation boundary and
  atomic two-handed count truth.
- Detached Phase 9 `CreativeSelection` and immediate Survival restoration.
- Phase 6 Raycast and Phase 9A fixed-step break/place state, cancellation,
  reservation-before-mutation, count conservation, and committed event order.
- `WorldMutationService` as the sole gameplay block-write path and
  `LogicalWorldItemService`/stable ID as the sole world-item authority.
- Phase 3 Chunk revision, dirty propagation, neighbour invalidation, and stale
  mesh result rejection.
- Immutable `HudPresentationSnapshot`, `UiFrame`, and
  `InteractionFeedbackFrame` renderer boundaries.
- F2/F3 first-step edge and zero-step lifecycle behavior; existing gameplay
  input remains untouched.
- Framebuffer-derived layout, per-axis content scale, and edge snapping.
- One crosshair authority and exact 16/2/4 geometry.
- Exact seven-pass order and full normal/exception GL state restoration.
- Main-thread creation/upload/draw/destruction and idempotent reverse cleanup.
- Project-owned JAR-safe UI resources and compare-only deterministic hashes.

## Suggested delivery

Suggested commit:

```text
feat(ui): add scalable astral physical inventory and interaction HUD
```

Suggested PR title:

```text
feat(ui): add Retina-safe three-slot astral HUD and debug metrics
```

Suggested PR description:

> Add a generic OpenGL 4.1 UI pass and a compact Astral Membrane / Quiet
> Membrane three-slot HUD backed by immutable Phase 8/9 presentation snapshots.
> The change adds truthful two-handed and detached Creative states,
> project-owned deterministic icons and bitmap text, one framebuffer-centred
> crosshair, short break progress, mode/failure/debug widgets, complete GL-state
> restoration, and JAR/installDist resource verification without introducing a
> gameplay mutation path or second item identity.
