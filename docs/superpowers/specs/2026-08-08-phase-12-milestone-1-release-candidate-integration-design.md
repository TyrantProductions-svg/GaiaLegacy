# Phase 12 Milestone 1 Release Candidate Integration Design

Date: 2026-08-08

Branch: `release/milestone-1-vertical-slice`

Baseline: `origin/main@25d3a78040b08f32d6264a6a7e2a8968eed679f9`

Status: approved design; implementation has not started.

## Purpose

Phase 12 converts the merged Milestone 1 systems into a release-candidate
vertical slice. It integrates, verifies, documents, and packages the existing
deterministic world, fixed-step gameplay, physical world items, feedback,
particles, HUD, and first-person presentation without adding another major
gameplay system.

The release candidate must be buildable from a clean clone and support a
repeatable 20-30 minute small-world gameplay loop on Windows and Apple Silicon
macOS. A platform without an actual native run remains `NOT RUN` or `PENDING`.

## Entry-gate evidence

Gate 12.0 passed before this design was written:

- `git fetch origin --prune` succeeded;
- the current branch is `release/milestone-1-vertical-slice`;
- HEAD and `origin/main` are both
  `25d3a78040b08f32d6264a6a7e2a8968eed679f9`;
- `origin/main...HEAD` divergence is `0 0`;
- the worktree was clean;
- the most recent history contains the merged Phase 4, 5A, 5B, 7, 8, 9A,
  9B, 10, and 11 deliveries;
- `clean test build` passed with all 29 actionable tasks executed;
- the baseline XML contains 947 Engine tests, 825 Game tests, and 27 Tools
  tests, with 1 Tools skip and no failures or errors;
- packaged resources, packaged Engine shaders, installed shaders, and
  generated UI assets passed inside the baseline build.

The first restricted build attempt failed before project configuration because
the sandbox could not create its configured Gradle user-home wrapper lock.
The identical approved rerun reached Gradle and is the baseline result above.

## Scope

Phase 12 may change only:

- integration defects found in the merged production ownership graph;
- explicit release/default configuration through existing configuration
  boundaries;
- clean-clone build and packaged resource defects;
- startup, shutdown, reservation, thread, and GPU-resource lifecycle defects;
- release documentation, acceptance records, known issues, and provenance;
- deterministic demo coordinates and release measurement records.

Phase 12 does not add:

- a main menu, save browser, or general settings architecture;
- persistence or cloud saves;
- crafting, mobs, a complete survival economy, or small-block editing;
- moving voxel assemblies, fluids, weather, PBR, or dynamic shadows;
- networking;
- body-body collision, rotation, pooling, or canonical world-item expiry;
- an audio system for the collaborator music assets.

`Gaia.mp3` and `Legacy.mp3` are documented as future Milestone 2 audio assets
only.

## Protected architecture

The release integration preserves these merged authorities:

- `GameBootstrap` is the application composition root;
- `GameContext` carries explicitly composed runtime ownership;
- `GameLoop` owns the fixed-step and render-frame orchestration;
- `LogicalWorldItemService` is the sole canonical world-item, motion,
  reservation, and stable-ID authority;
- `BodyInventoryService` is the sole body-inventory mutation authority;
- `WorldMutationService` is the gameplay block-write boundary;
- `ChunkRepository` owns Chunk publication, revision, dirty propagation,
  unload, and stale-result authority;
- `ChunkMeshManager` owns current CPU-result acceptance and main-thread GPU
  installation/release;
- `PlayerController` is the sole player-body integrator;
- Renderer and UI consume immutable presentation only;
- every GLFW, OpenGL, shader, texture, mesh, framebuffer, and GPU lifecycle
  operation remains on the context-owning main thread;
- production simulation remains exactly `1.0 / 60.0` second with the existing
  first-step press-edge and held-only catch-up rules.

Release fixes must extend these boundaries rather than creating a parallel
service, store, loop, identity namespace, renderer authority, or mutation
path.

## Release defaults

Phase 12 reconciles the following defaults through their existing owners:

| Default | RC value | Existing owner |
|---|---:|---|
| World seed | `12345` | `WorldGenerationConfig.visualRevisionCandidate()` |
| World algorithm | version `2` | `WorldGenerationConfig.visualRevisionCandidate()` |
| Finite loaded-world radius | `4` Chunks | `WorldGenerationConfig.visualRevisionCandidate()` |
| Loaded demo area | `81` Chunks | `WorldLoader` deterministic range |
| Vertical FOV | `70.0` degrees | `GameConfig.Rendering.FOV` |
| Mouse sensitivity | `0.1` | `GameConfig.Input.MOUSE_SENSITIVITY` |
| VSync | `true` | `GameConfig.Window.VSYNC` |
| Debug HUD | disabled | `HudPresenter` initial presentation state |
| Game mode | Survival | `GameBootstrap` / `GameModeManager` construction |

