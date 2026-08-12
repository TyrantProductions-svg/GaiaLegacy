# Phase 14 Save/Load v1 acceptance

## Candidate status

- Branch: `feat/save-load-v1`.
- Phase 13 baseline: merged `origin/main` predecessor of this branch.
- Gate 14A schema/ownership: **PASS**.
- Gate 14B core persistence/restore: **PASS**.
- Gate 14C atomicity/recovery: **PASS**.
- Gate 14D world-slot/product integration: **PASS**.
- Gate 14E Windows automated/package checks: **PASS**.
- Gate 14E Windows installDist human cycle: **HUMAN-REPORTED PASS**.
- Gate 14E Apple Silicon macOS: **HUMAN-REPORTED PASS**.
- Phase 14 cross-platform acceptance: **COMPLETE**.

This document separates deterministic automated evidence from human-observed
GLFW/OpenGL/OpenAL behavior. It does not treat a headless fixture as platform
acceptance and does not manufacture platform metadata or raw evidence that was
not supplied.

## Gate 14D automated evidence

| Check | Status | Evidence |
| --- | --- | --- |
| Unicode character input | PASS | Focused engine input tests passed |
| Dynamic control identity and route/modal matrix | PASS | Focused shell/UI tests passed |
| New World draft and paged World Slots | PASS | Focused draft, presenter, paging, DPI, and input tests passed |
| Initial save, load, manual checkpoint lifecycle | PASS | Focused launcher/coordinator/session tests passed; 9/9 in the final Task 4 slice |
| Product lifecycle and real bootstrap composition | PASS | Focused controller/ordering/composition tests passed |
| Shell/session/settings/audio regression matrix | PASS | 414/414 tests, 0 failures/errors/skips |
| Full `:game:test` | PASS | Final clean-build XML: 1,496/1,496, 0 failures/errors/skips |
| `git diff --check` | PASS | Exit 0; only pre-existing line-ending warnings |

## Gate 14E automated and performance evidence

| Check | Windows status | Evidence |
| --- | --- | --- |
| Forced engine Gate 14 focus | PASS | All tasks executed; `BUILD SUCCESSFUL in 16s` |
| Forced game Gate 14 focus | PASS | All tasks executed; `BUILD SUCCESSFUL in 36s` |
| Full engine | PASS | 1,141/1,141 |
| Full game | PASS | 1,496/1,496 |
| Full tools | PASS | 27 total, 26 passed, one pre-existing skip |
| Fresh `clean test build` | PASS | 30/30 tasks; 2,664 total, 2,663 passed, one skipped, 0 failures/errors; `BUILD SUCCESSFUL in 1m 55s` |
| Forced packaged/installed resources | PASS | 15/15 tasks; shaders, game resources, generated UI, and installed audio runtime passed |
| Installed Windows OpenAL | PASS | `lwjgl-openal-3.3.3.jar` plus `lwjgl-openal-3.3.3-natives-windows.jar` |

The representative radius-4 measurement used 81 Chunks, full canonical
capture/codec/archive/production reread, and fresh production restore in a
JUnit temporary directory. Time is evidence only, not a CI threshold.

| Run | Archive bytes | Capture ms | Encode ms | Write ms | Read/decode ms | Restore-to-ready ms |
| ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 1 | 7,819 | 113.266 | 44.502 | 57.287 | 44.956 | 393.099 |
| 2 | 7,819 | 119.667 | 42.994 | 56.770 | 42.772 | 433.673 |
| 3 | 7,819 | 121.139 | 45.329 | 55.014 | 43.335 | 422.213 |
| Median | 7,819 | 119.667 | 44.502 | 56.770 | 43.335 | 422.213 |

The archive is below the 16 MiB representative target. The median
capture+encode+write+production-reread path is about 264 ms, and the median
read/decode+restore-to-ready path is about 466 ms; both are below the 1.0 s
targets on this Windows reference run.

