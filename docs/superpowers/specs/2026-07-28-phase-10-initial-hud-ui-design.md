# Phase 10 Initial HUD and UI Design

Date: 2026-07-28

Branch: `feat/initial-hud-ui`

Baseline: `origin/main@250c3d628f82998eacf73b6a4cf2f8d16b17c7b8`

Status: written design approved; implementation has not started

## 1. Purpose and scope

Phase 10 adds the first formal, scalable, Retina-safe GaiaLegacy UI layer. It
presents the existing three-slot body inventory, interaction state, game mode,
render metrics, and authoritative player position. It does not add or change
gameplay.

The phase delivers:

- a bottom-centre left-hand, right-hand, and mouth HUD;
- active, empty, two-handed, and Creative presentation;
- project-owned item icons and bitmap text;
- one authoritative centre crosshair;
- a small break-progress indicator;
- Survival/Creative status and short mode-change feedback;
- an optional read-only DebugHud;
- a generic engine-owned UI rendering layer and a game-owned HUD presenter.

The phase does not add a backpack, drag-and-drop, crafting, settings screens,
chat, command entry, a HUD editor, real-time item previews, gameplay input
remapping, or any inventory/world/interaction mutation.

## 2. Baseline gate

The start gate was checked after `git fetch origin --prune`:

- Phase 9B is merged in `origin/main` as PR #18;
- `feat/initial-hud-ui` is clean;
- branch HEAD and `origin/main` are both `250c3d6`;
- initial divergence is `0/0`;
- Java source and target compatibility remain 17;
- all twelve current shaders declare `#version 410 core`;
- OpenGL remains 4.1 compatible with no compute shader or SSBO;
- `Renderer` constructs exactly one `CrosshairRenderPass`, which is the only
  current production crosshair draw authority.

## 3. Non-negotiable ownership boundaries

### 3.1 Domain ownership

- `BodyInventoryService` remains the only body-inventory mutation boundary.
- `ItemStack(ResourceLocation, positive count)` remains the only item stack
  domain value.
- `BlockRegistry` and `ItemFormDefinition` remain the item identity and rule
  source; no UI item registry is permitted.
- `CreativeSelection` remains independent from Survival inventory.
- `BlockInteractionController`, `WorldMutationService`, `WorldItemService`,
  Raycast, Physics, and Chunk lifecycle remain unchanged.
- Phase 10 reads `BodyInventoryViewModel`, `BlockInteractionViewModel`,
  `RenderMetricsSnapshot`, immutable world-item counts, and PhysicsBody state.
  It never receives a mutable service through a UI type.

### 3.2 Module ownership

- `engine` owns generic UI layout primitives, immutable draw commands, text
  layout, GPU batches, shaders, textures, the UI pass, and OpenGL state.
- `game` owns Gaia-specific HUD semantics, widgets, visual theme, item icon
  metadata, display names, and adapters from domain snapshots.
- `tools` is a build-time-only Gradle subproject above `game`. It may depend on
  `game`; neither `engine` nor `game` depends on `tools` at runtime.
- `engine` must not import `com.gaia`.
- `game` UI code must not import LWJGL or issue OpenGL calls.

### 3.3 Thread ownership

- Live inventory, interaction, Chunk, PhysicsWorld, and PhysicsBody reads are
  captured on their existing owning main/fixed-update thread.
- Immutable UI frames may cross internal presentation boundaries after capture.
- All UI shader, texture, VAO, VBO, EBO, upload, draw, and cleanup work executes
  on the context-owning main thread through `MainThreadGuard`.
- Worker tasks may generate CPU images only when explicitly invoked by tooling;
  no worker performs runtime OpenGL work.

## 4. Architecture and data flow

The selected architecture is game-owned semantic composition followed by an
engine-owned generic renderer:

```text
BodyInventoryViewModel
BlockInteractionViewModel
CreativeSelection
RenderMetricsSnapshot
authoritative PhysicsBody position
window/focus/loading/blocking state
             |
             v
game: HudPresenter + GaiaHudScreen + widgets
             |
             v
engine: immutable UiFrame + UiDrawCommand list
             |
             v
RenderFrameInput -> UiRenderPass -> UiRenderer/UiBatch
```

### 4.1 Engine UI boundary

The engine UI package provides these minimal boundaries:

- `UiLayoutContext`: logical and framebuffer dimensions, content scale,
  safe-area bounds, and pixel-snapping operations;
