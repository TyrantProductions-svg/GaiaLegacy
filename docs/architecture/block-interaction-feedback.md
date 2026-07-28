# Block Interaction Feedback Contract

Status: implemented Phase 9B candidate; automated verification, both owner
reviews and final branch-wide re-review complete and clean; Windows interactive
acceptance remains partial

Baseline: `origin/main` at `51cb3f23b7ebf9a8999451ac2cf3defb9eec2ceb`

## Ownership

- Phase 9A remains authoritative for targeting, break progress, crack stage,
  modes, placement, reservations, mutation, conservation, and cancellation.
- `LogicalWorldItemService` remains the only world-item store and stable-ID
  authority.
- `ChunkRepository` and `ChunkMeshManager` remain the only Chunk revision,
  dirty propagation, stale-result, upload, and GPU lifecycle authorities.
- Game composition adapts read-only gameplay values into immutable engine
  presentation values.
- Renderer owns all shaders, textures, VAOs, VBOs, EBOs, uploads, draws, and
  cleanup used by Phase 9B.
- All OpenGL work runs on the context-owning main thread through
  `MainThreadGuard`.

Renderer must never receive or call `WorldMutationService`, `WorldItemService`,
Inventory, Raycast, or `BlockInteractionController`. Engine code must never
import `com.gaia`.

## Presentation flow

Game composition reads `BlockInteractionViewModel` and an immutable copy of
`LogicalWorldItemService.snapshots()`. It also observes the existing
post-write `BlockChangedEvent`. These inputs produce one defensive immutable
`InteractionFeedbackFrame` carried by `RenderFrameInput`.

The frame contains lifecycle visibility, an optional damage visual, stable-ID
world-item visuals, and an immutable particle batch. It is presentation state,
not a second gameplay model.

Window focus comes from a read-only accessor over `InputManager`'s existing
GLFW focus state, so no second focus authority is introduced. Blocking UI is
an injected game-side `InteractionBlockState`; Phase 9B binds it to unblocked
and Phase 10 may supply the real UI state without changing Renderer.

## Committed event rule

`BlockChangedEvent` is the Phase 9B equivalent of `BlockBrokenCommitted` because
it is delivered only after the authoritative compare-and-set reports APPLIED.
A completion burst is accepted only for PRIMARY changes from a non-air block
to air. Coordinates and the old material identity are captured from the event;
the mutated World is never queried to reconstruct the previous block.

Before cancellation, reservation rejection, mutation rejection, and ordinary
session cancellation cannot produce a completion burst. A visual subscriber
failure is diagnosed locally. Recoverable runtime failures are contained;
fatal errors are rethrown and retain the existing `mutationApplied=true`
dispatch semantics. Neither case rolls back or automatically retries gameplay.
If a recoverable visual failure is followed by a fatal diagnostic failure, the
diagnostic `Error` remains observable and carries the original visual failure
as suppressed context. If the visual failure itself is fatal, its identity
remains authoritative and diagnostic failures are suppressed onto it.
The existing event has no stable event ID, so Phase 9B does not invent
cross-call deduplication.

## Pass order

1. sky;
2. opaque/transparent world;
3. block damage overlay;
4. world-item visuals;
5. particles;
6. existing debug world pass;
7. screen-space crosshair.

All new passes restore the exact incoming program, VAO/VBO/EBO bindings,
active texture and touched texture binding, depth enable/function/mask, blend
enable/function/equation, cull enable, polygon-offset enable/factor/units, and
viewport on both normal and exceptional exit.

The production OpenGL state adapter models all eight OpenGL 4.1 depth
functions and is exercised through a package-private recording command/query
facade. The public constructor still selects the LWJGL bridge. World-item
visuals explicitly disable culling while drawing, then restore the incoming
cull enable state.

## Crosshair

The crosshair consists of four white screen-space quads. Its total span is 16
framebuffer pixels, thickness is 2 pixels, center gap is 4 pixels, and each arm
is 6 pixels. The center is computed as `(framebufferWidth / 2.0,
framebufferHeight / 2.0)`, never from logical window dimensions.

