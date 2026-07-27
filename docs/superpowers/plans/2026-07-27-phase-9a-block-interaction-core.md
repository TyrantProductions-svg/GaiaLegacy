# Phase 9A Block Interaction Core Plan

## Baseline

- Branch: `feat/block-interaction-core`
- Base: `origin/main@078067e`
- Phase 8 merge divergence: `0/0`
- Baseline: `clean test build` successful, 938 tests

## Gate 9A.1 — mode, target, selection, view

1. Add failing contract tests for game-mode edges, non-cancellable exactly-once
   notifications, and same-step cancellation.
2. Add the Phase 6-to-Phase 7 raycast adapter and test body-authoritative origin,
   camera direction, ID/face mapping, miss, and unloaded target rejection.
3. Add `CreativeSelection` and immutable `BlockInteractionViewModel` snapshots.
4. Add mouse-button state to the fixed input snapshot with focus-loss and catch-up
   edge tests.

## Gate 9A.2 — deterministic break session

1. Add tests for required time and 10/60/144/240 render-frame schedules feeding the
   same 1/60 fixed steps.
2. Add break-session state and every cancellation condition.
3. Verify progress/crack changes never call world mutation or dirty Chunk state.
4. Add survival and creative policies, including hardness-zero behavior.

## Gate 9A.3 — logical world items

1. Add spawn/reserve/commit/rollback result-contract tests.
2. Implement one main-thread logical backend with one stable-ID namespace and
   immutable snapshots.
3. Test persistence, partial extraction, pickup delay metadata, terminal
   idempotency, rejected capacity, and mutable-state isolation.

## Gate 9A.4 — break/place transactions

1. Test every reservation rejection before implementing coordinators.
2. Implement survival break drop allocation and spawn-capacity reservation before
   world mutation; test exact conservation and rollback.
3. Implement placement validation and extraction reservation before mutation.
4. Treat mutation-applied dispatch failures as committed writes and commit item
   reservations once; never retry the block write.

## Gate 9A.5 — composition and integration

1. Add repository-backed `BlockWorldAccess` and synchronous event-publisher
   composition; test exact event order and boundary dirty revisions.
2. Wire interaction into `GameBootstrap`, `GameContext`, and the first/held-only
   fixed-step flow without renderer changes.
3. Add architecture scans for the unique raycast, mutation service, canonical stack,
   single world-item store, and forbidden direct gameplay `World.setBlock` calls.
4. Update architecture and `phase-09a-handoff.md`.
5. Run focused tests after every change, then `clean test build`, packaged-resource
   checks, `git diff --check`, repository hygiene checks, and Windows interactive
   acceptance. Record macOS as not run unless actually performed.

