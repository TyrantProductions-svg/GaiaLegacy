# Phase 10 UI Rendering and HUD Contract

## Scope

Phase 10 adds a read-only, framebuffer-aware HUD presentation layer. It presents
the Phase 8 body inventory, Phase 9 interaction and game-mode state, previous
completed render metrics, authoritative player-feet position, and immutable
feedback counts. It does not add a gameplay mutation path.

The engine owns generic layout, immutable draw commands, GPU resources, and the
final UI pass. The game owns Gaia-specific presentation semantics and widgets.
The build-only `tools` module owns deterministic font and icon generation.
Neither `engine` nor `game` has a runtime dependency on `tools`, and `engine`
does not depend on `game`.

## Immutable presentation flow

The production flow is:

```text
BodyInventoryViewModel + BlockInteractionViewModel
RenderMetricsSnapshot + PhysicsBody.position() + immutable counts
focus/cursor/lifecycle/blocking state + fixed-input sample
                           |
                           v
game: HudPresenter
                           |
                           v
game: immutable HudPresentationSnapshot
                           |
                           v
game: GaiaHudScreen + presentation-only widgets
                           |
                           v
engine: UiDrawList.seal() -> immutable UiFrame
                           |
                           v
RenderFrameInput -> RenderContext -> UiRenderPass -> UiRenderer
```

`HudPresenter` copies projected item identities and counts into immutable HUD
values. Those values are not an alternate `ItemStack`, item registry, or
inventory store and cannot be submitted to gameplay operations. `UiFrame`
contains only validated immutable draw commands. The renderer can reach no
inventory, interaction, world, physics, Chunk, or reservation service through
the UI frame.

### Allowed read dependencies

- `BodyInventoryViewModel` and its read-only slot projections;
- `BlockInteractionViewModel` and `CreativeSelection` presentation;
- the previous completed `RenderMetricsSnapshot`;
- `PhysicsBody.position()` copied as authoritative feet coordinates;
- immutable world-item and Phase 9B feedback counts;
- framebuffer size, content scale, focus, cursor capture, lifecycle, and
  blocking-UI state;
- immutable `GaiaUiAssets`, `UiIconAtlas`, and `BitmapFont` resources.

### Forbidden dependencies

Game UI and engine UI packages must not call or retain:

- `BodyInventoryService`, `InventoryService`, or any inventory mutation API;
- `WorldMutationService`, `WorldItemService`, or a world-item store;
- `BlockInteractionController`, Raycast, or a cancellable Before listener;
- `ChunkRepository`, dirty/revision/mesh-rebuild APIs, or mutable World state;
- mutable Physics APIs or interpolated camera position;
- LWJGL/OpenGL from `game` UI code.

F2/F3 visibility toggles are presentation state only. UI render or event
failures propagate for diagnosis but cannot roll back, retry, reserve, commit,
mutate, or dirty gameplay state.

## Frame and input timing

`GameLoopFrameOrchestrator` consumes fixed input only when a frame executes at
least one fixed step. The original snapshot is supplied to the first fixed
step; later catch-up steps receive `heldOnly()`. Every fixed system still runs
for every scheduled catch-up step.

After the fixed batch, `HudFrameCoordinator` captures exactly one presentation
and one UI frame for the rendered frame:

- a consumed fixed snapshot makes F2/F3 press edges visible once;
- F2/F3 use `GameConfig.Input.KEY_TOGGLE_HUD` and
  `KEY_TOGGLE_DEBUG_HUD` and do not repeat from held input;
- a zero-step frame uses neutral presentation input and does not consume or
  replay pending F2/F3 edges;
- monotonically increasing sample IDs prevent replay or out-of-order capture;
- active slot, F4 mode, and interaction progress are captured after fixed
  updates and therefore appear in the same rendered frame;
- 1/2/3, wheel, Q, F4, and destructive mouse input remain owned by their
  existing Phase 8/9 controllers.

Lifecycle visibility is evaluated once per render frame even when there are
zero fixed steps. Loading, shutdown, focus loss, F1 cursor release, or blocking
UI immediately hides gameplay HUD and clears transient interaction
presentation. Re-entry cannot revive stale progress; the presenter waits for a
neutral interaction projection before accepting new transient interaction
state. F2 hides gameplay HUD while F3 DebugHud remains independently available
during a safe running frame.

## HUD composition

`GaiaHudScreen` preserves this command order:

1. `BodyInventoryHud`;
2. `CrosshairWidget`;
3. `BreakProgressWidget`;
4. `GameModeWidget`;
5. `InteractionFailureWidget`;
6. `DebugHud`.

The body HUD is a compact bottom-centre triangle: 46 by 46 logical-pixel hand
slots, a 38 by 38 mouth slot above them, and a 12 logical-pixel bottom margin.
Two-handed presentation uses the selected hand as its sole visual anchor and
the other hand as its locked companion. When mouth is active, neither hand is
active and the left hand remains the deterministic anchor. Every case draws
one icon and one count. Creative selection is detached from the preserved
Survival slots and displays infinity rather than a fabricated inventory stack.

`ItemStack` remains the single canonical immutable stack value type. The
presenter defensively copies read-only `ItemStackView` data into canonical
values while sealing the immutable HUD snapshot; no alternate HUD stack type,
registry, store, or gameplay mutation dependency is introduced.

DebugHud reads the previous completed render metrics so it never displays a
partially accumulated current frame. Its player coordinates are copied from
the authoritative physics body, not the interpolated render camera.

## One crosshair authority

`CrosshairWidget` is the only production crosshair draw authority. It reuses
the Phase 9B `CrosshairGeometry` contract: four white framebuffer-space quads,
16-pixel total span, 2-pixel thickness, and a 4-pixel centre gap. The former
`CrosshairRenderPass` and its two dedicated shaders are deleted. Crosshair
geometry does not perform or influence Raycast.