It is shown only while the game is RUNNING, the gameplay cursor is captured,
the window is focused, no interaction-blocking UI is active, and the
framebuffer is drawable. F1, focus loss, loading, shutdown, or blocking UI
hides it immediately.

## Damage overlay

The project-owned damage atlas is
`assets/gaia/textures/effects/block_damage.png`: a horizontal `160 x 16` RGBA
image with ten `16 x 16` stages. It is generated deterministically by
`tools/src/main/java/com/gaia/tools/BlockDamageAtlasGenerator.java`, uses
nearest level-zero sampling, clamp-to-edge, half-texel UV inset, and alpha
cutout at `0.1`.

Missing, unreadable, undecodable, or invalid data reports the requested
resource while retaining the underlying loader cause and uses an explicit
black/magenta fallback. The original block atlas is unchanged.

The overlay uses one shared unit cube and the authoritative Phase 9A
`crackStage()`. A pure mapper supports 8 through 10 stages for validation:
`min(stageCount - 1, floor(progress * stageCount))`. Zero progress is hidden.

The only Z-fighting mechanism is `GL_POLYGON_OFFSET_FILL` with factor `-1.0`
and units `-1.0`. The model is not expanded. Damage state never changes a
block, Chunk revision, dirty set, or mesh lifecycle.

## Particles

Particles are CPU state updated at fixed `1/60`. The hard cap is 512. An active
Survival break emits one temporary particle every ten fixed steps; a committed
break emits exactly 24 particles before cap handling. Lifetimes range
deterministically from 0.35 to 0.75 seconds. At capacity, the oldest spawn
sequence is replaced.

Each particle carries position, velocity, age, lifetime, size, resolved
texture region, category, and spawn sequence. No shared random source,
collision, gravity simulation, PhysicsBody, compute shader, SSBO, or worker GL
call is allowed. Existing committed particles may expire naturally when
interaction becomes blocked; continuous emission stops immediately.

Continuous cadence identity includes block coordinates, canonical block and
hit face, and resets whenever observed progress regresses. A restarted break
session therefore receives ten fresh valid fixed steps before emission.

## World-item visuals

Each immutable `WorldItemSnapshot` maps by its canonical `WorldItemId` to at
most one presentation instance. New IDs add an instance, changed revisions
update it, missing IDs remove it, and input reordering does not alter identity.
The presentation cache stores only stable ID, canonical immutable item
`ResourceLocation`, source revision, render transform, and resolved region. It
is not a second world-item store or item registry and contains no `ItemStack`,
mutable stack, reservation, or gameplay state.

For an existing stable ID whose canonical item identity is unchanged, region
resolution is reused across position and revision updates. A changed item
identity is re-resolved. This keeps persistent unsupported items to one
fallback diagnostic per visual identity without creating an item registry or
parallel item store.

The visual is a textured cube with edge length 0.25 block at the exact logical
snapshot position. Material resolution follows canonical ItemStack to existing
`ItemFormDefinition`, `BlockRegistry`, and block-atlas metadata. Unsupported
items use the existing missing region and report exactly one failure-safe
presentation diagnostic identifying the item and resolution cause. Phase 9B
adds no motion, physics,
pickup, merge, expiry, persistence, or new ID.

## Lifecycle and cleanup

F1, focus loss, mode switch, loading, shutdown, and blocking UI clear current
damage and continuous-emission eligibility at the frame lifecycle boundary,
including frames with zero fixed steps. Recapture or focus regain cannot
restore old progress.

CPU presentation state is cleared during shutdown. Every Phase 9B GPU resource
is created, updated, drawn, and destroyed on the GL owner thread. Partial
initialization cleans successful resources in reverse order, cleanup is
idempotent, and secondary cleanup failures are suppressed onto the primary
failure.

## Compatibility contract

- Java source and target remain 17; JDK 21 may run the build.
- OpenGL remains at most 4.1 and all shaders use GLSL 410.
- `GL_FRAMEBUFFER_SRGB` remains disabled; the Phase 5B single shader gamma path
  is unchanged.