The radius is the finite demo-world loading radius, not a new dynamic
distance-streaming or distance-culling system. All loaded Chunks remain
eligible for the existing conservative frustum culling. Documentation must not
claim a separate configurable streaming distance.

The world seed, algorithm, and radius remain owned by
`WorldGenerationConfig`; Phase 12 does not duplicate them in an Engine config
or add a second world-generation configuration source. Debug HUD and default
game mode remain game-owned defaults. FOV, mouse sensitivity, and VSync use the
existing Engine `GameConfig` boundary.

## VSync contract

`GameConfig.Window.VSYNC` is explicitly `true` for the Milestone 1 release
candidate.

Window initialization must:

1. assert the existing owner/main-thread requirement;
2. initialize GLFW and create the window;
3. make the window's OpenGL context current;
4. call `glfwSwapInterval(1)` when VSync is true or
   `glfwSwapInterval(0)` when VSync is false;
5. continue the existing capability, callback, visibility, render, and cleanup
   lifecycle.

The call must never occur before a context is current. The disabled case is
explicit interval `0`; it must not rely on GLFW or driver defaults. Milestone
1 does not request adaptive VSync and must never use interval `-1`.

VSync is presentation-only. It does not change `FixedStepClock`, the 1/60
simulation interval, catch-up limits, input edge delivery, physics,
transactions, world mutation, or deterministic world generation.

If VSync initialization fails, the existing Window initialization failure path
must release any completed GLFW/window resources and preserve the primary
failure. Phase 12 does not add an alternate rendering loop.

## Configuration correction strategy

The only planned default-value production corrections are:

- add `GameConfig.Window.VSYNC = true` and apply its exact interval after
  `glfwMakeContextCurrent`;
- add `GameConfig.Input.MOUSE_SENSITIVITY = 0.1f` and replace the private
  `Camera` literal with that constant.

The existing FOV, version-2 world configuration, debug-HUD false state, and
Survival construction already match the RC contract. They require focused
regression/structure coverage and documentation, not new configuration
objects. This avoids a Milestone 2 settings architecture.

## Gate 12A: integration health

Audit the real production path:

```text
GameBootstrap
    -> GameContext
    -> GameLoop
    -> world / physics / interaction / feedback / renderer / UI
```

The audit verifies:

- exactly one owner and construction site for every mutable service;
- the fixed-step order and render-frame capture order;
- immutable renderer/UI presentation boundaries;
- absence of duplicate `ItemStack`, WorldItem, Chunk, mesh, physics, and game
  loop authority;
- reverse ownership shutdown;
- idempotent repeated close;
- terminal or released reservations after shutdown;
- main/context-thread GPU destruction;
- termination of world-loading, meshing, scheduler, and other owned tasks;
- removal of runtime bodies and presentation caches without deleting
  canonical world items.

No architecture refactor is allowed without a concrete failing test, runtime
failure, ownership leak, or release blocker.

## Gate 12B: deterministic demo world

The RC uses the existing seed `12345`, algorithm version `2`, and radius `4`.
There is no demo-only production branch.

Documentation records these existing deterministic inspection coordinates:

| Purpose | Coordinate |
|---|---:|
| Plains tree | `(-33, 33)` |
| Rolling-hills tree | `(0, 12)` |
| Rocky outcrop | `(-50, 8)` |
| Surface cave entrance A | `(-22, 24, 3)` |
| Surface cave entrance B | `(-6, 23, -21)` |
| Deep reachable cave | `(67, 2, -64)` |
| Cross-Chunk tunnel air | `(48, 57, -45)` |

The acceptance run records the exact safe spawn returned by the production
loader. It must not hard-code, teleport to, or manufacture a demo spawn.

The required live flow is:

```text
spawn
-> move and jump
-> select all body slots
-> Survival break
-> canonical block drop
-> physical motion
-> Shift+right manual pickup
-> placement
-> Q single drop
-> Ctrl+Q full-stack drop
-> Creative mode
-> return to Survival
-> clean exit
```

The flow must remain playable for 20-30 minutes without item loss/duplication,
crash, unbounded queues, duplicate meshes/bodies, stale reservations, or
shutdown residue.

## Gate 12C: documentation and packaging

Phase 12 updates or creates:

- `README.md`;
- `CONTROLS.md`;
- `docs/architecture/current-baseline.md`;
- `CHANGELOG.md`;
- `KNOWN_ISSUES.md`;
- `THIRD_PARTY_NOTICES.md` when the provenance audit requires it;
- `docs/agent-handoffs/phase-12-handoff.md`.

The documentation must let a clean-clone user:

- identify JDK, Java compatibility, OpenGL, and platform requirements;
- build and run with the checked-in Wrapper;
- understand all production controls, including Q, Ctrl+Q, Shift+right,
  F1-F4, slot selection, noclip, and Escape;