- `UiFrame`: an immutable ordered list of `UiDrawCommand` values;
- `UiDrawCommand`: immutable textured or solid quad data with framebuffer
  bounds, UV bounds, sRGBA tint, texture identity, and optional clip rectangle;
- `UiDrawList`: a mutable construction helper owned only while composing one
  frame, sealed into an immutable `UiFrame` before Renderer receives it;
- `Widget`: a presentation-only command producer;
- `HudScreen`: an ordered widget compositor;
- `TextRenderer`: CPU glyph layout that appends quad commands;
- `UiTexture`, `UiShader`, `UiBatch`, and `UiRenderer`: main-thread GPU
  resources and draw orchestration;
- `UiRenderPass`: the single final screen-space pass.

`UiFrame` contains no service, controller, World, Chunk, PhysicsBody,
`ItemStack`, reservation, or mutable collection. Constructors defensively copy
every list and reject non-finite geometry, invalid UVs, invalid clip rectangles,
and null resource identities.

### 4.2 Game presentation boundary

`HudPresenter` captures allowed read-only state after fixed updates and before
render-frame construction. It maintains only presentation state:

- elapsed slot-transition time;
- the last displayed active item identity and item-name timeout;
- the last displayed game mode and mode-notice timeout;
- F2/F3 visibility toggles;
- no domain object ownership.

Game widgets transform copied presentation values into generic engine draw
commands. `ItemStack` remains the sole canonical immutable stack value type.
The presenter may defensively copy an `ItemStackView` into that canonical type
while sealing a frame snapshot, but Phase 10 must not define an alternate HUD
stack type, registry, or store. Widgets receive no inventory mutation service,
so the copied canonical values remain presentation-only in this data flow.

### 4.3 Frame timing

- Fixed systems remain authoritative for inventory selection, interaction,
  GameMode, and Physics.
- `HudPresenter` samples after fixed updates so active slot, F4 mode, and break
  progress are visible in the same rendered frame.
- UI animations use render-frame delta and never feed back into fixed state.
- Debug metrics display the previous fully completed render frame. Current-frame
  metrics cannot be displayed before the current render has finished.
- That intentional one-frame delay applies only to diagnostic text.

## 5. Astral Membrane visual language

The approved style is **Quiet Membrane**: 80% clear, quiet function and 20%
astral membrane/portal language.

It uses translucent dark membranes, restrained cyan-grey rims, a thin cyan to
soft-violet active double ring, and warm star-white Creative accents. It does
not use eyes, tentacles, flesh, teeth, high-saturation full-screen purple,
continuous breathing scale, high-density particles, or refractive effects that
reduce item readability.

### 5.1 Design tokens

| Token | Value |
| --- | --- |
| Void background | `#071019D9` |
| Primary text | `#EAF6F4` |
| Inactive rim | `#708D94` |
| Active primary rim | `#8FDCCF` |
| Active secondary halo | `#9B83CF` |
| Creative accent | `#E7D89D` |
| Failure text | `#E15C64` |
| Debug background | `#05090DD9` |
| Debug text | `#D6E0E3` |
| Spacing scale | `2, 4, 6, 8, 12, 16` logical px |
| Slot transition | 150 ms ease-out |
| Item-name visibility | 1.5 s, final 250 ms fade |
| Mode notice | 1.25 s, final 250 ms fade |

Phase 10 does not enable a continuous rim shimmer. Any later shimmer must be
low contrast, presentation-only, and separately accepted.

## 6. Layout and scaling

### 6.1 Coordinate spaces

`UiLayoutContext` derives logical UI size from framebuffer size divided by the
corresponding positive content scale. Logical window dimensions remain recorded
for validation but never substitute for framebuffer geometry.

Layout occurs in logical UI units. Each left, top, right, and bottom edge is
converted with its axis content scale and rounded to the nearest integer
framebuffer coordinate. Text glyph origins and baselines use the same snapping
rule. This avoids cumulative drift at 1.25 and 1.5 scale.

The contract covers:

- 800x600, 1024x768, 1280x720, 1920x1080, 2560x1440, and 3840x2160;
- odd framebuffer dimensions;
- 4:3, 16:9, 16:10, and ultrawide;
- content scale 1.0, 1.25, 1.5, and 2.0;
- logical-window/framebuffer mismatch, resize, maximise, and monitor-scale
  transitions.

