# Phase 10 Initial HUD and UI Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:subagent-driven-development` (recommended) or `superpowers:executing-plans` to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a scalable, Retina-safe Astral Membrane HUD that truthfully presents the existing three-slot inventory, Creative selection, interaction progress, game mode, crosshair, render metrics, and authoritative player position without changing gameplay.

**Architecture:** The game module copies allowed domain views into immutable HUD presentation state and composes a generic immutable `UiFrame`. The engine owns layout primitives, drawing commands, OpenGL resources, state restoration, and one final `UiRenderPass`; a build-time-only `tools` project deterministically generates project-owned font and icon atlases.

**Tech Stack:** Java 17 source/target, Gradle Wrapper on JDK 21, JUnit 6.1.1, LWJGL/OpenGL 4.1, GLSL 410, Gson metadata, deterministic JDK PNG generation.

## Global Constraints

- Work only on `feat/initial-hud-ui`, based on `origin/main@250c3d628f82998eacf73b6a4cf2f8d16b17c7b8` unless the start gate is deliberately repeated.
- Do not stage, commit, push, create a pull request, or merge.
- Preserve Java 17 compatibility; never add a platform-specific absolute JDK path.
- Keep OpenGL at 4.1 or lower and shaders at `#version 410 core`; no compute shaders, SSBOs, or platform-only APIs.
- All OpenGL and GPU create/upload/destroy work runs on the context-owning main thread through `MainThreadGuard`.
- `engine` must not depend on `game`; `tools` is build-time-only and absent from runtime dependency graphs.
- UI is read-only. It must not receive or call inventory mutation services, `WorldMutationService`, `WorldItemService`, `BlockInteractionController`, `ChunkRepository`, or mutable Physics APIs.
- Preserve canonical `ResourceLocation`, `ItemStack`, `ItemFormDefinition`, `BlockRegistry`, reservations, Phase 9 fixed-step/transaction semantics, world-item stable IDs, and Chunk dirty/revision behavior.
- Keep exactly one crosshair draw authority; delete the Phase 9B pass only after parity tests pass.
- Use one straight-alpha and shader-gamma path; keep `GL_FRAMEBUFFER_SRGB` disabled.
- Do not modify the shared block atlas or existing UV regions. UI atlases are separate project-owned assets.
- Every production edit begins with a focused RED test and ends with focused GREEN plus related regressions.
- After each Gate 10.1–10.5, obtain an independent read-only review and fix all severities with focused RED/GREEN tests.

---

## File and responsibility map

### Engine-owned generic UI

- `engine/src/main/java/com/overlord/renderer/ui/`: immutable geometry, colours, UVs, texture identities, commands, frames, layout, widgets/screens, bitmap-font layout, CPU assets, stable batch planning, GPU backends/resources, renderer.
- `engine/src/main/java/com/overlord/renderer/pass/UiRenderPass.java`: single final screen-space pass.
- `engine/src/main/resources/assets/overlord/shaders/ui/ui.vert` and `ui.frag`: GLSL 410 UI shader pair.
- `engine/src/main/java/com/overlord/renderer/state/`: scissor and read/draw framebuffer state capture/restoration additions.
- `engine/src/main/java/com/overlord/renderer/{RenderFrameInput,Renderer}.java` and `renderer/pass/RenderContext.java`: immutable UI frame and renderer integration.

### Game-owned presentation

- `game/src/main/java/com/gaia/ui/`: immutable HUD snapshots, presenter, screen, Quiet Membrane theme, icon metadata/resolver/loader.
- `game/src/main/java/com/gaia/ui/widget/`: body inventory, crosshair, progress, mode, failure, and debug widgets.
- `game/src/main/java/com/gaia/{GameContext,GameBootstrap,GameLoop}.java`: snapshot capture, F2/F3 edges, lifecycle visibility, and render-frame composition.
- `engine/src/main/java/com/overlord/config/GameConfig.java`: non-conflicting F2/F3 key constants only.

### Build-time tools and resources

- `tools/`: deterministic project-owned glyph and block-icon generators plus tests.
- `game/src/main/resources/assets/gaia/ui/`: generated font/icon PNG and JSON metadata plus manifest.
- `settings.gradle`, `engine/build.gradle`, `game/build.gradle`, `tools/build.gradle`: module and package verification.

### Documentation

- `docs/architecture/ui-rendering-and-hud.md`
- `docs/architecture/astral-membrane-style-guide.md`
- `docs/architecture/ui-icon-and-font-assets.md`
- `docs/agent-handoffs/phase-10-handoff.md`

---

### Task 1: Extend OpenGL state capture for UI safety

**Gate:** 10.1

