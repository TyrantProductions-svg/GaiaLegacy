# Phase 13 - Product Shell, Settings, and Audio Handoff

## Status

**CURRENT: Gates 13A, 13B, and 13C PASS on Windows. Gate 13D automated,
packaged-resource, Windows development, and Windows installDist acceptance PASS
on the current working-tree candidate. Apple Silicon macOS remains NOT RUN /
PENDING.**

Gate 13A adds the product shell and a fresh, closeable gameplay-session
boundary. Gate 13B adds versioned persistent Settings and controlled runtime or
next-session application. Gate 13C adds the audio ownership/backend boundary,
Gaia/Legacy runtime assets, product-route music, fades/ducking, and Windows
native playback evidence. Final cross-platform acceptance remains Gate 13D.

## Completed work

- Added immutable raw UI input snapshots without consuming gameplay edges.
  Gameplay eligibility boundaries invalidate held keys/buttons until physical
  release, clear pending edges and scroll, and reset the mouse-look baseline.
- Added the closed product-route and modal model for Main Menu, Loading,
  Playing, Pause, Settings, Controls, Return-to-Main-Menu confirmation, Quit
  confirmation, and error acknowledgement.
- Added a read-only `SaveCatalog` seam with an empty adapter. Load World remains
  deliberately disabled because save discovery and persistence are not part of
  Gate 13A.
- Added immutable product UI presentation, painted/hit-region alignment,
  window-to-logical pointer mapping, modal exclusivity, enabled-action focus,
  pointer/keyboard highlight modes, and one-command-per-input-sample routing.
- Extracted gameplay construction and lifetime into a lazy `GameSessionFactory`
  and one optional `GameSession`. Each New World creates a fresh session; return
  to Main Menu and product exit close the active session.
- Kept fixed-step ownership inside the session at exactly 1/60 with the existing
  maximum eight-step catch-up. The first step receives the consumed frame input
  and later catch-up steps receive `heldOnly()`.
- Added the single outer `ProductLoop`, with callback polling before UI capture,
  lazy loading, immutable session/product presentation, world/HUD before shell
  overlay composition, one swap, and owner-thread enforcement.
- Unified Playing eligibility transitions. Pause, focus loss, resume, loading
  completion, return to menu, and startup cursor release invalidate gameplay
  input, discard session fixed remainder where applicable, cancel canonical
  interaction/feedback state through the session boundary, and set cursor state
  deterministically. A newly eligible Playing frame receives zero gameplay
  delta, so paused/loading product time cannot create fixed-step catch-up or
  canonical revision changes; continuous Playing still receives the full frame
  delta under the fixed 1/60, maximum-eight policy.
- Added explicit Loading cancellation and nonfatal failure recovery. Loading
  exposes one Cancel action and Escape route; cancellation closes and clears the
  half-built session before returning to Main Menu. A thrown load failure or
  published FAILED state closes and clears the session, opens one bounded error
  acknowledgement on Main Menu, and permits later product frames.
- Preserved cleanup-failure fatality across that recovery boundary. If a caught
  load failure already carries suppressed cleanup failure evidence, the
  follow-up close still runs but the exact original exception escapes unchanged
  before shell routing, rendering, or swap can continue.
- Closed modal legality over the complete route matrix. Main Menu admits only
  Quit and Error acknowledgement, Paused admits only Unsaved Progress, Settings
  admits only Dirty Settings, and every other pair is rejected before mutation.
- Expanded held-input release/re-press characterization over all 23 configured
  gameplay keys and both gameplay mouse buttons.
- Corrected the real GLFW coordinate contract and visible focus behavior during
  review rounds: callback coordinates are window coordinates, logical mapping
  uses the current content area and scale, pointer highlight exists only inside
  enabled painted actions, disabled Load World never highlights, and keyboard
  selection remains visible until actual pointer movement changes modality.
- Moved product/session UI-frame composition into the pure
  `ProductUiCompositor`, leaving renderer ownership and OpenGL calls outside
  product-shell code.
- Completed the Windows Gate 13A runtime path using human-supplied observation
  after in-app computer-use initialization was permission-blocked.
- Added immutable schema-v1 settings defaults, validation diagnostics, drafts,
  Apply/Discard/Cancel behavior, and a versioned JSON persistence boundary.
- Added platform settings paths for Windows, macOS/Darwin, and Linux/XDG plus
  atomic sibling-temp replacement. Corrupt settings fall back safely; save and
  load I/O failures remain typed and non-silent.
- Closed the atomic-write cleanup coherence boundary: a successful move
  transfers temporary-file ownership and performs no later delete, while a
  failed move plus failed cleanup promotes cleanup evidence to the top-level
  typed persistence exception and stops the shell frame before render/swap.
- Added hot application for VSync, FOV, mouse sensitivity, invert-Y, and the
  audio settings port. Loaded VSync reaches GLFW context-current construction;
  later VSync changes remain owner-thread presentation work and do not change
  fixed 1/60 simulation.
- Added next-session capture for Chunk radius, default game mode, and debug-HUD
  default. Each New World snapshots the current applied settings; open drafts
  cannot affect session construction.
