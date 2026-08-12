# Known Issues

## Release-candidate limitations

- The demo world is a deterministic finite 81-Chunk load. It does not stream
  an unbounded world and has no user-facing render-distance control.
- Local versioned world saves and the paged World Slots browser are implemented.
  Save data is finite-world v1 only; cloud synchronization, multiplayer save
  locking, infinite region streaming, and migration UI are not implemented.
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
  evidence.
- Phase 13 Apple Silicon MacBook Air / native arm64 / Java 26 acceptance is
  HUMAN-REPORTED PASS on exact implementation candidate
  `a16855c19082a09f21bd53389cd24f711bd13f0e`. The complete requested Gate 13D
  automated/native, product-shell, settings, lifecycle, audio, development, and
  installDist checklist was reported passing. Exact macOS version, test totals,
  raw logs, runtime durations, and audio-device details were not supplied and
  are not claimed.
- Phase 14 Windows automation and development runtime are PASS. The development
  tester confirmed New World `Test`, player-position and item-state restore,
  slot deletion, reduced mining shake, and clean exit. Phase 14 Windows
  installDist create/load, Save & Quit, relaunch, restored-state verification,
  and normal exit cycle is HUMAN-REPORTED PASS. The requested Apple Silicon
  macOS Gate 14E test is also HUMAN-REPORTED PASS, but the exact Mac model,
  macOS version, Java version, automated totals, raw logs, durations, and
  performance measurements were not supplied and are not claimed.

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

- Phase 14 cross-platform acceptance is complete. Detailed macOS numeric and
  environment evidence and an exact Windows installDist duration/raw runtime
  log remain unavailable and are not claimed.
- Save is manual through Pause (`Save` or `Save & Quit`). There is deliberately
  no timed autosave or background writer in v1.
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
