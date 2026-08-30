package com.gaia;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.overlord.physics.Aabb;
import com.overlord.physics.BlockCollisionShapeResolver;
import com.overlord.physics.BlockRaycast;
import com.overlord.physics.CollisionWorld;
import com.overlord.physics.DetailRaycastTarget;
import com.overlord.voxel.ChunkDetailMutation;
import com.overlord.voxel.ChunkDetailMutationOutcome;
import com.overlord.voxel.ChunkKey;
import com.overlord.voxel.LocalSubVoxelPosition;
import com.overlord.voxel.ParentCellObservation;
import com.overlord.voxel.World;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

class PhysicsDetailCompositionTest {
    @Test
    void productionSharedShapeResolverSupportsFullAndDetailCollision() {
        World world = new World();
        world.generate(new ChunkKey(0, 0), ignored -> {});
        assertTrue(world.setBlock(1, 0, 0, (byte) 7));
        placeDetail(
                world,
                0,
                0,
                0,
                new LocalSubVoxelPosition(0, 0, 0),
                (byte) 9);
        BlockCollisionShapeResolver shapes =
                BlockCollisionShapeResolver.fullCubesForNonAir();
        CollisionWorld collisions = new CollisionWorld(world, shapes);

        assertTrue(collisions.overlapsSolid(
                new Aabb(0.05f, 0.05f, 0.05f,
                        0.20f, 0.20f, 0.20f)));
        assertTrue(collisions.overlapsSolid(
                new Aabb(1.05f, 0.05f, 0.05f,
                        1.20f, 0.20f, 0.20f)));
    }

    @Test
    void productionSharedResolverKeepsRaycastAndCollisionOnSameCanonicalDetail() {
        World world = new World();
        world.generate(new ChunkKey(0, 0), ignored -> {});
        LocalSubVoxelPosition position = new LocalSubVoxelPosition(2, 1, 1);
        placeDetail(world, 0, 0, 0, position, (byte) 9);
        BlockCollisionShapeResolver shapes =
                BlockCollisionShapeResolver.fullCubesForNonAir();
        CollisionWorld collisions = new CollisionWorld(world, shapes);
        BlockRaycast raycast = new BlockRaycast(world, shapes);

        assertTrue(collisions.overlapsSolid(
                new Aabb(0.55f, 0.30f, 0.30f,
                        0.70f, 0.45f, 0.45f)));
        DetailRaycastTarget target = (DetailRaycastTarget) raycast.cast(
                        new Vector3f(0.0f, 0.375f, 0.375f),
                        new Vector3f(1, 0, 0),
                        1)
                .orElseThrow()
                .target();

        assertEquals(position, target.position());
    }

    private static void placeDetail(
            World world,
            int parentX,
            int parentY,
            int parentZ,
            LocalSubVoxelPosition position,
            byte blockId) {
        ParentCellObservation observation = world.observeCell(
                parentX, parentY, parentZ).observation().orElseThrow();
        ChunkDetailMutationOutcome outcome = world.chunks().mutateDetail(
                new ChunkDetailMutation.SetSubVoxel(
                        parentX,
                        parentY,
                        parentZ,
                        observation.chunkRevision(),
                        observation.state(),
                        position,
                        blockId));
        assertEquals(ChunkDetailMutationOutcome.Status.APPLIED, outcome.status());
    }
}
