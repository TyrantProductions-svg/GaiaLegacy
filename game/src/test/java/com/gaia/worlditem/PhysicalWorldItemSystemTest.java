package com.gaia.worlditem;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.overlord.assets.ResourceLocation;
import com.overlord.core.thread.MainThreadGuard;
import com.overlord.inventory.api.ItemStack;
import com.overlord.physics.Aabb;
import com.overlord.physics.BlockCollisionShape;
import com.overlord.physics.BlockCollisionShapeResolver;
import com.overlord.physics.CollisionWorld;
import com.overlord.physics.MassProperties;
import com.overlord.physics.PhysicsBody;
import com.overlord.physics.PhysicsWorld;
import com.overlord.physics.SimulationOrigin;
import com.overlord.voxel.World;
import com.overlord.voxel.ChunkDirtyTracker;
import com.overlord.voxel.ChunkKey;
import com.overlord.voxel.ChunkRepository;
import com.overlord.worlditem.LogicalWorldItemService;
import com.overlord.worlditem.LogicalWorldItemTestAccess;
import com.overlord.worlditem.api.WorldItemMotionUpdate;
import com.overlord.worlditem.api.WorldItemMotionUpdateResult;
import com.overlord.worlditem.api.WorldItemId;
import com.overlord.worlditem.api.WorldItemPhysicalSnapshot;
import com.overlord.worlditem.api.WorldItemPhysicalState;
import com.overlord.worlditem.api.WorldItemReservation;
import com.overlord.worlditem.api.WorldItemRuntimeAccess;
import com.overlord.worlditem.api.WorldItemSnapshot;
import com.overlord.worlditem.api.WorldItemSpawnRequest;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

class PhysicalWorldItemSystemTest {
    private static final ItemStack DIRT =
            new ItemStack(ResourceLocation.parse("gaia:dirt"), 1);

    @Test
    void preparedOriginRebaseKeepsCanonicalItemStateAndWritesBackThroughCommittedOrigin() {
        Fixture fixture = fixture(4);
        WorldItemSnapshot spawned = fixture.spawn(17.5, 4.0, 1.5);
        fixture.system.prepareStep(1);
        PhysicsBody body = fixture.physics.bodies().get(0);
        WorldItemSnapshot canonicalBefore = fixture.logical.snapshot(spawned.id()).orElseThrow();
        WorldItemPresentationSnapshot presentationBefore =
                fixture.system.presentationSnapshots().get(0);
        SimulationOrigin oldOrigin = new SimulationOrigin(new ChunkKey(0, 0));
        SimulationOrigin newOrigin = new SimulationOrigin(new ChunkKey(1, 0));

        var prepared = fixture.system.prepareOriginRebase(oldOrigin, newOrigin);

        assertEquals(canonicalBefore, fixture.logical.snapshot(spawned.id()).orElseThrow());
        assertEquals(new Vector3f(17.5f, 4.0f, 1.5f), body.position(new Vector3f()));
        assertEquals(new Vector3f(17.5f, 4.0f, 1.5f), body.previousPosition(new Vector3f()));
        assertEquals(presentationBefore, fixture.system.presentationSnapshots().get(0));

        prepared.commit();

        assertEquals(canonicalBefore, fixture.logical.snapshot(spawned.id()).orElseThrow());
        assertEquals(spawned.id(), fixture.system.presentationSnapshots().get(0).id());
        assertEquals(canonicalBefore.revision(), fixture.system.presentationSnapshots().get(0).revision());
        assertEquals(0.0, fixture.logical.snapshot(spawned.id()).orElseThrow().velocityX());
        assertEquals(new Vector3f(1.5f, 4.0f, 1.5f), body.position(new Vector3f()));
        assertEquals(new Vector3f(1.5f, 4.0f, 1.5f), body.previousPosition(new Vector3f()));
        assertEquals(1.5, fixture.system.presentationSnapshots().get(0).positionX(0.0f));
        assertEquals(1.5, fixture.system.presentationSnapshots().get(0).positionX(1.0f));

        body.setPosition(new Vector3f(2.5f, 4.0f, 1.5f));
        fixture.system.finishStep();

        WorldItemSnapshot writtenBack = fixture.logical.snapshot(spawned.id()).orElseThrow();
        assertEquals(18.5, writtenBack.positionX());
        assertEquals(4.0, writtenBack.positionY());
        assertEquals(1.5, writtenBack.positionZ());
    }

    @Test
    void projectionCreatedAfterLargeOriginCommitLocalizesBeforeFloatConversion() {
        Fixture fixture = fixture(4);
        SimulationOrigin oldOrigin = new SimulationOrigin(new ChunkKey(0, 0));
        SimulationOrigin largeOrigin =
                new SimulationOrigin(new ChunkKey(100_000_000, -100_000_000));
        fixture.system.prepareOriginRebase(oldOrigin, largeOrigin).commit();

        WorldItemSnapshot spawned =
                fixture.spawn(1_600_000_002.5, 4.0, -1_599_999_996.5);
        fixture.system.prepareStep(1);

        PhysicsBody body = fixture.physics.bodies().get(0);
        assertEquals(new Vector3f(2.5f, 4.0f, 3.5f), body.position(new Vector3f()));
        assertEquals(new Vector3f(2.5f, 4.0f, 3.5f), body.previousPosition(new Vector3f()));
        assertEquals(spawned.id(), fixture.system.presentationSnapshots().get(0).id());
        assertEquals(2.5, fixture.system.presentationSnapshots().get(0).positionX(1.0f));
        assertEquals(3.5, fixture.system.presentationSnapshots().get(0).positionZ(1.0f));
        assertEquals(
                1_600_000_002.5,
                fixture.logical.snapshot(spawned.id()).orElseThrow().positionX());
        assertEquals(
                -1_599_999_996.5,
                fixture.logical.snapshot(spawned.id()).orElseThrow().positionZ());
    }

    @Test
    void distantCanonicalCoverageUsesDoublesBeforeLocalFloatConversion() {
        MainThreadGuard guard = MainThreadGuard.captureCurrentThread();
        ChunkKey key = new ChunkKey(100_000_000, -100_000_000);
        ChunkRepository chunks = new ChunkRepository(8, new ChunkDirtyTracker());
        chunks.generate(key, ignored -> {});
        World world = new World(chunks);
        PhysicsWorld physics = new PhysicsWorld(
                new CollisionWorld(world, BlockCollisionShapeResolver.fullCubesForNonAir()),
                new Vector3f());
        LogicalWorldItemService logical = new LogicalWorldItemService(guard, 4, 0);
        PhysicalWorldItemSystem system = new PhysicalWorldItemSystem(
                logical, physics, chunks, guard, new WorldItemPhysicsConfig(0.50f, 4));
        SimulationOrigin zero = new SimulationOrigin(new ChunkKey(0, 0));
        SimulationOrigin origin = new SimulationOrigin(key);
        system.prepareOriginRebase(zero, origin).commit();
        logical.spawn(new WorldItemSpawnRequest(
                DIRT,
                1_600_000_015.9,
                4.0,
                -1_599_999_992.0,
                0.0, 0.0, 0.0, Optional.empty(), 1));

        system.prepareStep(1);

        assertTrue(physics.bodies().isEmpty(),
                "canonical half-extent crosses the unavailable east Chunk");
    }