**Files:**
- Create: `engine/src/main/java/com/overlord/renderer/state/ScissorBox.java`
- Modify: `engine/src/main/java/com/overlord/renderer/state/RenderStateSnapshot.java`
- Modify: `engine/src/main/java/com/overlord/renderer/state/RenderStateSpec.java`
- Modify: `engine/src/main/java/com/overlord/renderer/state/RenderStateBackend.java`
- Modify: `engine/src/main/java/com/overlord/renderer/state/OpenGlRenderStateApi.java`
- Modify: `engine/src/main/java/com/overlord/renderer/state/LwjglOpenGlRenderStateApi.java`
- Modify: `engine/src/main/java/com/overlord/renderer/state/OpenGlRenderStateBackend.java`
- Test: `engine/src/test/java/com/overlord/renderer/state/RenderStateScopeTest.java`
- Test: `engine/src/test/java/com/overlord/renderer/state/OpenGlRenderStateBackendTest.java`

**Produces:** `ScissorBox(int x, int y, int width, int height)`, `RenderStateBackend.setScissor(ScissorBox)`, and snapshots containing scissor enable/box plus read/draw framebuffer bindings.

- [ ] Write tests that require normal and exceptional restoration of every existing field plus scissor and both framebuffer IDs.

```java
assertThrows(TestFailure.class, () -> RenderStateScope.run(backend, uiState(), () -> {
    backend.setScissor(new ScissorBox(4, 5, 6, 7));
    throw new TestFailure();
}));
assertEquals(before, backend.current());
assertEquals(1, backend.restoreCount());
```

- [ ] Run RED:

```powershell
.\gradlew.bat :engine:test --tests "com.overlord.renderer.state.*" --console=plain --no-daemon
```

Expected: missing scissor/framebuffer representation or failed equality.

- [ ] Implement `glIsEnabled(GL_SCISSOR_TEST)`, `GL_SCISSOR_BOX`, `GL_DRAW_FRAMEBUFFER_BINDING`, and `GL_READ_FRAMEBUFFER_BINDING` capture and exact restoration; preserve compatibility constructors.
- [ ] Run the focused command GREEN, then `:engine:test --tests "com.overlord.renderer.pass.*"`.
- [ ] Run `git diff --check` and leave every file unstaged.

---

### Task 2: Add immutable UI geometry, layout, commands, and screens

**Gate:** 10.1

**Files:**
- Create: `engine/src/main/java/com/overlord/renderer/ui/UiRect.java`
- Create: `engine/src/main/java/com/overlord/renderer/ui/UiUvRect.java`
- Create: `engine/src/main/java/com/overlord/renderer/ui/UiColor.java`
- Create: `engine/src/main/java/com/overlord/renderer/ui/UiTextureId.java`
- Create: `engine/src/main/java/com/overlord/renderer/ui/UiDrawCommand.java`
- Create: `engine/src/main/java/com/overlord/renderer/ui/UiFrame.java`
- Create: `engine/src/main/java/com/overlord/renderer/ui/UiDrawList.java`
- Create: `engine/src/main/java/com/overlord/renderer/ui/UiLayoutContext.java`
- Create: `engine/src/main/java/com/overlord/renderer/ui/Widget.java`
- Create: `engine/src/main/java/com/overlord/renderer/ui/HudScreen.java`
- Test: `engine/src/test/java/com/overlord/renderer/ui/UiLayoutContextTest.java`
- Test: `engine/src/test/java/com/overlord/renderer/ui/UiFrameTest.java`
- Test: `engine/src/test/java/com/overlord/renderer/ui/HudScreenTest.java`

**Produces:**

```java
public record UiDrawCommand(UiTextureId texture, UiRect framebufferBounds,
        UiUvRect uv, UiColor tint, Optional<UiRect> clip) {}
public record UiFrame(List<UiDrawCommand> commands) { public static UiFrame empty(); }
public interface Widget { void append(UiLayoutContext layout, UiDrawList out); }
```

- [ ] Write validation/immutability tests and a parameterized matrix for 800x600 through 4K, odd dimensions, 4:3/16:9/16:10/ultrawide, content scales 1.0/1.25/1.5/2.0, logical/framebuffer mismatch, resize, maximise, and monitor-scale transitions.

```java
UiFrame frame = new UiFrame(source);
source.clear();
assertEquals(List.of(first, second), frame.commands());
assertThrows(UnsupportedOperationException.class, () -> frame.commands().clear());
```

- [ ] Run RED: `.\gradlew.bat :engine:test --tests "com.overlord.renderer.ui.*" --console=plain --no-daemon`.
- [ ] Implement finite geometry validation, defensive copying, seal-once `UiDrawList`, and edge-wise logical-to-framebuffer snapping with each edge independently rounded.
- [ ] Run focused GREEN and all Task 1 state tests.
- [ ] Record exact layout snapshots and leave changes unstaged.

---

### Task 3: Add CPU text layout, immutable assets, and stable batching

**Gate:** 10.1

