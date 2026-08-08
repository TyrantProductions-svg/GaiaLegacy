# Known Issues

## Release-candidate limitations

- The demo world is a deterministic finite 81-Chunk load. It does not stream
  an unbounded world and has no user-facing render-distance control.
- There is no save/load persistence, main menu, save browser, or settings UI.
- Survival is a systems vertical slice rather than a complete economy: there
  is no crafting, mob loop, or broader progression.
- Canonical WorldItems do not expire automatically. Sleeping and unloaded
  duration do not delete items, and shutdown removes runtime projections only.
- Pickup is deliberately manual: Shift+right-click in Survival. Walking near a
  WorldItem never attracts or collects it.
- Rotation and body-body collision are not implemented for physical
  WorldItems.
- Rapidly changing FPS and frame-time digits in the F3 debug HUD can leave a
  visible numeric ghost/trail on Windows. This is a debug-overlay readability
  defect; no gameplay, simulation, or resource-growth anomaly was observed.

## Platform acceptance

- Windows development runtime is human-tested and passing for the current
  Phase 12 candidate.
- Windows installDist completed a human-reported continuous 20-minute gameplay
  soak without crash, stutter, duplicate objects, or abnormal particle growth.
- Apple Silicon macOS automated build, development runtime, Retina/resize,
  Command+Tab, function-key, installDist, and clean-exit acceptance are
  `NOT RUN / PENDING`. Windows evidence is not macOS evidence.

## Performance observations

- The Phase 12 Windows installDist sample recorded 139 non-startup render
  samples at 94.52-110.84 FPS (average 100.96) and 9.02-10.58 ms frame time
  (average 9.91). Visible Chunks were 3-79, draw calls 8-88, triangles
  13,565-231,717, and mesh queue depth 0-1.
- The same approximately 140-second GC trace recorded 17 G1 young collections,
  with 0.916-4.080 ms pauses (average 2.618 ms). This is a short diagnostic
  sample, not the required 20-30 minute soak or evidence for pooling.
- Exact fixed-step, physics-body, WorldItem, and particle counts were not
  retained in the console trace. During the full human soak, particle count
  showed no abnormal growth and no duplicate object was observed.
- Pooling is intentionally absent until allocation attribution demonstrates a
  concrete benefit without weakening ownership or stable-ID contracts.

## Deferred to Milestone 2

- Menu and settings architecture, persistence, crafting, mobs, expanded
  content, small-block editing, moving assemblies, fluids, weather, PBR,
  dynamic shadows, networking, and cloud saves.
- `Gaia.mp3` and `Legacy.mp3` are prospective collaborator music assets. They
  are not in the repository and Milestone 1 has no new audio system.