    @Test
    void exactChunkEdgeTouchWhileRetreatingDoesNotFreezeWorldItem() {
        MainThreadGuard guard = MainThreadGuard.captureCurrentThread();
        ChunkRepository chunks = new ChunkRepository(8, new ChunkDirtyTracker());
        chunks.generate(new ChunkKey(0, 0), ignored -> {});
        PhysicsWorld physics = new PhysicsWorld(
                new CollisionWorld(new World(chunks),
                        BlockCollisionShapeResolver.fullCubesForNonAir()),
                new Vector3f());
        LogicalWorldItemService logical = new LogicalWorldItemService(guard, 4, 0);
        PhysicalWorldItemSystem system = new PhysicalWorldItemSystem(
                logical, physics, chunks, guard, new WorldItemPhysicsConfig(0.50f, 4));
        SimulationOrigin zero = new SimulationOrigin(new ChunkKey(0, 0));
        physics.prepareOriginRebase(zero, zero).commit();
        system.prepareOriginRebase(zero, zero).commit();
        WorldItemSnapshot spawned = logical.spawn(new WorldItemSpawnRequest(
                DIRT, 15.75, 4.0, 8.0, -1.0, 0.0, 0.0,
                Optional.empty(), 1)).item().orElseThrow();

        system.prepareStep(1);

        assertEquals(1, physics.bodies().size());
        assertEquals(spawned.id(), system.presentationSnapshots().get(0).id());
    }

    @Test
    void distantPostRebaseWorldItemSettlesOnCanonicalTerrain() {
        MainThreadGuard guard = MainThreadGuard.captureCurrentThread();
        ChunkKey key = new ChunkKey(100_000_000, -100_000_000);
        ChunkRepository chunks = new ChunkRepository(8, new ChunkDirtyTracker());
        chunks.generate(key, chunk -> chunk.setBlock(2, 0, 3, (byte) 1));
        World world = new World(chunks);
        PhysicsWorld physics = new PhysicsWorld(
                new CollisionWorld(world, BlockCollisionShapeResolver.fullCubesForNonAir()),
                new Vector3f(0, -25, 0));
        LogicalWorldItemService logical = new LogicalWorldItemService(guard, 4, 0);
        PhysicalWorldItemSystem system = new PhysicalWorldItemSystem(
                logical, physics, chunks, guard, new WorldItemPhysicsConfig(0.50f, 4));
        SimulationOrigin zero = new SimulationOrigin(new ChunkKey(0, 0));
        SimulationOrigin origin = new SimulationOrigin(key);
        physics.prepareOriginRebase(zero, origin).commit();
        system.prepareOriginRebase(zero, origin).commit();
        WorldItemSnapshot spawned = logical.spawn(new WorldItemSpawnRequest(
                DIRT,
                1_600_000_002.5,
                1.25,
                -1_599_999_996.5,
                0.0, 0.0, 0.0, Optional.empty(), 1)).item().orElseThrow();

        for (int tick = 1; tick <= 20; tick++) {
            system.prepareStep(tick);
            physics.step(1.0f / 60.0f);
            system.finishStep();
        }

        assertTrue(logical.snapshot(spawned.id()).orElseThrow().positionY() >= 1.24);
        assertEquals(2.5, system.presentationSnapshots().get(0).positionX(1.0f));
    }

    @Test
    void distantPostRebaseRestoreReconcileCreatesResidentLocalProjection() {
        MainThreadGuard guard = MainThreadGuard.captureCurrentThread();
        ChunkKey key = new ChunkKey(100_000_000, -100_000_000);
        ChunkRepository chunks = new ChunkRepository(8, new ChunkDirtyTracker());
        chunks.generate(key, chunk -> chunk.setBlock(2, 0, 3, (byte) 1));
        World world = new World(chunks);
        PhysicsWorld physics = new PhysicsWorld(
                new CollisionWorld(world, BlockCollisionShapeResolver.fullCubesForNonAir()),
                new Vector3f(0, -25, 0));
        LogicalWorldItemService logical = new LogicalWorldItemService(guard, 4, 0);
        PhysicalWorldItemSystem system = new PhysicalWorldItemSystem(
                logical, physics, chunks, guard, new WorldItemPhysicsConfig(0.50f, 4));
        SimulationOrigin zero = new SimulationOrigin(new ChunkKey(0, 0));
        SimulationOrigin origin = new SimulationOrigin(key);
        physics.prepareOriginRebase(zero, origin).commit();
        system.prepareOriginRebase(zero, origin).commit();
        logical.spawn(new WorldItemSpawnRequest(
                DIRT,
                1_600_000_002.5,
                1.5,
                -1_599_999_996.5,
                0.0, 0.0, 0.0, Optional.empty(), 1)).item().orElseThrow();

        system.reconcileRestoredCanonicalState(1L);

        Vector3f residentLocal = physics.bodies().get(0).position(new Vector3f());
        assertEquals(2.5f, residentLocal.x);
        assertEquals(1.5f, residentLocal.y);
        assertEquals(3.5f, residentLocal.z);
    }

    @Test
    void oneStableIdOwnsOneProjectionAndDuplicateReconcileIsIdempotent() {
        Fixture fixture = fixture(4);
        WorldItemSnapshot spawned = fixture.spawn(1.5, 4.0, 1.5);

        fixture.system.prepareStep(1);
        PhysicsBody firstBody = fixture.physics.bodies().get(0);
        fixture.system.prepareStep(1);

        assertEquals(1, fixture.physics.bodies().size());
        assertEquals(1, fixture.system.presentationSnapshots().size());
        assertSame(firstBody, fixture.physics.bodies().get(0));
        assertEquals(spawned.id(), fixture.system.presentationSnapshots()
                .get(0).id());
        assertEquals(1L, fixture.system.metrics().created());
        assertEquals(0L, fixture.system.metrics().destroyed());
    }

    @Test
    void snapshotUpdateKeepsProjectionIdentityAndUpdatesPresentation() {
        Fixture fixture = fixture(4);
        WorldItemSnapshot spawned = fixture.spawn(1.5, 4.0, 1.5);
        fixture.system.prepareStep(1);
        PhysicsBody body = fixture.physics.bodies().get(0);

        WorldItemPhysicalSnapshot updated = fixture.logical.updateMotion(
                new WorldItemMotionUpdate(
                        spawned.id(), spawned.revision(),
                        6.0, 7.0, 8.0, 0.5, 0.0, -0.5,
                        WorldItemPhysicalState.ACTIVE))
                .snapshot().orElseThrow();
        fixture.system.prepareStep(2);

        assertSame(body, fixture.physics.bodies().get(0));
        var presentation = fixture.system.presentationSnapshots().get(0);
        assertEquals(updated.runtime().item().revision(), presentation.revision());
        assertEquals(6.0, presentation.positionX(1.0f));
        assertEquals(7.0, presentation.positionY(1.0f));
        assertEquals(0L, fixture.system.metrics().rebuilt());
    }