**Files:**
- Create: `engine/src/main/java/com/overlord/renderer/ui/BitmapGlyph.java`
- Create: `engine/src/main/java/com/overlord/renderer/ui/BitmapFont.java`
- Create: `engine/src/main/java/com/overlord/renderer/ui/TextRenderer.java`
- Create: `engine/src/main/java/com/overlord/renderer/ui/UiTextureData.java`
- Create: `engine/src/main/java/com/overlord/renderer/ui/UiAssetBundle.java`
- Create: `engine/src/main/java/com/overlord/renderer/ui/UiBatchRun.java`
- Create: `engine/src/main/java/com/overlord/renderer/ui/UiBatchPlanner.java`
- Test: `engine/src/test/java/com/overlord/renderer/ui/TextRendererTest.java`
- Test: `engine/src/test/java/com/overlord/renderer/ui/UiBatchPlannerTest.java`
- Test: `engine/src/test/java/com/overlord/renderer/ui/UiAssetBundleTest.java`

**Produces:** CPU-only glyph measurement/layout and consecutive texture/clip runs without reordering.

- [ ] Write tests for infinity, missing glyph, baseline snapping, 144px ellipsis fit, defensive pixel/map copies, and exact run order.

```java
assertEquals(List.of(List.of(iconA), List.of(fontA), List.of(iconB)),
        planner.plan(List.of(iconA, fontA, iconB)).stream()
                .map(UiBatchRun::commands).toList());
```

- [ ] Run RED: `.\gradlew.bat :engine:test --tests "com.overlord.renderer.ui.*" --console=plain --no-daemon`.
- [ ] Implement immutable glyph maps, CPU quad layout, explicit fallback, and consecutive-only batching; do not access system fonts, AWT font rasterization, OpenGL, or gameplay services.
- [ ] Run focused GREEN and the complete Gate 10.1 CPU suite.
- [ ] Review type names/signatures across Tasks 2–3 for consistency.

---

### Task 4: Implement the generic OpenGL UI renderer and pass

**Gate:** 10.1

**Files:**
- Create: `engine/src/main/java/com/overlord/renderer/ui/UiGpuBackend.java`
- Create: `engine/src/main/java/com/overlord/renderer/ui/OpenGlUiGpuBackend.java`
- Create: `engine/src/main/java/com/overlord/renderer/ui/UiShader.java`
- Create: `engine/src/main/java/com/overlord/renderer/ui/UiTexture.java`
- Create: `engine/src/main/java/com/overlord/renderer/ui/UiBatch.java`
- Create: `engine/src/main/java/com/overlord/renderer/ui/UiRenderer.java`
- Create: `engine/src/main/java/com/overlord/renderer/pass/UiRenderPass.java`
- Create: `engine/src/main/resources/assets/overlord/shaders/ui/ui.vert`
- Create: `engine/src/main/resources/assets/overlord/shaders/ui/ui.frag`
- Modify: `engine/src/main/java/com/overlord/renderer/pass/RenderContext.java`
- Modify: `engine/src/main/java/com/overlord/renderer/RenderFrameInput.java`
- Test: `engine/src/test/java/com/overlord/renderer/ui/UiRendererTest.java`
- Test: `engine/src/test/java/com/overlord/renderer/pass/UiRenderPassTest.java`
- Test: `engine/src/test/java/com/overlord/renderer/ui/OpenGlUiResourceLifecycleTest.java`

**Produces:** `UiRenderer.create(UiAssetBundle, UiGpuBackend, MainThreadGuard)`, `render(UiFrame, RenderSurfaceMetrics)`, and idempotent `close()`.

- [ ] Write recording-backend tests for exact vertex upload, unit-zero use, clip runs, zero-framebuffer no-op, full state restore on setup/draw failure, owner-thread rejection, reverse partial-init cleanup, and exactly-once shutdown.

```java
assertThrows(UiInitializationException.class,
        () -> UiRenderer.create(bundle, failAt(CREATE_BATCH), ownerThread));
assertEquals(List.of("delete-font", "delete-icons", "delete-program"), backend.cleanup());
```

- [ ] Run RED:

```powershell
.\gradlew.bat :engine:test --tests "com.overlord.renderer.ui.UiRendererTest" --tests "com.overlord.renderer.pass.UiRenderPassTest" --tests "com.overlord.renderer.ui.OpenGlUiResourceLifecycleTest" --console=plain --no-daemon
```

- [ ] Implement a position/UV/tint quad format, GLSL 410 top-left framebuffer-to-NDC conversion, straight alpha, shader sRGB decode/tint/encode, and complete Task 1 state restoration.
- [ ] Run focused GREEN and all engine UI/state/shader tests.
- [ ] Request a Gate 10.1 read-only owner review; fix all severities with RED/GREEN before Task 5.

---

