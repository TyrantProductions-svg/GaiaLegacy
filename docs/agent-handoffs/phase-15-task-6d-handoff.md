# Phase 15 Task 6D handoff

## Completed work

Task 6D makes WorldItem activation, hibernation, and exact-tick expiry atomic
with their owner-thread physical projection changes. Same-service mutations
during projection callbacks fail before canonical mutation; reads remain
available. Callback/projection `RuntimeException` and `Error` failures restore
the exact logical and physical pre-call state and preserve the approved ticket
retry/consumption semantics.

## Unfinished work

Task 6E acceptance and Task 7 streaming control are not authorized by this
handoff. Do not infer authorization to continue.

## Core architecture decisions

- `LogicalWorldItemService` remains the only WorldItem semantic, stable-ID,
  allocator/high-water, ItemStack, lifecycle, expiry, and paging authority.
- Projection callbacks execute on the owner thread. Every same-service mutator
  checks one guard before any allocator, tick, reservation, item, cache, ticket,
  or projection mutation. Read-only snapshots remain valid.
- Activation failure consumes its activation ticket and restores the exact
  pre-call epoch, so unrelated prepared tickets remain valid.
- Hibernation failure restores active canonical/projection state and leaves the
  exact hibernate ticket retryable.
- Expiry failure restores authoritative world tick, deterministic expiry
  ordering, metadata, reservations, canonical state, and projections; cleanup
  is published only after projection success.
- Physical rollback restores body instances/order, projection and recovery
  maps, prepared state, and all metrics. Construction cleanup is ownership
  aware and cannot detach an externally registered body.
- Paged activation preparation is cache-mutation-free; commit snapshots and
  restores the bounded resident cache and cleanup state exactly on failure.
- Multi-item reconcile builds detached candidates, registers the complete
  batch, and publishes one projection map transaction without partial callback
  visibility.
- Task4, v1/v2 save formats, TTL=18,000, durable-before-evict, stable-ID, and
  bounded current-live contracts are unchanged.

## Modified files

- `engine/src/main/java/com/overlord/worlditem/LogicalWorldItemService.java`
- `engine/src/main/java/com/overlord/worlditem/WorldItemPageCache.java`
- `game/src/main/java/com/gaia/worlditem/PhysicalWorldItemSystem.java`
- `game/src/main/java/com/gaia/session/GameSessionFactory.java`
- `engine/src/test/java/com/overlord/worlditem/LogicalWorldItemPagingTest.java`
- `engine/src/test/java/com/overlord/worlditem/LogicalWorldItemExpiryTest.java`
- `game/src/test/java/com/gaia/worlditem/PhysicalWorldItemPagingTest.java`
- Task 6D report, this handoff, active paging plan, and progress ledger

The repository is a shared dirty Phase 15 worktree. No stage, commit, push, PR,
merge, or `dist/` mutation was performed.

## Test commands and results

- `./gradlew.bat :engine:test --tests "com.overlord.worlditem.LogicalWorldItem*Test" :game:test --tests "com.gaia.worlditem.PhysicalWorldItemPagingTest" --tests "com.gaia.worlditem.PhysicalWorldItemSystemTest" --console=plain --no-daemon`:
  152/152 GREEN (engine 96, game 56).
- `./gradlew.bat :game:test --tests "com.gaia.worlditem.*" --tests "com.gaia.inventory.*" --tests "com.gaia.interaction.*" --tests "com.gaia.session.*" --tests "com.gaia.save.session.*" --console=plain --no-daemon`:
  438/438 GREEN.
- Frozen Task 6C command selected all 16 methods in
  `WorldItemPagingRestartTest` except the known 28-minute
  `legalOneThousandTwentyFourOwnerCheckpointPublishesThroughBoundedStaging`,
  plus `WorldItemPageCodecTest`, `WorldItemPagingCheckpointCodecTest`, and
  `WorldItemDurabilityCapabilityTest`: 32/32 GREEN.
- Affected engine proportional matrix: 397/399 passed. The two failures are
  pre-existing `BlockRaycastTest` extreme-coordinate fixture failures caused by
  the unrelated `ChunkCoordinatePolicy` safe envelope before any 6D path is
  reached; see the report for stack evidence.
- Independent review round 1: 0 Critical / 3 Important / 0 Minor. All three
  findings were addressed. Final rereview: 0 Critical / 0 Important / 0 Minor
  — READY.
- `git diff --check`: PASS (line-ending conversion warnings only).
- Process audit: no Java, JavaW, or Gradle process remains.
- Shared tracked-worktree `git diff --stat`: 61 files changed, 6,805
  insertions, 680 deletions. It includes prior Phase 15 work and excludes
  untracked files, so it is not a Task 6D-only delta.

No interactive GLFW smoke or disproportionate full-repository validation was
run. A mistakenly selected known 28-minute Task 6C acceptance was canceled once
identified and replaced by the 32-case proportional subset.

## Known risks and interfaces not to break

- Do not weaken guard-first same-service mutation rejection or allow callback
  mutation through a new public mutator.
- Do not change activation-ticket consumption, hibernate-ticket retry, or
  exact same-tick expiry retry semantics.
- Do not split GameSession expiry back into semantic mutation followed by a
  separate best-effort physical removal.
- Do not move projection/body/GPU lifecycle to workers.
- Do not create a second WorldItem repository or expand Task4 beyond generic
  opaque durable bytes.
- The unrelated BlockRaycast/ChunkCoordinatePolicy conflict remains outside
  Task 6D and requires separate authorization if it is to be repaired.

## Suggested integration text

- Suggested commit: `fix: make world item projections transactional`
- Suggested PR title: `Phase 15: make WorldItem projection callbacks rollback-safe`
- Suggested PR summary: guards same-service callback mutations and atomically
  coordinates activation, hibernation, and expiry with bounded owner-thread
  projection construction/removal and exact failure rollback; Task 6E remains
  unstarted.
