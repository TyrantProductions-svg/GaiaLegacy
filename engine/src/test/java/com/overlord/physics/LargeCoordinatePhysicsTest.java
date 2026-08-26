package com.overlord.physics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.overlord.voxel.ChunkAvailability;
import com.overlord.voxel.ChunkCoordinatePolicy;
import com.overlord.voxel.ChunkGenerationMode;
import com.overlord.voxel.ChunkKey;
import com.overlord.voxel.GlobalPosition;
import com.overlord.voxel.World;
import java.util.concurrent.atomic.AtomicInteger;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

class LargeCoordinatePhysicsTest {
    @Test
    void simulationOriginRoundTripsPositiveAndNegativeLargeGlobalPositionsAsSmallLocals() {
        SimulationOrigin origin =
                new SimulationOrigin(new ChunkKey(100_000_000, -100_000_000));
        GlobalPosition global =
                new GlobalPosition(new ChunkKey(100_000_001, -100_000_001), 3.5, 64.25, 12.75);

        Vector3f local = origin.toLocal(global);

        assertEquals(new Vector3f(19.5f, 64.25f, -3.25f), local);
        assertEquals(global, origin.toGlobal(local));
        assertFalse(Math.abs(local.x()) > 32.0f);
        assertFalse(Math.abs(local.z()) > 32.0f);
    }