### Task 5: Generate the project-owned bitmap font

**Gate:** 10.2

**Files:**
- Modify: `settings.gradle`
- Create: `tools/build.gradle`
- Create: `tools/src/main/java/com/gaia/tools/ui/GlyphSource.java`
- Create: `tools/src/main/java/com/gaia/tools/ui/BitmapFontGenerator.java`
- Create: `tools/src/main/java/com/gaia/tools/ui/UiAssetGenerator.java`
- Create: `tools/src/test/java/com/gaia/tools/ui/BitmapFontGeneratorTest.java`
- Create: `tools/src/test/java/com/gaia/tools/ui/UiAssetGeneratorTest.java`
- Create: `game/src/main/resources/assets/gaia/ui/ui_font.png`
- Create: `game/src/main/resources/assets/gaia/ui/ui_font.json`

**Produces:** deterministic 128x64 RGBA atlas, 8x8 cells, printable ASCII 32–126, U+221E, and one missing glyph.

- [ ] Write tests for exact coverage, unique cells, visible infinity/missing glyph, white RGB under zero alpha, stable JSON ordering, order-independent PNG bytes, and stable SHA-256.
- [ ] Run RED: `.\gradlew.bat :tools:test --tests "com.gaia.tools.ui.*" --console=plain --no-daemon`.
- [ ] Implement the independently authored Quiet Rune 5x7 pattern grammar,
  deterministic 8x8-cell PNG/JSON rasterization, and versioned source
  fingerprint without system fonts, local absolute paths, network assets, or
  screenshots.
- [ ] Run `.\gradlew.bat :tools:generateUiAssets --console=plain --no-daemon`, lock the approved hash, rerun twice, and require byte-for-byte GREEN.
- [ ] Confirm only declared resources changed and no build output is tracked.

---

### Task 6: Generate block icons and load immutable UI metadata

**Gate:** 10.2

**Files:**
- Create: `tools/src/main/java/com/gaia/tools/ui/BlockIconGenerator.java`
- Create: `tools/src/test/java/com/gaia/tools/ui/BlockIconGeneratorTest.java`
- Create: `game/src/main/resources/assets/gaia/ui/ui_icons.png`
- Create: `game/src/main/resources/assets/gaia/ui/ui_icons.json`
- Create: `game/src/main/resources/assets/gaia/ui/ui-assets.json`
- Create: `game/src/main/java/com/gaia/ui/UiIconDefinition.java`
- Create: `game/src/main/java/com/gaia/ui/UiIconAtlas.java`
- Create: `game/src/main/java/com/gaia/ui/UiIconResolver.java`
- Create: `game/src/main/java/com/gaia/ui/GaiaUiAssetLoader.java`
- Modify: `game/src/main/resources/assets/gaia/resource-index.json`
- Test: `game/src/test/java/com/gaia/ui/GaiaUiAssetLoaderTest.java`
- Test: `game/src/test/java/com/gaia/ui/UiIconResolverTest.java`

**Produces:**

```java
public record UiIconDefinition(ResourceLocation itemId, String displayName, UiUvRect region) {}
public final class UiIconResolver { public UiIconDefinition resolve(ResourceLocation id); }
public record GaiaUiAssets(UiAssetBundle renderAssets, UiIconAtlas icons) {}
```

- [ ] Write tests for grass/dirt/stone/oak_log/oak_leaves plus explicit missing fallback, canonical mapping, non-overlap, two unused cells, distinct icon hashes, 100/82/68 face light, diagnose-once, and no alternate registry/stack.
- [ ] Run RED:

```powershell
.\gradlew.bat :tools:test --tests "com.gaia.tools.ui.BlockIconGeneratorTest" --console=plain --no-daemon
.\gradlew.bat :game:test --tests "com.gaia.ui.GaiaUiAssetLoaderTest" --tests "com.gaia.ui.UiIconResolverTest" --console=plain --no-daemon
```

- [ ] Generate 32x32 isometric icons from canonical UP/NORTH/EAST atlas regions into a separate 128x64 atlas; never modify the block atlas or UV metadata.
- [ ] Implement immutable runtime loading that rejects duplicate IDs, overlaps, invalid bounds/JSON/image dimensions, and missing fallback with path/cause diagnostics.
- [ ] Run generator and focused tests GREEN, lock hashes, then request Gate 10.2 review for provenance, identity, fallback, packaging inputs, and duplicate-model absence.

---

### Task 7: Define immutable HUD snapshots, theme, and presenter lifecycle

**Gate:** 10.3

