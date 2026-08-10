# Phase 13 — Product Shell, Settings & Audio Foundation Design

Status: approved for implementation planning on 2026-08-08
Milestone: 2, Phase 1
Target branch: `feat/product-shell-audio`
Approved baseline: `origin/main@80ea67bf9a41e467dbd17ba81876ab870c41407d`

## 1. Purpose

Phase 13 turns the Milestone 1 sandbox executable into a product-shaped application without replacing its deterministic gameplay architecture. It adds one main menu, one pause flow, settings persistence and application boundaries, a controls screen, modal ownership, and a minimal cross-platform audio foundation. The existing gameplay HUD remains a gameplay overlay inside a session.

The phase must preserve:

- one application loop and one GLFW/OpenGL owner thread;
- the fixed 1/60 gameplay simulation;
- immutable render and UI presentation snapshots;
- canonical input ownership and edge semantics;
- existing World, inventory, block-interaction, WorldItem, pickup, and transaction authority;
- OpenGL 4.1 / GLSL 410 compatibility;
- reverse-order, idempotent runtime cleanup.

The phase does not implement SaveGame v1, world catalogs with real entries, cloud saves, full key rebinding, inventory/crafting UI, complex accessibility, cinematic menu animation, or Milestone 2 gameplay systems.

## 2. Baseline findings

The accepted Milestone 1 baseline has the following relevant structure:

- `GameBootstrap` creates the engine, all gameplay services, and an asynchronous world load before entering `GameLoop`.
- `GameLoop` directly owns loading/running/stopping state and Escape currently exits the process.
- `GameLoopFrameOrchestrator` already guarantees that only the first fixed step receives pressed edges; catch-up steps receive `InputSnapshot.heldOnly()`.
- `InputManager` already centralizes GLFW callbacks, focus invalidation, mouse baselines, pressed edges, and destructive mouse suppression, but it does not expose an immutable UI pointer snapshot or general held-input release gate.
- `UiRenderer` is a pure draw-command renderer. The baseline has no button hit-testing, focus navigation, screen stack, or modal input model.
- `HudPresenter` already accepts a `blockingUi` presentation input and suppresses interaction presentation when blocked.
- VSync is configured only at window initialization. FOV and mouse sensitivity are static constants. The world generation Chunk radius is captured when a world is created.
- The project uses LWJGL 3.3.3 core, GLFW, OpenGL, and STB. It has no OpenAL module.
- No current main-menu, settings-store, or audio implementation exists.

The untracked `dist/GaiaLegacy-v0.2.0-alpha.1-windows-x64.zip` is pre-existing user/generated output. Phase 13 must preserve it and exclude it from the intended file inventory.

## 3. Chosen architecture

### 3.1 One product loop, optional one game session

A long-lived `ProductLoop` owns application-level resources:

- the GLFW window and renderer;
- UI rendering and product-screen presentation;
- `ScreenRouter` and modal state;
- the loaded immutable settings snapshot and persistence boundary;
- `AudioDevice` and `MusicManager`;
- zero or one active `GameSession`.

There is never a second loop. Window polling, surface updates, product UI, audio presentation updates, gameplay fixed steps when eligible, rendering, and buffer swap remain ordered in the single owner-thread loop.

`GameSessionFactory` creates a new session only after the user activates New World. `GameSession` owns all world-specific and gameplay-specific resources:

- the canonical `World` and Chunk storage;
- world loading, generation, mesh management, and executors;
- player, physics, and camera-session bindings;
- inventory, game mode, interaction, and input controllers;
- logical and physical WorldItems and pickup;
- interaction feedback, particles, transient presentations, and gameplay HUD capture;
- the fixed-step clock and session shutdown registrations.

The generic engine must no longer be a second World owner. The existing World construction currently located inside `Engine` is moved to the game-session composition boundary. Renderer and camera remain reusable engine facilities, while the session supplies the current immutable render content.

Return to Main Menu closes the active session in reverse ownership order and clears its last presentation before showing the product shell without a gameplay world. A subsequent New World creates a distinct World and distinct session resources. No gameplay object may survive a completed session close or resurrect after it.

### 3.2 Screen router and modal layer

The required primary routes are:

- `MAIN_MENU`
- `LOADING`
- `PLAYING`
- `PAUSED`
- `SETTINGS`
- `CONTROLS`

Settings and Controls carry an explicit return target of Main Menu or Paused. They do not duplicate controllers or domain behavior.

Modal state is orthogonal and always topmost. A modal receives input before the current screen and blocks both the screen beneath it and gameplay. The required modals are:

- quit confirmation from Main Menu;
- unsaved-progress confirmation before leaving an active session;
- dirty-settings Apply / Discard / Cancel confirmation;
- non-fatal, input-blocking error acknowledgement for session-load failure where appropriate.

The valid high-level flow is:

```text
MAIN_MENU
  |-- SETTINGS(return=MAIN_MENU)
  |-- CONTROLS(return=MAIN_MENU)
  `-- LOADING --> PLAYING --> PAUSED
                              |-- SETTINGS(return=PAUSED)
                              |-- CONTROLS(return=PAUSED)
                              `-- confirmation --> MAIN_MENU
```

Invalid transitions are rejected deterministically and leave state unchanged. Repeated close, cancel, or transition commands must be idempotent.

### 3.3 Session loading and failure

New World uses the current fixed seed `12345` and the validated next-session settings snapshot. Load World remains visible but disabled with `Available in Phase 14`.

Phase 13 defines only the narrow future-facing boundary needed by Phase 14: a read-only `SaveCatalog` that supplies immutable save summaries and a typed session-launch command that can distinguish New World from a future Load request. The Phase 13 production adapter returns an empty catalog, so Load World is disabled. It does not define save files, serialization, world restoration, autosave, or a SaveGame transaction.

While `LOADING` is active, the product loop continues polling and rendering the product UI. No gameplay fixed step runs. If loading succeeds, player placement and all existing session readiness checks complete before the router enters Playing.

If loading fails, or the user cancels loading, the half-constructed session is closed completely: cancel the world future, stop executors within the existing shutdown deadline, close mesh/runtime resources, clear presentation, and return to Main Menu. A failure is reported without terminating the application unless product-shell infrastructure itself is no longer usable.

## 4. Pause and focus policy

Pause is a hard gameplay pause:

- fixed-step accumulation and all gameplay fixed systems stop;
- player, physics, WorldItems, interaction, events, and gameplay modules do not advance;
- window events, UI, immutable presentation rendering, and audio fades continue;
- the last valid gameplay frame remains available behind the pause overlay;
- no canonical state or revision changes because time was spent paused.

On resume, the fixed-step accumulator's partial time is discarded, the mouse baseline is reset, and pre-resume input edges are invalidated. No catch-up burst is allowed.

Focus behavior is:

- focus loss during Playing transitions to Paused and invalidates interaction input;
- focus loss on Main Menu, Paused, Settings, or Controls clears input state but does not change route;
- focus recovery never captures the cursor or resumes gameplay implicitly;
- the user explicitly resumes before gameplay input becomes eligible again.

Shortcut behavior is reconciled as follows:

- Escape follows screen routing and no longer directly stops the process;
- Escape in Playing enters Paused;
- Escape in Paused resumes;
- Escape in Main Menu opens quit confirmation, and Escape on that modal dismisses it;
- F1 is an alternate Playing/Paused transition, preserving its cursor-release/capture purpose without a third half-paused state;
- F2, F3, and F4 are routed to gameplay only in Playing;
- menu screens never leak these gameplay shortcuts.

## 5. Input ownership

GLFW callbacks remain installed once through `InputManager`. Each product frame creates one immutable UI input sample containing:

- logical pointer coordinates derived from the current window/content-scale metrics;
- key-down and key-pressed state;
- mouse-down and mouse-pressed state;
- bounded scroll input;
- current focus state;
- a monotonically increasing sample identifier.

Input routing order is fixed:

1. GLFW events update callback-owned state.
2. The product loop captures one immutable UI input sample.
3. The top modal, if any, receives it exclusively.
4. Otherwise the active product screen receives it.
5. Only Playing, focused, cursor-captured, modal-free state permits fixed gameplay input consumption.

The existing first-fixed-step / `heldOnly()` rule remains unchanged after input becomes gameplay-eligible.

Every focus, screen, modal, or cursor-capture boundary:

- cancels active block interaction and clears transient action feedback;
- discards key, mouse, and scroll edges;
- resets the mouse-motion baseline;
- marks gameplay-relevant keys and buttons that are still held as suppressed;
- keeps each suppressed control unavailable until a real release callback occurs.

This release gate covers at least Q, Ctrl, right click, left click, jump, movement keys, slot selection, and gameplay debug shortcuts. Returning to Playing cannot replay a held action.

## 6. UI presentation and interaction

`UiRenderer` remains a pure engine draw boundary. Product UI behavior is implemented in the game module through separate immutable layers:

- `ScreenViewModel`: labels, values, enabled state, selection, diagnostics;
- `ScreenPresenter`: layout to `UiFrame` and matching immutable hit regions;
- `ScreenInputController`: pointer/keyboard input to typed `ScreenCommand`;
- `ProductShellController`: executes routing, settings, session, and quit commands.