    @Test
    void simulationOriginRejectsUnsafeKeysAndImpreciselyDistantConversions() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new SimulationOrigin(
                                new ChunkKey(
                                        ChunkCoordinatePolicy.MAX_SAFE_CHUNK_COORDINATE + 1, 0)));

        SimulationOrigin origin = new SimulationOrigin(new ChunkKey(0, 0));
        GlobalPosition distant =
                new GlobalPosition(new ChunkKey(1_000_000, -1_000_000), 0.5, 40.0, 0.5);

        assertThrows(IllegalArgumentException.class, () -> origin.toLocal(distant));
    }

    @Test
    void rebasingBodyMovesPreviousAndCurrentTogetherWithoutChangingVelocity() {
        PhysicsBody body =
                new PhysicsBody(
                        new Aabb(-0.3f, 0.0f, -0.3f, 0.3f, 1.8f, 0.3f),
                        MassProperties.dynamic(2.0f));
        body.teleport(new Vector3f(13.0f, 70.0f, -9.0f));
        body.beginStep();
        body.setPosition(new Vector3f(14.0f, 70.0f, -8.0f));
        body.setLinearVelocity(new Vector3f(2.5f, -1.0f, 0.25f));

        body.rebase(new Vector3f(-16.0f, 0.0f, 32.0f));

        assertEquals(new Vector3f(-2.0f, 70.0f, 24.0f), body.position(new Vector3f()));
        assertEquals(new Vector3f(-3.0f, 70.0f, 23.0f), body.previousPosition(new Vector3f()));
        assertEquals(new Vector3f(-2.5f, 70.0f, 23.5f), body.interpolatedPosition(0.5f, new Vector3f()));
        assertEquals(new Vector3f(2.5f, -1.0f, 0.25f), body.linearVelocity(new Vector3f()));
    }

    @Test
    void preparedBodyRebaseDoesNotMutateUntilItsNonThrowingCommit() {
        PhysicsBody body = new PhysicsBody(
                new Aabb(-0.5f, 0, -0.5f, 0.5f, 1, 0.5f),
                MassProperties.dynamic(1));
        body.teleport(new Vector3f(20, 3, -4));
        body.beginStep();
        body.setPosition(new Vector3f(21, 3, -3));

        PhysicsBody.PreparedRebase prepared =
                body.prepareRebase(new Vector3f(-16, 0, 32));
        assertEquals(new Vector3f(21, 3, -3), body.position(new Vector3f()));
        assertEquals(new Vector3f(20, 3, -4), body.previousPosition(new Vector3f()));

        prepared.commit();
        assertEquals(new Vector3f(5, 3, 29), body.position(new Vector3f()));
        assertEquals(new Vector3f(4, 3, 28), body.previousPosition(new Vector3f()));
    }

    @Test
    void originAwareRaycastReportsUnavailableSpaceBeforeSamplingAndKeepsLargeBlockIdentity() {
        ChunkKey key = new ChunkKey(100_000_000, -100_000_000);
        SimulationOrigin origin = new SimulationOrigin(key);
        World world = new World();
        world.generate(key, chunk -> chunk.setBlock(2, 1, 0, (byte) 7));
        BlockRaycast raycast = new BlockRaycast(world, BlockCollisionShapeResolver.fullCubesForNonAir());
        CollisionWorld collisions =
                new CollisionWorld(world, BlockCollisionShapeResolver.fullCubesForNonAir());

        SpatialQueryResult<BlockRaycastHit> available =
                raycast.cast(
                        origin,
                        new Vector3f(0.5f, 1.5f, 0.5f),
                        new Vector3f(1.0f, 0.0f, 0.0f),
                        4.0f);
        SpatialQueryResult<BlockRaycastHit> unknown =
                raycast.cast(
                        new SimulationOrigin(new ChunkKey(99_999_999, -100_000_000)),
                        new Vector3f(0.5f, 1.5f, 0.5f),
                        new Vector3f(1.0f, 0.0f, 0.0f),
                        4.0f);
        ChunkKey failedKey = new ChunkKey(99_999_998, -100_000_000);
        world.chunks()
                .failGeneration(
                        world.chunks().beginGeneration(failedKey, ChunkGenerationMode.INITIAL),
                        new IllegalStateException("generation failed"));
        SpatialQueryResult<BlockRaycastHit> failed =
                raycast.cast(
                        new SimulationOrigin(failedKey),
                        new Vector3f(0.5f, 1.5f, 0.5f),
                        new Vector3f(1.0f, 0.0f, 0.0f),
                        4.0f);
        SpatialQueryResult<SweepResult> collision =
                collisions.sweep(
                        origin,
                        new Aabb(0.0f, 0.0f, 0.0f, 0.5f, 1.0f, 1.0f),
                        new Vector3f(1.25f, 1.0f, 0.0f),
                        new Vector3f(1.0f, 0.0f, 0.0f));

        assertEquals(ChunkAvailability.AVAILABLE, world.chunks().availability(key));
        assertEquals(SpatialQueryResult.Status.AVAILABLE, available.status());
        assertEquals(1_600_000_002, available.result().orElseThrow().blockX());
        assertEquals(-1_600_000_000, available.result().orElseThrow().blockZ());
        assertEquals(SpatialQueryResult.Status.UNKNOWN, unknown.status());
        assertEquals(new ChunkKey(99_999_999, -100_000_000), unknown.unavailableKey().orElseThrow());
        assertFalse(unknown.result().isPresent());
        assertEquals(SpatialQueryResult.Status.FAILED, failed.status());
        assertEquals(failedKey, failed.unavailableKey().orElseThrow());
        assertFalse(failed.result().isPresent());
        assertEquals(SpatialQueryResult.Status.AVAILABLE, collision.status());
        assertEquals(1_600_000_002, collision.result().orElseThrow().blockX());
    }

    @Test
    void retreatFromLoadedEdgeDoesNotQueryMerelyTouchedUnknownNeighbor() {
        World world = new World();
        world.generate(new ChunkKey(0, 0), ignored -> {});
        CollisionWorld collisions = new CollisionWorld(
                world, BlockCollisionShapeResolver.fullCubesForNonAir());

        SpatialQueryResult<SweepResult> retreat = collisions.sweep(
                new SimulationOrigin(new ChunkKey(0, 0)),
                new Aabb(-0.5f, 0, -0.5f, 0.5f, 1, 0.5f),
                new Vector3f(15.5f, 2, 8),
                new Vector3f(-0.25f, 0, 0));

        assertEquals(SpatialQueryResult.Status.AVAILABLE, retreat.status());
    }

    @Test
    void physicsWorldUsesCommittedOriginAndFailsClosedAtUnknownTerrain() {
        ChunkKey key = new ChunkKey(100_000_000, -100_000_000);
        World world = new World();
        world.generate(key, chunk -> chunk.setBlock(2, 0, 3, (byte) 1));
        CollisionWorld collisions = new CollisionWorld(
                world, BlockCollisionShapeResolver.fullCubesForNonAir());
        PhysicsWorld physics = new PhysicsWorld(collisions, new Vector3f(0, -25, 0));
        physics.prepareOriginRebase(
                new SimulationOrigin(new ChunkKey(0, 0)), new SimulationOrigin(key)).commit();
        PhysicsBody body = new PhysicsBody(
                new Aabb(-0.25f, -0.25f, -0.25f, 0.25f, 0.25f, 0.25f),
                MassProperties.dynamic(1));
        body.teleport(new Vector3f(2.5f, 1.25f, 3.5f));
        physics.addBody(body);

        for (int step = 0; step < 20; step++) {
            physics.step(1.0f / 60.0f);
        }
        assertTrue(body.position(new Vector3f()).y >= 1.24f);

        body.teleport(new Vector3f(15.75f, 2, 3.5f));
        body.setLinearVelocity(new Vector3f(30, 0, 0));
        physics.step(1.0f / 60.0f);
        assertTrue(body.position(new Vector3f()).x <= 15.75f);
    }

    @Test
    void unavailableOriginAwareQueryDoesNotInvokeVoxelShapeSampling() {
        AtomicInteger shapeSamples = new AtomicInteger();
        World world = new World();
        BlockRaycast raycast = new BlockRaycast(world, block -> {
            shapeSamples.incrementAndGet();
            return BlockCollisionShape.empty();
        });

        SpatialQueryResult<BlockRaycastHit> result = raycast.cast(
                new SimulationOrigin(new ChunkKey(5, -7)),
                new Vector3f(1.5f, 2, 1.5f),
                new Vector3f(1, 0, 0),
                2);

        assertEquals(SpatialQueryResult.Status.UNKNOWN, result.status());
        assertEquals(0, shapeSamples.get());
    }
}
