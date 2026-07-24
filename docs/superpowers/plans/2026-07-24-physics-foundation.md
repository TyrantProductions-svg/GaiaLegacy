# Phase 6 Physics Foundation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the Camera-owned player collision code with deterministic
1/60-second swept voxel collision, a reusable raycast service, interpolated
render state, and a minimal generic rigid-body foundation.

**Architecture:** `CollisionWorld` is the single static-voxel collision kernel
for the player, generic bodies, and block raycasts. `PlayerController` owns
character-only movement policy, while `PhysicsWorld` integrates ordinary
`PhysicsBody` instances; both use authoritative previous/current body state and
never read interpolated Camera position.

**Tech Stack:** Java 17, Gradle 8.5 Wrapper, JOML 1.10.5, JUnit Jupiter,
existing Phase 1 fixed loop, existing Phase 3 `World`/`ChunkRepository`.

## Global Constraints

- Start from `origin/main` at `ad02717` on `refactor/physics-foundation`.
- Keep Java 17 source and target compatibility; JDK 21 may run Gradle.
- Use the checked-in Gradle Wrapper only.
- Do not introduce Bullet, Jolt, another physics library, copied code, or
  third-party assets.
- Preserve the `engine` -> `game` module boundary; engine must not import Gaia
  classes.
- Physics is CPU-only. No GLFW, OpenGL, renderer, Mesh, Texture, Shader, or GPU
  work may enter `engine.physics`.
- Preserve OpenGL 4.1 / GLSL 410 compatibility and main-thread GPU ownership.
- Production physics uses exactly `1.0 / 60.0` second and no internal
  wall-clock accumulator.
- Keep the existing 0.25-second frame clamp; change the fixed catch-up limit
  from five to eight steps.
- Use constructor injection or explicit `GameContext`; do not expand
  `ServiceLocator`.
- Preserve non-allocating missing-Chunk reads and Phase 3 mesh lifecycle APIs.
- Follow strict RED -> GREEN -> REFACTOR. Do not add production behavior before
  observing the focused test fail for the expected reason.
- Keep each task in its own commit and run `git diff --check` before committing.
- Do not push, merge, or open a PR.

---

## File map

### Engine physics production

- Create `engine/src/main/java/com/overlord/physics/Aabb.java`: immutable bounds
  math.
- Create `engine/src/main/java/com/overlord/physics/SweepResult.java`: one
  immutable time-of-impact contact.
- Create `engine/src/main/java/com/overlord/physics/MotionResult.java`: final
  position, applied displacement, and ordered contacts.
- Create `engine/src/main/java/com/overlord/physics/BlockCollisionShape.java`:
  immutable local sub-box collection.
- Create
  `engine/src/main/java/com/overlord/physics/BlockCollisionShapeResolver.java`:
  stored-ID shape boundary.
- Create `engine/src/main/java/com/overlord/physics/CollisionWorld.java`:
  voxel broad phase, sweep, slide, overlap, and depenetration.
- Create `engine/src/main/java/com/overlord/physics/BlockRaycast.java`: DDA plus
  exact shape intersection.
- Create `engine/src/main/java/com/overlord/physics/BlockRaycastHit.java`:
  immutable interaction hit.
- Create `engine/src/main/java/com/overlord/physics/MassProperties.java`: dynamic
  and static mass invariants.
- Create `engine/src/main/java/com/overlord/physics/ForceAccumulator.java`:
  force, impulse, and reserved torque accumulation.
- Create `engine/src/main/java/com/overlord/physics/PhysicsBody.java`:
  authoritative previous/current transform and body properties.
- Create `engine/src/main/java/com/overlord/physics/PhysicsWorld.java`: generic
  body fixed integration.
- Create `engine/src/main/java/com/overlord/physics/PlayerController.java`:
  player sweep, step, snap, spawn recovery, and noclip.
- Delete `engine/src/main/java/com/overlord/physics/PhysicsManager.java` after
  all consumers migrate.

### Existing engine integration

- Modify `engine/src/main/java/com/overlord/core/PlayerManager.java`: convert
  input to player intent and fixed-step double-Space state.
- Modify
  `engine/src/main/java/com/overlord/core/input/InputSnapshot.java`: create a
  held-only snapshot.
- Modify
  `engine/src/main/java/com/overlord/core/time/FixedStepClock.java`: expose
  interpolation alpha.
- Modify `engine/src/main/java/com/overlord/renderer/Camera.java`: copy assigned
  render positions.
- Modify `engine/src/main/java/com/overlord/config/GameConfig.java`: physics
  iteration, step/snap, and double-tap constants.

### Game integration

- Modify `game/src/main/java/com/gaia/GameBootstrap.java`: explicit physics
  composition and eight-step catch-up.
- Modify `game/src/main/java/com/gaia/GameContext.java`: replace
  `PhysicsManager` with explicit new services.
- Modify `game/src/main/java/com/gaia/GameLoop.java`: first-step input edges,
  generic body stepping, spawn recovery, and Camera interpolation.
- Modify `game/src/main/java/com/gaia/world/WorldLoader.java`: return feet spawn.
- Modify `game/src/main/java/com/gaia/world/WorldLoadResult.java`: name the feet
  position contract.

### Tests and documentation

- Add focused test classes matching every production type.
- Migrate existing PlayerManager, bootstrap, loop, and world-loader tests.
- Create `docs/agent-handoffs/phase-06-handoff.md`.
- Update `docs/architecture/current-baseline.md` only for final physics and loop
  state.

---

### Task 1: Immutable collision value types

**Files:**
- Create: `engine/src/main/java/com/overlord/physics/Aabb.java`
- Create: `engine/src/main/java/com/overlord/physics/SweepResult.java`
- Create: `engine/src/main/java/com/overlord/physics/MotionResult.java`
- Create: `engine/src/test/java/com/overlord/physics/AabbTest.java`
- Create: `engine/src/test/java/com/overlord/physics/CollisionValueTest.java`

**Interfaces:**
- Consumes: JOML `Vector3fc`/`Vector3f`.
- Produces:
  - `Aabb.translated(Vector3fc)`, `sweptBounds(Vector3fc)`,
    `intersects(Aabb)`, and per-axis overlap depth.
  - immutable `SweepResult`.
  - immutable `MotionResult`.

- [ ] **Step 1: Write failing immutable-bounds tests**

```java
@Test
void touchingFacesDoNotIntersectButPositiveVolumeDoes() {
    Aabb left = new Aabb(0, 0, 0, 1, 1, 1);
    assertFalse(left.intersects(new Aabb(1, 0, 0, 2, 1, 1)));
    assertTrue(left.intersects(new Aabb(0.999f, 0, 0, 2, 1, 1)));
}

@Test
void sweptBoundsCoverStartAndEnd() {
    Aabb start = new Aabb(-0.3f, 0, -0.3f, 0.3f, 1.8f, 0.3f);
    assertEquals(
            new Aabb(-0.3f, -4, -0.3f, 2.3f, 1.8f, 0.3f),
            start.sweptBounds(new Vector3f(2, -4, 0)));
}

@Test
void invalidBoundsAreRejected() {
    assertThrows(
            IllegalArgumentException.class,
            () -> new Aabb(Float.NaN, 0, 0, 1, 1, 1));
    assertThrows(
            IllegalArgumentException.class,
            () -> new Aabb(2, 0, 0, 1, 1, 1));
}
```

