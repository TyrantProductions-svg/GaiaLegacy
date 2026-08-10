# Phase 13 product shell, settings, and audio acceptance

## Candidate

- Branch: `feat/product-shell-audio`
- Baseline and current unstaged HEAD: `80ea67bf9a41e467dbd17ba81876ab870c41407d`
- Candidate form: tracked and untracked Phase 13 working tree; no Phase 13
  commit exists yet.
- Preserved non-candidate artifact:
  `dist/GaiaLegacy-v0.2.0-alpha.1-windows-x64.zip`.

This record distinguishes automated evidence from actual platform execution.
Fake UI/audio backends and native-classifier tests never substitute for human
GLFW/OpenGL/OpenAL acceptance.

## Automated matrix

| Check | Windows status | Evidence |
| --- | --- | --- |
| Gate 13A input/time focused tests | PASS | `BUILD SUCCESSFUL in 7s` |
| Gate 13A shell/session focused tests | PASS | `BUILD SUCCESSFUL in 8s` |
| Gate 13B engine settings focused tests | PASS | `BUILD SUCCESSFUL in 7s` |
| Gate 13B settings/shell focused tests | PASS | `BUILD SUCCESSFUL in 8s` |
| Gate 13C engine audio focused tests | PASS | `BUILD SUCCESSFUL in 7s` |
| Gate 13C game audio focused tests | PASS | `BUILD SUCCESSFUL in 38s` |
| `:engine:test` | PASS | `BUILD SUCCESSFUL in 12s`; 1,102/1,102 passed |
| `:game:test` | PASS | `BUILD SUCCESSFUL in 1m 7s`; 1,119/1,119 passed |
| `:tools:test` | PASS | `BUILD SUCCESSFUL in 8s`; 26 passed, 1 skipped |
| Shader/resource/UI/audio packaging | PASS | Forced engine, packaged-game, and installDist checks passed; installed audit found exactly OpenAL 3.3.3 API plus Windows native |
| Final `clean test build` | PASS | `BUILD SUCCESSFUL in 1m 36s`; 30/30 tasks executed; 2,248 total, 2,247 passed, 1 skipped, 0 failures/errors |

## Windows interactive matrix

The complete integrated Gate 13D development and installDist paths were run by
the human Windows tester on the current working-tree candidate. Development ran
for 10 minutes and installDist ran for 7 minutes. The tester reported no
runtime anomaly.

| Check | Development runtime | installDist runtime |
| --- | --- | --- |
| Main Menu mouse/keyboard and disabled Phase 14 Load World | PASS | PASS |
| Settings/Controls return targets and dirty-settings modal | PASS | PASS |
| Loading, Playing, Pause/Resume, second session | PASS | PASS |
| Movement, traversal presentation, break/place/drop/pickup | PASS | PASS |
| F1/F2/F3/F4 eligibility and no held-edge replay | PASS | PASS |
| FOV/sensitivity/invert-Y/VSync/audio Apply | PASS | PASS |
| Gaia startup/continuity/pause duck/focus mute | PASS | PASS |
| Resize/DPI hit alignment and Alt+Tab recovery | PASS | PASS |
| Persisted relaunch | PASS | PASS |
| Clean quit with no GL/GLFW/OpenAL/shutdown diagnostic | PASS | PASS |
| Actual duration | 10 minutes | 7 minutes |

The tester requested a Loading progress bar, then explicitly accepted deferral.
The current session boundary exposes only Loading/Ready/Failed, not a truthful
percentage. No indeterminate or fabricated percentage bar was added during this
acceptance gate.

## Apple Silicon macOS matrix

No Phase 13 Gate 13D candidate has been run on an actual Apple Silicon Mac.
Milestone 1 Phase 12 evidence does not establish Phase 13 UI/settings/audio
acceptance.

| Check | Status |
| --- | --- |
| Native arm64 clean build and module totals | NOT RUN / PENDING |
| Packaged shaders/UI/OGG/OpenAL native | NOT RUN / PENDING |
| Development runtime | NOT RUN / PENDING |
| installDist runtime | NOT RUN / PENDING |
| Retina/resize hit alignment | NOT RUN / PENDING |
| Command+Tab pause/focus/audio recovery | NOT RUN / PENDING |
| Native Gaia playback and settings volume | NOT RUN / PENDING |
| Function keys and complete product/gameplay path | NOT RUN / PENDING |
| Clean close/relaunch and duration | NOT RUN / PENDING |
| F3 numeric ghosting | NOT CHECKED |

## Accepted known issue

Rapidly changing F3 FPS/frame-time digits can leave a visible numeric trail.
This is an accepted debug-HUD-only issue. No gameplay, simulation, audio, or
resource-growth failure is attributed to it. Gate 13D must record whether it was
checked on each platform and must not fix it opportunistically.