- Renderer consumes immutable presentation values only.
- Phase 9A transaction and event ordering are unchanged.
- Chunk damage feedback never requests a Chunk rebuild.
- Phase 11 must reuse existing world-item stable IDs.

## Resource provenance and packaging contract

All Phase 9B visual resources are project-owned and were created for
GaiaLegacy. No third-party code, texture, shader, model, or art asset was
introduced.

The damage atlas is reproducible from source with:

```powershell
java tools/src/main/java/com/gaia/tools/BlockDamageAtlasGenerator.java `
  game/src/main/resources/assets/gaia/textures/effects/block_damage.png
```

The generator uses the fixed seed `0x474149413942L`. The verified output is a
`160 x 16` image whose SHA-256 is
`10866639349013A1ABF50472F32B2B06071BCDFABF26F4E5EAF9A304CB3B2FCB`.
The deterministic reproduction test generates two temporary copies and checks
that both match this packaged image byte for byte.

The cumulative resource verifiers require each of these exactly once:

- game JAR: `assets/gaia/textures/effects/block_damage.png`;
- engine JAR and installDist: the `crosshair`, `block_damage`, `particle`, and
  `world_item` GLSL 410 vertex/fragment shader pairs under
  `assets/overlord/shaders/feedback/`.

Each verifier entry was introduced before its resource and produced the
expected missing-entry RED: crosshair in Task 2, damage image/shaders in Task
3, particle shaders in Task 4, and world-item shaders in Task 5. Phase 9B does
not modify the shared block atlas or its metadata.

## Implemented lifecycle boundary

The production frame order is:

1. input and frame-lifecycle handling;
2. fixed-step Phase 9A interaction;
3. committed visual-event adaptation;
4. fixed `1/60` particle/coordinator update;
5. immutable feedback snapshot construction;
6. Renderer consumption through the seven-pass pipeline.

Loading, F1 cursor release, focus loss, mode switching, blocking UI, and
shutdown clear transient damage and continuous-emission eligibility at the
frame boundary. Other fixed-step systems continue running when interaction
feedback is blocked. Non-running frames omit logical world-item visuals while
already committed particles retain their ordinary lifetime.

The authoritative `InteractionBlockState` is resampled after all fixed systems
and immediately before lifecycle clearing and feedback snapshot construction.
A Phase 10 UI that begins blocking in either a leading or trailing fixed system
therefore hides the crosshair and overlay in the same rendered frame and resets
continuous-emission cadence.

GPU creation is transactional. If feedback initialization fails, already
created resources are released in reverse order; cleanup is owner-thread
guarded and idempotent. The normal shutdown order is feedback shader programs,
damage texture, shared cube, streaming particle batch, and screen-quad batch,
followed by the pre-existing renderer resources according to Renderer
ownership.

## Verification snapshot

On 2026-07-28, Windows/JDK 21 automated verification produced:

- Engine: 790 tests, zero failures, errors, or skips;
- Game: 390 tests, zero failures, errors, or skips;
- total: 1,180 tests;
- `clean test build`: successful with all 22 actionable tasks executed;
- all three cumulative packaged-resource verifiers: successful when rerun
  separately with `--rerun-tasks`;
- `git diff --check`: successful;
- protected-boundary and forbidden-feature scans: empty.

Windows acceptance is partial: the development launch covered
initial/maximized framebuffer centering, F1 hide/recapture, clean rendering and
Escape exit with code 0. installDist rendered with a centred crosshair, F1
hide/recapture and one aimed Creative break, and two installed launches exited
with code 0. A debug F5/Q path produced a near-camera textured drop visual, but
a stable stand-off world-item view, sustained overlay and particle behavior,
Alt+Tab, movement/jump and the remaining DPI/resize matrix were not completed.
Native macOS build, launch, Retina,
resize, overlay, alpha cutout, particles,
world-item visuals, focus lifecycle, and shutdown are **NOT RUN** and must not
be inferred from automated compatibility coverage.
