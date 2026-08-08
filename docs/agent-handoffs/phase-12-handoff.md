# Phase 12 — Milestone 1 Release Candidate Integration Handoff

## Status

**CURRENT: Windows automated verification PASS; Windows development runtime
PASS; continuous 20-minute Windows installDist gameplay soak PASS; Apple
Silicon macOS NOT RUN / PENDING.**

This candidate is based on
`origin/main@25d3a78040b08f32d6264a6a7e2a8968eed679f9` on
`release/milestone-1-vertical-slice`. No work has been staged, committed,
pushed, tagged, merged, or submitted as a pull request.

## Completed work

- Established release defaults: seed `12345`, generation algorithm `2`, finite
  radius `4` (81 Chunks), vertical FOV `70.0`, sensitivity `0.1`, VSync on,
  debug HUD off, and Survival startup.
- Added an explicit VSync configuration boundary. The GLFW context becomes
  current on the owner thread before swap interval `1` is applied; disabling
  VSync explicitly applies interval `0`. Adaptive VSync is absent and the
  simulation remains fixed at exactly 1/60.
- Audited the production ownership graph
  `GameBootstrap -> GameContext -> GameLoop`, fixed-step order, reservation
  cleanup, renderer presentation boundaries, and reverse/idempotent shutdown.
- Recorded deterministic demo seed evidence and the production safe spawn at
  player feet `(0,25,0)`.
- Reconciled build/run instructions, controls, architecture, changelog, known
  limitations, dependency/asset provenance, packaged resources, and platform
  evidence.
- Corrected a Windows-observed presentation defect in rapid Creative breaking.
  The original `+0.55` degree pitch and `+/-0.14` degree yaw envelope added new
  events to unfinished impulses and could remain at its `1.0`-degree pitch cap.
  Break feedback is now exactly half strength at `+0.275` pitch and
  deterministic `+/-0.07` yaw, and rapid breaks restart rather than accumulate
  the 0.20-second envelope. Placement feedback, held-item swing, canonical
  Camera state, collision, raycasts, transactions, and world mutation are
  unchanged.

## Deterministic demo evidence

- Seed: `12345`
- Algorithm: `2`
- Radius: `4`; loaded Chunks: `81`
- Generation hash:
  `ec2c76a97f36d34b7360ae9abbb0be60fb8790f275fdaf5227a7daeae9754353`
- Visual fingerprint:
  `56cb2f243319c7cf275ade89f480f9208ce5c1f85334eb225e6b56ed18e3012a`
- Safe spawn: `(0,25,0)`
- Plains tree: `(-33,33)`
- Rolling-hills tree: `(0,12)`
- Rocky outcrop: `(-50,8)`
- Surface cave entrances: `(-22,24,3)` and `(-6,23,-21)`
- Deep reachable cave: `(67,2,-64)`
- Cross-Chunk tunnel air: `(48,57,-45)`

Demo coordinates are documentation only. There is no demo-specific gameplay
branch.

## Automated verification

Fresh post-correction Windows commands:

```text
.\gradlew.bat :engine:test --console=plain --no-daemon
.\gradlew.bat :game:test --console=plain --no-daemon
.\gradlew.bat :tools:test --console=plain --no-daemon
.\gradlew.bat clean test build --console=plain --no-daemon
.\gradlew.bat :game:verifyPackagedResources :engine:verifyPackagedShaderResources :game:verifyInstalledShaderResources :tools:verifyGeneratedUiAssets --rerun-tasks --console=plain --no-daemon
```

All commands returned exit `0`. The clean build executed all 29 tasks; the
forced resource run executed all 14 tasks.

Fresh JUnit XML totals:

| Module | Suites | Tests | Passed | Skipped | Failures/errors |
| --- | ---: | ---: | ---: | ---: | ---: |
| Engine | 102 | 951 | 951 | 0 | 0 |
| Game | 89 | 827 | 827 | 0 | 0 |
| Tools | 5 | 27 | 26 | 1 | 0 |
| Total | 196 | 1,805 | 1,804 | 1 | 0 |

The Tools skip is the existing Windows symlink-capability skip.

The rapid-break correction followed RED/GREEN. Before production changed,
`CameraImpulseControllerTest` completed 14 tests with 6 failures at the old
`0.55` pitch path. The exact regression and all related feedback, movement,
transaction, and render-view composition tests then passed.

## Windows interactive evidence

- Development VSync smoke: PASS for launch, move, jump, break, place, Q,
  Ctrl+Q, Shift+right manual pickup, and Escape shutdown.
- Repeated development startup/shutdown: PASS with exit `0` and no retained
  runtime failure.
- Demo/surface session: human-reported eight minutes, safe spawn `(0,25,0)`,
  surface traversal PASS, no anomaly reported.
- installDist surface session: human-reported eight minutes, PASS.
- Post-correction Creative rapid-break session: launch and shutdown exit `0`;
  human retest confirmed the shake was greatly reduced.
- Final installDist gameplay soak: human-reported continuous 20 minutes, PASS;
  no crash, stutter, duplicate objects, or abnormal particle growth.

The F3 debug HUD showed visible ghosting on its rapidly changing FPS and
frame-time digits. This was the only newly reported issue and did not coincide
with a gameplay, simulation, or resource-growth anomaly.

## Performance observations

An installDist run used `-Dgaia.renderMetrics=true -Xlog:gc`. Across 139
non-startup render samples:

