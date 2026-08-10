# Phase 13D Integration and Runtime Acceptance Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close Phase 13 with coherent architecture documentation, fresh automated verification, packaged-resource evidence, and real Windows/macOS product-shell and audio acceptance records.

**Architecture:** Gate 13D adds no new product behavior. It verifies the integrated ProductLoop, SettingsStore, GameSession, UI, and AudioDevice as one release candidate and records observed platform status without converting unrun checks into claims.

**Tech Stack:** Gradle Wrapper, JUnit 6.1.1, application/installDist packaging, Windows GLFW/OpenGL/OpenAL runtime, Apple Silicon macOS native runtime, Git read-only audits.

## Global Constraints

- Do not add new gameplay, save/load, key rebinding, UI animation, or settings behavior during closure.
- A failure is diagnosed with systematic debugging and fixed through TDD in the owning Gate before continuing.
- Any fix touching GLFW/OpenGL/shader/input/OpenAL/lifecycle receives immediate focused GREEN and real runtime smoke.
- Automated fake backends do not substitute for actual Windows/macOS runtime evidence.
- A platform not actually run is `NOT RUN / PENDING`.
- Preserve the fixed 1/60 simulation, canonical gameplay transactions, and owner-thread native lifetimes.
- Preserve and exclude `dist/GaiaLegacy-v0.2.0-alpha.1-windows-x64.zip` unless the user separately authorizes release packaging work.
- Do not stage, commit, push, create a PR, or merge.

## File Structure

- `docs/architecture/product-shell-settings-audio.md`: final ownership, state, settings, and audio architecture.
- `docs/audio-provenance.md`: final source/derivative/audio authorization evidence from Gate 13C.
- `docs/testing/phase-13-product-shell-audio-acceptance.md`: automated and platform acceptance matrix.
- `docs/agent-handoffs/phase-13-handoff.md`: phase completion, unfinished work, interfaces, file inventory, and risk.
- `README.md`: factual launch/product-shell summary.
- `CONTROLS.md`: factual menu/gameplay controls.
- `KNOWN_ISSUES.md`: only reproduced or explicitly accepted Phase 13 issues.

---

### Task 1: Architecture, controls, provenance, and handoff documentation

**Files:**
- Create: `docs/architecture/product-shell-settings-audio.md`
- Verify/update: `docs/audio-provenance.md`
- Create: `docs/testing/phase-13-product-shell-audio-acceptance.md`
- Create/update: `docs/agent-handoffs/phase-13-handoff.md`
- Modify: `README.md`
- Modify: `CONTROLS.md`
- Modify only for observed issues: `KNOWN_ISSUES.md`
- Reference: `docs/superpowers/specs/2026-08-08-phase-13-product-shell-settings-audio-design.md`

**Interfaces:**
- Consumes: actual implemented APIs and evidence from Gates 13A–13C.
- Produces: one current, non-stale Phase 13 documentation set.

- [ ] **Step 1: Inventory actual implementation before writing claims**

Run:

`git status --short --untracked-files=all`

`git diff --name-status`

`git diff --stat`

`git ls-files --others --exclude-standard`

Classify every path as 13A, 13B, 13C, documentation, approved source/runtime audio, the pre-existing distribution ZIP, or unexpected. Stop on any unexpected production/generated file.

- [ ] **Step 2: Write the final architecture from production, not the proposal**

Document exact class names and flow:

```text
GameBootstrap
  -> ProductLoop
       -> ScreenRouter / ProductShellController
       -> SettingsController / SettingsStore
       -> MusicManager / AudioDevice
       -> Optional<GameSession>
            -> World / physics / interaction / WorldItem / HUD
```

Include primary screen/modal transitions, frame order, hard pause, focus loss, release gate, hot vs next-session settings, platform settings paths, OpenAL owner thread, Silent fallback, OGG streaming bounds, shutdown order, and Phase 14 SaveCatalog seam. Verify every name against source before recording it.

- [ ] **Step 3: Reconcile README and CONTROLS**

README must state the application now launches to Main Menu, list Settings/Controls/Pause/audio at a user level, retain current Windows/macOS commands, and avoid claiming unrun acceptance.

CONTROLS must state:

- mouse and keyboard menu navigation;
- Enter/Space activation and Escape behavior by screen;
- F1 Playing/Paused alias;
- F2 HUD, F3 debug HUD, F4 game mode only in Playing;
- existing movement, break/place, Q/Ctrl+Q, and Shift+right pickup controls unchanged.

- [ ] **Step 4: Verify provenance against actual files**

Recompute SHA-256 for both source MP3 and runtime OGG files and compare them to `docs/audio-provenance.md`. Verify exact public credit and authorization wording. Verify the recorded conversion command/tool version matches Gate 13C evidence.

- [ ] **Step 5: Prepare the acceptance matrix without premature PASS values**