**Files:**
- Create: `game/src/main/java/com/gaia/ui/HudVisibility.java`
- Create: `game/src/main/java/com/gaia/ui/HudSlotSnapshot.java`
- Create: `game/src/main/java/com/gaia/ui/HudDebugSnapshot.java`
- Create: `game/src/main/java/com/gaia/ui/HudPresentationSnapshot.java`
- Create: `game/src/main/java/com/gaia/ui/GaiaUiTheme.java`
- Create: `game/src/main/java/com/gaia/ui/HudPresenter.java`
- Test: `game/src/test/java/com/gaia/ui/HudPresentationSnapshotTest.java`
- Test: `game/src/test/java/com/gaia/ui/HudPresenterTest.java`
- Test: `game/src/test/java/com/gaia/ui/GaiaUiThemeTest.java`

**Consumes:** copied `BodyInventoryViewModel`, `BlockInteractionViewModel`, previous completed `RenderMetricsSnapshot`, immutable counts, copied authoritative feet XYZ, frame delta, and lifecycle flags.

- [ ] Write tests for defensive copies, F2/F3 press-only toggles, catch-up non-repeat, same-render-frame mode/slot changes, 150ms slot transition, item-name and mode-notice timings, and immediate zero-fixed-step lifecycle clearing.

```java
HudPresentationSnapshot creative = presenter.capture(creativeInput);
assertEquals(dirtId, creative.slot(LEFT_HAND).stack().orElseThrow().itemId());
assertEquals(stoneId, creative.creative().orElseThrow().itemId());
assertTrue(creative.creative().orElseThrow().infinite());
```

- [ ] Run RED: `.\gradlew.bat :game:test --tests "com.gaia.ui.Hud*Test" --tests "com.gaia.ui.GaiaUiThemeTest" --console=plain --no-daemon`.
- [ ] Implement defensive immutable projections and presentation-only timers. Derive two-handed state only from matching hand projections plus canonical `ItemFormDefinition.twoHanded()`; retain the sole canonical `ItemStack` value type and never create an alternate HUD stack type, registry, or store.
- [ ] Run focused GREEN plus all inventory and interaction tests; assert zero mutation/event/reservation calls from UI.

---

### Task 8: Implement the compact three-slot Astral Membrane HUD

**Gate:** 10.3

**Files:**
- Create: `game/src/main/java/com/gaia/ui/GaiaHudScreen.java`
- Create: `game/src/main/java/com/gaia/ui/widget/BodyInventoryHud.java`
- Test: `game/src/test/java/com/gaia/ui/widget/BodyInventoryHudTest.java`
- Test: `game/src/test/java/com/gaia/ui/GaiaHudLayoutMatrixTest.java`

**Produces:** ordered generic draw commands only.

- [ ] Write exact immutable draw snapshots for 46x46 left/right, 38x38 mouth, 12px bottom margin, physical identity, 1/2/3 labels, mouth arc, active double ring plus ACTIVE, empty outline plus EMPTY, icon/count, long-name ellipsis, and the full layout matrix.
- [ ] Add truthfulness tests:

```java
assertEquals(1, tagged(twoHandedFrame, "two-handed-icon").size());
assertEquals(1, text(twoHandedFrame, "64").size());
assertEquals(List.of("selected-hand-anchor", "other-hand-locked-companion", "shared-halo"),
        semanticTags(twoHandedHandActiveFrame));
assertEquals(List.of("left-inactive-anchor", "right-locked-companion", "mouth-active"),
        semanticTags(twoHandedMouthActiveFrame));
assertEquals(1, text(creativeFrame, "∞").size());
assertEquals(3, tagged(creativeFrame, "preserved-survival-slot").size());
```

- [ ] Run RED: `.\gradlew.bat :game:test --tests "com.gaia.ui.widget.BodyInventoryHudTest" --tests "com.gaia.ui.GaiaHudLayoutMatrixTest" --console=plain --no-daemon`.
- [ ] Implement minimal solid/outlined quad composition with Quiet Membrane tokens; no shimmer, breathing, refraction, duplicate icon/count, or runtime 3D preview.
- [ ] Run focused GREEN, all `com.gaia.ui.*` and inventory regressions, then request Gate 10.3 review and fix all severities.

---

### Task 9: Add the single UI crosshair and short break-progress bar

**Gate:** 10.4

**Files:**
- Create: `game/src/main/java/com/gaia/ui/widget/CrosshairWidget.java`
- Create: `game/src/main/java/com/gaia/ui/widget/BreakProgressWidget.java`
- Test: `game/src/test/java/com/gaia/ui/widget/CrosshairWidgetTest.java`
- Test: `game/src/test/java/com/gaia/ui/widget/BreakProgressWidgetTest.java`
- Modify: `engine/src/test/java/com/overlord/renderer/feedback/CrosshairGeometryTest.java`

**Produces:** Phase 9B-equivalent four white quads and a 28x2 framebuffer-pixel bar placed 7 framebuffer pixels below the crosshair extent.