- reproduce the fixed demo world and find representative terrain/features;
- distinguish development, installDist, automated, and human acceptance;
- see known limitations and Milestone 2 deferrals;
- understand that `Gaia.mp3` and `Legacy.mp3` are future assets and are not
  wired into an audio system.

Packaging verification must cover every existing cumulative game resource,
Engine shader, installed shader, and generated UI asset check. A clean clone
must not depend on local absolute paths or generated files stored in Git.

## Gate 12D: automated and interactive acceptance

### Immediate runtime rule

Any fix touching GLFW, OpenGL, shader, uniform, GL state, input, audio, or
runtime lifecycle follows this order:

1. focused RED;
2. minimal GREEN;
3. focused automated GREEN;
4. actual platform runtime smoke immediately;
5. related/full verification only after the runtime path is stable.

Fake or recording render backends and source scans do not replace the actual
GLFW/OpenGL run.

For the VSync correction, the Windows runtime smoke occurs immediately after
its focused GREEN and exercises launch, movement, jump, one block break, one
block placement, Q, Ctrl+Q, Shift+right pickup, and Escape shutdown.

### Windows

Required automation:

```powershell
.\gradlew.bat clean test build --console=plain --no-daemon
.\gradlew.bat :game:verifyPackagedResources --rerun-tasks --console=plain --no-daemon
```

All existing shader and generated-UI verification tasks also run with
`--rerun-tasks`. `git diff --check` must pass.

Required interactive evidence covers development and installDist launch,
20-30 minute gameplay, resize/DPI, Alt+Tab, F1/F2/F3/F4, VSync-enabled runtime,
and clean Escape shutdown without shader, uniform, GL-state, cleanup, or
reservation errors.

### Apple Silicon macOS

Required automation uses the checked-in `./gradlew` Wrapper on a native arm64
JVM. Required interactive evidence covers development and installDist launch,
Retina/resize, Command+Tab, function-key behavior, the complete gameplay loop,
and clean shutdown.

Windows results never imply macOS success. If no Apple Silicon environment is
available, every macOS row remains `NOT RUN` or `PENDING`.

## Performance and soak evidence

The RC records representative observations for:

- FPS and frame time;
- fixed steps and catch-up behavior;
- loaded and visible Chunks;
- generation and mesh queue depth;
- draw calls and triangles;
- physics bodies, world items, and particles;
- memory and GC behavior.

The existing Phase 11 profiler showed approximately 58.5 MiB/s allocation,
12 collections, 27 ms collection time, and a 3 ms maximum observed pause over
its sample. This requires attribution before any reuse optimization. Phase 12
does not introduce pooling merely because the measurement is high.

## Testing strategy

Focused VSync/default tests establish:

- the RC default is `true`;
- enabled maps to interval `1`;
- disabled maps to interval `0`;
- no adaptive interval is present;
- swap interval is applied only after the context is current;
- the owner-thread initialization guard remains active;
- Camera sensitivity comes from `GameConfig.Input.MOUSE_SENSITIVITY`;
- fixed-step constants and ordering remain unchanged.

Integration tests and audits cover ownership uniqueness, shutdown idempotency,
reservation release, GPU-thread ownership, resource packaging, config/default
documentation, and absence of duplicate authorities.

Every defect correction uses `systematic-debugging` before implementation and
a focused RED/GREEN cycle. Completion claims use fresh
`verification-before-completion` evidence.

## Failure and stop policy

Stop the release integration when any of these occurs:

- canonical item duplication or loss;
- a crash or non-clean shutdown;
- an unexplained Windows or macOS build failure;
- shader, uniform, OpenGL-state, or GPU cleanup failure;
- a reservation that remains locked after its owner shuts down;
- duplicate stable IDs, bodies, meshes, or mutable service authority;
- a proposed fix disables transaction, thread, revision, or state protection;
- the demo requires a large new system.

The first sandbox-only Gradle cache failure is not a product stop because the
identical approved build passed. Future environment failures must likewise be
distinguished from product failures with concrete evidence.

## Gate 12E: release-candidate closure

Before any optional staging, the final audit records:

```text
git status --short --untracked-files=all
git diff --name-status
git diff --stat
git ls-files --others --exclude-standard
git diff --check
```

Generated output, logs, screenshots, saves, crash dumps, absolute local paths,
IDE metadata, `.class` files, and unrelated experiments are rejected from the
candidate.

The final handoff reports exact changed files, current test totals, platform
development/installDist status, performance observations, known issues,
Milestone 2 deferrals, and release notes. It proposes but does not execute:

- commit: `release: integrate milestone 1 physical sandbox vertical slice`;
- PR title: `release: GaiaLegacy Milestone 1 vertical slice`;
- release/tag notes.

No file is staged, committed, pushed, tagged, included in a pull request, or
merged without explicit later authorization.
