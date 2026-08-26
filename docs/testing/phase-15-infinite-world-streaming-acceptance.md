# Phase 15 infinite-world streaming acceptance

## Automated structural Gate 15F

The typed production-session probe drives more than 500 Chunk transitions in
all cardinal directions, negative coordinates, reversal, rapid travel,
multiple origin rebases, unload/regeneration, one distant modification,
Save & Quit style restart, WorldItem hibernation/activation/expiry, canonical
raycast/collision queries, and return travel.

The formal same-JVM matrix is **3/3 GREEN**. The final focused rerun completed
in 9m14s, and its production performance case recorded 530.65 seconds. No FPS
or latency threshold is used. Raw observations assert literal
resident, scheduler, mesh/GPU, metadata, cleanup, authority, identity, hash,
and canonical-coordinate bounds.

Additional focused and proportional evidence collected before full
verification:

- affected game streaming/save/session/WorldItem matrix: **148/148 GREEN**;
- affected engine Chunk/WorldItem matrix: **55/55 GREEN**;
- exact legacy-v1 regression rerun after the compatibility repair:
  **10/10 GREEN**;
- post-Windows-acceptance Task4/Phase14 migration matrix: **129/129 GREEN**;
- post-Windows-acceptance production session/origin/shutdown/save matrix:
  **57/57 GREEN**;
- fresh bootstrap composition: **4/4 GREEN**;
- durability-fence focused RED-to-GREEN set: **2/2 GREEN**;
- scheduler/pipeline fault matrix after the repair: **29/29 GREEN**;
- affected production session/shutdown/GPU-owner boundary: **64/64 GREEN**;
- formal production structural matrix: **3/3 GREEN**.

The final `clean test build` completed in 1h58m01s with **3,351 tests total:
3,350 passed, one platform-conditional tools test skipped, zero failures and
zero errors**. Module totals were engine 1,309, game 2,015, and tools 27. The
build also executed `:game:installDist` and the packaged shader/resource/OpenAL
audits.

## Structural acceptance assertions

- resident authority never exceeds the radius-7 225-Chunk hysteresis bound and
  a settled observation returns to the radius-5 121-Chunk footprint;
- load/generate, save, mesh, upload, and destruction work stays within the
  fixed accepted/active/per-frame limits;
- untouched bytes regenerate identically and modified bytes reload exactly at
  a newer repository publication revision;
- canceled and stale work is observed but never published late;
- canonical block raycast and collision agree after multiple rebases;
- Save & Quit/restart preserves save identity and authoritative world tick;
- WorldItem unload/reload preserves stable ID and stack, exact-tick expiry is
  semantic, cleanup failure does not resurrect it, and one concrete expired
  page revisit converges without a global directory scan;
- current-live metadata, page cache, cleanup intents/bytes/tombstones, worker
  work, diagnostics, and resident state remain bounded as travel distance grows.

## Platform status

| Platform gate | Status | Evidence |
| --- | --- | --- |
| Windows automated | **PASS** | `clean test build`: 3,351 total, 3,350 passed, one conditional tools skip, zero failures/errors; `:game:installDist` and package audits passed. |
| Windows development interactive | **PASS** | `:game` created the real GLFW/OpenGL window; Main Menu and Settings rendered and accepted input; return navigation and standard Alt+F4 shutdown completed with Gradle exit 0. The Gradle process reported 1h25m29s, including a desktop-control authorization wait, so it is not a performance measurement. |
| Windows installDist interactive | **PASS** | `game/build/install/game/bin/game.bat` launched independently of Gradle; `New Worlx826` entered gameplay, switched to Creative, and placed a visible dirt block. Save & Quit returned to the menu and the process exited 0. A fresh process loaded the same streamed-v2 world with the same view/mode and dirt block visible; the second Save & Quit and final shutdown exited 0. One sandbox-restricted relaunch was rejected before bootstrap by AppData `AccessDenied` and was excluded; the normal-permission relaunch passed. No standalone duration was instrumented. |
| Apple Silicon macOS native arm64 | **PENDING / NOT SUPPLIED** | No native Apple Silicon host or new Phase 15 evidence was supplied; Windows evidence is not substituted. |

Historical Phase 12-14 macOS results remain historical and do not establish
Phase 15 Gate 15F.

Final dual owner review has **0 unresolved Critical / 0 Important / 0 Minor**.
The game/save review's single documentation-only stale-stat Minor was resolved
by recording the final read-only diff stat in the Phase 15 handoff.

## Acceptance command set

```powershell
.\gradlew.bat -g .gradle-user-home --no-daemon :game:test --tests com.gaia.world.streaming.ChunkStreamingSoakTest --tests com.gaia.world.streaming.ChunkStreamingPerformanceMeasurementTest
.\gradlew.bat -g .gradle-user-home --no-daemon clean test build
.\gradlew.bat -g .gradle-user-home --no-daemon :game
.\game\build\install\game\bin\game.bat
git diff --check
git status --short --untracked-files=all
```