    @ParameterizedTest(name = "unrepresentable motion component {0} is atomic")
    @ValueSource(ints = {0, 1, 2, 3, 4, 5})
    void unrepresentableMotionNeverPartiallyMutatesProjection(int component) {
        Fixture fixture = fixture(4);
        WorldItemSnapshot spawned = fixture.spawn(1.5, 4.0, 1.5);
        fixture.system.prepareStep(1);
        PhysicsBody body = fixture.physics.bodies().get(0);
        Vector3f oldPosition = body.position(new Vector3f());
        Vector3f oldVelocity = body.linearVelocity(new Vector3f());
        WorldItemPresentationSnapshot oldPresentation =
                fixture.system.presentationSnapshots().get(0);
        WorldItemPhysicsMetrics oldMetrics = fixture.system.metrics();

        double[] motion = {6.0, 7.0, 8.0, 0.5, -0.25, -0.5};
        motion[component] = Double.MAX_VALUE;
        WorldItemMotionUpdateResult result = fixture.logical.updateMotion(new WorldItemMotionUpdate(
                spawned.id(),
                spawned.revision(),
                motion[0], motion[1], motion[2],
                motion[3], motion[4], motion[5],
                WorldItemPhysicalState.ACTIVE));

        if (component == 0 || component == 2) {
            assertEquals(WorldItemMotionUpdateResult.Status.INVALID_MOTION, result.status());
            assertEquals(spawned, fixture.logical.snapshot(spawned.id()).orElseThrow());
            assertDoesNotThrow(() -> fixture.system.prepareStep(2));
        } else {
            assertEquals(WorldItemMotionUpdateResult.Status.APPLIED, result.status());
            assertThrows(IllegalArgumentException.class, () -> fixture.system.prepareStep(2));
        }

        assertEquals(1, fixture.physics.bodies().size());
        assertSame(body, fixture.physics.bodies().get(0));
        assertEquals(oldPosition, body.position(new Vector3f()));
        assertEquals(oldVelocity, body.linearVelocity(new Vector3f()));
        assertEquals(oldPresentation, fixture.system.presentationSnapshots().get(0));
        assertEquals(oldMetrics, fixture.system.metrics());
    }

    @Test
    void externalBodyMotionWritesThroughCanonicalRuntimeBoundary() {
        Fixture fixture = fixture(4);
        WorldItemSnapshot spawned = fixture.spawn(1.5, 4.0, 1.5);
        fixture.system.prepareStep(1);
        PhysicsBody body = fixture.physics.bodies().get(0);
        body.setPosition(new Vector3f(2.0f, 3.0f, 4.0f));
        body.setLinearVelocity(new Vector3f(1.0f, 2.0f, 3.0f));

        fixture.system.finishStep();

        WorldItemSnapshot canonical = fixture.logical.snapshot(spawned.id()).orElseThrow();
        assertEquals(1L, canonical.revision());
        assertEquals(2.0, canonical.positionX());
        assertEquals(3.0, canonical.positionY());
        assertEquals(1.0, canonical.velocityX());
        assertEquals(3.0, canonical.velocityZ());
    }

    @Test
    void revisionExhaustionRestoresPhysicalScratchWithoutReplacingProjection() {
        Fixture fixture = fixture(4);
        WorldItemSnapshot spawned = fixture.spawn(1.5, 4.0, 1.5);
        fixture.system.prepareStep(1);
        PhysicsBody body = fixture.physics.bodies().get(0);
        LogicalWorldItemTestAccess.forceRevision(
                fixture.logical, spawned.id(), Long.MAX_VALUE);
        fixture.system.prepareStep(2);
        Vector3f canonicalPosition = body.position(new Vector3f());
        Vector3f canonicalVelocity = body.linearVelocity(new Vector3f());
        WorldItemPresentationSnapshot canonicalPresentation =
                fixture.system.presentationSnapshots().get(0);
        WorldItemPhysicsMetrics beforeAttempt = fixture.system.metrics();

        body.setPosition(new Vector3f(9.0f, 8.0f, 7.0f));
        body.setLinearVelocity(new Vector3f(6.0f, 5.0f, 4.0f));
        fixture.system.finishStep();

        assertEquals(1, fixture.physics.bodies().size());
        assertSame(body, fixture.physics.bodies().get(0));
        assertEquals(canonicalPosition, body.position(new Vector3f()));
        assertEquals(canonicalVelocity, body.linearVelocity(new Vector3f()));
        assertEquals(
                canonicalPresentation,
                fixture.system.presentationSnapshots().get(0));
        assertEquals(beforeAttempt, fixture.system.metrics());
        assertEquals(
                Long.MAX_VALUE,
                fixture.logical.snapshot(spawned.id()).orElseThrow().revision());
    }

    @Test
    void logicalTerminalRemovalRemovesOnlyTheProjection() {
        Fixture fixture = fixture(4);
        WorldItemSnapshot spawned = fixture.spawn(1.5, 4.0, 1.5);
        fixture.system.prepareStep(1);
        WorldItemReservation reservation = fixture.logical.reserve(spawned.id(), 1)
                .reservation().orElseThrow();
        fixture.logical.commit(reservation.id());

        fixture.system.prepareStep(2);

        assertTrue(fixture.logical.snapshot(spawned.id()).isEmpty());
        assertTrue(fixture.physics.bodies().isEmpty());
        assertEquals(1L, fixture.system.metrics().destroyed());
    }

    @Test
    void staleWriteDiscardsProjectionAndNextReconcileRebuildsSameStableId() {
        Fixture fixture = fixture(4);
        WorldItemSnapshot spawned = fixture.spawn(1.5, 4.0, 1.5);
        fixture.system.prepareStep(1);
        PhysicsBody oldBody = fixture.physics.bodies().get(0);
        oldBody.setPosition(new Vector3f(9.0f, 9.0f, 9.0f));
        fixture.logical.updateMotion(new WorldItemMotionUpdate(
                spawned.id(), spawned.revision(),
                5.0, 6.0, 7.0, 0.0, 0.0, 0.0,
                WorldItemPhysicalState.ACTIVE));

        fixture.system.finishStep();

        assertTrue(fixture.physics.bodies().isEmpty());
        assertEquals(1L, fixture.system.metrics().destroyed());
        assertEquals(1L, fixture.system.metrics().staleRejections());
        fixture.system.prepareStep(2);
        PhysicsBody rebuilt = fixture.physics.bodies().get(0);
        assertNotSame(oldBody, rebuilt);
        assertEquals(5.0f, rebuilt.position(new Vector3f()).x);
        assertEquals(2L, fixture.system.metrics().created());
        assertEquals(1L, fixture.system.metrics().destroyed());
        assertEquals(1L, fixture.system.metrics().staleRejections());
    }

    @Test
    void externallyLostBodyIsRebuiltForSameStableIdWithoutDeletingLogicalItem() {
        Fixture fixture = fixture(4);
        WorldItemSnapshot spawned = fixture.spawn(1.5, 4.0, 1.5);
        fixture.system.prepareStep(1);
        PhysicsBody oldBody = fixture.physics.bodies().get(0);
        WorldItemPhysicsMetrics before = fixture.system.metrics();

        assertTrue(fixture.physics.removeBody(oldBody));
        fixture.system.prepareStep(2);

        PhysicsBody rebuilt = fixture.physics.bodies().get(0);
        assertNotSame(oldBody, rebuilt);
        assertEquals(2L, fixture.system.metrics().created());
        assertEquals(1L, fixture.system.metrics().rebuilt());
        assertEquals(0L, fixture.system.metrics().destroyed());
        assertEquals(before.lost() + 1, fixture.system.metrics().lost());
        assertEquals(spawned, fixture.logical.snapshot(spawned.id()).orElseThrow());
    }

