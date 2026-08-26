# Phase 15 Task 6B handoff

## Completed work

Task 6B implements bounded dormant activation and durable eviction on top of
the closed Task 6A TTL/page seam. `LogicalWorldItemService` remains the sole
WorldItem semantic, stable-ID, allocator, and lifecycle authority. Exact-tick
expiry, bounded live metadata, durable-before-evict persistence, pinned retry,
validate-first activation, cleanup saturation/rediscovery, and same-ID partial
pickup are implemented and tested.

## Unfinished work

Task 6C (v1 fail-closed and v2 process restart integration), Task 6D
(same-service callback reentrancy hardening), and Task 6E acceptance are not
started by this handoff. Do not infer authorization to start them.

## Core architecture decisions

- `expiresAtWorldTick` is the sole lifetime field; default TTL is 18,000 fixed
  simulation ticks. Pause and process downtime do not advance it.
- The service owns complete <=1,024 current-live metadata and the exact-ID
  expiry index. Expired ID history is not retained, but allocator high-water
  never moves backward and IDs are never reused.
- Task 4 stores generic opaque bytes only. A service-issued ticket plus a
  backend-private verified proof is required before DTO eviction.
- Dirty or unproved pages remain resident and pinned. Cleanup may fail or lag
  without changing semantic death or permitting resurrection.
- No second repository, permanent dormant DTO history, catalog/refcount/GC,
  maintenance overlay, database, or wall-clock expiry exists.

## Modified Task 6B files

- `engine/src/main/java/com/overlord/worlditem/LogicalWorldItemService.java`
- `engine/src/main/java/com/overlord/worlditem/WorldItemExpiryIndex.java`
- `engine/src/main/java/com/overlord/worlditem/WorldItemPageCache.java`
- `engine/src/main/java/com/overlord/worlditem/api/WorldItemActivationResult.java`
- `engine/src/main/java/com/overlord/worlditem/api/WorldItemHibernateResult.java`
- `engine/src/main/java/com/overlord/worlditem/api/WorldItemLiveMetadata.java`
- `engine/src/main/java/com/overlord/worlditem/api/WorldItemLiveState.java`
- `engine/src/main/java/com/overlord/worlditem/api/WorldItemPageCachePolicy.java`
- `engine/src/main/java/com/overlord/worlditem/api/WorldItemPagingMetrics.java`
- the focused engine/game Task 6B test files, the projection atomicity
  regression, and Task 6B report/plan files

The shared worktree also contains earlier Phase 15 changes; this list is the
Task 6B scope, not a claim that other dirty files belong to Task 6B.

## Verification

- Task 6B engine focused: 61/61 GREEN; real backend capability: 2/2 GREEN.
- Affected engine WorldItem/inventory/interaction: 227/227 GREEN.
- Affected game WorldItem/inventory/interaction/projection: 349/349 GREEN.
- Task 6A TTL/page/backend subset remains 59/59 GREEN; the combined selector
  including the new Task 6B backend cleanup case is 60/60 GREEN.
- `git diff --check`: exit 0, with only existing line-ending notices.
- Static audits: no direct filesystem or wall-clock lifecycle access; no
  prohibited catalog/refcount/GC/database/maintenance/second repository.

The production reviews reported, in order, 7 Critical / 4 Important / 3 Minor,
4 Critical / 5 Important / 3 Minor, 2 Critical / 1 Important / 2 Minor, and
1 Critical / 0 Important / 1 Minor. Every Critical and Important was converted
into a reachable focused regression and addressed before the verification
above. The final independent review returned **0 Critical / 0 Important / 0
Minor — READY**.

## Known risks and interfaces not to break

- Task 6C must restore checkpoint world tick and allocator before publishing
  pages, and must not select duplicate IDs by load order.
- Task 6D must add the already-planned same-service projection callback
  reentrancy guard without weakening exact rollback.
- Task 4 remains generic; it must not interpret WorldItem IDs, ItemStacks,
  expiry, ownership, or allocator semantics.
- Task 6B is closed. Do not enter Task 6C without separate explicit approval.

## Final diff and suggested handoff text

The shared repository-wide tracked `git diff --stat` reports 45 files changed,
4,637 insertions, and 610 deletions. It includes all dirty tracked Phase 15 work
and excludes untracked files; see the Task 6B modified-file list above for
scope ownership.

- Suggested commit: `feat: add bounded world item dormant paging`
- Suggested PR title: `Phase 15: add bounded WorldItem dormant paging`
- Suggested PR summary: bounded exact-tick TTL lifecycle, authenticated
  durable-before-evict paging, stable-ID activation, bounded cleanup/cache
  state, and failure-safe rollback; Task 6C intentionally not started.