- [ ] Write RED parity tests comparing UI rectangles with `CrosshairGeometry.quads` for odd/even and DPI-mismatched surfaces; assert no target/Raycast dependency.
- [ ] Write RED lifecycle/progress tests for F1, focus, loading, shutdown, blocking UI, F2, zero fixed steps, recapture, no target, cancellation, completion, Creative, and target change.
- [ ] Run RED: `.\gradlew.bat :game:test --tests "com.gaia.ui.widget.CrosshairWidgetTest" --tests "com.gaia.ui.widget.BreakProgressWidgetTest" --console=plain --no-daemon`.
- [ ] Implement exact 16px span, 2px thickness, 4px gap and exact progress geometry in framebuffer pixels without Raycast/gameplay calls.
- [ ] Run focused GREEN and existing Phase 9B crosshair/feedback regressions. Keep the old production pass until Task 11.

---

### Task 10: Add mode, failure, and DebugHud widgets

**Gate:** 10.4/10.5

**Files:**
- Create: `game/src/main/java/com/gaia/ui/widget/GameModeWidget.java`
- Create: `game/src/main/java/com/gaia/ui/widget/InteractionFailureWidget.java`
- Create: `game/src/main/java/com/gaia/ui/widget/DebugHud.java`
- Test: `game/src/test/java/com/gaia/ui/widget/GameModeWidgetTest.java`
- Test: `game/src/test/java/com/gaia/ui/widget/InteractionFailureWidgetTest.java`
- Test: `game/src/test/java/com/gaia/ui/widget/DebugHudTest.java`

- [ ] Write RED tests for persistent SURVIVAL/CREATIVE infinity, 1.25s notice plus final 250ms fade, same-frame mode change, failure text without retry, and exact DebugHud ordered lines.
- [ ] Debug lines must cover previous-frame FPS/frame time/draw calls/triangles/visible chunks/mesh queue, loaded chunks, physics body count, authoritative feet XYZ, world-item count, target presence, and feedback damage/item/particle counts; absent optional metrics are `N/A`.
- [ ] Run RED: `.\gradlew.bat :game:test --tests "com.gaia.ui.widget.GameModeWidgetTest" --tests "com.gaia.ui.widget.InteractionFailureWidgetTest" --tests "com.gaia.ui.widget.DebugHudTest" --console=plain --no-daemon`.
- [ ] Implement a restrained top-right mode marker, standard-red failure line, and neutral top-left DebugHud; F3 does not reset or mutate metrics.
- [ ] Run focused GREEN and request Gate 10.4 widget review; resolve all findings before integration.

---

### Task 11: Integrate UI and migrate to exactly one crosshair authority

**Gate:** 10.4/10.5

**Files:**
- Modify: `engine/src/main/java/com/overlord/config/GameConfig.java`
- Modify: `engine/src/main/java/com/overlord/renderer/RenderFrameInput.java`
- Modify: `engine/src/main/java/com/overlord/renderer/pass/RenderContext.java`
- Modify: `engine/src/main/java/com/overlord/renderer/Renderer.java`
- Modify: `game/src/main/java/com/gaia/GameContext.java`
- Modify: `game/src/main/java/com/gaia/GameBootstrap.java`
- Modify: `game/src/main/java/com/gaia/GameLoop.java`
- Delete: `engine/src/main/java/com/overlord/renderer/pass/CrosshairRenderPass.java`
- Delete: `engine/src/main/resources/assets/overlord/shaders/feedback/crosshair.vert`
- Delete: `engine/src/main/resources/assets/overlord/shaders/feedback/crosshair.frag`
- Modify: `engine/src/test/java/com/overlord/renderer/RendererStructureTest.java`
- Modify: `engine/src/test/java/com/overlord/renderer/RenderFrameInputTest.java`
- Modify: `engine/src/test/java/com/overlord/renderer/pass/RenderPipelineTest.java`
- Modify: `game/src/test/java/com/gaia/GameLoopStructureTest.java`
- Create: `game/src/test/java/com/gaia/UiGameLoopIntegrationTest.java`

**Produces:** `RenderFrameInput(..., InteractionFeedbackFrame feedback, UiFrame ui)` and pass order sky/world/damage/world-items/particles/debug/UI.

- [ ] Write RED integration tests for F2/F3 first-step edges, held non-repeat, other systems running every catch-up step, zero-step lifecycle visibility, untouched 1/2/3/wheel/Q/F4/mouse input, immutable frame ownership, and render failures never invoking gameplay rollback/retry.
- [ ] Write RED pass-order/authority tests:

```java
assertEquals(List.of("sky", "world", "damage", "world-items", "particles", "debug", "ui"),
        renderer.passNames());
assertFalse(renderer.productionTypes().contains(CrosshairRenderPass.class));
```

- [ ] Run RED:

```powershell
.\gradlew.bat :engine:test --tests "com.overlord.renderer.RenderFrameInputTest" --tests "com.overlord.renderer.RendererStructureTest" --tests "com.overlord.renderer.pass.RenderPipelineTest" --console=plain --no-daemon
.\gradlew.bat :game:test --tests "com.gaia.UiGameLoopIntegrationTest" --tests "com.gaia.GameLoopStructureTest" --console=plain --no-daemon
```

- [ ] Add F2/F3 constants; process toggle edges only on step zero while lifecycle visibility runs once per render frame even with zero fixed steps.
- [ ] In `GameBootstrap`, load immutable UI CPU assets through `AssetManager`, install them through a generic Renderer API on the main thread, and register cleanup. In `GameLoop`, capture allowed copies after fixed updates and use `PhysicsBody.position`, not interpolated camera position.
- [ ] After parity GREEN, remove the old crosshair pass/shaders/resources and retain only one UI draw path.
- [ ] Run focused GREEN, all Phase 8/9/10 integration tests, and Gate 10.4 integration review; resolve all severities.

---

### Task 12: Package and verify all UI resources

**Gate:** 10.5

**Files:**
- Modify: `engine/build.gradle`
- Modify: `game/build.gradle`
- Modify: `tools/build.gradle`
- Modify: `engine/src/test/java/com/overlord/renderer/shader/ShaderResourceLoaderTest.java`
- Modify: `game/src/test/java/com/gaia/assets/GaiaResourceLoaderTest.java`
- Create: `game/src/test/java/com/gaia/ui/UiPackagedResourceContractTest.java`

- [ ] Write tests/Gradle assertions that engine JAR/installDist contain UI shaders and game JAR/installDist contain font/icon PNG/JSON plus manifest; deleted crosshair shaders are no longer required; committed assets match deterministic regeneration.
- [ ] Run the three existing package tasks RED and capture missing entries.
- [ ] Register `verifyGeneratedUiAssets` as compare-only during `check`; it must regenerate into a temporary build directory and never rewrite source resources. Keep runtime modules independent from `tools`.
- [ ] Run GREEN:

```powershell
.\gradlew.bat :game:verifyPackagedResources --rerun-tasks --console=plain --no-daemon
.\gradlew.bat :engine:verifyPackagedShaderResources --rerun-tasks --console=plain --no-daemon
.\gradlew.bat :game:verifyInstalledShaderResources --rerun-tasks --console=plain --no-daemon
.\gradlew.bat :tools:verifyGeneratedUiAssets --rerun-tasks --console=plain --no-daemon
```

- [ ] Inspect JAR ZIP entries directly and prove classpath/JAR-safe loading with no `file:`-only assumption.

---

### Task 13: Write final architecture, style, provenance, and handoff docs

**Gate:** 10.5

**Files:**
- Create: `docs/architecture/ui-rendering-and-hud.md`
- Create: `docs/architecture/astral-membrane-style-guide.md`
- Create: `docs/architecture/ui-icon-and-font-assets.md`
- Create: `docs/agent-handoffs/phase-10-handoff.md`

- [ ] Document final immutable data flow, dependencies, pass/state/GPU lifecycle, framebuffer scaling, alpha/gamma, one crosshair authority, and forbidden gameplay dependencies from actual code.
- [ ] Record Quiet Membrane colours/spacing/type/animation tokens, 46/46/38 sizes, 12px margin, Shared Core, Detached Creative, 16/2/4 crosshair, and 28x2/7px progress bar.
- [ ] Record project-owned generator sources, commands, exact atlas hashes/cells/coverage, fallback, and absence of copied web artwork/system fonts.
- [ ] Populate handoff with completed/unfinished work, architecture choices, complete file inventory, exact tests/counts, Windows matrix, screenshot status, macOS status, risks, protected interfaces, diff stat, suggested commit, and PR title/description. Unrun evidence is `NOT RUN`.
- [ ] Run:

```powershell
git diff --check
rg -n "unresolved placeholder|f[i]ll in details|\\#|\\-|\\\[\\\[" docs/architecture docs/agent-handoffs docs/superpowers
git status --short --untracked-files=all
```

---

### Task 14: Run complete automation, hygiene, and architecture scans

**Gate:** 10.5

**Files:** Update only when a failing check exposes a Phase 10 defect; every correction receives focused RED/GREEN. Update the handoff with exact final evidence.

- [ ] Run module and clean builds:

```powershell
.\gradlew.bat :engine:test --console=plain --no-daemon
.\gradlew.bat :game:test --console=plain --no-daemon
.\gradlew.bat :tools:test --console=plain --no-daemon
.\gradlew.bat clean test build --console=plain --no-daemon
```

- [ ] Run all four Task 12 resource checks.
- [ ] Run hygiene:

```powershell
git diff --check
git status --short --untracked-files=all
git diff --stat
git diff --name-status
git ls-files | rg "(^|/)(build|bin)/|\.class$|hs_err_pid|replay_pid|\.idea/|\.vscode/"
rg -n "org\.gradle\.java\.home|/Library/Java/JavaVirtualMachines|[A-Za-z]:\\.*jdk" --glob "!**/build/**"
```

- [ ] Run architecture/graphics scans:

```powershell
rg -n "#version (42[0-9]|4[3-9][0-9])|GL_COMPUTE_SHADER|GL_SHADER_STORAGE_BUFFER|\bSSBO\b" engine game tools
rg -n "com\.gaia" engine/src
rg -n "WorldMutationService|WorldItemService|BodyInventoryService|InventoryService|BlockInteractionController|ChunkRepository" engine/src/main/java/com/overlord/renderer/ui game/src/main/java/com/gaia/ui
rg -n "gl[A-Z]|org\.lwjgl" game/src/main/java/com/gaia/ui
rg -n "record ItemStack|class ItemStack|interface ItemStack|WorldItem.*Store|Item.*Registry" game/src/main/java/com/gaia/ui engine/src/main/java/com/overlord/renderer/ui
```

- [ ] Inspect every match; fix only evidence-backed defects with focused RED/GREEN, then repeat all commands.
- [ ] Request separate UI/render-owner and inventory/presentation-owner reviews; fix all severities and repeat automation.

---

### Task 15: Perform Windows visual acceptance and record macOS status

**Gate:** final manual acceptance

**Files:** Update only `docs/agent-handoffs/phase-10-handoff.md`; keep screenshots outside tracked paths unless the user later requests documentation images.

- [ ] Run development and record Escape exit code:

```powershell
.\gradlew.bat :game --console=plain --no-daemon
```

- [ ] Run installDist and record process exit code:

```powershell
.\gradlew.bat :game:installDist --console=plain --no-daemon
.\game\build\install\game\bin\game.bat
```

- [ ] Check Windows 100/125/150% DPI, available high DPI, 800x600/1024x768/1920x1080, maximise, repeated resize, Alt+Tab, F1/F2/F3/F4, Escape, and cross-monitor scaling if hardware permits.
- [ ] Capture untracked acceptance screenshots for Survival, Creative, two-handed, mouth-active, missing icon, DebugHud, F1 hide, break progress, resolutions, and 100/150% DPI. Check clarity, one count, infinity, clipping, no fringes/jitter, and unchanged 3D passes.
- [ ] If no actual Mac exists, mark each Apple Silicon/Retina/native launch/resize/window/fullscreen/scale/F1/focus/state/shutdown item `NOT RUN`; Windows evidence cannot substitute.
- [ ] Any manual correction follows focused RED/GREEN and repeats Task 14.

---

### Task 16: Fresh independent Sol High branch-wide final review

**Gate:** final readiness

**Files:** Modify only for evidence-backed findings; update the handoff with final verdict and HEAD.

- [ ] Start a fresh Sol High read-only session that inspects the complete diff and tests without trusting the handoff.
- [ ] Require review of UI read-only boundaries, mutable escape, one crosshair, two-handed/Creative truthfulness, icon identity, font/resources, framebuffer/content scale, alpha/gamma, GL restoration, partial init/shutdown, provenance, visual readability, and behavioral test strength.
- [ ] Require every finding to include severity, exact file/line, concrete reproduction, violated contract, correction, and regression test.
- [ ] Resolve every finding with focused RED/GREEN, rerun Tasks 14–15 as applicable, then obtain a fresh final review with exactly 0 Critical, 0 Important, and 0 Minor.
- [ ] Report branch, final HEAD, full diff stat/file inventory, total tests, resource hashes/checks, Windows matrix/exit codes, macOS status, screenshots, owner/final verdicts, risks, unfinished items, suggested commit `feat(ui): add scalable astral physical inventory and interaction HUD`, and suggested PR title `feat(ui): add Retina-safe three-slot astral HUD and debug metrics`.
- [ ] End with `git status --short --untracked-files=all` and confirm nothing was staged, committed, pushed, merged, or placed in a PR.

---

## Plan self-review record

- All approved design sections map to Tasks 1–16; no production requirement is deferred silently.
- Game constructs immutable `UiFrame`; engine renders it; tools generates assets and is absent at runtime.
- Two-handed Shared Core derives from read-only slots plus canonical `ItemFormDefinition.twoHanded()` and never creates an alternate stack.
- Creative remains detached and never enters Survival inventory.
- Crosshair deletion follows parity RED/GREEN and ends with one production authority.
- GPU work remains owner-thread guarded; state scope includes scissor and both framebuffer bindings.
- No task authorizes gameplay mutation, block-atlas UV changes, staging, commit, push, PR, or merge.
- Unavailable manual evidence is recorded as `NOT RUN`; READY still requires all automated checks and zero review findings.