- Added `ProductSettingsLifecycle` as the sole product settings owner. It loads
  before runtime construction, exposes bounded diagnostics, persists only the
  applied snapshot, never writes per frame, and closes idempotently.
- Completed two human-confirmed Windows Settings runs: edit/Apply and immediate
  behavior in the first process, followed by relaunch persistence and new-world
  application in the second process.
- Added the engine-owned audio boundary: validated bus settings, opaque
  backend-owned handles, owner-thread `AudioDevice`, bounded Silent fallback,
  caller-owned compressed-buffer release, and idempotent reverse cleanup.
- Added checked OpenAL 3.3.3 and STB Vorbis streaming with three bounded
  buffers, exact source/buffer/device/context ownership, AL/ALC error checking,
  broken-state containment after unproven cleanup, and native-linkage fallback.
- Added `MusicManager` with one duplicate-free Gaia voice across Main Menu,
  gameplay, pause, secondary screens, load failure, and later sessions. Startup,
  route, and focus envelopes compose independently; pause ducks, focus loss can
  mute, and presentation failures cannot crash or retry-spam the simulation.
- Connected schema-v1 Master/Music/SFX values and mute-when-unfocused through
  the existing transactional Settings port. A fresh runtime baseline forces the
  default Music value `0.65` to apply before the first product frame.
- Preserved verified Gaia and Legacy MP3 sources outside runtime resources,
  generated and hashed Ogg/Vorbis derivatives, recorded author credit and
  GaiaLegacy-specific authorization, and rejected MP3s from packaged output.
- Completed human-confirmed Windows Gate 13C audio acceptance. No runtime
  duration or audio-device model was supplied, so neither is inferred.
- Closed the approved disabled-load presentation contract: Main Menu now shows
  `Load World - Available in Phase 14` inside the disabled action, backed by a
  behavioral glyph-output regression; save discovery remains unchanged.

## Unfinished work

- **Gate 13D - Windows complete / macOS pending:** fresh focused, module,
  packaged-resource, installDist-audit, and clean-build automation pass. Human
  Windows development (10 minutes) and installDist (7 minutes) paths pass with
  no reported anomaly. Apple Silicon macOS menu/settings/native-audio/Retina/
  focus/shutdown acceptance has not run and remains `NOT RUN / PENDING`.
- Save discovery and persistence are deferred; `EmptySaveCatalog` keeps Load
  World disabled.

## Core architecture decisions

- `ProductLoop` is the only outer application loop. It captures its owner
  thread, polls GLFW before capture, routes at most one shell command per raw UI
  sample, advances at most one optional session, renders immutable presentation,
  and swaps once. A non-eligible-to-Playing transition advances with zero delta;
  ordinary eligible Playing frames retain their complete product-frame delta.
- `ScreenRouter` owns route/modal state; `ProductShellController` translates
  commands into lifecycle intents. Neither receives World, inventory,
  interaction, feedback, voxel, or other gameplay mutation services.
- `GameSession` is the sole gameplay/world lifetime boundary. Creation is lazy,
  close is reverse/idempotent, and a second New World receives fresh owned
  state rather than reusing the closed session. Loading cancel/failure closes
  and clears this ownership before another session presentation can be emitted.
  An ordinary load failure remains recoverable, but a load failure carrying
  suppressed cleanup failure evidence remains fatal because safe ownership is
  not proven.
- `InputManager.captureUiInput()` copies callback-owned state without consuming
  gameplay edges. Shell-only and non-Playing frames retire stale edges, while
  `invalidateGameplayInput()` suppresses held gameplay actions through release.
- GLFW cursor callbacks publish window coordinates. `UiLayoutContext` performs
  the single window-to-logical mapping using current content offsets/scales;
  paint bounds are converted separately to framebuffer bounds.
- Product presentation is immutable and domain-read-only. Modal hit regions
  replace underlying regions, disabled regions cannot focus or activate, and
  `ProductUiCompositor` combines immutable session and product UI frames without
  owning the renderer bridge.
- OpenGL, GLFW polling, renderer lifecycle, and GPU-resource ownership remain on
  the main context-owning thread. Engine code remains independent of `game`.
- `ProductSettingsLifecycle` owns path resolution, validated startup loading,
  the applied `SettingsController`, per-session configuration snapshots, and
  final applied-only persistence. The Settings screen never receives renderer,
  Window, World, inventory, or transaction internals.
- Initial VSync is an Engine/Window construction input and is applied only after
  the GLFW context becomes current. Runtime VSync, Renderer FOV, Camera look,
  and audio-bus updates pass through `SettingsApplier` with reverse rollback.
  Chunk radius, mode, and debug default are intentionally next-session only.
- `AudioDevice` owns exactly one backend on the main thread. Native
  initialization `RuntimeException` or `LinkageError` selects a diagnostic
  Silent backend; unrelated fatal `Error` values remain visible. OpenAL creator
  boundaries locally release handles when a post-call error prevents ownership
  transfer, and unproven teardown makes the backend terminal rather than
  admitting replacement voices.