The implemented ordering is `SAVING` render/swap, then next-frame capture,
atomic write, exact checkpoint publication, and only then optional session
close. Tests also cover failure without checkpoint advance, stable-ID
Delete/Recover confirmations, dirty Return, second session, explicit catalog
refresh, and absence of a timed/background save path.

## Windows development runtime

Human-reported PASS on 2026-08-12 for the final integrated Gate 14D development
runtime. The tester created the English-named `Test` world, deleted the test
slot through the product UI, and confirmed restored player position and item
state. An initial create attempt exposed a real lifecycle defect: a LOADING
session was incorrectly asked for a paused frame and threw `session is not
ready`. The failure was reproduced by a focused RED, fixed by suppressing
session-frame capture until `READY`, and verified by focused and full game
regressions before the human rerun passed.

The tester also requested substantially smaller committed mining camera shake.
The approved adjustment retains the 0.20-second deterministic envelope while
reducing pitch/yaw peaks to 20% (`0.055`/`0.014` degrees). Focused RED/GREEN,
interaction-feedback regressions, full game tests, and the final human runtime
check all passed.

| Gate 14D runtime check | Status |
| --- | --- |
| New World name `Test`, Create, Loading, playable session | HUMAN-REPORTED PASS after RED/GREEN fix |
| Initial save and World Slots integration | HUMAN-REPORTED PASS |
| Save/load player position | HUMAN-REPORTED PASS |
| Save/load item state | HUMAN-REPORTED PASS |
| Delete selected test slot with confirmation | HUMAN-REPORTED PASS |
| Reduced mining camera shake | HUMAN-REPORTED PASS |
| Development process clean exit | PASS; Gradle `:game` completed successfully |

Gate 14D Windows development status: **PASS**.

## Windows installDist runtime

The checked-in wrapper produced the installed distribution successfully with
`:game:installDist` (`BUILD SUCCESSFUL`; 8/8 tasks up to date). The installed
launcher was then used for the requested create/load, position-or-item change,
Save & Quit, relaunch, restored-state verification, and normal-exit cycle. The
collaborator reported the complete cycle **PASS**. The launched Java process
was no longer present after the reported clean exit.

This is human-reported runtime evidence. An exact soak duration, frame metrics,
raw console log, and per-step timestamps were not supplied and are not
claimed.

## Save root and policy

- Windows: `%APPDATA%/GaiaLegacy/saves`.
- macOS: `~/Library/Application Support/GaiaLegacy/saves`.
- Other desktop platforms: `$XDG_DATA_HOME/GaiaLegacy/saves`, falling back to
  `~/.local/share/GaiaLegacy/saves`.
- Each world uses its canonical UUID directory with `current.glsave`, optional
  `backup.glsave`, and transaction-owned sibling temporary files.
- Saves are manual. No autosave timer or background save thread exists.
- Loading and Saving expose state text, not a fabricated percentage.

## Apple Silicon macOS

The collaborator reports that the requested Apple Silicon macOS Gate 14E test
passed, including the native automated/package path, development
create/save/relaunch/load cycle, path/case behavior, corruption handling,
Retina/resize/focus lifecycle, native OpenAL path, installDist runtime, and
clean shutdown. These items are recorded as **HUMAN-REPORTED PASS**.

The exact Mac model, macOS version, `uname -m` output, Java version, candidate
SHA/clean-checkout command output, automated test totals, raw logs,
development/installDist durations, audio device/OpenAL device name, and macOS
archive-size or latency measurements were not supplied and are therefore not
claimed. The Windows measurements above are not used as macOS evidence.

## Non-blocking/deferred

- Loading percentage remains deferred until a truthful progress contract
  exists.
- Rapidly changing F3 FPS/frame-time numeric ghosting remains the accepted
  debug-HUD-only issue.
- Cloud sync, multiplayer locking, migration UI, infinite streaming, and
  detail-block payloads are outside Save/Load v1.
