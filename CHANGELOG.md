# Changelog

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