- `ProductLoop` owns one injected `MusicManager`, maps route and focus once per
  product frame, updates music before session work, and closes manager before
  device before Engine. Public constructors cannot silently omit this audio
  lifecycle. Audio callbacks never mutate gameplay state or fixed-step timing.

## Automated verification evidence

Fresh Gate 13D automation on 2026-08-10:

| Command | Result |
| --- | --- |
| Gate 13A focused input/time and shell/session commands | PASS; `BUILD SUCCESSFUL` in 7s and 8s. |
| Gate 13B focused engine settings and game settings/shell commands | PASS; `BUILD SUCCESSFUL` in 7s and 8s. |
| Gate 13C focused engine and game audio commands | PASS; `BUILD SUCCESSFUL` in 7s and 38s. |
| `.\gradlew.bat :engine:test --console=plain --no-daemon` | PASS; `BUILD SUCCESSFUL in 12s`; 1,102/1,102. |
| `.\gradlew.bat :game:test --console=plain --no-daemon` | PASS; `BUILD SUCCESSFUL in 1m 7s`; 1,119/1,119. |
| `.\gradlew.bat :tools:test --console=plain --no-daemon` | PASS; `BUILD SUCCESSFUL in 8s`; 26 passed, 1 skipped. |
| Forced engine/game packaged-resource and installDist checks | PASS; installed audit reported exactly `lwjgl-openal-3.3.3.jar` plus `lwjgl-openal-3.3.3-natives-windows.jar`. |
| `.\gradlew.bat clean test build --console=plain --no-daemon` | PASS; `BUILD SUCCESSFUL in 1m 36s`; 30/30 tasks executed. Engine 1,102/1,102, Game 1,119/1,119, Tools 26 passed plus 1 skipped: 2,248 total, 2,247 passed, 1 skipped, 0 failures/errors. |
| `git diff --check` | PASS before the automated matrix; final audit will rerun it after interactive evidence is recorded. |

Fresh Gate 13C evidence on 2026-08-10:

| Command | Result |
| --- | --- |
| `.\gradlew.bat :engine:test --tests 'com.overlord.audio.*' --console=plain --no-daemon` | PASS; controller rerun `BUILD SUCCESSFUL in 7s`. |
| `.\gradlew.bat :game:test --tests 'com.gaia.audio.*' --console=plain --no-daemon` | PASS; controller rerun `BUILD SUCCESSFUL in 37s`. |
| `.\gradlew.bat :engine:test --tests com.overlord.audio.AudioDeviceTest --tests com.overlord.audio.openal.LwjglOpenAlApiErrorContractTest --tests com.overlord.core.lifecycle.ShutdownCoordinatorTest --console=plain --no-daemon` | Review-closure focused PASS; `BUILD SUCCESSFUL in 7s`. |
| `.\gradlew.bat :game:test --tests com.gaia.settings.ProductSettingsLifecycleTest --tests com.gaia.GameBootstrapTest --tests com.gaia.shell.ProductLoopApiSurfaceTest --console=plain --no-daemon` | Review-closure focused PASS; `BUILD SUCCESSFUL in 6s`. |
| `.\gradlew.bat clean test build --console=plain --no-daemon` | Final Gate 13C tree PASS; `BUILD SUCCESSFUL in 1m 29s`; 30/30 actionable tasks executed. Engine 1,102/1,102, Game 1,118/1,118, Tools 26 passed plus 1 skipped: 2,247 total, 2,246 passed, 1 skipped, 0 failures/errors. |
| Packaged/install checks executed by the clean build | PASS; both OGG resources present, runtime MP3s absent, and installed OpenAL audit reported exactly `lwjgl-openal-3.3.3.jar` plus `lwjgl-openal-3.3.3-natives-windows.jar`. |
| `git diff --check` | PASS; exit `0`, with only `core.autocrlf` line-ending forecasts. |

The Gate 13C, Gate 13B, and Gate 13A tables below are historical checkpoint
evidence. The Gate 13D clean build above is the current aggregate.

Fresh Gate 13B evidence on 2026-08-09:

| Command | Result |
| --- | --- |
| `.\gradlew.bat :game:test --tests com.gaia.settings.ProductSettingsLifecycleTest --tests com.gaia.shell.ProductLoopClosePolicyTest --console=plain --no-daemon` | PASS; controller rerun `BUILD SUCCESSFUL in 7s`. |
| `.\gradlew.bat :engine:test --tests com.overlord.core.WindowVsyncContractTest --tests com.overlord.renderer.RendererProjectionSettingsTest --tests com.overlord.renderer.CameraLookSettingsTest --console=plain --no-daemon` | PASS; controller rerun `BUILD SUCCESSFUL in 6s`. |
| `.\gradlew.bat :game:test --tests 'com.gaia.settings.*' --tests 'com.gaia.shell.*Settings*' --console=plain --no-daemon` | PASS; controller rerun `BUILD SUCCESSFUL in 7s`. |
| `.\gradlew.bat :engine:test --tests com.overlord.core.time.FixedStepClockTest --console=plain --no-daemon` | PASS; `BUILD SUCCESSFUL in 6s`. |
| `.\gradlew.bat :game:test --tests 'com.gaia.physics.*' --tests 'com.gaia.worlditem.*' --console=plain --no-daemon` | PASS; `BUILD SUCCESSFUL in 7s`. |
| `.\gradlew.bat :game:test --tests com.gaia.settings.DefaultSettingsPathProviderTest --tests com.gaia.settings.JsonSettingsStoreTest --console=plain --no-daemon` | Darwin RED reproduced, then final 27/27 GREEN; `BUILD SUCCESSFUL in 7s`. |
| `.\gradlew.bat :game:test --tests com.gaia.settings.AtomicFileWriterTest --tests com.gaia.settings.SettingsPersistenceFailureIntegrationTest --console=plain --no-daemon` | Cleanup-coherence RED reproduced with exactly 2/9 failures, then final 9/9 GREEN; controller `BUILD SUCCESSFUL in 7s`. |
| `.\gradlew.bat clean test build --console=plain --no-daemon` | Historical Gate 13B tree PASS; 29/29 tasks executed; `BUILD SUCCESSFUL in 1m 1s`. Engine 995/995, Game 1,064/1,064, Tools 26 passed plus 1 skipped: 2,086 total, 2,085 passed, 1 skipped, 0 failures/errors. |
| `.\gradlew.bat :engine:verifyPackagedShaderResources :game:verifyInstalledShaderResources :game:verifyPackagedResources :tools:verifyGeneratedUiAssets --rerun-tasks --console=plain --no-daemon` | PASS; 14/14 tasks executed; `BUILD SUCCESSFUL in 17s`. |

The Gate 13A table below is historical evidence for the accepted shell/session
checkpoint; the Gate 13C clean build above is the current aggregate.

Fresh final-fix evidence on 2026-08-09:

| Command | Result |
| --- | --- |
| `.\gradlew.bat :game:test --tests com.gaia.shell.ProductLoopTest --console=plain --no-daemon` | Exceptional cleanup-failure GREEN: 45/45 passed; `BUILD SUCCESSFUL in 8s`. |
| `.\gradlew.bat :game:test --tests com.gaia.shell.ProductLoopTest --tests com.gaia.shell.ProductShellControllerTest --tests com.gaia.shell.ScreenRouterTest --tests com.gaia.shell.ui.ProductScreenPresenterTest --tests com.gaia.shell.ui.ProductScreenInputControllerTest --console=plain --no-daemon` | Focused GREEN: 104/104 passed; `BUILD SUCCESSFUL in 7s`. |
| `.\gradlew.bat :engine:test --tests com.overlord.core.input.InputManagerTest --console=plain --no-daemon` | Characterization GREEN: 45/45 passed; `BUILD SUCCESSFUL in 8s`. |
| `.\gradlew.bat :engine:test --tests 'com.overlord.core.input.*' --tests 'com.overlord.core.time.*' --console=plain --no-daemon` | Gate 13A engine input/time regression PASS; `BUILD SUCCESSFUL in 11s`. |
| `.\gradlew.bat :game:test --tests 'com.gaia.shell.*' --tests 'com.gaia.session.*' --tests com.gaia.UiGameLoopIntegrationTest --tests com.gaia.InteractionFeedbackGameLoopTest --tests com.gaia.GameBootstrapTest --tests com.gaia.GameBootstrapStructureTest --tests com.gaia.GameLoopStructureTest --console=plain --no-daemon` | Gate 13A shell/session/integration/structure regression PASS: 186/186; `BUILD SUCCESSFUL in 8s`. |
| `.\gradlew.bat clean test build --console=plain --no-daemon` | Historical Gate 13A tree PASS; `BUILD SUCCESSFUL in 58s`; 29/29 actionable tasks executed, including packaged resource/installDist checks. Engine 988/988, game 959/959, tools 26 passed plus 1 skipped: 1,974 total, 1,973 passed, 1 skipped, 0 failures/errors. |
| `.\gradlew.bat :engine:test :game:test :tools:test --rerun-tasks --console=plain --no-daemon` | Earlier pre-exception aggregate PASS; `BUILD SUCCESSFUL in 1m 5s`; 15/15 tasks executed. Superseded for current totals by the fresh clean build above. |
| `git diff --check` | Final result recorded below. |

These results verify the historical Gate 13A checkpoint. They do not replace
the current Gate 13D aggregate or current platform acceptance record.

## Windows interactive evidence

Final Gate 13D integrated Windows evidence:

- Development runtime: PASS; human-tested for 10 minutes; no reported anomaly;
  clean exit and Gradle exit code `0`.
- installDist runtime: PASS; human-tested for 7 minutes; no reported anomaly;
  launcher exit code `0`.
- Covered Main Menu mouse/keyboard input and disabled Load World label,
  Settings/Controls/modal paths, Loading/Playing/pause/resume/second-session,
  gameplay interactions, function-key eligibility, hot settings, native Gaia
  audio behavior, resize/DPI/focus recovery, persisted relaunch, and clean
  shutdown.
- The tester requested a Loading progress bar and then explicitly accepted its
  deferral. Current loading exposes no truthful percentage contract; Gate 13D
  did not fabricate one or add new product behavior.

Exact command:

```powershell
.\gradlew.bat :game:run --console=plain --no-daemon
```

Historical Gate 13A platform: Windows. Evidence source: direct human observation
supplied after computer-use initialization was permission-blocked. No Gate 13A
runtime duration was supplied or inferred.

- Launch to Main Menu with normal released cursor: **PASS**.
- Pointer highlight only within enabled rectangles and immediate clear outside:
  **PASS**.
- Disabled Load World never highlights or activates: **PASS**.
- Tab and up/down arrow navigation/focus visibility plus Enter activation:
  **PASS** as human-reported.
- Mouse and keyboard open Controls and return: **PASS**.
- New World enters Loading and then Playing: **PASS**.
- Escape pauses and releases cursor: **PASS**.
- F1 resumes and pauses consistently: **PASS**.
- Alt+Tab from Playing returns in Paused state: **PASS**.
- Held Q, right mouse, and jump do not replay after resume: **PASS**.
- Return to Main Menu shows the unsaved warning and closes the active session:
  **PASS**.
- A second New World starts a fresh session: **PASS**.
- Quit confirmation exits without a crash: **PASS**.

Gate 13B persistence acceptance used the same command twice. Evidence source is
direct human observation plus controller-read process output and provider-derived
file inspection.

- First run: **PASS**, clean process exit; Gradle runtime duration `7m 34s`.
- Settings edit/Apply across VSync, FOV, sensitivity, invert-Y, audio values,
  mute-unfocused, Chunk radius, default mode, and debug default: **PASS**.
- Immediate hot settings and next-New-World-only labels/behavior: **PASS**.
- Resize/DPI pointer alignment in Settings: **PASS**.
- The provider-reported location matched
  `%APPDATA%\GaiaLegacy\settings.json`; the personal home prefix is intentionally
  omitted from repository documentation.
- JSON inspection: schema 1, finite values, no sibling temp file: **PASS**.
- Second run/relaunch: **PASS**, clean process exit; Gradle runtime duration
  `12m 57s`.
- Persisted values displayed after relaunch and applied values used by a new
  world: **PASS**.

Gate 13C used the same development-run command after focused audio GREEN and a
real OpenAL launch. Evidence source is direct human listening/interaction. The
tester reported the requested audio checklist as **PASS**; no duration, output
device model, or per-step timing was supplied, so those values remain
unrecorded.

- Gaia playback from Main Menu and continued playback into gameplay: **PASS**.
- Pause duck and Resume recovery: **PASS**.
- Master/Music Settings Apply behavior: **PASS**.
- Focus-loss mute and focus-return recovery: **PASS**.
- Return to Main Menu without duplicate/restarted playback: **PASS**.
- Native OpenAL playback path produced audible output rather than Silent
  fallback: **PASS**, established by the human audio result.
- Windows Gate 13C audio acceptance: **PASS**.

## Known issues and risks

- The Phase 12 F3 rapidly changing FPS/frame-time numeric ghosting remains an
  accepted debug-HUD-only baseline issue. Gate 13A did not claim to fix or
  freshly reproduce it; no gameplay, simulation, or resource-growth failure is
  attributed to it.
- Load World remains disabled because real save discovery/persistence belongs
  to Phase 14. Gate 13C does not change that intentional placeholder.
- `Legacy` is registered and packaged as the second authored theme but is not
  forced into ordinary exploration; future POI/credits/anomaly routing remains
  deferred.
- Gate 13D Windows acceptance is complete. Apple Silicon macOS native audio,
  Retina, focus, installDist, and shutdown acceptance remains `NOT RUN /
  PENDING`; Phase 12 macOS evidence does not substitute for this candidate.
- Loading shows status text and Cancel but no progress bar. The tester accepted
  deferral because the current loading boundary has no truthful percentage
  contract; do not fabricate progress in presentation.
- Runtime UI behavior is human-supplied because computer-use initialization was
  permission-blocked. The controller independently recorded both Gradle process
  results/durations and inspected the provider-reported settings file.
- The Gate 13A final-fix rounds did not launch the game. Their
  transition timing, Loading cancel/failure, cleanup-failure fatality, and
  modal-matrix corrections have fresh automated evidence but no new human
  runtime claim.
- The working tree intentionally contains the approved untracked
  `dist/GaiaLegacy-v0.2.0-alpha.1-windows-x64.zip`; Task 6 preserved it.

## Modified files

Build and packaging:

- `engine/build.gradle`
- `game/build.gradle`

Production:

- `engine/src/main/java/com/overlord/audio/AudioAssetSource.java`
- `engine/src/main/java/com/overlord/audio/AudioBackend.java`
- `engine/src/main/java/com/overlord/audio/AudioBackendFactory.java`
- `engine/src/main/java/com/overlord/audio/AudioBusSettings.java`
- `engine/src/main/java/com/overlord/audio/AudioDevice.java`
- `engine/src/main/java/com/overlord/audio/AudioDiagnostic.java`
- `engine/src/main/java/com/overlord/audio/MusicHandle.java`
- `engine/src/main/java/com/overlord/audio/SilentAudioBackend.java`
- `engine/src/main/java/com/overlord/audio/SoundCue.java`
- `engine/src/main/java/com/overlord/audio/SoundEvent.java`
- `engine/src/main/java/com/overlord/audio/openal/LwjglOpenAlApi.java`
- `engine/src/main/java/com/overlord/audio/openal/OpenAlApi.java`
- `engine/src/main/java/com/overlord/audio/openal/OpenAlAudioBackend.java`
- `engine/src/main/java/com/overlord/audio/vorbis/StbVorbisDecoder.java`
- `engine/src/main/java/com/overlord/audio/vorbis/VorbisDecoder.java`
- `engine/src/main/java/com/overlord/core/Engine.java`
- `engine/src/main/java/com/overlord/core/Window.java`
- `engine/src/main/java/com/overlord/core/input/InputManager.java`
- `engine/src/main/java/com/overlord/core/input/UiInputSnapshot.java`
- `engine/src/main/java/com/overlord/core/time/FixedStepClock.java`
- `engine/src/main/java/com/overlord/renderer/ui/UiLayoutContext.java`
- `engine/src/main/java/com/overlord/renderer/Camera.java`
- `engine/src/main/java/com/overlord/renderer/Renderer.java`
- `game/src/main/java/com/gaia/GameBootstrap.java`
- `game/src/main/java/com/gaia/GameContext.java`
- `game/src/main/java/com/gaia/GameLoop.java`
- `game/src/main/java/com/gaia/audio/GaiaAudioSettingsAdapter.java`
- `game/src/main/java/com/gaia/audio/GaiaMusicCatalog.java`
- `game/src/main/java/com/gaia/audio/MusicManager.java`
- `game/src/main/java/com/gaia/audio/MusicManagerSnapshot.java`
- `game/src/main/java/com/gaia/audio/MusicRoute.java`
- `game/src/main/java/com/gaia/session/GameSession.java`
- `game/src/main/java/com/gaia/session/GameSessionConfig.java`
- `game/src/main/java/com/gaia/session/GameSessionFactory.java`
- `game/src/main/java/com/gaia/session/GameSessionFrame.java`
- `game/src/main/java/com/gaia/session/GameSessionState.java`
- `game/src/main/java/com/gaia/settings/AtomicFileWriter.java`
- `game/src/main/java/com/gaia/settings/AudioSettingsPort.java`
- `game/src/main/java/com/gaia/settings/DefaultSettingsPathProvider.java`
- `game/src/main/java/com/gaia/settings/JsonSettingsStore.java`
- `game/src/main/java/com/gaia/settings/ProductSettingsLifecycle.java`
- `game/src/main/java/com/gaia/settings/SettingsApplier.java`
- `game/src/main/java/com/gaia/settings/SettingsController.java`
- `game/src/main/java/com/gaia/settings/SettingsDefaults.java`
- `game/src/main/java/com/gaia/settings/SettingsDiagnostic.java`
- `game/src/main/java/com/gaia/settings/SettingsDocument.java`
- `game/src/main/java/com/gaia/settings/SettingsDraftSnapshot.java`
- `game/src/main/java/com/gaia/settings/SettingsLoadResult.java`
- `game/src/main/java/com/gaia/settings/SettingsPathProvider.java`
- `game/src/main/java/com/gaia/settings/SettingsPersistenceException.java`
- `game/src/main/java/com/gaia/settings/SettingsSnapshot.java`
- `game/src/main/java/com/gaia/settings/SettingsStore.java`
- `game/src/main/java/com/gaia/settings/SettingsValidator.java`
- `game/src/main/java/com/gaia/shell/ModalId.java`
- `game/src/main/java/com/gaia/shell/ProductLoop.java`
- `game/src/main/java/com/gaia/shell/ProductShellController.java`
- `game/src/main/java/com/gaia/shell/ProductShellSnapshot.java`
- `game/src/main/java/com/gaia/shell/ScreenCommand.java`
- `game/src/main/java/com/gaia/shell/ScreenId.java`
- `game/src/main/java/com/gaia/shell/ScreenReturnTarget.java`
- `game/src/main/java/com/gaia/shell/ScreenRouter.java`
- `game/src/main/java/com/gaia/shell/save/EmptySaveCatalog.java`
- `game/src/main/java/com/gaia/shell/save/SaveCatalog.java`
- `game/src/main/java/com/gaia/shell/save/SaveSummary.java`
- `game/src/main/java/com/gaia/shell/ui/ProductScreenInputController.java`
- `game/src/main/java/com/gaia/shell/ui/ProductScreenPresenter.java`
- `game/src/main/java/com/gaia/shell/ui/ProductUiCompositor.java`
- `game/src/main/java/com/gaia/shell/ui/ProductUiLayout.java`
- `game/src/main/java/com/gaia/shell/ui/UiActionId.java`
- `game/src/main/java/com/gaia/shell/ui/UiHitRegion.java`

Tests and fixtures:

- `engine/src/test/java/com/overlord/audio/AudioBusSettingsTest.java`
- `engine/src/test/java/com/overlord/audio/AudioDeviceTest.java`
- `engine/src/test/java/com/overlord/audio/OpenAlDependencyContractTest.java`
- `engine/src/test/java/com/overlord/audio/SilentAudioBackendTest.java`
- `engine/src/test/java/com/overlord/audio/openal/LwjglOpenAlApiErrorContractTest.java`
- `engine/src/test/java/com/overlord/audio/openal/MusicHandleDomainAccessTest.java`
- `engine/src/test/java/com/overlord/audio/openal/OpenAlAudioBackendOwnerThreadTest.java`
- `engine/src/test/java/com/overlord/audio/openal/OpenAlAudioBackendTest.java`
- `engine/src/test/java/com/overlord/audio/vorbis/StbVorbisDecoderTest.java`
- `engine/src/test/java/com/overlord/core/EngineLifecycleTest.java`
- `engine/src/test/java/com/overlord/core/WindowVsyncContractTest.java`
- `engine/src/test/java/com/overlord/core/input/InputManagerTest.java`
- `engine/src/test/java/com/overlord/core/input/UiInputSnapshotTest.java`
- `engine/src/test/java/com/overlord/core/time/FixedStepClockTest.java`
- `engine/src/test/java/com/overlord/renderer/ui/UiLayoutContextTest.java`
- `engine/src/test/java/com/overlord/renderer/CameraLookSettingsTest.java`
- `engine/src/test/java/com/overlord/renderer/RendererProjectionSettingsTest.java`
- `engine/src/test/java/com/overlord/renderer/RendererStructureTest.java`
- `engine/src/testFixtures/java/com/overlord/core/input/InputManagerTestDriver.java`
- `game/src/test/java/com/gaia/GameBootstrapStructureTest.java`
- `game/src/test/java/com/gaia/GameBootstrapTest.java`
- `game/src/test/java/com/gaia/GameLoopStructureTest.java`
- `game/src/test/java/com/gaia/PhysicsCompositionStructureTest.java`
- `game/src/test/java/com/gaia/audio/GaiaAudioSettingsAdapterTest.java`
- `game/src/test/java/com/gaia/audio/GaiaMusicAssetDecodeTest.java`
- `game/src/test/java/com/gaia/audio/GaiaMusicCatalogTest.java`
- `game/src/test/java/com/gaia/audio/InstalledAudioRuntimeAuditTest.java`
- `game/src/test/java/com/gaia/audio/MusicManagerTest.java`
- `game/src/test/java/com/gaia/audio/PackagedAudioResourceTest.java`
- `game/src/test/java/com/gaia/audio/ProductMusicLifecycleIntegrationTest.java`
- `game/src/test/java/com/gaia/session/GameSessionApiSurfaceTest.java`
- `game/src/test/java/com/gaia/session/GameSessionEligibilityBoundaryTest.java`
- `game/src/test/java/com/gaia/session/GameSessionFactoryTest.java`
- `game/src/test/java/com/gaia/session/GameSessionLifecycleTest.java`
- `game/src/test/java/com/gaia/settings/AtomicFileWriterTest.java`
- `game/src/test/java/com/gaia/settings/DefaultSettingsPathProviderTest.java`
- `game/src/test/java/com/gaia/settings/JsonSettingsStoreTest.java`
- `game/src/test/java/com/gaia/settings/ProductSettingsLifecycleTest.java`
- `game/src/test/java/com/gaia/settings/SettingsApplierTest.java`
- `game/src/test/java/com/gaia/settings/SettingsControllerTest.java`
- `game/src/test/java/com/gaia/settings/SettingsSnapshotTest.java`
- `game/src/test/java/com/gaia/settings/SettingsPersistenceFailureIntegrationTest.java`
- `game/src/test/java/com/gaia/settings/SettingsValidatorTest.java`
- `game/src/test/java/com/gaia/shell/ProductLoopClosePolicyTest.java`
- `game/src/test/java/com/gaia/shell/ProductLoopApiSurfaceTest.java`
- `game/src/test/java/com/gaia/shell/SettingsShellIntegrationTest.java`
- `game/src/test/java/com/gaia/shell/ProductLoopTest.java`
- `game/src/test/java/com/gaia/shell/ProductShellControllerTest.java`
- `game/src/test/java/com/gaia/shell/ScreenRouterTest.java`
- `game/src/test/java/com/gaia/shell/save/EmptySaveCatalogTest.java`
- `game/src/test/java/com/gaia/shell/ui/ProductScreenInputControllerTest.java`
- `game/src/test/java/com/gaia/shell/ui/ProductScreenPresenterTest.java`
- `game/src/test/java/com/gaia/shell/ui/ProductUiCompositorTest.java`
- `game/src/test/java/com/gaia/shell/ui/ProductUiDpiMatrixTest.java`
- `game/src/test/java/com/gaia/shell/ui/SettingsScreenPresenterTest.java`

Documentation:

- `CONTROLS.md`
- `docs/agent-handoffs/phase-13-handoff.md`
- `docs/audio-provenance.md`
- `docs/superpowers/plans/2026-08-08-phase-13a-product-shell-session-lifecycle.md`
- `docs/superpowers/plans/2026-08-08-phase-13b-settings-store-and-ui.md`
- `docs/superpowers/plans/2026-08-08-phase-13c-audio-foundation.md`
- `docs/superpowers/plans/2026-08-08-phase-13d-integration-acceptance.md`
- `docs/superpowers/specs/2026-08-08-phase-13-product-shell-settings-audio-design.md`