### 6.2 Body HUD geometry

The approved bottom-centre triangle is:

```text
            MOUTH (38 x 38)
    LEFT (46 x 46)  RIGHT (46 x 46)
```

- bottom safe margin: 12 logical px;
- left hand is always visually left and right hand visually right;
- mouth uses a simple organic arc marker, not a monster mouth;
- key labels are `1`, `2`, and `3` with body-part labels;
- quantities are shown once in the lower-right icon-safe area;
- item name appears above the slot group, never below the bottom safe edge;
- the cluster remains below the crosshair and primary world view.

Active state uses both shape and text: a two-ring halo plus `ACTIVE`. Empty
uses an empty outline plus `EMPTY`. Neither relies only on colour.

### 6.3 Two-handed truthfulness

The approved **Shared Core** presentation preserves Phase 8 atomic ownership
while making the currently selected hand visually truthful:

- one icon is drawn once between the hands;
- one quantity is drawn once;
- one shared halo spans both hands;
- if a hand is active, that hand is the sole presentation anchor and the other
  hand is the `LOCKED` dashed companion;
- if mouth is active, neither hand is marked active and the left hand remains
  the deterministic presentation/storage anchor;
- no mirrored second icon or second count is rendered.

### 6.4 Creative truthfulness

The approved **Detached Selection** presentation keeps Creative separate:

- the selected Creative icon appears in one warm-gold independent membrane;
- quantity is `infinity` (`U+221E`), with `CREATIVE` text as a redundant cue;
- real body slots remain visible at reduced emphasis and are labelled as
  preserved, not active;
- no Creative `ItemStack` is written into or projected as Survival inventory;
- returning to Survival removes the Creative membrane in the same frame and
  restores the real active slot presentation.

## 7. Crosshair and interaction widgets

### 7.1 Single crosshair authority

Phase 10 migrates the Phase 9B crosshair into `UiRenderPass` only after parity
tests pass. The old `CrosshairRenderPass`, its shader, and its dedicated batch
are then removed from Renderer composition and resource ownership.

The UI crosshair preserves exactly:

- four white quads;
- total 16 framebuffer px span;
- 2 framebuffer px thickness;
- 4 framebuffer px centre gap;
- centre at `(framebufferWidth / 2.0, framebufferHeight / 2.0)`;
- visibility only when running, focused, cursor captured, drawable, HUD visible,
  and interaction not blocked.

It does not participate in Raycast and does not inspect a target.

### 7.2 Break progress

Break progress is a quiet white bar:

- width 28 framebuffer px;
- height 2 framebuffer px;
- top edge 7 framebuffer px below the crosshair extent;
- completed segment pure white;
- remaining track white at 22% alpha;
- no large central bar and no circular ring.

It reads authoritative `InteractionViewModel.progress`. It hides immediately
for no target, cancellation, completion, Creative instant break, target change,
loading, F1, focus loss, F2, blocking UI, or shutdown. It does not redefine
the Phase 9A/9B crack stage and never mutates Chunk or interaction state.

### 7.3 Mode and failure

- A low-interference persistent mode marker sits at the top right.
- F4 expands it into `SURVIVAL` or `CREATIVE infinity` for 1.25 seconds.
- A recent interaction failure uses standard red text below the progress area.
- Failure text is presentation-only and does not retry an operation.

## 8. UI input boundary

- F2 toggles the complete gameplay HUD: inventory, crosshair, mode, progress,
  item name, and failure text.
- F3 toggles DebugHud independently; DebugHud starts hidden.
- F2/F3 use existing `InputManager` key press edges and execute only on the
  first fixed step of a catch-up batch.
- F1, focus loss, loading, shutdown, and blocking UI hide gameplay HUD at the
  render-frame lifecycle boundary even if the frame has zero fixed steps.
- F1/focus boundaries clear transient UI timers and break-progress presentation.
- UI never consumes or rewrites 1/2/3, wheel, Q, F4, or mouse interaction.
- Phase 10 introduces no clickable, hover-driven, drag, menu, or command input.

## 9. Font and icon assets

### 9.1 Project-owned bitmap font

The project uses the deterministic, independently authored Quiet Rune 5x7
version 1 bitmap font. The checked-in `tools` source uses explicit five-column,
seven-row project patterns rasterized into 8x8 cells; it is not derived from an
operating-system or third-party font table.

