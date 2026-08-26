# Phase 15 Task 7 handoff

## Completed work

Task 7 implements the approved deterministic Chunk streaming policy/controller
boundary. `GlobalPosition` is an engine-owned immutable coordinate value only.
Game-owned immutable observation and decision records isolate repository state
from policy computation. The controller emits desired sets, desired epoch,
admissions, cancellations, rejections, and unload candidates without executing
or scheduling any work.

The exact production radii are simulation 2, render 4, preload 5, and unload 7.
Priority is desired class, squared Chunk distance, then canonical x/z. Epoch
advances only when desired-set identity changes. Resident/request completion
does not consume outstanding capacity, and materialized desired sets are hard
bounded to radius 7/225 keys.

## Unfinished work

Task 7 is complete. Task 8 is not authorized by this handoff. Task 10 owns all
origin/rebase and global-to-resident-local conversion work.

## Core architecture decisions

- `GlobalPosition` contains only safe `ChunkKey`, canonical finite local X/Z,
  and finite Y. Signed zero is normalized for deterministic record identity.
- `ChunkStreamingObservation` and `ChunkStreamingDecision` defensively own
  immutable values; neither stores repositories, executors, callbacks, or
  mutable collections.
- `outstandingRequests = requested - resident`, so completion snapshots do not
  consume queue slots or receive cancellation.
- Desired-set identity, not update/frame count or observation completion,
  controls the monotonic desired epoch.
- Eager desired-set materialization fails closed above radius 7; the maximum
  materialized footprint is 225 keys per set.
- The controller has no IO, generation, save, repository mutation/publication,
  worker scheduling, OpenGL/GPU, or Task 10 origin responsibility.
- Persistence/save formats are unchanged.

## Modified files

- `engine/src/main/java/com/overlord/voxel/GlobalPosition.java`
- `engine/src/test/java/com/overlord/voxel/GlobalPositionTest.java`
- `game/src/main/java/com/gaia/world/streaming/ChunkStreamingPolicy.java`
- `game/src/main/java/com/gaia/world/streaming/ChunkDesiredSets.java`
- `game/src/main/java/com/gaia/world/streaming/ChunkPriority.java`
- `game/src/main/java/com/gaia/world/streaming/ChunkStreamingObservation.java`
- `game/src/main/java/com/gaia/world/streaming/ChunkStreamingDecision.java`
- `game/src/main/java/com/gaia/world/streaming/ChunkStreamingController.java`
- `game/src/test/java/com/gaia/world/streaming/ChunkStreamingPolicyTest.java`
- `game/src/test/java/com/gaia/world/streaming/ChunkStreamingControllerTest.java`
- Phase 15 design, plan, report, progress ledger, and this handoff

The worktree includes accepted earlier Phase 15 changes. No Git mutation or
`dist/` change was made.

## Test commands and results

- Focused Task 7: 14/14 GREEN (engine 3, game 11).
- Repository/session/product-loop proportional matrix: 191/191 GREEN
  (engine 98, game 93).
- Same-package streaming plus frozen WorldItem boundary: 15/15 GREEN.
- Static prohibited-dependency scan: PASS.
- Shared accepted Phase 15 tracked `git diff --stat`: 61 files changed,
  6,919 insertions, 694 deletions. This is an aggregate accepted-worktree count;
  untracked Task 7 and earlier Phase 15 files are excluded.
- Independent review: initial 0 Critical / 2 Important / 1 Minor; final
  rereview 0 Critical / 0 Important / 0 Minor — READY.
- Full repository build and interactive `:game` smoke were not run; Task 7 used
  the approved proportional boundary.

## Known risks and interfaces not to break

- Do not make epoch advance on every update or on observation-only changes.
- Do not treat completed resident keys as outstanding requested work.
- Do not remove the pre-enumeration desired-footprint bound.
- Do not add repository mutation, scheduling, IO, or GPU work to the controller.
- Do not add SimulationOrigin, RenderOrigin, rebase, float conversion, camera
  precision, or physics-origin migration to `GlobalPosition`.
- macOS/Apple Silicon smoke was not observed and must not be claimed.

## Suggested integration text

- Suggested commit: `feat: add deterministic chunk streaming policy`
- Suggested PR title: `Phase 15: add bounded Chunk streaming controller`
- Suggested PR summary: introduces the canonical global-position value and a
  pure immutable 2/4/5/7 streaming decision boundary with identity-driven
  epochs, deterministic bounded admission, hysteresis, teleport cancellation,
  and no IO/repository/GPU side effects.