- [ ] **Step 2: Run RED**

Run:

```powershell
.\gradlew.bat :engine:test --tests "com.overlord.physics.AabbTest" --tests "com.overlord.physics.CollisionValueTest" --console=plain --no-daemon
```

Expected: test compilation fails because `Aabb`, `SweepResult`, and
`MotionResult` do not exist.

- [ ] **Step 3: Implement immutable values**

Use these exact public declarations:

```java
public record Aabb(
        float minX, float minY, float minZ,
        float maxX, float maxY, float maxZ) {
    public Aabb translated(Vector3fc offset) {
        return new Aabb(
                minX + offset.x(), minY + offset.y(), minZ + offset.z(),
                maxX + offset.x(), maxY + offset.y(), maxZ + offset.z());
    }
    public Aabb sweptBounds(Vector3fc displacement) {
        return new Aabb(
                minX + Math.min(0, displacement.x()),
                minY + Math.min(0, displacement.y()),
                minZ + Math.min(0, displacement.z()),
                maxX + Math.max(0, displacement.x()),
                maxY + Math.max(0, displacement.y()),
                maxZ + Math.max(0, displacement.z()));
    }
    public boolean intersects(Aabb other) {
        return minX < other.maxX && maxX > other.minX
                && minY < other.maxY && maxY > other.minY
                && minZ < other.maxZ && maxZ > other.minZ;
    }
    public float overlapX(Aabb other) {
        return Math.max(
                0, Math.min(maxX, other.maxX) - Math.max(minX, other.minX));
    }
    public float overlapY(Aabb other) {
        return Math.max(
                0, Math.min(maxY, other.maxY) - Math.max(minY, other.minY));
    }
    public float overlapZ(Aabb other) {
        return Math.max(
                0, Math.min(maxZ, other.maxZ) - Math.max(minZ, other.minZ));
    }
}

public record SweepResult(
        float fraction,
        float normalX, float normalY, float normalZ,
        float pointX, float pointY, float pointZ,
        int blockX, int blockY, int blockZ,
        Aabb blockShape) {
    public Vector3f normal(Vector3f destination) {
        return destination.set(normalX, normalY, normalZ);
    }
    public Vector3f point(Vector3f destination) {
        return destination.set(pointX, pointY, pointZ);
    }
}

public record MotionResult(
        float x, float y, float z,
        float appliedX, float appliedY, float appliedZ,
        List<SweepResult> contacts) {
    public MotionResult {
        contacts = List.copyOf(contacts);
    }
    public Vector3f position(Vector3f destination) {
        return destination.set(x, y, z);
    }
    public Vector3f appliedDisplacement(Vector3f destination) {
        return destination.set(appliedX, appliedY, appliedZ);
    }
}
```

Validate every primitive for finiteness. Validate `SweepResult.fraction` in
`[0,1]`, unit axis normals, non-null shape, and immutable contact lists.

- [ ] **Step 4: Run GREEN and full engine regression**

```powershell
.\gradlew.bat :engine:test --tests "com.overlord.physics.AabbTest" --tests "com.overlord.physics.CollisionValueTest" --console=plain --no-daemon
.\gradlew.bat :engine:test --console=plain --no-daemon
```

Expected: both commands succeed.

- [ ] **Step 5: Commit**

```powershell
git add engine/src/main/java/com/overlord/physics/Aabb.java engine/src/main/java/com/overlord/physics/SweepResult.java engine/src/main/java/com/overlord/physics/MotionResult.java engine/src/test/java/com/overlord/physics/AabbTest.java engine/src/test/java/com/overlord/physics/CollisionValueTest.java
git diff --check
git commit -m "feat(physics): add immutable collision values"
```

---

### Task 2: Block shapes and continuous voxel sweep

**Files:**
- Create:
  `engine/src/main/java/com/overlord/physics/BlockCollisionShape.java`
- Create:
  `engine/src/main/java/com/overlord/physics/BlockCollisionShapeResolver.java`
- Create: `engine/src/main/java/com/overlord/physics/CollisionWorld.java`
- Create:
  `engine/src/test/java/com/overlord/physics/BlockCollisionShapeTest.java`
- Create:
  `engine/src/test/java/com/overlord/physics/CollisionWorldSweepTest.java`

**Interfaces:**
- Consumes: Task 1 values, `World.getBlock`.
- Produces:
  - `BlockCollisionShape.empty()`, `fullCube()`, `of(List<Aabb>)`.
  - `BlockCollisionShapeResolver.fullCubesForNonAir()`.
  - the `CollisionWorld.sweep` query.

- [ ] **Step 1: Write failing shape and sweep tests**

Cover a unit floor, wall, ceiling, high-speed fall, multi-box shape, negative
coordinate, and X=15/16 Chunk boundary:

```java
@Test
void highSpeedFallHitsFloorBeforeEndpoint() {
    World world = worldWithBlock(0, 0, 0);
    CollisionWorld collisions = fullCubeWorld(world);
    Aabb body = new Aabb(-0.3f, 0, -0.3f, 0.3f, 1.8f, 0.3f);

    SweepResult hit =
            collisions.sweep(
                            body,
                            new Vector3f(0.5f, 20, 0.5f),
                            new Vector3f(0, -40, 0))
                    .orElseThrow();

    assertEquals(1.0f, hit.normalY());
    assertTrue(hit.fraction() > 0.0f && hit.fraction() < 1.0f);
    assertEquals(0, hit.blockY());
}

@Test
void sweepCrossesChunkBoundaryAtSixteen() {
    World world = worldWithBlock(16, 1, 0);
    SweepResult hit =
            fullCubeWorld(world)
                    .sweep(
                            PLAYER_BOX,
                            new Vector3f(15.2f, 1, 0.5f),
                            new Vector3f(2, 0, 0))
                    .orElseThrow();
    assertEquals(16, hit.blockX());
    assertEquals(-1.0f, hit.normalX());
}
```

- [ ] **Step 2: Run RED**

```powershell
.\gradlew.bat :engine:test --tests "com.overlord.physics.BlockCollisionShapeTest" --tests "com.overlord.physics.CollisionWorldSweepTest" --console=plain --no-daemon
```

Expected: compilation fails on the missing shape/resolver/world APIs.

- [ ] **Step 3: Implement shapes and slab sweep**

Public declarations:

```java
public final class BlockCollisionShape {
    public static BlockCollisionShape empty();
    public static BlockCollisionShape fullCube();
    public static BlockCollisionShape of(List<Aabb> boxes);
    public List<Aabb> boxes();
    public boolean isEmpty();
}

@FunctionalInterface
public interface BlockCollisionShapeResolver {
    BlockCollisionShape shapeFor(byte blockId);

    static BlockCollisionShapeResolver fullCubesForNonAir() {
        return blockId ->
                blockId == 0
                        ? BlockCollisionShape.empty()
                        : BlockCollisionShape.fullCube();
    }
}

public final class CollisionWorld {
    public CollisionWorld(
            World world,
            BlockCollisionShapeResolver shapeResolver);

    public Optional<SweepResult> sweep(
            Aabb localCollider,
            Vector3fc position,
            Vector3fc displacement);
}
```

For each sweep:

- translate the collider;
- calculate swept broad phase;
- enumerate inclusive `floor` block ranges;
- translate each local sub-box by block coordinates;
- calculate X/Y/Z entry/exit times;
- reject separated or out-of-range intervals;
- choose earliest contact using Y, X, Z axis priority, then ascending block
  X/Y/Z and declared sub-box order;
- calculate the contact point from start plus `displacement * fraction`.

- [ ] **Step 4: Run GREEN plus negative and boundary cases**

```powershell
.\gradlew.bat :engine:test --tests "com.overlord.physics.BlockCollisionShapeTest" --tests "com.overlord.physics.CollisionWorldSweepTest" --console=plain --no-daemon
```

Expected: all focused cases pass without loading or allocating missing Chunks.

- [ ] **Step 5: Commit**

```powershell
git add engine/src/main/java/com/overlord/physics/BlockCollisionShape.java engine/src/main/java/com/overlord/physics/BlockCollisionShapeResolver.java engine/src/main/java/com/overlord/physics/CollisionWorld.java engine/src/test/java/com/overlord/physics/BlockCollisionShapeTest.java engine/src/test/java/com/overlord/physics/CollisionWorldSweepTest.java
git diff --check
git commit -m "feat(physics): add continuous voxel collision queries"
```

---

### Task 3: Sweep-and-slide and penetration recovery

**Files:**
- Modify: `engine/src/main/java/com/overlord/physics/CollisionWorld.java`
- Create:
  `engine/src/test/java/com/overlord/physics/CollisionWorldMotionTest.java`

**Interfaces:**
- Consumes: Task 2 `sweep`.
- Produces:
  - `CollisionWorld.moveAndSlide`.
  - `CollisionWorld.overlapsSolid`.
  - `CollisionWorld.depenetrate`.

- [ ] **Step 1: Write failing motion tests**

```java
@Test
void diagonalMotionSlidesAlongWall() {
    World world = wallAtX(2);
    MotionResult result =
            fullCubeWorld(world)
                    .moveAndSlide(
                            PLAYER_BOX,
                            new Vector3f(1.5f, 1, 0.5f),
                            new Vector3f(2, 0, 2),
                            4);

    assertTrue(result.x() < 2.0f - PLAYER_HALF_WIDTH);
    assertTrue(result.z() > 2.0f);
    assertEquals(-1.0f, result.contacts().get(0).normalX());
}

@Test
void depenetrationPrefersUpwardOnEqualDepth() {
    CollisionWorld collisions = fullCubeWorld(worldWithBlock(0, 0, 0));
    Vector3f recovered =
            collisions
                    .depenetrate(
                            new Aabb(-0.5f, 0, -0.5f, 0.5f, 1, 0.5f),
                            new Vector3f(0.5f, 0.5f, 0.5f),
                            8)
                    .orElseThrow();
    assertTrue(recovered.y > 1.0f - 1.0e-4f);
}
```

Also test a two-wall corner, zero displacement, maximum four contacts, and an
unresolvable bounded overlap returning empty.

- [ ] **Step 2: Run RED**

```powershell
.\gradlew.bat :engine:test --tests "com.overlord.physics.CollisionWorldMotionTest" --console=plain --no-daemon
```

Expected: compilation fails because the motion APIs are absent.

- [ ] **Step 3: Implement shared movement**

Add:

```java
public MotionResult moveAndSlide(
        Aabb localCollider,
        Vector3fc position,
        Vector3fc displacement,
        int maxIterations);

public boolean overlapsSolid(Aabb worldBounds);

public Optional<Vector3f> depenetrate(
        Aabb localCollider,
        Vector3fc position,
        int maxIterations);
```

`moveAndSlide` must:

- reject negative iteration counts and non-finite inputs;
- repeatedly call `sweep`;
- advance to `max(0, fraction - skinFraction)`;
- append contacts in order;
- remove only the remaining component directed into the contact normal;
- stop when remaining length is below tolerance or iterations are exhausted;
- return actual applied displacement.

`depenetrate` must collect strict overlaps, choose the smallest translation,
prefer +Y for equal depths, and confirm the final box is clear.

- [ ] **Step 4: Run GREEN and complete engine tests**

```powershell
.\gradlew.bat :engine:test --tests "com.overlord.physics.CollisionWorldMotionTest" --console=plain --no-daemon
.\gradlew.bat :engine:test --console=plain --no-daemon
```

- [ ] **Step 5: Commit**

```powershell
git add engine/src/main/java/com/overlord/physics/CollisionWorld.java engine/src/test/java/com/overlord/physics/CollisionWorldMotionTest.java
git diff --check
git commit -m "feat(physics): add sweep-and-slide collision motion"
```

---

### Task 4: Shared shape-aware block raycast

**Files:**
- Create: `engine/src/main/java/com/overlord/physics/BlockRaycast.java`
- Create: `engine/src/main/java/com/overlord/physics/BlockRaycastHit.java`
- Create:
  `engine/src/test/java/com/overlord/physics/BlockRaycastTest.java`

**Interfaces:**
- Consumes: `World`, `BlockCollisionShapeResolver`, `Aabb`.
- Produces:
  `BlockRaycast.cast(Vector3fc, Vector3fc, float)` and immutable hit data for
  Phases 4/5.

- [ ] **Step 1: Write failing DDA and shape tests**

```java
@Test
void returnsHitAndAdjacentPlacementCoordinate() {
    BlockRaycast raycast =
            raycastFor(worldWithBlock(2, 1, 0));

    BlockRaycastHit hit =
            raycast.cast(
                            new Vector3f(0.5f, 1.5f, 0.5f),
                            new Vector3f(1, 0, 0),
                            5)
                    .orElseThrow();

    assertEquals(2, hit.blockX());
    assertEquals(1, hit.adjacentX());
    assertEquals(-1.0f, hit.normalX());
    assertEquals(1.5f, hit.distance(), 1.0e-5f);
}

@Test
void exactSubShapeMissesEmptyHalfOfVoxel() {
    BlockCollisionShape upperHalf =
            BlockCollisionShape.of(
                    List.of(new Aabb(0, 0.5f, 0, 1, 1, 1)));
    assertTrue(
            raycastFor(worldWithBlock(1, 0, 0), id -> upperHalf)
                    .cast(
                            new Vector3f(0, 0.25f, 0.5f),
                            new Vector3f(1, 0, 0),
                            3)
                    .isEmpty());
}
```

Also cover negative/Chunk-boundary cells, origin inside, maximum distance,
zero direction, non-finite input, and miss.