The generated font atlas contract is:

- image: `assets/gaia/ui/ui_font.png`;
- metadata: `assets/gaia/ui/ui_font.json`;
- atlas: 128x64 RGBA;
- glyph cell: 8x8;
- glyph set: printable ASCII 32 through 126, `U+221E`, and one missing glyph;
- nearest, level-zero sampling; clamp-to-edge; no mipmaps;
- transparent pixels use white RGB with zero alpha to avoid fringe pollution;
- unsupported characters resolve to the missing glyph with one diagnostic per
  code point.

The source/provenance record includes the `quiet-rune-5x7` algorithm ID,
version, source fingerprint, independent construction process, and rejection
fingerprint for the discarded provenance-ambiguous candidate. No external font
license is needed because no external font data remains.

### 9.2 UI icon atlas

The icon generator is an offline `tools` task and uses the final Phase 2
resource catalog as input. It never invokes runtime Renderer or takes game
screenshots.

The generated icon atlas contract is:

- image: `assets/gaia/ui/ui_icons.png`;
- metadata: `assets/gaia/ui/ui_icons.json`;
- atlas: 128x64 RGBA;
- tile: 32x32;
- four columns by two rows;
- six used cells for five current item forms and the missing fallback; the two
  remaining cells stay explicitly unassigned;
- nearest, level-zero sampling; clamp-to-edge; no mipmaps.

For every block item, the generator resolves the canonical item through the
existing BlockRegistry and uses the block's authoritative UP, NORTH, and EAST
texture regions to draw a three-face isometric cube. Lighting is fixed and
identity-preserving: top 100%, left 82%, right 68%. Atlas UVs and the shared
block atlas are not modified.

Current required item icons are:

- `gaia:grass`;
- `gaia:dirt`;
- `gaia:stone`;
- `gaia:oak_log`;
- `gaia:oak_leaves`.

The missing icon is a dark membrane containing a white wireframe cube and a
small missing mark. It never substitutes another real item image.

### 9.3 Display metadata

`UiIconDefinition` contains:

- canonical item `ResourceLocation`;
- immutable English display name;
- icon atlas region.

`UiIconAtlas` owns immutable metadata and texture identity.
`UiIconResolver` maps a canonical item ID to a definition or explicit missing
definition. These are presentation resources, not item definitions, stack
values, placement rules, or a second registry. Gameplay cannot query them.

Names are data-driven in UI metadata. Java class names are never displayed.
Names wider than 144 logical px are truncated to fit and end with ASCII `...`.

### 9.4 Generation and provenance

- Both atlas generators are deterministic Java 17 tools.
- Generated images and metadata are committed; runtime never generates them.
- Verification regenerates into a temporary directory and compares exact
  SHA-256 and metadata.
- The handoff records generator commands, final hashes, paths, ownership, and
  the absence of copied web artwork.

## 10. DebugHud

DebugHud is a neutral top-left panel with no astral decoration. It uses the
bundled bitmap font and displays:

- FPS and frame time from the previous completed `RenderMetricsSnapshot`;
- draw calls, triangles, visible chunks, and mesh queue depth;
- loaded chunks from `ChunkRepository.keys().size()`;
- physics body count from immutable `PhysicsWorld.bodies().size()`;
- authoritative player feet position copied from `PhysicsBody.position(...)`;
- immutable world-item snapshot count;
- current interaction target presence;
- damage overlay count (`0` or `1`), immutable world-item visual count, and
  immutable particle count from the same frame's `InteractionFeedbackFrame`.

Any unavailable optional metric is rendered as `N/A`. DebugHud reads snapshots
only, starts hidden, and cannot reset counters or mutate gameplay.

## 11. Render pass and OpenGL contract

The final production pass order is:

1. sky;
2. opaque/transparent world;
3. block damage overlay;
4. world-item visuals;
5. particles;
6. debug world;
7. UI/HUD;
8. swap buffers.

### 11.1 UI draw semantics

- `UiRenderer` preserves command order.
- It batches only consecutive commands with identical texture and clip state.
- It does not reorder by texture or widget.
- All commands are quads; platform-dependent line width is not used.
- Screen-space coordinates use a top-left origin and are converted to NDC with
  a framebuffer-size uniform.
- TextRenderer lays out glyphs on CPU and appends ordinary textured quads.

### 11.2 Alpha and gamma