The short break-progress widget is 28 by 2 framebuffer pixels. Its top edge is
15 pixels below framebuffer centre, which places it 7 pixels below the
crosshair's lower extent. Completed progress is white; the remaining track is
white at 22% alpha. It is hidden outside an eligible Survival break session.

## Coordinate spaces and snapping

`UiLayoutContext` preserves logical window dimensions for diagnostics, but
derives logical UI size from framebuffer size divided independently by X/Y
content scale. Logical layout is converted to framebuffer rectangles by
rounding each edge independently with `Math.round(logical * scale)`.

Consequences:

- framebuffer centre, not logical-window centre, owns the crosshair;
- X and Y scales remain independent at fractional or asymmetric scale;
- resize, maximise, Retina/DPI changes, and monitor-scale transitions rebuild
  the immutable layout from the current `RenderSurfaceMetrics`;
- text origins, baselines, borders, and icons share the same edge-snapping
  policy;
- inventory identity, state, quantity, and temporary-name glyphs use a
  pixel-grid-safe source scale: one-half scale below the readable DPI
  threshold, otherwise an integer scale, with a one-to-one source-pixel floor
  at 150% content scale and above. X and Y are quantized independently. This
  prevents the 8 by 8 bitmap cells from being resampled at 0.625 or 0.75 source
  pixels on Windows fractional DPI;
- zero-width or zero-height framebuffer surfaces perform no UI upload or draw.

## Render pass order

After UI installation, the production pass order is exactly:

1. `sky`;
2. `world`;
3. `block-damage`;
4. `world-items`;
5. `particles`;
6. `debug`;
7. `ui`;
8. buffer swap outside the pipeline.

The UI renderer preserves command order and batches only consecutive commands
with identical texture and clip state. It never texture-sorts across widgets.

## Alpha, gamma, and GL state

The UI uses straight alpha throughout:

- RGB blend: `SRC_ALPHA`, `ONE_MINUS_SRC_ALPHA`;
- alpha blend: `ONE`, `ONE_MINUS_SRC_ALPHA`;
- RGB and alpha equations: `FUNC_ADD`;
- depth test and depth writes disabled;
- culling and polygon offset disabled;
- scissor enabled only for clipped runs;
- viewport set to the current drawable framebuffer.

Texture and tint RGB are sRGB content. `ui.frag` decodes both to linear,
multiplies them, and encodes the result once. `GL_FRAMEBUFFER_SRGB` is disabled
while the UI pass draws, preserving the Phase 5B single explicit gamma path,
then restored to its captured incoming enable state. Alpha is multiplied
without gamma conversion.

On normal and exceptional exits, the state boundary restores:

- program;
- VAO, array-buffer, and element-buffer bindings;
- active texture and unit-zero 2D texture binding;
- blend enable, separate RGB/alpha factors, and equations;
- depth enable, function, and write mask;
- cull and polygon-offset state, factor, and units;
- viewport;
- scissor enable and box;
- draw and read framebuffer bindings;
- framebuffer-sRGB enable state.

## CPU/GPU ownership and cleanup

`GaiaUiAssetLoader` performs JAR-safe CPU resource loading through
`AssetManager`. `GameBootstrap` passes the immutable `UiAssetBundle` through
the generic `Renderer.installUiAssets` API. Shader, textures, VAO, VBO, EBO,
uploads, draws, and destruction are all guarded by `MainThreadGuard` on the
OpenGL context owner.

Creation order is shader, icon texture, font texture, then batch resources.
Partial construction cleans successful resources in reverse order. Installed
UI cleanup is idempotent; cleanup continues after a failure, preserves the
first failure, and suppresses distinct later failures. Duplicate installation
and non-owner installation are rejected before extra allocation.

## Failure semantics

- Missing required shader, atlas, metadata, or manifest fails UI installation
  with the JAR-safe resource path and retained cause.
- Unknown item identity resolves to the explicit missing icon and is diagnosed
  once per canonical `ResourceLocation`.
- Unsupported text resolves to U+FFFD and is diagnosed once per code point.
- Missing optional metrics render as `N/A`.
- A UI composition or render exception is delivered once. It does not retry
  the frame or invoke gameplay mutation/transaction callbacks.

## Protected Phase 8/9 interfaces

Future UI work must preserve:

- the canonical `ResourceLocation`, `ItemStack`, `ItemFormDefinition`,
  `InventoryReservation`, and body-slot contracts;
- `BodyInventoryService` as the only body-inventory mutation boundary;
- atomic two-handed ownership and exact count conservation;
- Creative selection remaining separate from Survival inventory;
- the Phase 6 Raycast and Phase 9A fixed-step interaction state machine;
- reservation-before-mutation placement/break transactions and committed event
  ordering;
- `WorldMutationService` as the only gameplay block-write boundary;
- `LogicalWorldItemService` and stable `WorldItemId` as the sole logical
  world-item authority;
- Phase 3 revision, dirty propagation, neighbour invalidation, and stale mesh
  rejection;
- immutable `InteractionFeedbackFrame` and `HudPresentationSnapshot` as
  renderer-facing boundaries;
- the seven-pass order, one crosshair authority, and context-owner GL lifetime.

## Related documents

- [Astral Membrane style guide](astral-membrane-style-guide.md)
- [UI icon and font assets](ui-icon-and-font-assets.md)
- [Phase 8 body inventory](body-inventory.md)
- [Phase 9A interaction core](block-interaction-core.md)
- [Phase 9B interaction feedback](block-interaction-feedback.md)
- [Phase 5B rendering contract](phase-05b-rendering-contract.md)