- [ ] **Step 2: Run RED**

```powershell
.\gradlew.bat :engine:test --tests "com.overlord.physics.BlockRaycastTest" --console=plain --no-daemon
```

Expected: missing raycast types.

- [ ] **Step 3: Implement DDA plus exact ray/AABB slabs**

Use:

```java
public final class BlockRaycast {
    public BlockRaycast(
            World world,
            BlockCollisionShapeResolver shapeResolver);

    public Optional<BlockRaycastHit> cast(
            Vector3fc origin,
            Vector3fc direction,
            float maxDistance);
}

public record BlockRaycastHit(
        int blockX, int blockY, int blockZ,
        int adjacentX, int adjacentY, int adjacentZ,
        byte blockId,
        float normalX, float normalY, float normalZ,
        float pointX, float pointY, float pointZ,
        float distance) {
    public Vector3f normal(Vector3f destination) {
        return destination.set(normalX, normalY, normalZ);
    }
    public Vector3f point(Vector3f destination) {
        return destination.set(pointX, pointY, pointZ);
    }
}
```

Normalize a copied direction, traverse voxels with finite DDA, and test every
sub-shape exactly. For an origin inside a shape, return distance zero and a
normal opposite the dominant direction using Y, X, Z tie order.

- [ ] **Step 4: Run GREEN**

```powershell
.\gradlew.bat :engine:test --tests "com.overlord.physics.BlockRaycastTest" --console=plain --no-daemon
```

- [ ] **Step 5: Commit**

```powershell
git add engine/src/main/java/com/overlord/physics/BlockRaycast.java engine/src/main/java/com/overlord/physics/BlockRaycastHit.java engine/src/test/java/com/overlord/physics/BlockRaycastTest.java
git diff --check
git commit -m "feat(physics): add shared block raycast service"
```

---

### Task 5: Body state, mass, force, and interpolation

**Files:**
- Create: `engine/src/main/java/com/overlord/physics/MassProperties.java`
- Create:
  `engine/src/main/java/com/overlord/physics/ForceAccumulator.java`
- Create: `engine/src/main/java/com/overlord/physics/PhysicsBody.java`
- Create:
  `engine/src/test/java/com/overlord/physics/MassPropertiesTest.java`
- Create:
  `engine/src/test/java/com/overlord/physics/ForceAccumulatorTest.java`
- Create:
  `engine/src/test/java/com/overlord/physics/PhysicsBodyTest.java`

**Interfaces:**
- Consumes: `Aabb`, JOML vectors.
- Produces: validated body fields and strict previous/current/render separation.

- [ ] **Step 1: Write failing state and accumulator tests**

```java
@Test
void dynamicMassDerivesInverseAndStaticUsesZeroSentinel() {
    assertEquals(0.25f, MassProperties.dynamic(4).inverseMass());
    assertEquals(0.0f, MassProperties.staticBody().mass());
    assertEquals(0.0f, MassProperties.staticBody().inverseMass());
}

@Test
void interpolationDoesNotMutatePhysicsPositions() {
    PhysicsBody body = bodyAt(0, 0, 0);
    body.beginStep();
    body.setPosition(new Vector3f(2, 0, 0));

    Vector3f rendered = body.interpolatedPosition(0.25f, new Vector3f());

    assertEquals(0.5f, rendered.x);
    assertEquals(2.0f, body.position(new Vector3f()).x);
    assertEquals(0.0f, body.previousPosition(new Vector3f()).x);
}
```

Test force/impulse/torque defensive copies, consume-and-clear behavior,
teleport setting both transforms, coefficients, active/sleeping, and all
non-finite validation.

- [ ] **Step 2: Run RED**

```powershell
.\gradlew.bat :engine:test --tests "com.overlord.physics.MassPropertiesTest" --tests "com.overlord.physics.ForceAccumulatorTest" --tests "com.overlord.physics.PhysicsBodyTest" --console=plain --no-daemon
```

- [ ] **Step 3: Implement exact body API**

```java
public final class MassProperties {
    private final float mass;
    private final float inverseMass;

    private MassProperties(float mass, float inverseMass) {
        this.mass = mass;
        this.inverseMass = inverseMass;
    }
    public static MassProperties dynamic(float mass) {
        if (!Float.isFinite(mass) || mass <= 0) {
            throw new IllegalArgumentException(
                    "dynamic mass must be finite and positive");
        }
        return new MassProperties(mass, 1.0f / mass);
    }
    public static MassProperties staticBody() {
        return new MassProperties(0, 0);
    }
    public float mass() { return mass; }
    public float inverseMass() { return inverseMass; }
    public boolean isStatic() { return inverseMass == 0; }
}

public final class ForceAccumulator {
    private final Vector3f force = new Vector3f();
    private final Vector3f impulse = new Vector3f();
    private final Vector3f torque = new Vector3f();

    public void applyForce(Vector3fc value) {
        requireFinite(value, "force");
        force.add(value);
    }
    public void applyImpulse(Vector3fc value) {
        requireFinite(value, "impulse");
        impulse.add(value);
    }
    public void applyTorque(Vector3fc value) {
        requireFinite(value, "torque");
        torque.add(value);
    }
    public Vector3f consumeForce(Vector3f destination) {
        destination.set(force);
        force.zero();
        return destination;
    }
    public Vector3f consumeImpulse(Vector3f destination) {
        destination.set(impulse);
        impulse.zero();
        return destination;
    }
    public Vector3f consumeTorque(Vector3f destination) {
        destination.set(torque);
        torque.zero();
        return destination;
    }
    public void clear() {
        force.zero();
        impulse.zero();
        torque.zero();
    }
    private static void requireFinite(Vector3fc value, String label) {
        if (value == null
                || !Float.isFinite(value.x())
                || !Float.isFinite(value.y())
                || !Float.isFinite(value.z())) {
            throw new IllegalArgumentException(label + " must be finite");
        }
    }
}

public final class PhysicsBody {
    public PhysicsBody(Aabb collider, MassProperties massProperties);
    public void beginStep();
    public void teleport(Vector3fc position);
    public Vector3f position(Vector3f destination);
    public Vector3f previousPosition(Vector3f destination);
    public void setPosition(Vector3fc position);
    public Vector3f interpolatedPosition(float alpha, Vector3f destination);
    public Vector3f linearVelocity(Vector3f destination);
    public void setLinearVelocity(Vector3fc velocity);
    public Vector3f angularVelocity(Vector3f destination);
    public void setAngularVelocity(Vector3fc velocity);
    public ForceAccumulator forces();
    public Aabb collider();
    public MassProperties massProperties();
    public float gravityScale();
    public void setGravityScale(float gravityScale);
    public float restitution();
    public void setRestitution(float restitution);
    public float friction();
    public void setFriction(float friction);
    public boolean isActive();
    public void setActive(boolean active);
    public boolean isSleeping();
    public void setSleeping(boolean sleeping);
}
```

- [ ] **Step 4: Run GREEN**