- The entire UI uses straight alpha.
- RGB blend factors are `SRC_ALPHA` and `ONE_MINUS_SRC_ALPHA`; alpha blend
  factors are `ONE` and `ONE_MINUS_SRC_ALPHA`.
- Blend equations are `ADD`.
- UI textures and theme tints are sRGB content; the shader decodes both RGB
  inputs to linear, applies tint, and encodes once for output.
- `GL_FRAMEBUFFER_SRGB` remains disabled, preserving the Phase 5B explicit
  shader gamma path and preventing double encoding.
- Generated transparent pixels have non-polluting RGB, and nearest sampling
  prevents filtered dark fringes.

### 11.3 UI pass state

The pass requests:

- depth test disabled;
- depth write disabled;
- cull disabled;
- blend enabled with the straight-alpha contract;
- polygon offset disabled;
- viewport equal to the drawable framebuffer;
- scissor enabled only for clipped command runs.

The state scope captures and restores on normal and exceptional exit:

- program;
- VAO, array buffer, and element buffer;
- active texture and the touched unit-zero 2D binding;
- blend enable, RGB/alpha factors, and RGB/alpha equations;
- depth enable, depth function, and depth mask;
- cull enable;
- polygon-offset enable, factor, and units;
- viewport;
- scissor enable and scissor box;
- draw and read framebuffer bindings.

The UI uses texture unit zero only, so no other texture-unit binding is touched.

### 11.4 GPU lifecycle

- Renderer owns all UI GPU resources.
- Creation order is shader, icon texture, font texture, VAO/VBO/EBO batch.
- Partial initialization cleans successful resources in exact reverse order.
- Shutdown is idempotent and owner-thread guarded.
- Cleanup continues through all resources; the first failure remains primary
  and distinct later failures are suppressed.
- A zero framebuffer performs no UI upload or draw.
- UI pass failure cannot roll back or retry gameplay state.

## 12. Resource failure semantics

- Unknown canonical item: render missing icon and diagnose once per item ID.
- Unsupported glyph: render missing glyph and diagnose once per code point.
- Missing optional debug metric: render `N/A`.
- Missing or invalid required atlas, metadata, or shader: fail Renderer UI
  initialization with exact ResourceLocation and underlying cause.
- Required-resource failure triggers reverse cleanup of every successful UI GPU
  allocation.
- No missing resource silently substitutes a different real item.

## 13. TDD and automated verification

Every production change begins with a focused failing behavioral test. Source
structure tests supplement but never replace runtime contract tests.

### Gate 10.1: UI foundation

- immutable layout and command validation;
- all required resolution/aspect/content-scale matrices;
- fractional scale snapping and resize/monitor transitions;
- ordered batching and clipping;
- complete normal/exception GL restoration;
- partial-init reverse cleanup and idempotent shutdown;
- main-thread GPU ownership and engine-to-game direction.

### Gate 10.2: font and icons

- deterministic generator byte equality and hashes;
- atlas bounds and non-overlap;
- all current item forms resolve to icon or explicit fallback;
- display-name mapping stability;
- missing item/glyph diagnostics;
- game JAR, engine JAR, and installDist resource verification.

### Gate 10.3: body HUD

- physical triangular placement and safe-area constraints;
- left/right/mouth identities and key labels;
- active double-ring and empty redundant cues;
- quantity and long-name layout;
- Shared Core single icon/count for two-handed ownership;
- Detached Selection infinity and Survival restoration;
- immutable inputs and zero mutation dependencies.

### Gate 10.4: crosshair, progress, and mode

- exactly one production crosshair draw authority;
- Phase 9B geometry and lifecycle parity;
- F1, focus, loading, blocking, F2, resize, and zero-fixed-step behavior;
- break-progress visibility and target-change reset;
- Creative instant-break exclusion;
- mode notice timing and same-frame presentation.

### Gate 10.5: DebugHud and integration

- previous-completed-frame metric formatting;
- authoritative player-position capture;
- loaded Chunk, physics body, and world-item counts;
- unavailable metric fallback;
- F2/F3 press/hold/release/catch-up behavior;
- final pass order, no service dependency, shutdown, and failure paths.

After every gate, an independent read-only review checks its production diff,
tests, ownership, and failure paths. Critical, Important, and Minor findings
must receive a focused RED/GREEN correction before the next gate.

Final commands are:

```powershell
.\gradlew.bat :engine:test --console=plain --no-daemon
.\gradlew.bat :game:test --console=plain --no-daemon
.\gradlew.bat clean test build --console=plain --no-daemon
.\gradlew.bat :game:verifyPackagedResources --rerun-tasks --console=plain --no-daemon
.\gradlew.bat :engine:verifyPackagedShaderResources --rerun-tasks --console=plain --no-daemon
.\gradlew.bat :game:verifyInstalledShaderResources --rerun-tasks --console=plain --no-daemon
git diff --check
git status --short --untracked-files=all
git diff --stat
git diff --name-status
```

Architecture and hygiene scans must find no generated build output, IDE cache,
crash dump, absolute JDK path, OpenGL above 4.1, GLSL above 410, compute/SSBO,
engine-to-game dependency, worker-thread GL, UI mutation service dependency,
second item registry, alternate stack type, or second crosshair.

## 14. Manual acceptance

Windows development and installDist runs cover:

- 100%, 125%, 150%, and available high-DPI settings;
- 800x600, 1024x768, 1920x1080, maximise, repeated resize, and available
  cross-monitor scale transition;
- Survival, Creative, Shared Core two-handed fixture, mouth-active fixture,
  missing icon fixture, DebugHud, F1 hide, and break-progress fixture;
- active readability, item distinction, quantity clarity, long-name clipping,
  restrained membrane styling, and absence of black fringes or pixel jitter;
- F1, F2, F3, F4, slot changes, focus/Alt+Tab, Escape, and clean shutdown;
- preserved world, damage overlay, particle, and world-item rendering.

Screenshots are acceptance evidence and are not committed unless a later user
instruction explicitly adds them to project documentation.

Native macOS acceptance must cover Apple Silicon build and launch, Retina,
resize, window movement, fullscreen/windowed transitions, content scale,
F1/focus, UI state restoration, and shutdown. If no Mac is available, every
native result is recorded as **NOT RUN**.

## 15. Risks and mitigations

- A small 8x8 font has limited glyph coverage. Phase 10 explicitly supports
  English UI text, ASCII punctuation, and infinity only; missing glyph is
  visible and diagnosed.
- Metrics displayed one frame late could be misunderstood. DebugHud labels the
  snapshot as the last completed frame and never presents it as gameplay state.
- Fractional content scale can cause uneven one-pixel widths. Edge-wise snapping
  and snapshot tests at 1.25 and 1.5 constrain the result.
- Migrating crosshair ownership could duplicate or remove it. Parity tests land
  before the old pass is deleted, and an architecture test requires exactly one
  final authority.
- Adding scissor/framebuffer capture expands shared GL state. Recording-backend
  tests cover every state field and both setup/draw failure paths before UI
  composition uses them.
- Generated icons could drift when block resources change. Deterministic hashes
  and coverage verification fail the build until the checked-in UI atlas is
  deliberately regenerated.

## 16. Protected interfaces for later phases

- canonical `ResourceLocation`, `ItemStack`, `ItemFormDefinition`, and
  `BlockRegistry` identity/rule contracts;
- `BodyInventoryService` as sole body-inventory mutation boundary;
- immutable `BodyInventoryViewModel` and `InteractionViewModel` snapshots;
- independent `CreativeSelection` and real Survival active-slot restoration;
- Phase 9A fixed-step input, Raycast, reservation, mutation, event, count, and
  dirty/revision semantics;
- Phase 9B immutable feedback frame, committed-only bursts, world-item stable
  IDs, and non-mutating overlay;
- one crosshair authority and exact framebuffer geometry;
- Phase 5A/5B pass/state ownership, shader gamma, framebuffer/content-scale,
  renderer lifecycle, OpenGL 4.1, and GLSL 410;
- Engine-to-game dependency direction and main-thread GPU ownership.

## 17. Delivery status rule

Phase 10 may be reported READY only when:

- clean test build and all resource checks pass;
- Windows visual matrix passes;
- macOS is run or honestly marked NOT RUN;
- `git diff --check` and hygiene scans pass;
- visual resources are project-owned or have complete provenance;
- UI/render and inventory/presentation owner reviews are READY;
- a fresh independent Sol High branch-wide read-only review reports zero
  Critical, Important, and Minor findings.

No file is staged, committed, pushed, merged, or placed in a pull request by
this design activity.
