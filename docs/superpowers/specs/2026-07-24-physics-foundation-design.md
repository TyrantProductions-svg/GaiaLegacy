# Phase 6 Physics Foundation Design

**Status:** Approved in conversation on 2026-07-24

**Branch:** `refactor/physics-foundation`

**Base:** `origin/main` at `ad02717`

## Context

GaiaLegacy is intentionally building its lower-level systems in this order:

```text
0 -> 1 -> 2 -> 3 -> 6 -> 7 -> 8 -> 9 -> 4 -> 5 -> 10 -> 11 -> 12
```

Phase 6 therefore builds directly on the Phase 3 main branch. It must provide
stable physics and raycast interfaces for later player interaction phases
without assuming that Phases 4 or 5 already exist, and it must leave advanced
rigid-body work to Phase 11.

The current `PhysicsManager` treats the Camera as the player body, performs
overlap checks only at final positions, and combines gravity, player collision,
step-up, spawn placement, and grounded state. Phase 1 already provides a
1/60-second fixed clock, but the current maximum of five steps per frame cannot
simulate 10 FPS without dropping time.

## Goals

- Replace player-specific `PhysicsManager` behavior with reusable collision,
  character-controller, raycast, and simple rigid-body foundations.
- Use continuous swept collision instead of final-position overlap tests.
- Keep authoritative physics state separate from interpolated render state.
- Preserve deterministic 1/60-second simulation at 10, 60, 144, and 240 FPS.
- Support stable ground, walls, ceilings, corners, high-speed falls, Chunk
  boundaries, spawn penetration recovery, noclip, one-block stepping, and
  one-block ground snapping.
- Provide one shared shape-aware `BlockRaycast` service for later interaction
  systems.
- Allow a `PhysicsBody` to drive simple falling objects.

## Non-goals

- Body-to-body collision.
- Rotated collision shapes or angular integration.
- Constraints, joints, contact manifolds, islands, or a complete solver.
- Bullet, Jolt, or another external physics engine.
- Moving voxel ships.
- New player interaction behavior, block editing, visual effects, UI, or
  automatic world streaming.
- Phase 11's advanced rigid-body implementation.

## Architectural decision

Phase 6 uses one shared static-voxel collision kernel with separate character
and generic-body integration layers:

```text
World + BlockCollisionShapeResolver
                 |
          CollisionWorld
          /      |      \
   sweep/slide  raycast  depenetration
       |          |          |
PlayerController  BlockRaycast  PhysicsWorld
       |                          |
 player PhysicsBody          generic PhysicsBody
```

This keeps step-up, ground snap, jump, and noclip out of generic rigid-body
integration while ensuring that player movement, falling bodies, and raycasts
use the same block shape definitions and coordinate rules.

## Package and type boundaries

All reusable physics types live under `engine/src/main/java/com/overlord/physics`.
The `engine` module remains independent of `game`.

### `Aabb`

`Aabb` is an immutable axis-aligned box represented by six finite floats:

```java
public record Aabb(
        float minX, float minY, float minZ,
        float maxX, float maxY, float maxZ)
```

The constructor rejects non-finite values and any axis where `min > max`.
Touching faces are not penetration; strict positive overlap is required for
`intersects`. The API provides translated bounds, swept broad-phase bounds,
dimensions, and overlap depths without exposing mutable vectors.

Body colliders are local-space AABBs. A body position translates the local
collider into world space. The player position is the center of the feet and
uses:

```text
[-Player.WIDTH / 2, 0, -Player.WIDTH / 2]
    to
[ Player.WIDTH / 2, Player.HEIGHT, Player.WIDTH / 2]
```

### `BlockCollisionShape`

`BlockCollisionShape` owns an immutable ordered list of local-space AABBs.
Phase 6 supplies:

- `EMPTY` for air;
- `FULL_CUBE` for `[0,0,0] -> [1,1,1]`;
- a factory for multiple sub-boxes, reserved for later slabs and steps.

Sub-boxes must remain within the unit block. The shape list preserves declared
order to make tie-breaking deterministic.

### `BlockCollisionShapeResolver`