    @Test
    void lostProjectionDoesNotBypassUnifiedStableIdAdmission() {
        AdmissionFixture fixture = admissionFixture();
        fixture.system.prepareStep(1);
        PhysicsBody oldHigherBody = fixture.physics.bodies().get(0);
        assertEquals(fixture.higher.id(), fixture.system.presentationSnapshots().get(0).id());
        assertTrue(fixture.physics.removeBody(oldHigherBody));

        List<WorldItemPhysicalSnapshot> source = fixture.logical.physicalSnapshots();
        fixture.access.setSnapshots(List.of(source.get(1), source.get(0)));
        fixture.system.prepareStep(2);

        assertEquals(List.of(fixture.lower.id()), fixture.system.presentationSnapshots().stream()
                .map(WorldItemPresentationSnapshot::id)
                .toList());
        WorldItemPhysicsMetrics metrics = fixture.system.metrics();
        assertEquals(1L, metrics.lost());
        assertEquals(0L, metrics.rebuilt());
        assertEquals(2L, metrics.created());
        assertEquals(0L, metrics.destroyed());
        assertEquals(List.of(fixture.higher.id()), metrics.capacitySkippedIds());
        assertEquals(fixture.lower, fixture.logical.snapshot(fixture.lower.id()).orElseThrow());
        assertEquals(fixture.higher, fixture.logical.snapshot(fixture.higher.id()).orElseThrow());
    }

    @Test
    void lostAdmissionIsDeterministicForDuplicateShuffledSnapshots() {
        AdmissionTrace first = lostAdmissionTrace(List.of(1, 0, 1));
        AdmissionTrace second = lostAdmissionTrace(List.of(0, 1, 0, 1));

        assertEquals(first, second);
        assertEquals(List.of(new WorldItemId(0)), first.admittedIds());
        assertEquals(List.of(new WorldItemId(1)), first.capacitySkippedIds());
        assertEquals(1L, first.lost());
        assertEquals(0L, first.rebuilt());
        assertEquals(2L, first.created());
        assertEquals(0L, first.destroyed());
    }

    @Test
    void skippedLostProjectionIsAdmittedAfterLowerIdIsRemoved() {
        AdmissionFixture fixture = admissionFixture();
        fixture.system.prepareStep(1);
        assertTrue(fixture.physics.removeBody(fixture.physics.bodies().get(0)));
        List<WorldItemPhysicalSnapshot> source = fixture.logical.physicalSnapshots();
        fixture.access.setSnapshots(List.of(source.get(1), source.get(0)));
        fixture.system.prepareStep(2);
        PhysicsBody lowerBody = fixture.physics.bodies().get(0);

        WorldItemReservation reservation = fixture.logical.reserve(fixture.lower.id(), 1)
                .reservation().orElseThrow();
        fixture.logical.commit(reservation.id());
        fixture.access.setSnapshots(fixture.logical.physicalSnapshots());
        fixture.system.prepareStep(3);

        assertEquals(List.of(fixture.higher.id()), fixture.system.presentationSnapshots().stream()
                .map(WorldItemPresentationSnapshot::id)
                .toList());
        assertNotSame(lowerBody, fixture.physics.bodies().get(0));
        WorldItemPhysicsMetrics metrics = fixture.system.metrics();
        assertEquals(1L, metrics.lost());
        assertEquals(1L, metrics.rebuilt());
        assertEquals(3L, metrics.created());
        assertEquals(1L, metrics.destroyed());
        assertEquals(List.of(), metrics.capacitySkippedIds());
        assertTrue(fixture.logical.snapshot(fixture.lower.id()).isEmpty());
        assertEquals(fixture.higher, fixture.logical.snapshot(fixture.higher.id()).orElseThrow());
    }

    @Test
    void failedAdmissionBatchDoesNotPublishLostMetricsAndRetriesDeterministically() {
        MainThreadGuard guard = MainThreadGuard.captureCurrentThread();
        LogicalWorldItemService logical = new LogicalWorldItemService(guard, 8, 0);
        PhysicsWorld physics = new PhysicsWorld(
                new CollisionWorld(new World(), BlockCollisionShapeResolver.fullCubesForNonAir()),
                new Vector3f());
        WorldItemSnapshot lower = logical.spawn(new WorldItemSpawnRequest(
                DIRT, 1.5, 4.0, 1.5, 0.0, 0.0, 0.0, Optional.empty(), 1))
                .item().orElseThrow();
        WorldItemSnapshot higher = logical.spawn(new WorldItemSpawnRequest(
                DIRT, 2.5, 4.0, 1.5, 0.0, 0.0, 0.0, Optional.empty(), 1))
                .item().orElseThrow();
        WorldItemPhysicalSnapshot lowerSnapshot = logical.physicalSnapshot(lower.id()).orElseThrow();
        WorldItemPhysicalSnapshot higherSnapshot = logical.physicalSnapshot(higher.id()).orElseThrow();
        ShuffledRuntimeAccess access = new ShuffledRuntimeAccess(logical);
        access.setSnapshots(List.of(lowerSnapshot));
        AtomicBoolean failHigherOnce = new AtomicBoolean(false);
        PhysicalWorldItemSystem system = new PhysicalWorldItemSystem(
                access,
                physics,
                guard,
                new WorldItemPhysicsConfig(0.50f, 2),
                snapshot -> {
                    if (snapshot.id().equals(higher.id()) && failHigherOnce.getAndSet(false)) {
                        throw new IllegalStateException("injected later-candidate failure");
                    }
                    return body(snapshot);
                },
                stage -> {
                });

        system.prepareStep(1);
        PhysicsBody originalLowerBody = physics.bodies().get(0);
        assertTrue(physics.removeBody(originalLowerBody));
        access.setSnapshots(List.of(higherSnapshot, lowerSnapshot));
        failHigherOnce.set(true);

        assertThrows(IllegalStateException.class, () -> system.prepareStep(2));

        assertTrue(physics.bodies().isEmpty());
        assertTrue(system.presentationSnapshots().isEmpty());
        WorldItemPhysicsMetrics failedMetrics = system.metrics();
        assertEquals(0L, failedMetrics.lost());
        assertEquals(0L, failedMetrics.rebuilt());
        assertEquals(2L, failedMetrics.created());
        assertEquals(1L, failedMetrics.destroyed());
        assertEquals(lower, logical.snapshot(lower.id()).orElseThrow());
        assertEquals(higher, logical.snapshot(higher.id()).orElseThrow());

        system.prepareStep(3);

        assertEquals(List.of(lower.id(), higher.id()), system.presentationSnapshots().stream()
                .map(WorldItemPresentationSnapshot::id)
                .toList());
        assertEquals(2, physics.bodies().size());
        assertFalse(physics.bodies().contains(originalLowerBody));
        WorldItemPhysicsMetrics recoveredMetrics = system.metrics();
        assertEquals(1L, recoveredMetrics.lost());
        assertEquals(1L, recoveredMetrics.rebuilt());
        assertEquals(4L, recoveredMetrics.created());
        assertEquals(1L, recoveredMetrics.destroyed());
        assertEquals(lower, logical.snapshot(lower.id()).orElseThrow());
        assertEquals(higher, logical.snapshot(higher.id()).orElseThrow());
    }

