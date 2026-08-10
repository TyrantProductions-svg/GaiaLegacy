# Known Issues

## Release-candidate limitations

- The demo world is a deterministic finite 81-Chunk load. It does not stream
  an unbounded world and has no user-facing render-distance control.
- The product shell, Main Menu, Pause Menu, Controls, and persistent Settings
  are implemented. Real world/save persistence and a save browser are not;
  `Load World - Available in Phase 14` is deliberately disabled.
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

- Windows development runtime is human-tested and passing for the Phase 12
  candidate. Phase 13 Gates 13A–13C also have focused Windows development
  evidence, including native audible OpenAL music.
- Windows installDist completed a human-reported continuous 20-minute gameplay
  soak for Phase 12 without crash, stutter, duplicate objects, or abnormal
  particle growth. Phase 13 Gate 13D development and installDist also pass on
  the current working-tree candidate: 10 minutes and 7 minutes respectively,
  with no reported product-shell, settings, gameplay, OpenGL, or OpenAL anomaly.
- Apple Silicon MacBook Air acceptance is PASS on the exact RC commit
  `477945913cbeffbf7886b7eed0f152519a4f120b` with Java 26: clean clone,
  automated build, packaged resources/shaders, development runtime,
  Retina/resize, Command+Tab, function keys, complete gameplay, clean exit,
  installDist, and a continuous 26-minute soak. The macOS version and
  automated test totals were not supplied. This is historical Phase 12
  evidence; Apple Silicon macOS Phase 13 menu/settings/native-audio acceptance
  remains `NOT RUN / PENDING`.

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

## Deferred work

- Phase 14 owns save discovery, real Save/Load, world serialization, and
  restoration. Cloud saves remain outside that initial persistence scope.
- Loading currently presents status text and Cancel but no progress bar. A
  truthful percentage requires a future loading-progress contract; the human
  Gate 13D tester explicitly accepted deferral rather than adding fabricated
  progress during release acceptance.
- Crafting, mobs, expanded content, full key rebinding/accessibility,
  small-block editing, moving assemblies, fluids, weather, PBR, dynamic
  shadows, and networking remain later Milestone 2 work.
- Gaia and Legacy music plus the minimal OpenAL/STB foundation are implemented.
  Legacy is registered for future explicit routing and is not forced into
  ordinary exploration.