This constructor-injected functional interface maps a stored block ID to its
shape:

```java
BlockCollisionShape shapeFor(byte blockId);
```

The Phase 6 default maps ID `0` to `EMPTY` and every other ID to `FULL_CUBE`.
Later data-driven block phases may replace this mapping without changing
`CollisionWorld`, `PlayerController`, or `BlockRaycast`.

### `SweepResult`

`CollisionWorld.sweep` returns `Optional<SweepResult>`. A result contains:

- normalized time of impact in `[0,1]`;
- contact normal as primitive components;
- contact point as primitive components;
- block coordinates;
- the world-space block sub-shape that was hit.

The result is immutable. Convenience methods copy normal and contact point into
caller-provided JOML vectors; it never returns an internally mutable vector.

### `MotionResult`

`MotionResult` is the immutable result of a complete sweep-and-slide operation.
It contains the final position as primitive components, the applied
displacement, and the ordered immutable list of `SweepResult` contacts. Both
`PhysicsWorld` and `PlayerController` use these contacts to update velocity and
ground/ceiling state; neither implements a second slide loop.

### `CollisionWorld`

`CollisionWorld` is constructed with `World` and
`BlockCollisionShapeResolver`. It is CPU-only and provides:

```java
Optional<SweepResult> sweep(
        Aabb localCollider,
        Vector3fc position,
        Vector3fc displacement);

MotionResult moveAndSlide(
        Aabb localCollider,
        Vector3fc position,
        Vector3fc displacement,
        int maxIterations);

boolean overlapsSolid(Aabb worldBounds);

Optional<Vector3f> depenetrate(
        Aabb localCollider,
        Vector3fc position,
        int maxIterations);
```

`moveAndSlide` always performs at most `maxIterations` calls to `sweep` and
returns every accepted contact in order. Missing Chunks and out-of-height block
reads are air through the existing `World` contract.

### `MassProperties`

`MassProperties` is immutable and provides:

```java
MassProperties dynamic(float mass);
MassProperties staticBody();
float mass();
float inverseMass();
```

Dynamic mass must be finite and greater than zero. A static body has
`inverseMass == 0`. Mass and inverse mass cannot be set independently.

### `ForceAccumulator`

`ForceAccumulator` owns mutable accumulated force, impulse, and reserved
torque vectors. It supports:

```java
void applyForce(Vector3fc force);
void applyImpulse(Vector3fc impulse);
void applyTorque(Vector3fc torque);
```

Physics integration consumes and clears all accumulators once per fixed step.
Force changes velocity by `force * inverseMass * dt`; impulse changes velocity
by `impulse * inverseMass`. Torque is accepted and cleared, but does not rotate
the body in Phase 6.

### `PhysicsBody`

`PhysicsBody` owns:

- previous and current position;
- linear velocity;
- reserved angular velocity;
- `MassProperties`;
- gravity scale;
- restitution;
- friction;
- local `Aabb` collider;
- active and sleeping flags;
- one `ForceAccumulator`.

For Phase 6, the body's `transform` is this previous/current translational
state. Rotation is deliberately absent until angular integration is introduced;
the reserved angular velocity does not create a second transform authority.

Position and velocity accessors copy into caller-provided vectors. Mutators copy
their inputs so neither physics nor render code can retain aliases to internal
state. Restitution and friction are finite values in `[0,1]`; gravity scale is
finite.

At the start of each fixed step, `beginStep()` copies current position to
previous position. Teleports and initial spawn set both positions together.
Interpolated position is a pure read:

```text
lerp(previousPosition, currentPosition, alpha)
```

It never modifies either physics state.

### `PhysicsWorld`

`PhysicsWorld` owns an insertion-ordered set of generic bodies and one
`CollisionWorld`. It does not own a clock or accumulator. The only production
step is supplied by the Phase 1 loop at exactly `1/60` second.

For each active, awake, dynamic body:

```text
beginStep()
velocity += force * inverseMass * dt
velocity += gravity * gravityScale * dt
velocity += impulse * inverseMass
position = sweepAndSlide(position, velocity * dt)
```

