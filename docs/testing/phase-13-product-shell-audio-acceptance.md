# Phase 13 product shell, settings, and audio acceptance

## Candidate

- Branch: `feat/product-shell-audio`
- Baseline: `origin/main@80ea67bf9a41e467dbd17ba81876ab870c41407d`.
- Exact implementation candidate tested on Windows and macOS:
  `a16855c19082a09f21bd53389cd24f711bd13f0e`.
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

Human-reported PASS on Apple Silicon MacBook Air / native arm64 with Java 26.
The exact implementation candidate was
`a16855c19082a09f21bd53389cd24f711bd13f0e`, and the complete requested Gate
13D acceptance checklist was reported passing. Exact macOS version, JUnit
totals, raw logs, development duration, installDist duration, audio-device
model, OpenAL device name, and performance numbers were not supplied and are
therefore not claimed.

| Check | Status |
| --- | --- |
| Fresh candidate checkout/clone, exact SHA, correct branch family | HUMAN-REPORTED PASS |
| Native arm64 automated build/test and clean build | HUMAN-REPORTED PASS; exact totals not supplied |
| Packaged shaders, UI, OGG, resources, installed runtime, macOS OpenAL native | HUMAN-REPORTED PASS |
| Main Menu mouse/keyboard, Controls, disabled Load World, Settings and modals | HUMAN-REPORTED PASS |
| FOV, sensitivity, invert-Y, VSync, Master/Music/SFX, mute-unfocused Apply | HUMAN-REPORTED PASS |
| Next-session Chunk radius, game mode and debug-HUD defaults | HUMAN-REPORTED PASS |
| Settings persistence across relaunch | HUMAN-REPORTED PASS |
| Loading, New World, Pause/Resume, Return to Menu, second fresh session | HUMAN-REPORTED PASS |
| Retina, resize and pointer/layout alignment | HUMAN-REPORTED PASS |
| Command+Tab, focus recovery and no implicit gameplay resume | HUMAN-REPORTED PASS |
| Gaia audible in Main Menu/gameplay, pause duck, resume and volume settings | HUMAN-REPORTED PASS |
| Focus mute/recovery and no duplicate Gaia across menu/second session | HUMAN-REPORTED PASS |
| Native OpenAL audible path rather than Silent fallback | HUMAN-REPORTED PASS |
| Development runtime and clean shutdown | HUMAN-REPORTED PASS; duration not supplied |
| installDist menus/settings, native audio, gameplay and clean shutdown | HUMAN-REPORTED PASS; duration not supplied |
| F3 ghosting reproduction status | NOT SUPPLIED; accepted debug-only issue remains open |

## Final Gate 13D status

- Gate 13A: **PASS**.
- Gate 13B: **PASS**.
- Gate 13C: **PASS**.
- Gate 13D Windows automated/development/installDist: **PASS**.
- Gate 13D Apple Silicon macOS automated/native/development/installDist:
  **HUMAN-REPORTED PASS**.
- Phase 13: **CROSS-PLATFORM ACCEPTANCE COMPLETE / READY FOR PR**.

## Accepted known issue

Rapidly changing F3 FPS/frame-time digits can leave a visible numeric trail.
This is an accepted debug-HUD-only issue. No gameplay, simulation, audio, or
resource-growth failure is attributed to it. The Phase 13 macOS tester did not
supply a separate reproduced/not-reproduced value; no value is inferred. Do not
fix it opportunistically.
