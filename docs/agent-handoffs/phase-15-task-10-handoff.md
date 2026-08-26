# Phase 15 Task 10 handoff

## Completed work

Task 10 adds checked `SimulationOrigin`/`RenderOrigin`, explicit
`SpatialQueryResult`, availability-first origin-aware collision/raycast seams,
bounded `UnknownSpaceBarrier`, and atomic owner-thread participant
initialization/rebasing. Player, physics, WorldItem, camera, particle, transient,
Chunk-render, and Gaia raycast seams are ready for Task 11 composition.

## Unfinished work

Task 10 is complete and independently reviewed. Task 11 live session
composition is not authorized by this handoff and must not be started without
separate explicit approval.

## Core architecture decisions

- Canonical global coordinates remain `ChunkKey` plus canonical local doubles;
  floats are resident-local only and conversions are checked.
- UNKNOWN and FAILED space never becomes AIR. Queries expose status and the
  canonical unavailable key before voxel sampling.
- Simulation and render origins are distinct types but one atomic publication
  always names the same Chunk.
- Rebase uses side-effect-free preparation, concrete prebuilt replacements,
  bounded assignment-only commits, and final dual-origin publication.
- Physics current/previous endpoints and interpolation move together. Stable
  IDs, WorldItem canonical state, Chunk ownership/revisions, worker payloads,
  velocities, and GPU mesh identity remain unchanged.
- Legacy zero-origin APIs remain available until Task 11 explicitly initializes
  and installs the origin-aware participants.

## Modified Task 10 files

- engine physics: `SimulationOrigin`, `SpatialQueryResult`, `BlockRaycast`,
  `CollisionWorld`, `PhysicsBody`, `PhysicsWorld`, `PlayerController`;
- engine rendering/mesh: `RenderOrigin`, `Camera`, `ChunkRenderObject`,
  `ParticleSystem`, `ChunkMeshManager`;
- game: `UnknownSpaceBarrier`, `SimulationOriginCoordinator`,
  `GaiaBlockRaycastService`, `TransientBlockVisualSystem`,
  `PhysicalWorldItemSystem`;
- focused tests, active Phase 15 design/plan, Task 10 report, and this handoff.

## Test commands and results

- Expanded Task 10 focused: **185/185 GREEN**.
- Affected game proportional: **416/416 GREEN**.
- Engine renderer plus frozen addressing/repository/mesh: **477/477 GREEN**.
- Full engine physics: **148/150 GREEN**; only two known legacy
  `BlockRaycastTest` fixtures fail during illegal `Integer.MIN_VALUE` world
  setup before raycast.
- `git diff --check`: PASS with line-ending warnings only.
- Gradle daemon shutdown/status: no running daemon.
- Cumulative tracked Phase 15 diff stat: 81 files, 8,731 insertions, 916
  deletions; this includes accepted Tasks 1-9 and excludes untracked files.
- Interactive `:game` and macOS smoke were not run.

## Independent review

Initial: 1 Critical / 7 Important / 0 Minor. All findings and later
composition gaps were closed through RED-driven minimal repairs.

Final: **0 Critical / 0 Important / 0 Minor — READY for Task 11**.

## Interfaces Task 11 must not break

- Call `SimulationOriginCoordinator.initializeParticipants()` once on the
  owner thread before origin-aware gameplay, then use `rebase()` for changes.
- Include all concrete prepared participants; never update one origin consumer
  ad hoc or publish simulation/render origins separately.
- Use the origin-aware PlayerController, PhysicsWorld, WorldItem, collision,
  and Gaia raycast paths after initialization; unavailable results fail closed.
- Preserve the Task 7 desired epoch contract and Task 8/9 bounded scheduler,
  owner publication, mesh upload, and GPU destruction budgets.
- Keep Task 6 WorldItem lifecycle/TTL/stable-ID authority unchanged.
- Do not add a second Chunk/WorldItem authority, global mutable float position,
  background database, or worker-side GPU/repository mutation.

## Suggested integration text

- Suggested commit: `feat: add checked origins and atomic rebasing`
- Suggested PR title: `Phase 15: add UNKNOWN barriers and atomic origin rebasing`
- Suggested PR description: delivers checked global/local conversion,
  availability-first spatial queries, bounded UNKNOWN barriers, and concrete
  prepare-all/publish-last origin participants ready for Task 11 composition.