Create rows for focused tests, full build, packaged resources, Windows development, Windows installDist, Windows DPI/resize/focus/audio/persistence, macOS automated, macOS development, macOS installDist, Retina/Command+Tab/audio, and clean close. Initial runtime cells remain `NOT RUN / PENDING` until the corresponding human/runtime evidence exists.

- [ ] **Step 6: Update handoff current state**

Record completed work, unfinished work, decisions, exact modified paths, commands/results, known risks, audio attribution, and Phase 14 interfaces. Do not copy stale Milestone 1 test totals or working-tree counts.

- [ ] **Step 7: Documentation whitespace check**

Run: `git diff --check`

Expected: no errors.

---

### Task 2: Fresh focused and full automated verification

**Files:**
- Update with actual results: `docs/testing/phase-13-product-shell-audio-acceptance.md`
- Update with actual totals: `docs/agent-handoffs/phase-13-handoff.md`

**Interfaces:**
- Produces: fresh test totals and packaged-resource status tied to the current working tree.

- [ ] **Step 1: Run Gate 13A focused tests**

Run:

```powershell
./gradlew.bat :engine:test --tests 'com.overlord.core.input.*' --tests 'com.overlord.core.time.*' --console=plain --no-daemon
./gradlew.bat :game:test --tests 'com.gaia.shell.*' --tests 'com.gaia.session.*' --console=plain --no-daemon
```

Expected: PASS.

- [ ] **Step 2: Run Gate 13B focused tests**

Run:

```powershell
./gradlew.bat :engine:test --tests com.overlord.core.WindowVsyncContractTest --tests com.overlord.renderer.RendererProjectionSettingsTest --tests com.overlord.renderer.CameraLookSettingsTest --console=plain --no-daemon
./gradlew.bat :game:test --tests 'com.gaia.settings.*' --tests 'com.gaia.shell.*Settings*' --console=plain --no-daemon
```

Expected: PASS.

- [ ] **Step 3: Run Gate 13C focused tests**

Run:

```powershell
./gradlew.bat :engine:test --tests 'com.overlord.audio.*' --console=plain --no-daemon
./gradlew.bat :game:test --tests 'com.gaia.audio.*' --console=plain --no-daemon
```

Expected: PASS.

- [ ] **Step 4: Run fresh module suites**

Run separately so totals/failures are attributable:

```powershell
./gradlew.bat :engine:test --console=plain --no-daemon
./gradlew.bat :game:test --console=plain --no-daemon
./gradlew.bat :tools:test --console=plain --no-daemon
```

Expected: every module completes with zero failures/errors. Record executed, passed, skipped, and failed totals from fresh XML reports, not console estimates.

- [ ] **Step 5: Run packaged resource checks explicitly**

```powershell
./gradlew.bat :engine:verifyPackagedShaderResources --rerun-tasks --console=plain --no-daemon
./gradlew.bat :game:verifyPackagedResources --rerun-tasks --console=plain --no-daemon
./gradlew.bat :game:verifyInstalledShaderResources --rerun-tasks --console=plain --no-daemon
```

Expected: shaders, UI, Gaia OGG, Legacy OGG, and platform OpenAL runtime are present.

- [ ] **Step 6: Run the final clean build**

Run: `./gradlew.bat clean test build --console=plain --no-daemon`

Expected: BUILD SUCCESSFUL with zero test failures/errors.

- [ ] **Step 7: Recompute fresh totals after clean build**

Parse current `engine/build/test-results/test`, `game/build/test-results/test`, and `tools/build/test-results/test` XML. Record module totals, aggregate executed/passed/skipped/failures/errors, and the clean-build command in both acceptance and handoff docs.

- [ ] **Step 8: Stop on any unexplained red**

Do not edit documentation to call a failure expected. Diagnose the owning Gate with `superpowers:systematic-debugging`, add a RED regression through `superpowers:test-driven-development`, run focused GREEN, run the real runtime if native/input/render behavior changed, then repeat Tasks 2.1–2.7.

---

### Task 3: Windows development and installDist acceptance

**Files:**
- Update only after observed evidence: `docs/testing/phase-13-product-shell-audio-acceptance.md`
- Update only after observed evidence: `docs/agent-handoffs/phase-13-handoff.md`
- Update only for reproduced issue: `KNOWN_ISSUES.md`

**Interfaces:**
- Produces: actual Windows native acceptance for the current candidate.

- [ ] **Step 1: Launch the development runtime**

Run: `./gradlew.bat :game:run --console=plain --no-daemon`

Keep the process available for human interaction.

- [ ] **Step 2: Execute the complete Windows path**

Verify:

1. Main Menu mouse and keyboard navigation;
2. disabled Load World and Phase 14 label;
3. Controls and Settings return targets;
4. New World Loading to Playing;
5. movement, walk bob, step smoothing, jump/landing;
6. Survival break/place, physical drop, Shift+right pickup, Q, Ctrl+Q;
7. Escape and F1 Pause/Resume, no held-edge replay;
8. F2/F3/F4 only in Playing;
9. FOV, sensitivity, invert-Y, VSync, Master/Music/SFX settings;
10. Gaia continuity and fade/duck behavior;
11. Alt+Tab auto-pause, focus mute, mouse recovery;
12. resize and Windows DPI behavior;
13. Return Main unsaved warning and fresh second session;
14. clean quit confirmation and shutdown;
15. relaunch with persisted settings.

- [ ] **Step 3: Launch the installDist executable**

After `installDist`, run the generated Windows launcher under `game/build/install/game/bin`. Repeat Main Menu, New World, Pause, Settings persistence, Gaia playback, focus recovery, Return Main, and clean Quit.

- [ ] **Step 4: Inspect runtime output**

Confirm no shader/uniform/GLFW/OpenGL/OpenAL exception, no native cleanup error, no duplicate music source diagnostic, no surviving executor, and no reservation/shutdown failure.

- [ ] **Step 5: Record actual duration and outcome**

Record development/installDist PASS/FAIL separately, actual runtime duration, machine/Windows/JVM details if supplied, and whether a human heard and interacted with audio. If interaction/audio cannot be verified by the agent, state `WINDOWS INTERACTIVE RETEST REQUIRED BY USER` and leave acceptance pending.

---

### Task 4: Apple Silicon macOS acceptance handoff

**Files:**
- Update only after supplied evidence: `docs/testing/phase-13-product-shell-audio-acceptance.md`
- Update only after supplied evidence: `docs/agent-handoffs/phase-13-handoff.md`
- Update only for reproduced issue: `KNOWN_ISSUES.md`

**Interfaces:**
- Produces: exact macOS acceptance status for the same candidate.

- [ ] **Step 1: If no native Mac is available, record pending and stop this task**

Record `Apple Silicon macOS automated: NOT RUN / PENDING`, `development runtime: NOT RUN / PENDING`, and `installDist: NOT RUN / PENDING`. Do not infer macOS success from Windows or classifier tests.

- [ ] **Step 2: On an actual Apple Silicon Mac, run automated verification**

```bash
./gradlew clean test build --console=plain --no-daemon
./gradlew :engine:verifyPackagedShaderResources --rerun-tasks --console=plain --no-daemon
./gradlew :game:verifyPackagedResources --rerun-tasks --console=plain --no-daemon
./gradlew :game:verifyInstalledShaderResources --rerun-tasks --console=plain --no-daemon
```

Record native arm64 JVM, Mac model, macOS version, exact candidate SHA/working-tree provenance, and fresh totals if supplied.

- [ ] **Step 3: Run development and installDist interactively on macOS**

Verify the same product path as Windows plus Retina layout/hit alignment, resize, Command+Tab auto-pause/focus audio recovery, function-key behavior, native OpenAL playback, and clean close. Record actual duration.

- [ ] **Step 4: Record F3 ghosting only if checked**

The Milestone 1 debug-HUD numeric ghosting remains a known debug-only issue. Record `REPRODUCED`, `NOT REPRODUCED`, or `NOT CHECKED`; do not fix it opportunistically in Phase 13 closure.

---

### Task 5: Final repository audit and Phase 13 report

**Files:**
- Final update: `docs/testing/phase-13-product-shell-audio-acceptance.md`
- Final update: `docs/agent-handoffs/phase-13-handoff.md`

**Interfaces:**
- Produces: final unstaged working-tree inventory and completion report.

- [ ] **Step 1: Run final file audit**

```powershell
git status --short --untracked-files=all
git diff --name-status
git diff --stat
git ls-files --others --exclude-standard
git diff --check
```

Reject build/bin/out directories, class files, logs, screenshots, saves, crash dumps, IDE-local files, local settings, native extraction caches, conversion temp files, and machine-specific paths. Preserve the pre-existing distribution ZIP but keep it outside the Phase 13 intended set.

- [ ] **Step 2: Verify no repository mutation was authorized beyond working files**

Run:

`git diff --cached --name-status`

`git log -1 --oneline --decorate`

Expected: cached diff empty; HEAD unchanged from the Phase 13 starting commit unless the user separately authorized a repository operation.

- [ ] **Step 3: Finalize handoff facts**

Record exact modified/untracked counts from current status, full test totals, packaged-resource results, Windows status, macOS status, known issues, deferred Phase 14 work, suggested commit message `feat(shell): add menus settings and audio foundation`, and suggested PR title `feat(shell): establish GaiaLegacy product shell and audio foundation`.

- [ ] **Step 4: Apply verification-before-completion**

Invoke `superpowers:verification-before-completion` and re-check every success statement against fresh command/runtime evidence. Do not claim a platform PASS without actual platform execution.

- [ ] **Step 5: Stop without repository integration**

Do not stage, commit, push, create a PR, or merge. Report implementation state, exact files, tests, packaged resources, runtime status, pending human/platform checks, and known issues to the user.