- FPS: 94.52-110.84, average 100.96;
- frame time: 9.02-10.58 ms, average 9.91 ms;
- visible Chunks: 3-79;
- draw calls: 8-88;
- triangles: 13,565-231,717;
- mesh queue depth: 0-1.

The approximately 140-second trace recorded 17 G1 young collections with
0.916-4.080 ms pauses, average 2.618 ms. Physics-body, WorldItem, particle, and
fixed-step HUD values were not retained exactly in the console trace. Human
observation during the full soak found no abnormal particle growth or duplicate
objects. The short metrics sample is not evidence to add pooling.

## Platform acceptance

| Platform/gate | Automated | Development runtime | installDist/runtime soak |
| --- | --- | --- | --- |
| Windows | PASS | PASS | continuous 20-minute gameplay soak PASS |
| Apple Silicon macOS | NOT RUN / PENDING | NOT RUN / PENDING | NOT RUN / PENDING |

Windows evidence does not imply macOS success. Native arm64 JVM, development
run, installDist, Retina/resize, Command+Tab, function-key behavior, complete
gameplay loop, and clean exit remain unverified on Apple Silicon macOS.

## Unfinished work and known risks

- Run the complete automated and interactive matrix on Apple Silicon macOS.
- Diagnose and correct the F3 FPS/frame-time numeric ghosting in a focused HUD
  task if release triage promotes this debug-only readability issue. Do not
  infer a fix or change UI behavior without a reproduction.
- Do not infer clean-clone reproducibility from the current dirty integration
  tree; it must be verified after an explicitly authorized commit exists.
- Milestone 1 intentionally has no persistence, main menu, settings UI,
  crafting, mobs, complete survival economy, automatic world-item expiry,
  body-body item collision, or rotating WorldItems.
- `Gaia.mp3` and `Legacy.mp3` remain named future Milestone 2 collaborator
  assets. They are not present or packaged; source and license must be recorded
  before introduction.

## Files changed

Production:

- `engine/src/main/java/com/overlord/config/GameConfig.java`
- `engine/src/main/java/com/overlord/core/Window.java`
- `engine/src/main/java/com/overlord/renderer/Camera.java`
- `game/src/main/java/com/gaia/interaction/feedback/CameraImpulseController.java`

Tests:

- `engine/src/test/java/com/overlord/core/WindowVsyncContractTest.java`
- `engine/src/test/java/com/overlord/renderer/CameraPositionTest.java`
- `game/src/test/java/com/gaia/GameBootstrapStructureTest.java`
- `game/src/test/java/com/gaia/interaction/feedback/CameraImpulseControllerTest.java`
- `game/src/test/java/com/gaia/world/generation/WorldGenerationConfigTest.java`

Documentation:

- `README.md`
- `CONTROLS.md`
- `CHANGELOG.md`
- `KNOWN_ISSUES.md`
- `THIRD_PARTY_NOTICES.md`
- `docs/architecture/current-baseline.md`
- `docs/architecture/physical-world-items.md`
- `docs/agent-handoffs/phase-12-handoff.md`
- `docs/superpowers/specs/2026-08-08-phase-12-milestone-1-release-candidate-integration-design.md`
- `docs/superpowers/plans/2026-08-08-phase-12-milestone-1-release-candidate-integration.md`

Current final-audit working-tree inventory is 12 modified tracked files and 7
untracked source/documentation files, 19 paths total. All 19 are listed above;
no generated build output, local log, screenshot, save, crash dump, IDE file,
class file, or local absolute path is included. `git diff --check` passes.

## Interfaces the next phase must not break

- Exact 1/60 authoritative simulation and immutable render interpolation.
- Context-current-before-swap-interval ordering on the GL owner thread.
- Unique inventory, WorldItem, mutation, player, Chunk, mesh, and loop
  authorities.
- Transactional break/place/drop/pickup commit barriers and stable-ID item
  conservation.
- Canonical Camera, collision, targeting, and raycast independence from all
  first-person presentation.
- Depth-correct held-block viewmodel and validated uniform-array upload path.
- Reverse/idempotent shutdown with reservation and GPU cleanup on their owning
  boundaries.

## Suggested release text

Suggested commit message, if later authorized:

```text
release: integrate milestone 1 physical sandbox vertical slice
```

Suggested pull-request title:

```text
release: GaiaLegacy Milestone 1 vertical slice
```

Suggested PR description:

```text
Integrates the Milestone 1 deterministic physical sandbox from current main.
Adds explicit RC defaults and VSync ownership, reconciles clean-clone/package
resources and controls, records the fixed demo route, and closes the rapid
Creative-break presentation issue without changing gameplay authority.

Windows automated, development runtime, and continuous 20-minute installDist
gameplay gates pass. The rapidly changing F3 FPS/frame-time values have a known
numeric ghosting issue. All native Apple Silicon macOS gates remain explicitly
pending. No large Milestone 2 system is included.
```

Suggested tag notes, without creating a tag:

```text
GaiaLegacy Milestone 1 is a pre-alpha deterministic voxel sandbox vertical
slice with transactional three-slot inventory, physical drops, manual pickup,
fixed-step movement, OpenGL 4.1 presentation, particles, HUD, and first-person
movement/held-block feedback. Persistence, menus, crafting, mobs, expanded
survival progression, and audio remain deferred to Milestone 2.
```