```powershell
.\gradlew.bat :engine:test --tests "com.overlord.physics.MassPropertiesTest" --tests "com.overlord.physics.ForceAccumulatorTest" --tests "com.overlord.physics.PhysicsBodyTest" --console=plain --no-daemon
```

- [ ] **Step 5: Commit**

```powershell
git add engine/src/main/java/com/overlord/physics/MassProperties.java engine/src/main/java/com/overlord/physics/ForceAccumulator.java engine/src/main/java/com/overlord/physics/PhysicsBody.java engine/src/test/java/com/overlord/physics/MassPropertiesTest.java engine/src/test/java/com/overlord/physics/ForceAccumulatorTest.java engine/src/test/java/com/overlord/physics/PhysicsBodyTest.java
git diff --check
git commit -m "feat(physics): add rigid-body state foundation"
```

---

### Task 6: Generic fixed-step PhysicsWorld

**Files:**
- Create: `engine/src/main/java/com/overlord/physics/PhysicsWorld.java`
- Create:
  `engine/src/test/java/com/overlord/physics/PhysicsWorldTest.java`

**Interfaces:**
- Consumes: `PhysicsBody`, `CollisionWorld`, `MotionResult`.
- Produces: insertion-ordered body registration and fixed-step integration.

- [ ] **Step 1: Write failing integration tests**

```java
@Test
void forceAndImpulseUseSemiImplicitEuler() {
    PhysicsBody body = dynamicBodyAt(0, 10, 0, 2.0f);
    body.forces().applyForce(new Vector3f(4, 0, 0));
    body.forces().applyImpulse(new Vector3f(0, 2, 0));
    PhysicsWorld physics =
            new PhysicsWorld(emptyCollisions(), new Vector3f(0, -10, 0));
    physics.addBody(body);

    physics.step(0.5f);

    assertVector(body.linearVelocity(new Vector3f()), 1, -4, 0);
    assertVector(body.position(new Vector3f()), 0.5f, 8, 0);
}

@Test
void fallingBodyStopsOnVoxelGround() {
    PhysicsBody body = dynamicBodyAt(0.5f, 5, 0.5f, 1);
    PhysicsWorld physics = gravityWorldWithGround(body);
    for (int step = 0; step < 180; step++) {
        physics.step(1.0f / 60.0f);
    }
    assertEquals(1.0f, body.position(new Vector3f()).y, 1.0e-3f);
}
```

Also test restitution, friction, static/inactive/sleeping bodies, removal, one
integration per registered body, accumulator clearing, and no body-body
collision.

- [ ] **Step 2: Run RED**

```powershell
.\gradlew.bat :engine:test --tests "com.overlord.physics.PhysicsWorldTest" --console=plain --no-daemon
```

- [ ] **Step 3: Implement fixed integration**

```java
public final class PhysicsWorld {
    public PhysicsWorld(
            CollisionWorld collisionWorld,
            Vector3fc gravity);
    public boolean addBody(PhysicsBody body);
    public boolean removeBody(PhysicsBody body);
    public List<PhysicsBody> bodies();
    public void step(float fixedDeltaSeconds);
}
```

Use a `LinkedHashSet` internally. Each dynamic step copies previous state,
consumes accumulators, applies semi-implicit velocity, calls
`moveAndSlide` with four maximum iterations, and updates normal/tangent velocity from ordered
contacts. Always clear accumulators for a stepped body. Do not integrate the
player body in this collection.

- [ ] **Step 4: Run GREEN and complete engine tests**

```powershell
.\gradlew.bat :engine:test --tests "com.overlord.physics.PhysicsWorldTest" --console=plain --no-daemon
.\gradlew.bat :engine:test --console=plain --no-daemon
```

- [ ] **Step 5: Commit**

```powershell
git add engine/src/main/java/com/overlord/physics/PhysicsWorld.java engine/src/test/java/com/overlord/physics/PhysicsWorldTest.java
git diff --check
git commit -m "feat(physics): integrate simple voxel physics bodies"
```

---

### Task 7: Core PlayerController sweep behavior

**Files:**
- Create:
  `engine/src/main/java/com/overlord/physics/PlayerController.java`
- Modify: `engine/src/main/java/com/overlord/config/GameConfig.java`
- Create:
  `engine/src/test/java/com/overlord/physics/PlayerControllerCollisionTest.java`

**Interfaces:**
- Consumes: player body, `CollisionWorld`, fixed movement intent.
- Produces: grounded movement, jump, walls, corners, ceilings, and terminal
  falls.

- [ ] **Step 1: Write failing player collision scenarios**

Use real `World` fixtures and test:

```java
@Test
void diagonalWallImpactSlidesAlongWall() {
    PlayerController player = controllerFacingWallAtX2();
    player.teleport(new Vector3f(1.5f, 1, 0.5f));

    for (int step = 0; step < 60; step++) {
        player.fixedUpdate(
                FIXED_STEP, 1, 1, false, false, false);
    }

    Vector3f feet = player.body().position(new Vector3f());
    assertTrue(feet.x < 2.0f - GameConfig.Player.WIDTH / 2);
    assertTrue(feet.z > 2.0f);
}

@Test
void jumpStopsAtCeiling() {
    PlayerController player = groundedControllerWithCeiling();
    player.fixedUpdate(FIXED_STEP, 0, 0, true, false, false);
    advance(player, 30);
    assertTrue(player.body().linearVelocity(new Vector3f()).y <= 0);
    assertFalse(player.overlapsSolid());
}
```

Also cover floor, corner, high-speed fall, Chunk boundary, terminal velocity,
invalid delta, and stable grounded state.

- [ ] **Step 2: Run RED**

```powershell
.\gradlew.bat :engine:test --tests "com.overlord.physics.PlayerControllerCollisionTest" --console=plain --no-daemon
```

- [ ] **Step 3: Implement core controller**

Add configuration constants:

```java
public static final float MAX_STEP_HEIGHT = 1.0f;
public static final float GROUND_SNAP_DISTANCE = 1.0f;
public static final int MAX_SLIDE_ITERATIONS = 4;
public static final int MAX_DEPENETRATION_ITERATIONS = 8;
public static final int NOCLIP_DOUBLE_TAP_STEPS = 15;
```

Use:

```java
public final class PlayerController {
    public PlayerController(
            PhysicsBody body,
            CollisionWorld collisionWorld,
            float movementSpeed,
            float noclipSpeed,
            float jumpVelocity,
            float gravity,
            float terminalVelocity);

    public PhysicsBody body();
    public boolean isGrounded();
    public boolean isNoclip();
    public void teleport(Vector3fc feetPosition);
    public void fixedUpdate(
            float fixedDeltaSeconds,
            float moveX,
            float moveZ,
            boolean jumpPressed,
            boolean ascendHeld,
            boolean descendHeld);
}
```

Normalize horizontal intent, preserve vertical velocity, apply gravity and
terminal clamp, execute shared move-and-slide, and derive ground/ceiling state
from contact normals.

- [ ] **Step 4: Run GREEN**