Audio assets:

- `game/src/main/source-assets/audio/Gaia.mp3`
- `game/src/main/source-assets/audio/Legacy.mp3`
- `game/src/main/resources/assets/gaia/audio/music/gaia.ogg`
- `game/src/main/resources/assets/gaia/audio/music/legacy.ogg`

Preserved approved artifact:

- `dist/GaiaLegacy-v0.2.0-alpha.1-windows-x64.zip`

## Final working-tree checks

Fresh checks after Gate 13D automation, documentation, Windows development and
installDist acceptance, and macOS pending-status recording:

- `git diff --check`: exit `0`, no whitespace errors. Git printed only
  `core.autocrlf` forecasts for tracked LF working copies.
- `git status --short --untracked-files=all`: 27 tracked modifications and 119
  untracked files. Every entry is an intended Gate 13A/13B/13C production,
  test, asset, provenance, or documentation path; an approved Phase 13
  design/plan path; or the preserved release ZIP. No generated build output,
  log, screenshot, save, crash dump, IDE file, cache, user settings file, or
  unrelated artifact appeared.
- `git diff --stat`: 27 tracked files, 1,382 insertions and 1,891 deletions.
  Untracked files, including the new audio implementation and binary assets,
  are not represented by this command.

Condensed tracked `git diff --stat` inventory:

```text
 CONTROLS.md                                        |  60 +-
 KNOWN_ISSUES.md                                    |  37 +-
 README.md                                          |  54 +-
 engine/build.gradle                                |   2 +
 .../core/lifecycle/ShutdownCoordinator.java        |   2 +-
 game/build.gradle                                  |  99 ++-
 game/src/main/java/com/gaia/GameBootstrap.java     | 820 ++++++---------------
 game/src/test/java/com/gaia/GameBootstrapTest.java | 600 ++++-----------
 ... 18 other tracked paths ...
 27 files changed, 1382 insertions(+), 1891 deletions(-)
```

## Interfaces the next phase must not break

- The one outer owner-thread `ProductLoop` and one session-owned fixed-step
  authority; do not add a second application or fixed-step loop.
- Lazy/fresh `GameSession` creation, sole optional session ownership, and
  reverse/idempotent close on return to menu or product exit.
- Exact 1/60 fixed simulation, maximum eight-step catch-up during continuous
  Playing, zero delta on the first newly eligible Playing frame, consume-once
  first fixed input, and `heldOnly()` for later catch-up steps.
- Raw UI capture that does not consume gameplay edges, one command per UI sample,
  and held-input suppression until physical release across eligibility changes.
- Focus loss always hard-pauses Playing; focus recovery never implicitly resumes
  or captures the cursor.
- Window-coordinate callback input, single window-to-logical mapping, separate
  logical-to-framebuffer painting, and exact enabled painted/hit bounds.
- Modal-first routing, disabled Load behavior, immutable product/session
  presentation, and product UI's lack of gameplay-domain mutation services.
- Main-thread GLFW/OpenGL/GPU ownership, renderer-boundary separation, engine
  independence from `game`, and the pure `ProductUiCompositor` architecture.
- The schema-v1 settings contract, platform path, atomic persistence,
  `ProductSettingsLifecycle`, Apply rollback semantics, and hot-vs-next-session
  split.
- The owner-thread `AudioDevice`/backend boundary, opaque handle domains,
  checked AL/ALC operations, explicit compressed-buffer release, bounded
  three-buffer streaming, terminal broken-state containment, and Silent
  fallback for unavailable native audio.
- One product-owned `MusicManager`, duplicate-free Gaia playback, independent
  startup/route/focus envelopes, Settings-driven bus math, manager-before-device
  shutdown, and the rule that no audio callback mutates gameplay authority.
- Exact authored-source provenance, verified Gaia/Legacy OGG hashes, runtime
  MP3 exclusion, and the GaiaLegacy-specific authorization wording in
  `docs/audio-provenance.md`.

## Suggested integration text

Suggested Phase 13 commit message, if later authorized:

```text
feat(shell): add menus settings and audio foundation
```

Suggested pull-request title:

```text
feat(shell): establish GaiaLegacy product shell and audio foundation
```

Suggested pull-request description:

```text
Adds the Gate 13A product shell and session lifecycle, Gate 13B schema-v1
settings and atomic platform persistence, and Gate 13C owner-thread OpenAL/STB
audio with Gaia/Legacy assets, fades, pause ducking, focus mute, transactional
audio settings, packaged-resource audits, and provenance.

The current clean build is green at 2,248 tests: 2,247 passed, one skipped, zero
failures/errors. Windows Gate 13D development and installDist human acceptance
passed at 10 and 7 minutes respectively, with no reported anomaly. Apple
Silicon macOS remains NOT RUN / PENDING. F3 numeric ghosting remains the
accepted debug-only baseline known issue; Loading progress remains deferred.
```