Collisions remove or reflect inward normal velocity using restitution and
attenuate tangential velocity using friction. Static, inactive, and sleeping
bodies do not integrate. The player body is not added to `PhysicsWorld`;
`PlayerController` is its sole integrator.

### `PlayerController`

`PlayerController` owns the player `PhysicsBody`, player grounded/noclip state,
and one `CollisionWorld`. It accepts normalized movement intent, jump edges,
held ascend/descend state, and a fixed delta.

It implements:

- gravity and terminal downward velocity;
- continuous sweep-and-slide;
- grounded state from upward contact normals;
- jump only while grounded;
- ceiling cancellation from downward contact normals;
- one-block step-up;
- one-block conditional ground snap;
- spawn and noclip-exit depenetration;
- noclip motion without gravity or collision.

`setNoclip(false)` succeeds only after a collision-free placement is found. If
local depenetration fails, the controller searches upward in deterministic
one-block increments up to the world height. If no valid placement exists, it
remains in noclip rather than enabling collision in an invalid state.

### `PlayerManager`

`PlayerManager` keeps input and view responsibilities:

- derive normalized horizontal movement intent from Camera orientation;
- apply mouse look;
- track double-Space edges in fixed-step counts;
- pass player intent to `PlayerController`.

The double-Space window is 15 fixed steps, exactly 0.25 seconds at 60 Hz.
The first press remains a normal jump. A second press inside the window toggles
noclip and does not issue a second jump. Entering noclip clears gravity velocity
and grounded state. In noclip, Space ascends and Shift descends.

`PhysicsManager` is removed from production code and tests. No deprecated
facade or ServiceLocator registration is added.

### `BlockRaycast` and `BlockRaycastHit`

`BlockRaycast` is constructed from the same `World` and shape resolver as
`CollisionWorld`:

```java
Optional<BlockRaycastHit> cast(
        Vector3fc origin,
        Vector3fc direction,
        float maxDistance);
```

`BlockRaycastHit` contains:

- hit block coordinates and stored block ID;
- adjacent placement coordinates;
- hit normal;
- exact hit point;
- distance from origin.

All vector data is immutable primitive state with copy-to-destination helpers.
Direction is copied and normalized. Zero-length direction, negative distance,
and non-finite inputs are rejected.

## Collision algorithms

### Swept AABB

For every movement:

1. Translate the local collider to its starting world bounds.
2. Union start and end bounds to obtain a swept broad phase.
3. Convert broad-phase minima/maxima with `floor` and iterate every candidate
   block coordinate, including negative coordinates and Chunk boundaries.
4. Resolve the block's ordered local sub-shapes and translate them to world
   bounds.
5. Use slab entry/exit times on X, Y, and Z to calculate continuous time of
   impact.
6. Select the earliest valid hit.

Equal-time hits use a fixed axis priority and then block `(x,y,z)` plus
sub-shape order. The axis priority is Y, then X, then Z; block priority is
ascending X, then Y, then Z, followed by declared sub-shape order. This
prevents hash or iteration order from changing movement. The collision skin
uses `GameConfig.Physics.COLLISION_TOLERANCE`.

### Sweep and slide

The mover advances to immediately before the earliest contact, removes the
remaining displacement component directed into the contact normal, and repeats
for at most four contacts. The same inward component is removed from velocity.
This produces:

- grounded contact on positive-Y normals;
- ceiling contact on negative-Y normals;
- wall sliding instead of complete diagonal stops;
- stable corners without unchecked iteration.

High-speed falls remain continuous because the broad phase covers the complete
fixed-step displacement rather than testing only its endpoint.

### Step-up

When a grounded player encounters a horizontal obstruction:

1. Calculate the normal slide result.
2. Sweep upward by at most one block.
3. Sweep the intended horizontal displacement from the raised position.
4. Sweep downward to a standable positive-Y surface.
5. Select the stepped route only when it is collision-free at final placement
   and produces more horizontal progress than normal sliding.

The Camera does not receive the step directly; interpolation renders the
previous-to-current foot movement.

### Ground snap

Ground snap sweeps down by at most one block only when all conditions hold:

- the preceding physical state was grounded;
- no jump was requested this step;
- vertical velocity is not upward;
- horizontal movement intent is non-zero.

A positive-Y hit moves the feet to the surface, clears downward velocity, and
keeps grounded. Airborne players and active jumps are never pulled down.

### Penetration recovery

CollisionWorld collects strict overlaps and applies deterministic minimum
translation vectors, preferring upward movement for equal-depth choices. It
repeats for a bounded number of iterations and confirms the final collider is
clear.

Spawn and noclip exit additionally search upward for the nearest clear full
player volume if bounded local recovery cannot resolve the overlap. Failure to
find a safe location is explicit; collision is not enabled in penetration.

### Block raycast

Raycast first traverses voxel cells with 3D DDA. Each non-empty shape is then
tested by exact ray-versus-AABB slabs. The nearest valid sub-shape hit determines
the normal and point. The adjacent placement coordinate is the hit block plus
the integer surface normal. Ties follow the same deterministic axis and block
ordering used by sweep. If the origin is already inside a solid sub-shape, the
hit distance is zero, the point is the origin, and the normal opposes the
dominant absolute direction component using Y, X, Z tie priority.

## Fixed step and render interpolation

`GameBootstrap` keeps the exact fixed step:

```text
1.0 / 60.0 seconds
```

It raises `MAX_FIXED_STEPS_PER_FRAME` from 5 to 8. A 10 FPS render frame
contains six physics steps; the old limit would drop one step every frame and
make the game systematically slower. Eight steps cover the required 10 FPS
case while retaining a spiral-of-death limit. The existing 0.25-second frame
delta clamp remains.

`FixedStepClock` adds:

```java
double interpolationAlpha();
```

This returns `remainderSeconds / fixedStepSeconds`, clamped to `[0,1)`.

The RUNNING frame order is:

1. poll input and update look orientation;
2. advance `FixedStepClock`;
3. consume one fixed input snapshot when at least one step is due;
4. for each fixed step:
   - `PlayerManager.fixedUpdate`, using the full snapshot for the first step
     and a held-keys-only snapshot for later catch-up steps;
   - `PhysicsWorld.step`;
   - `ModuleManager.updateAll`;
   - `EventBus.processAll`;
5. calculate interpolation alpha;
6. copy the player's interpolated feet position plus `EYE_HEIGHT` into Camera;
7. render.

Camera position is render output only. Collision, physics, and gameplay raycast
origins use current authoritative body state, not interpolated Camera position.
Camera orientation remains the source for look direction.

`InputSnapshot` adds a held-only copy operation that preserves keys currently
down and clears pressed edges. This prevents one Space edge from being observed
six times during a 10 FPS catch-up frame and keeps double-tap behavior
independent of render rate.

## Loading and composition

`WorldLoadResult` changes from an implicit Camera/top-of-body spawn position to
an explicit player feet position at the center of the spawn block:

```text
(spawnX + 0.5, highestSolidY + 1, spawnZ + 0.5)
```

After loading:

1. GameBootstrap/GameLoop teleports the player body so previous and current
   states match.
2. PlayerController performs penetration recovery.
3. Camera synchronizes to feet plus `EYE_HEIGHT`.
4. Normal fixed simulation starts only after the existing initial Chunk mesh
   readiness gate.

GameBootstrap constructs one default block shape resolver, `CollisionWorld`,
`BlockRaycast`, player body, `PlayerController`, and `PhysicsWorld`, then places
them explicitly in `GameContext`. No global locator dependency is introduced.

All physics and player updates remain on the main fixed-update thread.
Collision code is CPU-only and performs no GLFW, OpenGL, or GPU work.

## Determinism and numerical policy

- Fixed physics delta is always `1/60` second in production.
- All movement inputs are normalized before speed is applied.
- Candidate block, sub-shape, body, and collision tie orders are deterministic.
- Missing blocks are air and reads do not create Chunks.
- Physics rejects non-finite position, displacement, force, impulse, mass, and
  material values.
- Zero displacement produces no sweep hit and no state mutation beyond the
  required previous/current step snapshot.
