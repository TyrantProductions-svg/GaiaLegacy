# Phase 15 Task 6E handoff

## Completed work

Task 6E supplies process-style, corruption, bounded-memory, cleanup-failure,
shutdown, and 500-page-transition acceptance for the approved five-minute
WorldItem lifecycle. The real save root, Task4 store, WorldItem adapter,
logical authority, physical projection, and production session composition are
exercised without introducing a second authority or permanent history.

Complete read-only telemetry now exposes all hard resident dimensions. Strict
production composition sizes pages through the canonical codec, and shutdown
closes logical paging state after physical projections. The persistence/save
format is unchanged.

## Unfinished work

Task 6E is complete. Task 7 is not authorized by this handoff. Do not continue
automatically.

## Core architecture decisions

- `LogicalWorldItemService` remains the sole semantic, stable-ID, allocator,
  ItemStack, lifecycle, TTL, and paging-ticket authority.
- TTL remains exactly 18,000 authoritative simulation ticks and is represented
  only by `expiresAtWorldTick`; pause and process downtime do not advance it.
- Durable-before-evict, immutable read-view validation, publish-once restore,
  duplicate fail-closed, allocator non-reuse, and callback rollback contracts
  are unchanged.
- Task4 remains generic opaque-byte persistence. No catalog, refcount, GC,
  maintenance overlay, repository, database, or background compactor was added.
- Runtime bounds are observed through complete current-live state equations and
  separate page/byte/pin/dirty/cleanup/ticket/descriptor telemetry.
- Dirty candidate byte admission uses exact canonical codec output in the real
  persistence composition; the legacy non-persistent constructor remains only
  for frozen in-memory tests.
- Session shutdown closes physical projections before clearing logical paging
  tickets, pins, decoded pages, and current-live metadata. Allocator high-water
  is retained in the closed service and is never reset for reuse.
- Corruption/crash evidence is shared with frozen 6A-6D and Task4 suites where
  those tests already exercise the exact production boundary.

## Modified files

- `engine/src/main/java/com/overlord/worlditem/api/WorldItemPagingMetrics.java`
- `engine/src/main/java/com/overlord/worlditem/WorldItemPageCache.java`
- `engine/src/main/java/com/overlord/worlditem/LogicalWorldItemService.java`
- `game/src/main/java/com/gaia/session/GameSessionFactory.java`
- `game/src/test/java/com/gaia/save/streaming/WorldItemPagingAcceptanceFixture.java`
- `game/src/test/java/com/gaia/save/streaming/WorldItemPagingAcceptanceTest.java`
- `game/src/test/java/com/gaia/save/streaming/WorldItemPagingCorruptionTest.java`
- `game/src/test/java/com/gaia/world/streaming/WorldItemPagingMetricsTest.java`
- `game/src/test/java/com/gaia/world/streaming/WorldItemPagingSoakTest.java`
- `game/src/test/java/com/gaia/save/streaming/StreamedChunkStoreFaultTest.java`
- `game/src/test/java/com/gaia/session/GameSessionPersistenceTestFixture.java`
- Task 6E report, this handoff, active plan, and progress ledger

The worktree includes accepted earlier Phase 15 changes. No Git mutation or
`dist/` change was made.

## Test commands and results

- Task 6E focused matrix: 14/14 GREEN.
- Frozen Task 6A-6D matrix: 152/152 GREEN (engine 96, game 56).
- Affected game proportional matrix: 438/438 GREEN.
- Task4/5 proportional evidence: 170 unaffected cases GREEN; the 8 Windows
  short/long-path fixture failures were corrected test-only and the exact
  affected matrix passed 10/10.
- `git diff --check`: PASS (line-ending warnings only).
- Process audit: zero Java/Gradle processes remain.
- Shared accepted Phase 15 tracked `git diff --stat`: 61 files changed,
  6,919 insertions, 694 deletions (aggregate, not Task 6E-only; untracked files
  are excluded).
- Full clean build is not claimed; see the Task 6E report for the known
  unrelated failures and the stock Windows crash-matrix long-run diagnosis.
- Independent reviews: round 1 was 0 Critical / 5 Important / 0 Minor; round 2
  was 0 Critical / 3 Important / 1 Minor; final rereview was 0 Critical /
  0 Important / 0 Minor — READY.

## Known risks and interfaces not to break

- Do not infer that the production session now owns Task 7 unload selection;
  Task 6E validates the accepted seams only.
- Do not turn the read-only dirty-byte metric into a second mutable authority.
- Do not weaken exact-tick semantic death because durable cleanup is delayed or
  fails.
- Do not add a global historical item map or scan the whole explored world for
  ordinary expiry progression.
- Do not change dual-index crash semantics, ticket/proof issuer binding, or
  fresh-target publish-once restore.
- macOS/Apple Silicon smoke was not observed and must not be claimed.

## Suggested integration text

- Suggested commit: `test: close phase 15 world item paging acceptance`
- Suggested PR title: `Phase 15: complete WorldItem paging acceptance`
- Suggested PR summary: adds real process/restart/projection acceptance,
  corruption and last-known-good attacks, complete bounded paging telemetry,
  cleanup failure convergence, repeated shutdown checks, and a deterministic
  500-page-transition soak while preserving the accepted TTL and single-
  authority contracts.
