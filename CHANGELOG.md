# Changelog

## Phase 14 Save/Load v1 and World Slots - 2026-08-12

### Added

- Versioned Save/Load v1 for finite Chunk state, player transform/mode,
  three-slot inventory, and stable-ID WorldItems.
- Deterministic section codecs, bounded ZIP archives, atomic current/backup
  replacement, closed corruption diagnostics, catalog discovery, explicit
  recovery, and root-confined delete.
- Unicode New World name/seed input, four-row World Slots paging, Load,
  Recover, Delete, Pause Save, Save & Quit, and dirty-session confirmation.
- Fresh-session typed restore that bypasses generation and reconstructs
  presentation from canonical state.

### Corrected during Windows acceptance

- Loading sessions no longer call `capturePaused()` before they reach READY;
  this fixes the `session is not ready` crash observed after creating `Test`.
- Committed mining camera pitch/yaw peaks are reduced to 20% of the prior
  values (`0.055` / `0.014` degrees) while preserving the 0.20-second envelope.

### Verified

- Fresh `clean test build`: 30/30 tasks; 2,664 total tests, 2,663 passed, one
  pre-existing tools skip, zero failures/errors.
- Forced packaged shader/resource/UI checks and installed Windows OpenAL 3.3.3
  API/native audit passed.
- Representative 81-Chunk archive: 7,819 bytes. Three-run medians were capture
  119.667 ms, encode 44.502 ms, write 56.770 ms, read/decode 43.335 ms, and
  restore-to-ready 422.213 ms.
- Human Windows development runtime passed create/delete, player-position and
  item-state save/load, reduced mining shake, and clean exit.
- Human Windows installDist passed create/load, Save & Quit, relaunch,
  restored-state verification, and normal exit. Exact duration and raw runtime
  logs were not supplied.
- The requested Apple Silicon macOS Gate 14E automated/native, development,
  and installDist test is HUMAN-REPORTED PASS. Exact environment metadata,
  automated totals, raw logs, durations, and performance measurements were not
  supplied and are not claimed.
- Phase 14 cross-platform acceptance is complete.

### Deferred

- No timed autosave, background save thread, cloud sync, migration UI,
  infinite-world streaming, detail-block data, or Loading percentage.

## Phase 13 product shell, settings, and audio - 2026-08-10

### Added

- Main Menu, Pause, Settings, Controls, modal routing, input blocking, and a
  fresh `GameSession` lifecycle under one owner-thread `ProductLoop`.
- Versioned cross-platform settings persistence with transactional Apply,
  validated defaults, hot display/input/audio settings, and next-session world
  defaults.
- Owner-thread OpenAL/STB Vorbis streaming, Gaia Main Menu/exploration music,
  pause ducking, focus mute/recovery, volume buses, Silent fallback, packaged
  Gaia/Legacy runtime OGG assets, and authored-source provenance.

### Verified

- Windows fresh clean build: 2,248 tests, 2,247 passed, 1 skipped, zero
  failures/errors; 30/30 build tasks passed.
- Windows development runtime passed for 10 minutes and installDist passed for
  7 minutes with no reported anomaly.
- Apple Silicon MacBook Air / native arm64 / Java 26 complete Gate 13D
  automated/native, development, and installDist checklist is HUMAN-REPORTED
  PASS on exact implementation candidate
  `a16855c19082a09f21bd53389cd24f711bd13f0e`. Exact macOS version, test totals,
  raw logs, runtime durations, and audio-device details were not supplied.

### Deferred

- F3 numeric ghosting remains an accepted debug-only issue.
- Load World/save persistence belongs to Phase 14.
- Loading percentage progress and ordinary-exploration Legacy routing remain
  deferred.

## Milestone 1 release candidate - 2026-08-08

### Added across Milestone 1

- Deterministic version-2 finite world generation with 81 loaded Chunks,
  biome-shaped terrain, trees, rocky outcrops, and connected caves.
- OpenGL 4.1 / GLSL 410 Chunk, sky, feedback, world-item, first-person, and UI
  rendering with packaged shader/resource verification.
- Exact 1/60 player and world-item physics, static voxel collision, step and
  ground traversal, noclip, and immutable render interpolation.
- Three-slot body inventory; transactional break, placement, drop, and manual
  pickup; stable-ID physical WorldItems; bounded particles; HUD and debug HUD.
- First-person movement bob, traversal smoothing, jump/landing response,
  action impulses, and a depth-correct held-block viewmodel.

### Changed in Phase 12

- Declared VSync enabled as the release default and applied swap interval `1`
  only after the GLFW context becomes current on its owner thread.
- Defined the disabled VSync branch as explicit interval `0`; adaptive VSync
  is not used.
- Moved the existing `0.1` mouse sensitivity literal into the Engine
  configuration boundary.
- Reduced committed break camera pitch/yaw impulses by exactly 50%, from
  `0.55/0.14` degrees to `0.275/0.07` degrees. Rapid Creative breaks now
  restart that bounded envelope instead of accumulating toward the old
  `1.0`-degree clamp; held-item swing and canonical camera/raycast state are
  unchanged.
- Reconciled release documentation, controls, deterministic demo coordinates,
  packaging guidance, platform evidence, and known limitations.

### Verified

- Fresh post-correction Windows build: 1,805 tests, 1,804 passed, 1 skipped,
  zero failures or errors.
- Focused VSync, Camera, fixed-step, ownership, lifecycle, reservation,
  projection, and deterministic-world tests pass.
- Windows development launch, complete gameplay actions, VSync path, repeated
  startup/shutdown, and safe spawn `(0,25,0)` have human PASS evidence.
- Windows installDist completed a human-reported continuous 20-minute gameplay
  soak without crash, stutter, duplicate objects, or abnormal particle growth.
  A follow-up live Creative rapid-break check also confirmed the camera shake
  was greatly reduced.
- The exact RC commit `477945913cbeffbf7886b7eed0f152519a4f120b` passed
  Apple Silicon MacBook Air clean-clone automation, packaged resources/shaders,
  development runtime, Retina/resize/focus recovery, the complete gameplay
  matrix, clean shutdown, and a continuous 26-minute installDist soak under
  Java 26. The macOS version and automated test totals were not supplied.
- F3 FPS/frame-time numeric ghosting reproduced on macOS and remains an
  accepted debug-only known issue with no associated gameplay failure.

### Deferred to Milestone 2

- Main menu, save browser, persistence, settings UI, crafting, mobs, expanded
  survival economy, small-block editing, moving voxel assemblies, fluids,
  weather, PBR, dynamic shadows, networking, and audio integration.
- Canonical world-item automatic expiry; Milestone 1 defines no timeout-based
  stable-ID terminal path.