    @Test
    void reservationMetadataRefreshesWithoutReplacingUnchangedMotionProjection() {
        Fixture fixture = fixture(4);
        WorldItemSnapshot spawned = fixture.spawn(1.5, 4.0, 1.5);
        fixture.system.prepareStep(1);
        PhysicsBody body = fixture.physics.bodies().get(0);
        long revision = fixture.system.presentationSnapshots().get(0).revision();
        assertFalse(fixture.system.presentationSnapshots().get(0)
                .runtime().extractionReserved());

        WorldItemReservation reservation = fixture.logical.reserve(spawned.id(), 1)
                .reservation().orElseThrow();
        fixture.system.prepareStep(2);

        var reserved = fixture.system.presentationSnapshots().get(0);
        assertSame(body, fixture.physics.bodies().get(0));
        assertTrue(reserved.runtime().extractionReserved());
        assertEquals(revision, reserved.revision());
        assertEquals(0L, fixture.system.metrics().rebuilt());

        fixture.logical.rollback(reservation.id());
        fixture.system.prepareStep(3);

        var released = fixture.system.presentationSnapshots().get(0);
        assertSame(body, fixture.physics.bodies().get(0));
        assertFalse(released.runtime().extractionReserved());
        assertEquals(revision, released.revision());
        assertEquals(0L, fixture.system.metrics().rebuilt());
    }

    @Test
    void negativeZeroAndVeryLargeRepresentableCoordinatesRemainStable() {
        Fixture fixture = fixture(4);
        WorldItemSnapshot negative = fixture.logical.spawn(new WorldItemSpawnRequest(
                DIRT, -12.5, -3.0, -8.25, 0.0, 0.0, 0.0, Optional.empty(), 1))
                .item().orElseThrow();
        double large = 1.0e30;
        WorldItemSnapshot largeItem = fixture.logical.spawn(new WorldItemSpawnRequest(
                DIRT, large, large, large, 0.0, 0.0, 0.0, Optional.empty(), 1))
                .item().orElseThrow();

        fixture.system.prepareStep(1);

        assertEquals(List.of(negative.id(), largeItem.id()),
                fixture.system.presentationSnapshots().stream()
                        .map(WorldItemPresentationSnapshot::id)
                        .toList());
        PhysicsBody negativeBody = fixture.physics.bodies().get(0);
        PhysicsBody largeBody = fixture.physics.bodies().get(1);
        assertEquals(-12.5f, negativeBody.position(new Vector3f()).x);
        assertEquals(0.0f, negativeBody.linearVelocity(new Vector3f()).length());
        assertEquals((float) large, largeBody.position(new Vector3f()).x);
        assertEquals(0.0f, largeBody.linearVelocity(new Vector3f()).length());
    }

    @Test
    void capacityBoundarySkipsWithoutPartiallyCreatingBodies() {
        Fixture fixture = fixture(1);
        WorldItemSnapshot lower = fixture.spawn(1.5, 4.0, 1.5);
        WorldItemSnapshot higher = fixture.spawn(2.5, 4.0, 1.5);

        fixture.system.prepareStep(1);

        assertEquals(1, fixture.physics.bodies().size());
        assertEquals(lower.id(), fixture.system.presentationSnapshots().get(0).id());
        assertEquals(List.of(higher.id()), fixture.system.metrics().capacitySkippedIds());
    }

    @Test
    void capacityAdmitsStableLowestIdAndRecoversNextIdWhenCapacityFrees() {
        Fixture fixture = fixture(1);
        WorldItemSnapshot lower = fixture.spawn(1.5, 4.0, 1.5);
        WorldItemSnapshot higher = fixture.spawn(2.5, 4.0, 1.5);

        assertDoesNotThrow(() -> fixture.system.prepareStep(1));
        assertEquals(List.of(lower.id()),
                fixture.system.presentationSnapshots().stream()
                        .map(WorldItemPresentationSnapshot::id)
                        .toList());
        assertEquals(List.of(higher.id()), fixture.system.metrics().capacitySkippedIds());
        assertEquals(1L, fixture.system.metrics().capacitySkipped());
        fixture.system.finishStep();

        WorldItemReservation reservation = fixture.logical.reserve(lower.id(), 1)
                .reservation().orElseThrow();
        fixture.logical.commit(reservation.id());
        fixture.system.prepareStep(2);

        assertEquals(List.of(higher.id()),
                fixture.system.presentationSnapshots().stream()
                        .map(WorldItemPresentationSnapshot::id)
                        .toList());
        assertEquals(2L, fixture.system.metrics().created());
        assertEquals(1L, fixture.system.metrics().destroyed());
        assertEquals(1L, fixture.system.metrics().capacitySkipped());
        assertEquals(List.of(), fixture.system.metrics().capacitySkippedIds());
    }

    @Test
    void shuffledAndDuplicateSourceSnapshotsHaveOneDeterministicAdmissionTrace() {
        MainThreadGuard guard = MainThreadGuard.captureCurrentThread();
        LogicalWorldItemService logical = new LogicalWorldItemService(guard, 8, 0);
        World world = new World();
        PhysicsWorld physics = new PhysicsWorld(
                new CollisionWorld(world, BlockCollisionShapeResolver.fullCubesForNonAir()),
                new Vector3f());
        WorldItemSnapshot lower = logical.spawn(new WorldItemSpawnRequest(
                DIRT, 1.5, 4.0, 1.5, 0.0, 0.0, 0.0, Optional.empty(), 1))
                .item().orElseThrow();
        WorldItemSnapshot higher = logical.spawn(new WorldItemSpawnRequest(
                DIRT, 2.5, 4.0, 1.5, 0.0, 0.0, 0.0, Optional.empty(), 1))
                .item().orElseThrow();
        ShuffledRuntimeAccess access = new ShuffledRuntimeAccess(logical);
        List<WorldItemPhysicalSnapshot> snapshots = logical.physicalSnapshots();
        access.setSnapshots(List.of(snapshots.get(1), snapshots.get(0), snapshots.get(1)));
        PhysicalWorldItemSystem system = new PhysicalWorldItemSystem(
                access,
                physics,
                guard,
                new WorldItemPhysicsConfig(0.50f, 1));

        system.prepareStep(1);

        assertEquals(List.of(lower.id()), system.presentationSnapshots().stream()
                .map(WorldItemPresentationSnapshot::id)
                .toList());
        assertEquals(List.of(higher.id()), system.metrics().capacitySkippedIds());
        assertEquals(1L, system.metrics().created());
        assertEquals(0L, system.metrics().destroyed());
        system.close();
    }