```powershell
.\gradlew.bat :engine:test --tests "com.overlord.physics.PlayerControllerCollisionTest" --console=plain --no-daemon
```

- [ ] **Step 5: Commit**

```powershell
git add engine/src/main/java/com/overlord/physics/PlayerController.java engine/src/main/java/com/overlord/config/GameConfig.java engine/src/test/java/com/overlord/physics/PlayerControllerCollisionTest.java
git diff --check
git commit -m "feat(physics): add swept player controller"
```

---

### Task 8: Step-up, ground snap, depenetration, and noclip

**Files:**
- Modify:
  `engine/src/main/java/com/overlord/physics/PlayerController.java`
- Create:
  `engine/src/test/java/com/overlord/physics/PlayerControllerTraversalTest.java`

**Interfaces:**
- Consumes: Task 7 controller.
- Produces: one-block traversal, safe spawn, and collision-safe noclip toggle.

- [ ] **Step 1: Write failing traversal tests**

```java
@Test
void walksUpOneBlockAndPreservesHorizontalProgress() {
    PlayerController player = controllerAtOneBlockStep();
    advanceForward(player, 45);
    Vector3f feet = player.body().position(new Vector3f());
    assertTrue(feet.x > 1.5f);
    assertEquals(2.0f, feet.y, 2.0e-3f);
    assertTrue(player.isGrounded());
}

@Test
void walkingDownOneBlockUsesGroundSnapWithoutAirborneFrame() {
    PlayerController player = controllerAtPlatformEdge();
    for (int step = 0; step < 30; step++) {
        player.fixedUpdate(FIXED_STEP, 1, 0, false, false, false);
        assertTrue(player.isGrounded());
    }
}

@Test
void noclipExitInsideWallMovesToNearestSafeSpace() {
    PlayerController player = embeddedNoclipController();
    assertTrue(player.setNoclip(false));
    assertFalse(player.isNoclip());
    assertFalse(player.overlapsSolid());
}
```

Also test no snap during jump, step blocked by low ceiling, spawn overlap,
noclip gravity disabled, failed exit remains noclip, and ascend/descend.

- [ ] **Step 2: Run RED**

```powershell
.\gradlew.bat :engine:test --tests "com.overlord.physics.PlayerControllerTraversalTest" --console=plain --no-daemon
```

- [ ] **Step 3: Implement traversal policies**

Extend:

```java
public boolean setNoclip(boolean enabled);
public boolean recoverFromPenetration();
public boolean overlapsSolid();
```

Implement the approved order:

- normal horizontal slide baseline;
- grounded blocked path: up sweep -> horizontal sweep -> downward landing;
- choose step only for greater horizontal progress and clear final volume;
- previous-grounded, non-jump, non-upward, moving path: downward one-block snap;
- local eight-iteration depenetration;
- upward whole-block safe-space scan through world height;
- noclip movement without gravity/collision and collision-safe exit.

- [ ] **Step 4: Run GREEN and all player tests**

```powershell
.\gradlew.bat :engine:test --tests "com.overlord.physics.PlayerController*Test" --console=plain --no-daemon
```

- [ ] **Step 5: Commit**

```powershell
git add engine/src/main/java/com/overlord/physics/PlayerController.java engine/src/test/java/com/overlord/physics/PlayerControllerTraversalTest.java
git diff --check
git commit -m "feat(physics): add player traversal and noclip recovery"
```

---

### Task 9: Fixed input edges and PlayerManager migration

**Files:**
- Modify:
  `engine/src/main/java/com/overlord/core/input/InputSnapshot.java`
- Modify: `engine/src/main/java/com/overlord/core/PlayerManager.java`
- Modify:
  `engine/src/test/java/com/overlord/core/input/InputSnapshotTest.java`
- Modify:
  `engine/src/test/java/com/overlord/core/PlayerManagerTest.java`

**Interfaces:**
- Consumes: `PlayerController`.
- Produces: held-only catch-up input and deterministic 15-step Space toggle.

- [ ] **Step 1: Replace legacy tests with failing controller-intent tests**

```java
@Test
void heldOnlySnapshotClearsPressEdges() {
    InputSnapshot original =
            new InputSnapshot(Set.of(GLFW_KEY_SPACE), Set.of(GLFW_KEY_SPACE));
    InputSnapshot heldOnly = original.heldOnly();
    assertTrue(heldOnly.isKeyDown(GLFW_KEY_SPACE));
    assertFalse(heldOnly.isKeyPressed(GLFW_KEY_SPACE));
}

@Test
void secondSpaceWithinFifteenFixedStepsTogglesNoclipOnce() {
    PlayerFixture fixture = playerFixtureOnGround();
    fixture.player().fixedUpdate(FIXED_STEP, spacePressed());
    assertEquals(
            GameConfig.Player.JUMP_VELOCITY,
            fixture.controller().body().linearVelocity(new Vector3f()).y);
    repeat(
            14,
            () -> fixture.player().fixedUpdate(FIXED_STEP, noInput()));
    fixture.player().fixedUpdate(FIXED_STEP, spacePressed());
    assertTrue(fixture.controller().isNoclip());
    assertEquals(
            0.0f,
            fixture.controller().body().linearVelocity(new Vector3f()).y);
}
```

Also prove the six catch-up calls using one full plus five held-only snapshots
observe one edge, diagonal movement is normalized, and Space/Shift control
noclip vertical intent.

- [ ] **Step 2: Run RED**

```powershell
.\gradlew.bat :engine:test --tests "com.overlord.core.input.InputSnapshotTest" --tests "com.overlord.core.PlayerManagerTest" --console=plain --no-daemon
```

- [ ] **Step 3: Migrate PlayerManager**

Add:

```java
public InputSnapshot heldOnly() {
    return new InputSnapshot(keysDown, Set.of());
}
```

Change construction to:

```java
public PlayerManager(Camera camera, PlayerController playerController)
```

Keep:

```java
public void applyLook(MouseDelta delta);
public void fixedUpdate(float fixedDeltaSeconds, InputSnapshot input);
```

Track `remainingNoclipTapSteps`. Deliver the first Space press as jump and arm
15 steps; deliver the second as exactly one `setNoclip(!isNoclip())` request.
Pass world-space normalized X/Z intent and held ascend/descend state to the
controller.

- [ ] **Step 4: Run GREEN**

```powershell
.\gradlew.bat :engine:test --tests "com.overlord.core.input.InputSnapshotTest" --tests "com.overlord.core.PlayerManagerTest" --console=plain --no-daemon
```

- [ ] **Step 5: Commit**

```powershell
git add engine/src/main/java/com/overlord/core/input/InputSnapshot.java engine/src/main/java/com/overlord/core/PlayerManager.java engine/src/test/java/com/overlord/core/input/InputSnapshotTest.java engine/src/test/java/com/overlord/core/PlayerManagerTest.java
git diff --check
git commit -m "refactor(player): delegate fixed movement to player controller"
```

---

### Task 10: Fixed-clock interpolation and game composition

**Files:**
- Modify:
  `engine/src/main/java/com/overlord/core/time/FixedStepClock.java`
