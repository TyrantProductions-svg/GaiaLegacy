# Phase 15 Task 10 report

## Result

Task 10 implements checked simulation/render origins, availability-first
spatial queries, bounded UNKNOWN-space barriers, and one owner-thread
prepare-all/commit-all/publish-last origin transaction. It supplies concrete
prepared participants for player physics, general physics, WorldItem
projections, camera, particles, installed Chunk renders, and render-local
transients. Task 11 production-session composition was not started.

## Contract delivered

- `SimulationOrigin` and `RenderOrigin` are distinct immutable values backed by
  one checked safe `ChunkKey`. `SimulationOrigin` is the checked conversion
  boundary between canonical `GlobalPosition` and small resident-local floats.
- Origin-aware raycast and collision APIs return explicit `AVAILABLE`,
  `UNKNOWN`, or `FAILED` and check Chunk availability before voxel/shape
  sampling. Legacy zero-origin APIs remain compatible.
- Collision geometry stays resident-local while result block identities remain
  canonical global coordinates. Exclusive AABB maxima allow retreat from an
  exactly touched unavailable neighbor without allowing entry into it.
- `UnknownSpaceBarrier` is pure and bounded: movement visits each crossed Chunk
  up to the hard limit, teleport radius is at most 7, `FAILED` dominates
  `UNKNOWN` deterministically, and blocked decisions preserve an explicit
  prior-safe position.
- `SimulationOriginCoordinator` is owner-thread and reentrancy guarded. It
  prepares every participant before any commit, rejects mismatched simulation
  and render keys, publishes both origins last, and exposes an idempotent atomic
  participant initialization transaction for the initial zero origin.
- Concrete participants precompute validation and replacements. Commit changes
  only prepared body endpoints, camera position, particle states, WorldItem
  presentation/body state, installed render references, and committed origins;
  canonical WorldItem IDs/DTOs/revisions/velocity, Chunk keys/revisions, worker
  results, and GPU mesh identities do not change.
- `PhysicsWorld`, `PlayerController`, and `PhysicalWorldItemSystem` consistently
  use their committed simulation origin and fail closed on unavailable terrain.
  `GaiaBlockRaycastService` exposes an origin-aware adapter for Task 11 without
  changing `GameSessionFactory` in Task 10.

## Verification

- Tests-only RED failed only on the approved missing Task 10 contracts.
- Expanded concrete focused suites: **185/185 GREEN**.
- Affected game save/session/streaming/WorldItem/interaction packages:
  **416/416 GREEN**.
- Affected engine renderer plus frozen addressing/repository/mesh subsets:
  **477/477 GREEN**.
- Full engine physics: **148/150 GREEN**. The two failures are legacy
  `BlockRaycastTest` fixtures that attempt `world.setBlock(Integer.MIN_VALUE,
  ...)`; the frozen safe-coordinate policy rejects setup before raycast. Task
  10 does not cause or bypass that boundary.
- `git diff --check`: PASS with line-ending warnings only.
- Gradle daemon stopped; `--status` reported no running daemon.
- Cumulative tracked Phase 15 `git diff --stat`: 81 files, 8,731 insertions,
  916 deletions. It includes accepted Tasks 1-9 and omits untracked Phase 15
  files, so it is not a Task 10-only delta.

The interactive `:game` and macOS/Apple Silicon smoke were not run and are not
claimed.

## Review

Initial independent review: **1 Critical / 7 Important / 0 Minor — NOT
READY**. RED-driven repairs closed non-zero-origin collision, exclusive-bound,
barrier traversal, prepared-commit, distant WorldItem coverage, and origin-key
consistency defects. Follow-up review found and closed camera/render prepared
participants, player/raycast composability, explicit prior-safe teleport, and
same-origin participant initialization.

Final fresh independent review: **0 Critical / 0 Important / 0 Minor — READY
for Task 11**.

## Scope and risks

- Task 11 must call `initializeParticipants()` once, then compose every
  participant at the owner fixed-step/frame boundary and install the
  origin-aware Gaia raycast adapter.
- Task 11 must convert player/save/drop/interaction values through the same
  committed origin and must not mix legacy and origin-aware paths after
  initialization.
- The all-real-participant session integration test is intentionally Task 11.
- Allocation instrumentation around prepared commits is a non-blocking test
  opportunity; structural inspection and behavioral atomicity tests are GREEN.
- The two obsolete `Integer.MIN_VALUE` raycast fixtures require a separate test
  migration and must not be used to weaken the frozen safe-coordinate policy.
- No Git mutation was performed. The cumulative accepted Phase 15 working tree
  and pre-existing untracked `dist/` were preserved.

## Suggested integration text

- Suggested commit: `feat: add checked origins and atomic rebasing`
- Suggested PR title: `Phase 15: add UNKNOWN barriers and atomic origin rebasing`
- Suggested PR summary: introduces checked simulation/render origins,
  availability-first collision and raycast results, bounded directional UNKNOWN
  barriers, prepared concrete rebase participants, and an owner-thread
  initialize/rebase transaction without starting Task 11 session composition.