- Skin/tolerance is applied consistently and is never accumulated as velocity.
- Mutable JOML values never cross public ownership boundaries without copying.

## Test strategy

### Math tests

- strict AABB intersection, containment, touching faces, translation, and
  swept broad phase;
- swept ground, wall, ceiling, corner, zero-displacement, and high-speed hits;
- deterministic tie normals;
- mass/inverse-mass validation;
- force and impulse integration;
- interpolation alpha and previous/current separation.
- held-only input snapshots preserve down state and clear press edges.

### CollisionWorld tests

- air and full-cube resolution;
- multiple sub-AABB shapes;
- cross-Chunk coordinates at X/Z 15 and 16;
- negative coordinates;
- missing Chunk reads;
- bounded penetration recovery;
- no endpoint tunneling during high-speed fall.

### PlayerController tests

- stable ground and edge standing;
- wall and corner collision;
- diagonal wall sliding;
- ceiling stops upward velocity;
- one-block step-up without blocked horizontal progress;
- one-block ground snap without transient airborne state;
- snap disabled during jump and upward motion;
- terminal-velocity fall onto ground;
- spawn inside a block;
- noclip enter, movement, and safe exit;
- failed noclip exit remains noclip;
- Chunk-boundary movement without jitter.

### BlockRaycast tests

- nearest full cube;
- exact hit point and normal;
- adjacent placement coordinates;
- multiple sub-AABB shape selection;
- cross-Chunk and negative coordinates;
- origin inside a shape;
- zero direction, invalid distance, miss, and maximum distance.

### Fixed-rate acceptance tests

A deterministic headless fixture feeds the real `FixedStepClock` frame deltas
for 10, 60, 144, and 240 FPS over the same wall-clock duration. It compares:

- horizontal travel distance;
- jump apex;
- landing position and grounded state.

All rates must produce the same fixed-step count and results within a documented
floating-point epsilon. A separate low-frame/high-speed scenario confirms no
terrain tunneling.

### Game integration tests

- no production reference to `PhysicsManager`;
- GameBootstrap explicit composition;
- fixed order of player, generic physics, modules, and events;
- pressed input edges are delivered only to the first catch-up step;
- maximum fixed steps raised to eight;
- Camera interpolation after fixed updates;
- render interpolation does not modify the body;
- spawn semantics use feet position;
- physics packages contain no OpenGL/GLFW calls.

## Migration and protected interfaces

- Remove `PhysicsManager` and migrate all game and test construction.
- `PlayerManager.fixedUpdate(float, InputSnapshot)` remains the game-loop input
  entry point, but delegates physical movement to `PlayerController`.
- Keep `FixedStepClock.advance`, `fixedStepSeconds`, and `remainderSeconds`;
  add interpolation without changing existing semantics.
- Preserve the Phase 3 `World`/`ChunkRepository` non-allocating read contract.
- Preserve Phase 3 Chunk mesh and renderer ownership.
- Preserve Java 17, OpenGL 4.1, GLSL 410, Gradle Wrapper, and main-thread GPU
  constraints.
- Phase 4/5 must consume `BlockRaycast`; they must not introduce a second
  voxel raycast implementation.
- Phase 11 may extend angular integration and body-body collision without
  changing Phase 6's static collision and body state ownership contracts.

## Known risks

- Full one-block ground snapping is intentionally game-like and may require
  tuning after visual smoke tests.
- The default resolver treats every non-air block as a full cube until block
  definitions gain collision-shape data.
- A maximum of eight fixed steps still drops excess whole simulation time
  during frames longer than roughly 133 ms; this is the deliberate
  spiral-of-death boundary. The required 10 FPS case does not drop time.
- Phase 6 stores angular velocity and accepts torque but deliberately does not
  rotate bodies.
- Windows interactive movement and native macOS behavior require explicit
  smoke verification and must not be inferred from headless tests.

## Handoff

Phase 6 will create `docs/agent-handoffs/phase-06-handoff.md` with completed and
unfinished work, architecture decisions, modified files, exact test results,
known risks, protected interfaces, final diff stat, suggested commit message,
and suggested pull request title/description.