    @Test
    void partialConstructionFailureRollsBackAlreadyAddedBodies() {
        Fixture fixture = fixture(4, snapshot -> {
            if (snapshot.id().value() == 1) {
                throw new IllegalStateException("injected projection failure");
            }
            return body(snapshot);
        });
        fixture.spawn(1.5, 4.0, 1.5);
        fixture.spawn(2.5, 4.0, 1.5);

        assertThrows(IllegalStateException.class, () -> fixture.system.prepareStep(1));
        assertTrue(fixture.physics.bodies().isEmpty());
        assertEquals(0, fixture.system.metrics().liveProjections());
        assertEquals(1L, fixture.system.metrics().created());
        assertEquals(1L, fixture.system.metrics().destroyed());
    }

    @ParameterizedTest(name = "construction failure at {0} rolls back")
    @EnumSource(PhysicalWorldItemSystem.ProjectionConstructionStage.class)
    void everyConstructionFailureStageRollsBackAndCanRecover(
            PhysicalWorldItemSystem.ProjectionConstructionStage failureStage) {
        AtomicBoolean injectFailure = new AtomicBoolean(true);
        Fixture fixture = fixture(
                4,
                PhysicalWorldItemSystem::createDefaultBody,
                reachedStage -> {
                    if (reachedStage == failureStage
                            && injectFailure.getAndSet(false)) {
                        throw new IllegalStateException(
                                "injected construction failure at " + reachedStage);
                    }
                });
        WorldItemSnapshot spawned = fixture.spawn(1.5, 4.0, 1.5);
        long expectedUnregisters = switch (failureStage) {
            case AFTER_REGISTRATION, AFTER_MAP_INSERTION -> 1L;
            default -> 0L;
        };

        assertThrows(IllegalStateException.class, () -> fixture.system.prepareStep(1));

        assertTrue(fixture.physics.bodies().isEmpty());
        assertEquals(0, fixture.system.metrics().liveProjections());
        assertEquals(0L, fixture.system.metrics().created());
        assertEquals(expectedUnregisters, fixture.system.metrics().destroyed());
        assertEquals(spawned, fixture.logical.snapshot(spawned.id()).orElseThrow());

        fixture.system.prepareStep(2);

        assertEquals(1, fixture.physics.bodies().size());
        assertEquals(1, fixture.system.metrics().liveProjections());
        assertEquals(1L, fixture.system.metrics().created());
        assertEquals(expectedUnregisters, fixture.system.metrics().destroyed());
        assertEquals(spawned.id(), fixture.system.presentationSnapshots().get(0).id());
        assertEquals(spawned, fixture.logical.snapshot(spawned.id()).orElseThrow());
    }

    @Test
    void presentationIsStableIdOrderedAndInterpolatedWithoutExposingMutableProjection() {
        Fixture fixture = fixture(4);
        WorldItemSnapshot first = fixture.spawn(1.5, 4.0, 1.5);
        WorldItemSnapshot second = fixture.spawn(2.5, 4.0, 1.5);
        fixture.system.prepareStep(1);
        fixture.physics.bodies().get(0).setPosition(new Vector3f(3.0f, 4.0f, 5.0f));
        fixture.system.finishStep();

        var snapshots = fixture.system.presentationSnapshots();
        assertEquals(List.of(first.id(), second.id()),
                snapshots.stream().map(snapshot -> snapshot.id()).toList());
        assertEquals(2.25, snapshots.get(0).positionX(0.5f));
        assertThrows(UnsupportedOperationException.class, snapshots::clear);
    }

    @Test
    void presentationSnapshotsExposeUninterpolatedValuesAndCallersInterpolateOnce() {
        Fixture fixture = fixture(4);
        fixture.spawn(1.5, 4.0, 1.5);
        fixture.system.prepareStep(1);

        assertEquals(1, fixture.system.presentationSnapshots().size());
        assertEquals(1.5, fixture.system.presentationSnapshots().get(0).positionX(0.5f));
    }

    @Test
    void everyPublicProjectionOperationRejectsWorkerThreadAccess() throws InterruptedException {
        Fixture fixture = fixture(4);
        fixture.spawn(1.5, 4.0, 1.5);
        List<Runnable> operations = List.of(
                () -> fixture.system.prepareStep(1),
                () -> fixture.system.step(1),
                fixture.system::finishStep,
                fixture.system::abortStep,
                fixture.system::presentationSnapshots,
                fixture.system::metrics,
                fixture.system::close);

        for (Runnable operation : operations) {
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread worker = new Thread(() -> {
                try {
                    operation.run();
                } catch (Throwable thrown) {
                    failure.set(thrown);
                }
            }, "physical-world-item-worker");
            worker.start();
            worker.join();
            assertTrue(failure.get() instanceof IllegalStateException);
        }
        fixture.system.close();
    }