No Screen, widget, or hit region may hold or call World, Inventory, WorldItem, Renderer, GLFW, or OpenAL services directly.

The visual direction is intentionally bounded:

- Main Menu uses a readable static product background, title/version, and a vertical action group;
- Pause renders a translucent overlay over the frozen gameplay presentation;
- Settings uses grouped rows with explicit current/draft values and Apply/Back controls;
- Controls is a read-only keyboard/mouse reference;
- Load World is visibly disabled rather than pretending an empty catalog is a load implementation;
- no cinematic or complex animated background is added.

The same layout calculation produces the painted bounds and hit regions. Layout uses logical coordinates and converts through `UiLayoutContext`, preserving existing framebuffer/content-scale handling on Windows DPI and macOS Retina. Keyboard navigation provides deterministic focus order, arrow/tab movement, Enter/Space activation, and Escape return semantics.

## 7. Settings schema and persistence

### 7.1 Immutable schema v1

The versioned immutable settings snapshot contains:

| Setting | Valid range | Default | Application |
|---|---:|---:|---|
| VSync | boolean | true | immediate on Apply |
| FOV | 50–100 degrees | 70 | immediate on Apply |
| Mouse sensitivity | 0.02–0.50 | 0.10 | immediate on Apply |
| Invert Y | boolean | false | immediate on Apply |
| Render distance / Chunk radius | 2–8 | 4 | next New World |
| Master volume | 0–100% | 100% | immediate on Apply |
| Music volume | 0–100% | 65% | immediate on Apply |
| SFX volume | 0–100% | 100% | immediate on Apply |
| Mute when unfocused | boolean | true | immediate on Apply |
| Default game mode | Survival/Creative | Survival | next New World |
| Debug HUD default | boolean | false | next GameSession |

Fullscreen/window-mode settings and an additional UI-scale setting are deferred. The existing resizable-window and DPI/Retina behavior remains authoritative.

### 7.2 Cross-platform path

`SettingsPathProvider` selects:

- Windows: `%APPDATA%\GaiaLegacy\settings.json`
- macOS: `~/Library/Application Support/GaiaLegacy/settings.json`
- Linux: `$XDG_CONFIG_HOME/GaiaLegacy/settings.json`, falling back to `~/.config/GaiaLegacy/settings.json`

The provider accepts injected properties/environment values for tests. Production files and documentation never contain a personal absolute path.

### 7.3 Read, validation, and atomic write

Missing settings load the defaults without an error. JSON is versioned. Unknown fields are ignored for forward compatibility. Unsupported schema versions, malformed JSON, non-finite values, and invalid enum or range values produce a bounded diagnostic and fall back safely rather than failing application startup.

Settings are written only after explicit Apply or as a final rewrite of the already-applied snapshot during normal product-shell close. Unapplied draft state is never persisted.

The save algorithm creates the parent directory, writes a temporary file in the same directory, flushes and closes it, then uses atomic replace when the filesystem supports it. A documented replace fallback is allowed when atomic movement is unavailable. Failed persistence leaves the prior valid settings file intact where practical and does not publish an unpersisted snapshot as successfully applied.

### 7.4 Draft and application boundaries

Settings opens with a draft copied from the current applied snapshot. Apply performs:

1. complete draft validation;
2. atomic persistence;
3. publication of a new immutable applied snapshot;
4. owner-thread application through explicit public boundaries.

Back with no changes returns immediately. Back with changes opens Apply / Discard / Cancel. Discard drops the draft. Cancel returns to Settings.

Hot-application boundaries are explicit:

- Window applies VSync as swap interval 1 or 0 after its GLFW context is current on the owner thread.
- Renderer/camera projection consumes validated FOV without changing canonical camera forward or raycast authority.
- Camera look processing consumes validated sensitivity and invert-Y.
- Audio consumes the validated volume and focus policy.

Render distance, game mode default, and debug HUD default are captured into a new immutable `GameSessionConfig`; they do not mutate an existing World or pretend to hot-apply.

VSync remains presentation-only and cannot alter the fixed 1/60 simulation.

## 8. Audio foundation

### 8.1 Approved dependency policy

Phase 13 is explicitly authorized to add:

- `org.lwjgl:lwjgl-openal` under the existing LWJGL 3.3.3 BOM;
- the matching OpenAL native runtime artifact for the same Windows, macOS, and Linux x64/arm64 classifier logic already used by other LWJGL modules.

Vorbis decode uses the already-present `lwjgl-stb`. No second decoder dependency is introduced.

### 8.2 Ownership and interfaces

The minimum architecture is:

- `AudioBackend`: low-level backend abstraction;
- `OpenAlAudioBackend`: production OpenAL implementation;
- `SilentAudioBackend`: deterministic fallback and test implementation;
- `AudioDevice`: owner-thread device/context/source/buffer lifetime and bounded voices;
- `MusicManager`: desired music state, catalog selection, streaming, fades, ducking, and duplicate suppression;
- `SoundEvent` and `SoundCue`: immutable identifiers and gain/category metadata;
- Master, Music, and SFX buses.

All OpenAL calls execute on the product owner thread. No audio callback mutates gameplay. Music uses a bounded streaming-buffer ring: processed buffers are unqueued, refilled from the STB Vorbis decoder, and requeued. The implementation must not permanently decode both complete tracks to PCM in memory.

Effective gain is validated and clamped as `master * channel * cue`. The SFX channel and a bounded voice policy are established, but Phase 13 does not force unrelated gameplay code to add sound effects. Missing cues log a bounded diagnostic and remain silent.

Device or context initialization failure selects the Silent backend and allows the product to continue. Track load/decode failure skips that track with a diagnostic. Neither condition may crash world simulation.

Close order is MusicManager, sources/streaming buffers, OpenAL context, and device. Close is idempotent; no update or route transition may recreate audio after close.

### 8.3 Music behavior v1

- Main Menu requests Gaia and fades it in over 2.0 seconds.
- New World and Gameplay continue the same Gaia playback instance and position; route transition does not restart or duplicate it.
- Pause, Settings-from-Pause, and Controls-from-Pause duck the current music target to 70% over 0.35 seconds.
- Return to Main Menu restores the normal Music-bus target without creating a second source.
- Track completion performs an ordinary replay with a short fade-in. Phase 13 does not claim gapless looping.
- Legacy is decoded, registered, packaged, and selectable in tests, but no normal v1 route automatically plays it.
- With mute-when-unfocused enabled, Master output fades to silence over 0.20 seconds and returns smoothly after focus recovery.

### 8.4 Original music and provenance

The two tracks are original collaborative works. Both authors have approved redistribution with GaiaLegacy source code, installers, and public releases.

Public credit:

`Music by Leo Deng (Leosteeeve) and David Li (Omi Hurricane)`

Phase 13 must not invent a Creative Commons or standalone reuse license. Documentation records only the approved GaiaLegacy redistribution permission.

Source metadata captured before implementation:

| Source | Duration | Sample rate | Channels | Bitrate | SHA-256 |
|---|---:|---:|---:|---:|---|
| `Gaia.mp3` | 289.8285833 s | 44,100 Hz | 2 | 192 kbps | `d3f7cb27ae858e9982c7b7d75ffb3677a5bd338f5c0f9776dd1493ce72b1cfb4` |
| `Legacy.mp3` | 252.6824583 s | 44,100 Hz | 2 | 192 kbps | `7872fec2e9c135411542f6690136efef1f63d1566e2d9a26d614ff0f7b6e23db` |

Approved source layout:

- `game/src/main/source-assets/audio/Gaia.mp3`
- `game/src/main/source-assets/audio/Legacy.mp3`

Approved runtime layout:

- `game/src/main/resources/assets/gaia/audio/music/gaia.ogg`
- `game/src/main/resources/assets/gaia/audio/music/legacy.ogg`

`docs/audio-provenance.md` records authorship, authorization, original and derivative metadata, source/derivative hashes, exact conversion tool/version/command, and packaged paths. Transcoding must be reproducible. If no verifiable conversion tool is available locally, implementation stops for tool-installation authorization rather than silently using an unknown converter.

## 9. Rendering and update order

The product-frame order is:

1. tick the frame clock;
2. poll GLFW events;
3. process focus/surface changes;
4. capture immutable UI input;
5. route modal/screen commands;
6. apply completed settings or lifecycle transitions;
7. update MusicManager fades/streaming;
8. if Playing, run the existing fixed-step batch;
9. capture one immutable product/session presentation;
10. render world presentation if a session supplies one;
11. render gameplay HUD only when its visibility model permits;
12. render the active product screen and top modal;
13. report metrics and swap buffers.

Paused and Loading frames skip step 8. UI and audio use bounded presentation delta and cannot change canonical gameplay time.

## 10. Verification design

### 10.1 Gate 13A — screen and session lifecycle

Focused RED/GREEN tests cover:

- complete legal screen-transition table and illegal-transition rejection;
- modal input exclusivity and no underlying activation;
- pointer and keyboard navigation;
- matching layout/hit regions at 1x, fractional DPI, and Retina-style scale;
- Escape and F1 pause/resume behavior;
- F2/F3/F4 suppression outside Playing;
- focus-loss automatic pause;
- paused frames do not advance the fixed clock or any gameplay system;
- repeated pause/resume does not accumulate time;
- held Q, Ctrl, mouse buttons, jump, movement, slots, and debug shortcuts require release before gameplay resumes;
- GameSession creation, load cancellation, close, and fresh re-creation;
- no duplicate World authority or surviving session service;
- reverse close and idempotent close;
- UI types cannot directly mutate domain services.

After local GREEN, the actual Windows application is launched immediately for Main Menu, New World, Pause/Resume, Settings/Back, and clean Escape/quit smoke.

### 10.2 Gate 13B — settings

Focused RED/GREEN tests cover:

- schema v1 round trip;
- defaults and all numeric boundaries;
- malformed JSON, unsupported version, unknown fields, invalid enums, NaN/Infinity, and range fallback;
- Windows, macOS, Linux XDG, and Linux fallback paths;
- temporary write and atomic-replace/fallback behavior;
- persistence failure leaves prior applied state coherent;
- Apply, Discard, Cancel, and dirty-back modal;
- hot settings call only the intended public boundary;
- VSync applies interval 1/0 on the context owner thread;
- FOV does not change camera/raycast authority;
- fixed 1/60 remains unchanged;
- next-session settings do not mutate the active session.

After local GREEN, Windows runtime verifies FOV, sensitivity, VSync, persisted relaunch, resize, and DPI behavior.

### 10.3 Gate 13C — audio

Focused RED/GREEN tests cover:

- volume clamping and bus multiplication;
- state transitions without duplicate playback or position restart;
- pause ducking, focus mute, and fade envelopes;
- Gaia and Legacy catalog registration;
- streaming-buffer and voice bounds;
- ordinary end-of-track replay without a gapless claim;
- missing/corrupt OGG diagnostic fallback;
- OpenAL initialization failure to Silent backend;
- owner-thread enforcement;
- idempotent close and no post-close resurrection;
- menu/gameplay/pause/resume does not leak or restart music;
- packaged game JAR and installDist contain both runtime OGG files;
- provenance source and derivative hashes match actual files.

After local GREEN, the real Windows runtime verifies playback, fades, settings volume, focus loss/Alt+Tab, clean close, and persisted relaunch. Any OpenAL, GLFW, input, shader, or GL-state correction receives a real runtime smoke immediately after its focused GREEN.

### 10.4 Gate 13D — final acceptance

Automated verification includes:

- all focused UI/settings/audio tests;
- `:engine:test`, `:game:test`, and `:tools:test`;
- `clean test build`;
- packaged resource, shader, UI, audio, and installDist checks;
- `git diff --check`;
- final tracked/untracked inventory and generated-artifact audit.

Windows interactive acceptance covers the full menu/settings/controls/session/pause/audio path, DPI/resize, mouse capture, focus recovery, clean shutdown, and persisted relaunch.

Apple Silicon macOS acceptance covers the equivalent development and installDist path, Retina/resize, Command+Tab, native OpenAL playback, and clean close. A platform not actually run is reported as `NOT RUN / PENDING`.

Automated fake backends do not substitute for actual GLFW/OpenGL/OpenAL runtime evidence.

## 11. Documentation and handoff

Phase 13 produces or updates:

- this approved design;
- a detailed RED/GREEN implementation plan;
- `docs/architecture/product-shell-settings-audio.md`;
- `docs/audio-provenance.md`;
- `docs/agent-handoffs/phase-13-handoff.md`;
- a Phase 13 Windows/macOS acceptance record;
- README, CONTROLS, and KNOWN_ISSUES only where final implemented behavior requires factual updates.

The handoff records completed and unfinished work, architectural ownership, exact modified files, focused/full verification, real platform status, audio provenance, known risks, and interfaces Phase 14 must preserve.

## 12. Stop conditions

Implementation stops for user direction if:

- a design requires a second application/game loop;
- a UI screen would directly mutate World, inventory, renderer internals, or audio backend internals;
- a session cannot close without leaking threads, reservations, GPU resources, or audio resources;
- OpenAL cannot be made cross-platform under the approved dependency policy;
- a verifiable source-to-OGG conversion tool is unavailable and installing one requires new authority;
- real SaveGame behavior begins expanding into Phase 13;
- an audio/input/render fix disables owner-thread, transaction, validation, or state-restoration protections;
- a real runtime shader, GL, GLFW, OpenAL, input, or shutdown failure remains unresolved.

No Phase 13 work is staged, committed, pushed, opened as a PR, or merged without separate explicit authorization.
