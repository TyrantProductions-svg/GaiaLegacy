package com.overlord.physics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.overlord.voxel.ChunkDetailMutation;
import com.overlord.voxel.ChunkDetailMutationOutcome;
import com.overlord.voxel.ChunkKey;
import com.overlord.voxel.LocalSubVoxelPosition;
import com.overlord.voxel.ParentCellObservation;
import com.overlord.voxel.World;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class DetailCollisionArchitectureTest {
    @Test
    void collisionWorldOwnsOnlyCanonicalWorldShapeResolverAndStatelessMerger() {
        Set<Class<?>> instanceDependencies = Arrays.stream(
                        CollisionWorld.class.getDeclaredFields())
                .filter(field -> !Modifier.isStatic(field.getModifiers()))
                .map(java.lang.reflect.Field::getType)
                .collect(Collectors.toSet());

        assertEquals(
                Set.of(
                        World.class,
                        BlockCollisionShapeResolver.class,
                        DetailCollisionBoxMerger.class),
                instanceDependencies);
        assertTrue(Modifier.isFinal(CollisionWorld.class.getModifiers()));
        assertTrue(Arrays.stream(DetailCollisionBoxMerger.class.getDeclaredFields())
                .allMatch(field -> Modifier.isStatic(field.getModifiers())
                        && Modifier.isFinal(field.getModifiers())
                        && field.getType().isPrimitive()));
    }

    @Test
    void typedDetailCollisionDoesNotMutateCanonicalRevisionOrState() {
        World world = new World();
        ChunkKey key = new ChunkKey(0, 0);
        world.generate(key, ignored -> {});
        LocalSubVoxelPosition position = new LocalSubVoxelPosition(0, 0, 0);
        ParentCellObservation full = world.observeCell(0, 0, 0)
                .observation().orElseThrow();
        ChunkDetailMutationOutcome placed = world.chunks().mutateDetail(
                new ChunkDetailMutation.SetSubVoxel(
                        0,
                        0,
                        0,
                        full.chunkRevision(),
                        full.state(),
                        position,
                        (byte) 7));
        assertEquals(ChunkDetailMutationOutcome.Status.APPLIED, placed.status());
        ParentCellObservation before = world.observeCell(0, 0, 0)
                .observation().orElseThrow();
        CollisionWorld collisions = new CollisionWorld(
                world, BlockCollisionShapeResolver.fullCubesForNonAir());

        assertTrue(collisions.overlapsSolid(
                new Aabb(0.05f, 0.05f, 0.05f, 0.2f, 0.2f, 0.2f)));

        ParentCellObservation after = world.observeCell(0, 0, 0)
                .observation().orElseThrow();
        assertEquals(before.chunkRevision(), after.chunkRevision());
        assertEquals(before.state(), after.state());
        assertFalse(collisions.overlapsSolid(
                new Aabb(0.3f, 0.05f, 0.05f, 0.45f, 0.2f, 0.2f)));
    }
}