    @Test
    void closeIsMainThreadOnlyIdempotentAndDoesNotDeleteLogicalItems()
            throws InterruptedException {
        Fixture fixture = fixture(4);
        WorldItemSnapshot spawned = fixture.spawn(1.5, 4.0, 1.5);
        fixture.system.prepareStep(1);

        fixture.system.close();
        fixture.system.close();

        assertTrue(fixture.physics.bodies().isEmpty());
        assertTrue(fixture.logical.snapshot(spawned.id()).isPresent());
        assertTrue(fixture.system.presentationSnapshots().isEmpty());

        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            try {
                fixture.system.metrics();
            } catch (Throwable thrown) {
                failure.set(thrown);
            }
        }, "physical-world-item-worker");
        worker.start();
        worker.join();
        assertTrue(failure.get() instanceof IllegalStateException);
    }

    @Test
    void writebackExceptionDiscardsIntegratedBodyAndCanonicalWinsOnRetry() {
        MainThreadGuard guard = MainThreadGuard.captureCurrentThread();
        LogicalWorldItemService logical = new LogicalWorldItemService(guard, 4, 0);
        ThrowingRuntimeAccess access = new ThrowingRuntimeAccess(logical);
        PhysicsWorld physics = new PhysicsWorld(
                new CollisionWorld(new World(), BlockCollisionShapeResolver.fullCubesForNonAir()),
                new Vector3f(0.0f, -25.0f, 0.0f));
        PhysicalWorldItemSystem system = new PhysicalWorldItemSystem(
                access, physics, guard, new WorldItemPhysicsConfig(0.50f, 4));
        WorldItemSnapshot item = logical.spawn(new WorldItemSpawnRequest(
                DIRT, 1.5, 4.0, 1.5, 0.0, 0.0, 0.0, Optional.empty(), 1))
                .item().orElseThrow();

        system.prepareStep(1);
        physics.step(1.0f / 60.0f);
        access.failNextUpdate.set(true);

        assertThrows(IllegalStateException.class, system::finishStep);
        assertTrue(physics.bodies().isEmpty());
        assertTrue(system.presentationSnapshots().isEmpty());
        assertEquals(item, logical.snapshot(item.id()).orElseThrow());

        system.prepareStep(2);
        assertEquals(1, physics.bodies().size());
        assertEquals(4.0f, physics.bodies().get(0).position(new Vector3f()).y);
    }

    @Test
    void collisionQueryExceptionAbortsCompleteStepWithoutCanonicalWriteback() {
        MainThreadGuard guard = MainThreadGuard.captureCurrentThread();
        LogicalWorldItemService logical = new LogicalWorldItemService(guard, 4, 0);
        AtomicBoolean failCollision = new AtomicBoolean(false);
        CollisionWorld collisions = new CollisionWorld(
                new World(),
                block -> {
                    if (failCollision.get()) {
                        throw new IllegalStateException("injected collision failure");
                    }
                    return block == 0
                            ? BlockCollisionShape.empty()
                            : BlockCollisionShape.fullCube();
                });
        PhysicsWorld physics = new PhysicsWorld(
                collisions, new Vector3f(0.0f, -25.0f, 0.0f));
        PhysicalWorldItemSystem system = new PhysicalWorldItemSystem(
                logical, physics, guard, new WorldItemPhysicsConfig(0.50f, 4));
        WorldItemSnapshot item = logical.spawn(new WorldItemSpawnRequest(
                DIRT, 1.5, 4.0, 1.5, 0.0, 0.0, 0.0, Optional.empty(), 1))
                .item().orElseThrow();
        failCollision.set(true);

        assertThrows(IllegalStateException.class, () -> system.step(1));

        assertEquals(item, logical.snapshot(item.id()).orElseThrow());
        assertTrue(physics.bodies().isEmpty());
        assertTrue(system.presentationSnapshots().isEmpty());
    }

    @Test
    void failedPreparationDoesNotPublishLostOrRebuiltMetricsBeforeRetry() {
        MainThreadGuard guard = MainThreadGuard.captureCurrentThread();
        ChunkRepository chunks = new ChunkRepository(32, new ChunkDirtyTracker());
        chunks.generate(new ChunkKey(0, 0), ignored -> {
        });
        World world = new World(chunks);
        AtomicBoolean failCollision = new AtomicBoolean(false);
        CollisionWorld collisions = new CollisionWorld(
                world,
                block -> {
                    if (failCollision.get()) {
                        throw new IllegalStateException("injected prepare failure");
                    }
                    return block == 0
                            ? BlockCollisionShape.empty()
                            : BlockCollisionShape.fullCube();
                });
        PhysicsWorld physics = new PhysicsWorld(collisions, new Vector3f());
        LogicalWorldItemService logical = new LogicalWorldItemService(guard, 4, 0);
        PhysicalWorldItemSystem system = new PhysicalWorldItemSystem(
                logical,
                physics,
                chunks,
                guard,
                new WorldItemPhysicsConfig(0.50f, 4));
        WorldItemSnapshot item = logical.spawn(new WorldItemSpawnRequest(
                DIRT, 1.5, 4.0, 1.5, 0.0, 0.0, 0.0, Optional.empty(), 1))
                .item().orElseThrow();
        system.prepareStep(1);
        PhysicsBody lostBody = physics.bodies().get(0);
        assertTrue(physics.removeBody(lostBody));
        failCollision.set(true);

        assertThrows(IllegalStateException.class, () -> system.prepareStep(2));

        assertTrue(physics.bodies().isEmpty());
        assertTrue(system.presentationSnapshots().isEmpty());
        assertEquals(0L, system.metrics().lost());
        assertEquals(0L, system.metrics().rebuilt());
        assertEquals(item, logical.snapshot(item.id()).orElseThrow());

        failCollision.set(false);
        system.prepareStep(3);
        assertEquals(1, physics.bodies().size());
        assertEquals(1L, system.metrics().lost());
        assertEquals(1L, system.metrics().rebuilt());
    }

    @Test
    void laterRetainedCollisionFailureDiscardsEarlierRecoveryScratch() {
        MainThreadGuard guard = MainThreadGuard.captureCurrentThread();
        ChunkRepository chunks = new ChunkRepository(32, new ChunkDirtyTracker());
        chunks.generate(new ChunkKey(0, 0), ignored -> {
        });
        World world = new World(chunks);
        CollisionWorld collisions = new CollisionWorld(
                world,
                block -> {
                    if (block == 2) {
                        throw new IllegalStateException("injected later collision failure");
                    }
                    return block == 0
                            ? BlockCollisionShape.empty()
                            : BlockCollisionShape.fullCube();
                });
        PhysicsWorld physics = new PhysicsWorld(collisions, new Vector3f());
        LogicalWorldItemService logical = new LogicalWorldItemService(guard, 4, 0);
        PhysicalWorldItemSystem system = new PhysicalWorldItemSystem(
                logical,
                physics,
                chunks,
                guard,
                new WorldItemPhysicsConfig(0.50f, 4));
        WorldItemSnapshot first = logical.spawn(new WorldItemSpawnRequest(
                DIRT, 1.5, 4.5, 1.5, 0.0, 0.0, 0.0, Optional.empty(), 1))
                .item().orElseThrow();
        WorldItemSnapshot second = logical.spawn(new WorldItemSpawnRequest(
                DIRT, 5.5, 4.5, 1.5, 0.0, 0.0, 0.0, Optional.empty(), 1))
                .item().orElseThrow();
        system.prepareStep(1);
        world.setBlock(1, 4, 1, (byte) 1);
        world.setBlock(5, 4, 1, (byte) 2);

        assertThrows(IllegalStateException.class, () -> system.prepareStep(2));

        assertTrue(physics.bodies().isEmpty());
        assertTrue(system.presentationSnapshots().isEmpty());
        assertEquals(first, logical.snapshot(first.id()).orElseThrow());
        assertEquals(second, logical.snapshot(second.id()).orElseThrow());
    }

    @Test
    void laterPreparationFailureDoesNotPublishEarlierRecoveryDiagnostics() {
        MainThreadGuard guard = MainThreadGuard.captureCurrentThread();
        ChunkRepository chunks = new ChunkRepository(16, new ChunkDirtyTracker());
        chunks.generate(new ChunkKey(0, 0), ignored -> {});
        World world = new World(chunks);
        for (int x = 1; x <= 3; x++) {
            for (int y = 0; y < 16; y++) {
                for (int z = 1; z <= 3; z++) {
                    world.setBlock(x, y, z, (byte) 1);
                }
            }
        }
        world.setBlock(5, 4, 1, (byte) 2);
        CollisionWorld collisions = new CollisionWorld(
                world,
                block -> {
                    if (block == 2) {
                        throw new IllegalStateException("injected later collision failure");
                    }
                    return block == 0
                            ? BlockCollisionShape.empty()
                            : BlockCollisionShape.fullCube();
                });
        PhysicsWorld physics = new PhysicsWorld(collisions, new Vector3f());
        LogicalWorldItemService logical = new LogicalWorldItemService(guard, 4, 0);
        PhysicalWorldItemSystem system = new PhysicalWorldItemSystem(
                logical,
                physics,
                chunks,
                guard,
                new WorldItemPhysicsConfig(
                        0.50f, -30.0f, 0.12f, 0.25f, 0.02f,
                        0.05f, 30, 8, 16, 3.5f));
        WorldItemSnapshot enclosed = logical.spawn(new WorldItemSpawnRequest(
                DIRT, 2.5, 0.5, 2.5, 0.0, 0.0, 0.0, Optional.empty(), 1))
                .item().orElseThrow();
        WorldItemSnapshot failing = logical.spawn(new WorldItemSpawnRequest(
                DIRT, 5.5, 4.5, 1.5, 0.0, 0.0, 0.0, Optional.empty(), 1))
                .item().orElseThrow();
        WorldItemPhysicsMetrics before = system.metrics();

        assertThrows(IllegalStateException.class, () -> system.prepareStep(1));

        assertTrue(physics.bodies().isEmpty());
        assertEquals(before.recoveryFailures(), system.metrics().recoveryFailures());
        assertEquals(before.recoveryBlockedIds(), system.metrics().recoveryBlockedIds());
        assertEquals(enclosed, logical.snapshot(enclosed.id()).orElseThrow());
        assertEquals(failing, logical.snapshot(failing.id()).orElseThrow());
    }

    private static Fixture fixture(int maxProjections) {
        return fixture(maxProjections, PhysicalWorldItemSystem::createDefaultBody);
    }

    private static AdmissionFixture admissionFixture() {
        MainThreadGuard guard = MainThreadGuard.captureCurrentThread();
        LogicalWorldItemService logical = new LogicalWorldItemService(guard, 8, 0);
        World world = new World();
        PhysicsWorld physics = new PhysicsWorld(
                new CollisionWorld(world, BlockCollisionShapeResolver.fullCubesForNonAir()),
                new Vector3f());
        WorldItemSnapshot lower = logical.spawn(new WorldItemSpawnRequest(
                DIRT, 1.5, 4.0, 1.5, 0.0, 0.0, 0.0, Optional.empty(), 1))
                .item().orElseThrow();
        WorldItemSnapshot higher = logical.spawn(new WorldItemSpawnRequest(
                DIRT, 2.5, 4.0, 1.5, 0.0, 0.0, 0.0, Optional.empty(), 1))
                .item().orElseThrow();
        ShuffledRuntimeAccess access = new ShuffledRuntimeAccess(logical);
        WorldItemPhysicalSnapshot higherSnapshot = logical.physicalSnapshot(higher.id()).orElseThrow();
        access.setSnapshots(List.of(higherSnapshot));
        PhysicalWorldItemSystem system = new PhysicalWorldItemSystem(
                access,
                physics,
                guard,
                new WorldItemPhysicsConfig(0.50f, 1));
        return new AdmissionFixture(logical, physics, access, system, lower, higher);
    }

    private static AdmissionTrace lostAdmissionTrace(List<Integer> order) {
        AdmissionFixture fixture = admissionFixture();
        fixture.system.prepareStep(1);
        assertTrue(fixture.physics.removeBody(fixture.physics.bodies().get(0)));
        WorldItemPhysicalSnapshot lower = fixture.logical.physicalSnapshot(fixture.lower.id()).orElseThrow();
        WorldItemPhysicalSnapshot higher = fixture.logical.physicalSnapshot(fixture.higher.id()).orElseThrow();
        List<WorldItemPhysicalSnapshot> source = order.stream()
                .map(index -> index == 0 ? lower : higher)
                .toList();
        fixture.access.setSnapshots(source);
        fixture.system.prepareStep(2);
        WorldItemPhysicsMetrics metrics = fixture.system.metrics();
        return new AdmissionTrace(
                fixture.system.presentationSnapshots().stream()
                        .map(WorldItemPresentationSnapshot::id)
                        .toList(),
                metrics.capacitySkippedIds(),
                metrics.lost(),
                metrics.rebuilt(),
                metrics.created(),
                metrics.destroyed());
    }

    private static Fixture fixture(
            int maxProjections,
            PhysicalWorldItemSystem.ProjectionFactory factory) {
        return fixture(
                maxProjections,
                factory,
                stage -> {
                });
    }

    private static Fixture fixture(
            int maxProjections,
            PhysicalWorldItemSystem.ProjectionFactory factory,
            PhysicalWorldItemSystem.ProjectionConstructionObserver observer) {
        MainThreadGuard guard = MainThreadGuard.captureCurrentThread();
        LogicalWorldItemService logical = new LogicalWorldItemService(guard, 8, 0);
        World world = new World();
        CollisionWorld collisions = new CollisionWorld(
                world, BlockCollisionShapeResolver.fullCubesForNonAir());
        PhysicsWorld physics = new PhysicsWorld(collisions, new Vector3f());
        PhysicalWorldItemSystem system = new PhysicalWorldItemSystem(
                logical,
                physics,
                guard,
                new WorldItemPhysicsConfig(0.50f, maxProjections),
                factory,
                observer);
        return new Fixture(logical, physics, system);
    }

    private static PhysicsBody body(WorldItemPhysicalSnapshot snapshot) {
        return PhysicalWorldItemSystem.createDefaultBody(snapshot, 0.50f);
    }

    private static final class ShuffledRuntimeAccess implements WorldItemRuntimeAccess {
        private final LogicalWorldItemService delegate;
        private List<WorldItemPhysicalSnapshot> snapshots = List.of();

        private ShuffledRuntimeAccess(LogicalWorldItemService delegate) {
            this.delegate = delegate;
        }

        private void setSnapshots(List<WorldItemPhysicalSnapshot> snapshots) {
            this.snapshots = List.copyOf(snapshots);
        }

        @Override
        public List<WorldItemPhysicalSnapshot> physicalSnapshots() {
            return snapshots;
        }

        @Override
        public Optional<WorldItemPhysicalSnapshot> physicalSnapshot(
                com.overlord.worlditem.api.WorldItemId itemId) {
            return delegate.physicalSnapshot(itemId);
        }

        @Override
        public com.overlord.worlditem.api.WorldItemMotionUpdateResult updateMotion(
                WorldItemMotionUpdate update) {
            return delegate.updateMotion(update);
        }
    }

    private static final class ThrowingRuntimeAccess implements WorldItemRuntimeAccess {
        private final LogicalWorldItemService delegate;
        private final AtomicBoolean failNextUpdate = new AtomicBoolean(false);

        private ThrowingRuntimeAccess(LogicalWorldItemService delegate) {
            this.delegate = delegate;
        }

        @Override
        public List<WorldItemPhysicalSnapshot> physicalSnapshots() {
            return delegate.physicalSnapshots();
        }

        @Override
        public Optional<WorldItemPhysicalSnapshot> physicalSnapshot(WorldItemId itemId) {
            return delegate.physicalSnapshot(itemId);
        }

        @Override
        public com.overlord.worlditem.api.WorldItemMotionUpdateResult updateMotion(
                WorldItemMotionUpdate update) {
            if (failNextUpdate.getAndSet(false)) {
                throw new IllegalStateException("injected writeback failure");
            }
            return delegate.updateMotion(update);
        }
    }

    private record Fixture(
            LogicalWorldItemService logical,
            PhysicsWorld physics,
            PhysicalWorldItemSystem system) {
        private WorldItemSnapshot spawn(double x, double y, double z) {
            return logical.spawn(new WorldItemSpawnRequest(
                    DIRT, x, y, z, 0.0, 0.0, 0.0, Optional.empty(), 1))
                    .item().orElseThrow();
        }
    }

    private record AdmissionFixture(
            LogicalWorldItemService logical,
            PhysicsWorld physics,
            ShuffledRuntimeAccess access,
            PhysicalWorldItemSystem system,
            WorldItemSnapshot lower,
            WorldItemSnapshot higher) {
    }

    private record AdmissionTrace(
            List<WorldItemId> admittedIds,
            List<WorldItemId> capacitySkippedIds,
            long lost,
            long rebuilt,
            long created,
            long destroyed) {
    }
}