- Modify: `engine/src/main/java/com/overlord/renderer/Camera.java`
- Modify:
  `engine/src/test/java/com/overlord/core/time/FixedStepClockTest.java`
- Create:
  `engine/src/test/java/com/overlord/renderer/CameraPositionTest.java`
- Modify: `game/src/main/java/com/gaia/GameBootstrap.java`
- Modify: `game/src/main/java/com/gaia/GameContext.java`
- Modify: `game/src/main/java/com/gaia/GameLoop.java`
- Modify: `game/src/main/java/com/gaia/world/WorldLoader.java`
- Modify: `game/src/main/java/com/gaia/world/WorldLoadResult.java`
- Modify: `game/src/test/java/com/gaia/GameBootstrapTest.java`
- Modify: `game/src/test/java/com/gaia/GameLoopStructureTest.java`
- Modify:
  `game/src/test/java/com/gaia/world/WorldLoaderTest.java`

**Interfaces:**
- Consumes: all Phase 6 physics services.
- Produces: explicit composition, feet spawn, eight-step catch-up, and render
  interpolation.

- [ ] **Step 1: Write failing clock and composition tests**

```java
@Test
void exposesRemainderAsInterpolationAlpha() {
    FixedStepClock clock = new FixedStepClock(1.0 / 60.0, 8);
    clock.advance(1.5 / 60.0);
    assertEquals(0.5, clock.interpolationAlpha(), 1.0e-9);
}

@Test
void worldLoaderReturnsFeetPosition() {
    WorldLoadResult result = loader.load(world);
    assertEquals(highestSolidY + 1.0f, result.playerFeetPosition().y);
}
```

Structure tests must require `PhysicsWorld`, `CollisionWorld`,
`PlayerController`, and `BlockRaycast` in `GameContext`, require the fixed
limit `8`, and reject `PhysicsManager`.

- [ ] **Step 2: Run RED**

```powershell
.\gradlew.bat :engine:test --tests "com.overlord.core.time.FixedStepClockTest" --tests "com.overlord.renderer.CameraPositionTest" --console=plain --no-daemon
.\gradlew.bat :game:test --tests "com.gaia.GameBootstrapTest" --tests "com.gaia.GameLoopStructureTest" --tests "com.gaia.world.WorldLoaderTest" --console=plain --no-daemon
```

- [ ] **Step 3: Add interpolation and feet spawn**

Add:

```java
public double interpolationAlpha() {
    return Math.min(
            Math.nextDown(1.0),
            Math.max(0.0, remainderSeconds() / fixedStepSeconds));
}
```

`Camera.setPosition(Vector3f)` must copy into its owned vector rather than
assigning the caller's object. Rename the load result field and accessor to:

```java
Vector3f playerFeetPosition()
```

WorldLoader returns `(spawnX + 0.5, highestY + 1, spawnZ + 0.5)`.

- [ ] **Step 4: Compose and order the fixed loop**

GameBootstrap must construct:

```java
BlockCollisionShapeResolver shapes =
        BlockCollisionShapeResolver.fullCubesForNonAir();
CollisionWorld collisions =
        new CollisionWorld(engine.getWorld(), shapes);
BlockRaycast raycast =
        new BlockRaycast(engine.getWorld(), shapes);
PhysicsBody playerBody =
        new PhysicsBody(
                new Aabb(
                        -GameConfig.Player.WIDTH / 2.0f,
                        0.0f,
                        -GameConfig.Player.WIDTH / 2.0f,
                        GameConfig.Player.WIDTH / 2.0f,
                        GameConfig.Player.HEIGHT,
                        GameConfig.Player.WIDTH / 2.0f),
                MassProperties.dynamic(1.0f));
PlayerController playerController =
        new PlayerController(
                playerBody,
                collisions,
                GameConfig.Player.MOVEMENT_SPEED,
                GameConfig.Player.NOCLIP_SPEED,
                GameConfig.Player.JUMP_VELOCITY,
                GameConfig.Physics.GRAVITY,
                GameConfig.Physics.TERMINAL_VELOCITY);
PhysicsWorld physicsWorld =
        new PhysicsWorld(
                collisions,
                new Vector3f(0, GameConfig.Physics.GRAVITY, 0));
```

Set `MAX_FIXED_STEPS_PER_FRAME = 8`. `GameContext` carries all four services.
Loading teleports/recoveries the player body. For catch-up steps, pass the full
snapshot to step zero and `input.heldOnly()` afterward. Call
`physicsWorld.step(fixedDelta)` after player update. Before rendering, set:

```java
Vector3f cameraFeet =
        playerController
                .body()
                .interpolatedPosition(
                        fixedStepClock.interpolationAlpha(),
                        interpolationScratch);
cameraFeet.y += GameConfig.Player.EYE_HEIGHT;
camera.setPosition(cameraFeet);
```

- [ ] **Step 5: Run GREEN and module regressions**

```powershell
.\gradlew.bat :engine:test --console=plain --no-daemon
.\gradlew.bat :game:test --console=plain --no-daemon
```

- [ ] **Step 6: Commit**

```powershell
git add engine/src/main/java/com/overlord/core/time/FixedStepClock.java engine/src/main/java/com/overlord/renderer/Camera.java engine/src/test/java/com/overlord/core/time/FixedStepClockTest.java engine/src/test/java/com/overlord/renderer/CameraPositionTest.java game/src/main/java/com/gaia/GameBootstrap.java game/src/main/java/com/gaia/GameContext.java game/src/main/java/com/gaia/GameLoop.java game/src/main/java/com/gaia/world/WorldLoader.java game/src/main/java/com/gaia/world/WorldLoadResult.java game/src/test/java/com/gaia/GameBootstrapTest.java game/src/test/java/com/gaia/GameLoopStructureTest.java game/src/test/java/com/gaia/world/WorldLoaderTest.java
git diff --check
git commit -m "refactor(game): compose fixed-step physics and interpolation"
```

---

### Task 11: Determinism, legacy removal, and architecture enforcement

**Files:**
- Delete:
  `engine/src/main/java/com/overlord/physics/PhysicsManager.java`
- Create:
  `engine/src/test/java/com/overlord/physics/PhysicsDeterminismTest.java`
- Create:
  `engine/src/test/java/com/overlord/physics/PhysicsArchitectureTest.java`
- Modify: `game/src/test/java/com/gaia/GaiaMainStructureTest.java`
- Modify or create:
  `game/src/test/java/com/gaia/PhysicsCompositionStructureTest.java`

**Interfaces:**
- Consumes: final Phase 6 surface.
- Produces: 10/60/144/240 equivalence and static boundary enforcement.

- [ ] **Step 1: Write failing render-rate determinism tests**

Build a helper that advances the real `FixedStepClock(1.0 / 60.0, 8)` with
exact frame intervals until the same wall duration. Deliver input edges only
to the first fixed step and record movement, apex, landing position, and fixed
step count:

```java
@ParameterizedTest
@ValueSource(ints = {10, 60, 144, 240})
void movementAndJumpMatchSixtyFps(int renderFps) {
    SimulationResult reference = simulate(60, 10.0);
    SimulationResult actual = simulate(renderFps, 10.0);
    assertEquals(reference.fixedSteps(), actual.fixedSteps());
    assertEquals(reference.horizontalDistance(), actual.horizontalDistance(), 1.0e-4);
    assertEquals(reference.jumpApex(), actual.jumpApex(), 1.0e-4);
    assertEquals(reference.landingY(), actual.landingY(), 1.0e-4);
}
```

Add a 0.1-second frame/high-speed fall case proving no tunneling.

- [ ] **Step 2: Write failing architecture tests**

Require:

- no `PhysicsManager` production file or reference;
- no `org.lwjgl`, renderer, GLFW, or OpenGL import under `engine.physics`;
- exactly one production `BlockRaycast` implementation;
- no Camera read inside `CollisionWorld`, `PhysicsWorld`, or
  `PlayerController`;
- no direct World collision loop in PlayerManager;
- Java 17-compatible sources.

- [ ] **Step 3: Run RED**

```powershell
.\gradlew.bat :engine:test --tests "com.overlord.physics.PhysicsDeterminismTest" --tests "com.overlord.physics.PhysicsArchitectureTest" --console=plain --no-daemon
.\gradlew.bat :game:test --tests "com.gaia.PhysicsCompositionStructureTest" --tests "com.gaia.GaiaMainStructureTest" --console=plain --no-daemon
```

Expected: legacy PhysicsManager and old structure assertions fail.

- [ ] **Step 4: Remove the legacy class and finish migrations**

Delete `PhysicsManager.java`, remove every import/construction/reference, and
update GaiaMain's minimal-entry structure test to reject physics construction
there while allowing GameBootstrap composition.

Run:

```powershell
rg -n "PhysicsManager|new BlockRaycast|org\\.lwjgl|org\\.opengl|com\\.overlord\\.renderer" engine/src/main/java/com/overlord/physics game/src/main
```

Expected after migration:

- no `PhysicsManager`;
- one `new BlockRaycast` in GameBootstrap;
- no LWJGL/OpenGL/renderer import in `engine.physics`.

- [ ] **Step 5: Run GREEN and clean build**

```powershell
.\gradlew.bat clean test build --console=plain --no-daemon
```

Expected: all engine/game tests and packaged resource checks pass.

- [ ] **Step 6: Commit**

```powershell
git add -A engine/src/main/java/com/overlord/physics engine/src/test/java/com/overlord/physics game/src/test/java/com/gaia
git diff --check
git commit -m "test(physics): enforce deterministic physics boundaries"
```

---

### Task 12: Phase 6 handoff and final verification

**Files:**
- Create: `docs/agent-handoffs/phase-06-handoff.md`
- Modify: `docs/architecture/current-baseline.md`

**Interfaces:**
- Consumes: the verified final branch.
- Produces: Phase 6 handoff and next-phase protected contracts.

- [ ] **Step 1: Run final automated Windows verification**

```powershell
.\gradlew.bat clean test build --console=plain --no-daemon
.\gradlew.bat :game:verifyPackagedResources --rerun-tasks --console=plain --no-daemon
git diff --check origin/main..HEAD
```

Record exit codes, actionable task counts, selected LWJGL natives, and JUnit XML
suite/test/failure/error/skip totals.

- [ ] **Step 2: Run policy scans**

```powershell
git status --short --branch
git diff --stat origin/main..HEAD
git ls-files | Select-String -Pattern '(^|/)bin(/|$)|\.class($|[^/]*$)|hs_err_pid|replay_pid'
Select-String -Path gradle.properties -Pattern 'org\.gradle\.java\.home|/Library/Java|[A-Za-z]:\\'
rg -n "PhysicsManager|Camera" engine/src/main/java/com/overlord/physics
rg -n "org\.lwjgl|glGen|glBind|glBuffer|glDelete|glDraw|new Mesh" engine/src/main/java/com/overlord/physics
rg -n "#version 4(2|3|4|5|6)|glDispatchCompute" engine/src game/src
```

Expected: all policy scans are empty except the explicit Camera prohibition scan
may match only test documentation outside production.

- [ ] **Step 3: Record interactive/platform status accurately**

Windows manual command:

```powershell
.\gradlew.bat :game
```

Check movement, jump, step-up, ground snap, wall slide, ceiling, noclip,
cursor-release resize, Chunk boundary, and clean Escape shutdown. If not run,
write `NOT RUN`.

macOS commands:

```bash
./gradlew clean test build
./gradlew :game
```

If no native macOS environment is available, write `NOT RUN`.

- [ ] **Step 4: Write the handoff with exact sections**

```markdown
## Completed work
## Incomplete work
## Core architecture decisions
## Modified files
## Test commands and results
## Known risks
## Interfaces the next phase must not break
## Final phase report
```

Protect:

- authoritative previous/current physics transform;
- Camera interpolation as one-way render output;
- 1/60 fixed step and eight-step 10 FPS catch-up;
- one `CollisionWorld` shape source for player, bodies, and raycast;
- shared `BlockRaycast`;
- deterministic sweep tie ordering;
- main-thread fixed simulation and GPU-free physics;
- no body-body solver until Phase 11.

Include exact final diff stat, suggested commit
`refactor(physics): add fixed-step collision and rigid-body foundation`, and a
complete PR title/description.

- [ ] **Step 5: Request engine and game owner review**

Engine review:

- math and continuous collision correctness;
- Chunk-boundary and negative coordinate queries;
- body state/mass/force invariants;
- no render/GPU dependency;
- raycast/sweep shared shapes.

Game review:

- input edges and double-Space behavior;
- fixed-update ordering;
- interpolation separation;
- spawn/step/snap/player feel;
- no unrelated terrain/material/gameplay changes.

Resolve every Critical and Important finding and rerun affected tests.

- [ ] **Step 6: Commit documentation**

```powershell
git add docs/agent-handoffs/phase-06-handoff.md docs/architecture/current-baseline.md
git diff --check
git commit -m "docs: record Phase 6 physics foundation handoff"
```

- [ ] **Step 7: Final branch review**

Review `origin/main..HEAD`, rerun full verification after any fix, confirm a
clean worktree, and report interactive/macOS commands without guessing.

---

## Expected commit sequence

```text
docs: design fixed-step physics foundation
docs: plan fixed-step physics foundation
feat(physics): add immutable collision values
feat(physics): add continuous voxel collision queries
feat(physics): add sweep-and-slide collision motion
feat(physics): add shared block raycast service
feat(physics): add rigid-body state foundation
feat(physics): integrate simple voxel physics bodies
feat(physics): add swept player controller
feat(physics): add player traversal and noclip recovery
refactor(player): delegate fixed movement to player controller
refactor(game): compose fixed-step physics and interpolation
test(physics): enforce deterministic physics boundaries
docs: record Phase 6 physics foundation handoff
```

Focused review fixes may add commits when a concrete defect is found.
