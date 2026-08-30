package com.overlord.physics;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.overlord.voxel.ChunkDetailMutation;
import com.overlord.voxel.ChunkDetailMutationOutcome;
import com.overlord.voxel.ChunkKey;
import com.overlord.voxel.LocalSubVoxelPosition;
import com.overlord.voxel.ParentCellObservation;
import com.overlord.voxel.World;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

class DetailRaycastArchitectureTest {
    @Test
    void targetBoundaryHasOnlyFullAndDetailCanonicalRepresentations() {
        assertTrue(RaycastCellTarget.class.isSealed());
        assertArrayEquals(
                new Class<?>[] {DetailRaycastTarget.class, FullRaycastTarget.class},
                Arrays.stream(RaycastCellTarget.class.getPermittedSubclasses())
                        .sorted(java.util.Comparator.comparing(Class::getName))
                        .toArray(Class<?>[]::new));
    }

    @Test
    void blockRaycastOwnsOnlyWorldObservationAndShapeResolutionDependencies() {
        Class<?>[] instanceFieldTypes = Arrays.stream(
                        BlockRaycast.class.getDeclaredFields())
                .filter(field -> !Modifier.isStatic(field.getModifiers()))
                .map(java.lang.reflect.Field::getType)
                .sorted(java.util.Comparator.comparing(Class::getName))
                .toArray(Class<?>[]::new);

        assertArrayEquals(
                new Class<?>[] {BlockCollisionShapeResolver.class, World.class},
                instanceFieldTypes);
    }

    @Test
    void detailRefinementUsesImmutableObservationWithoutMutationOrFullShapeSampling() {
        World world = new World();
        ChunkKey key = new ChunkKey(0, 0);
        world.generate(key, ignored -> {});
        ParentCellObservation fullAir = world.observeCell(1, 0, 0)
                .observation().orElseThrow();
        ChunkDetailMutationOutcome placed = world.chunks().mutateDetail(
                new ChunkDetailMutation.SetSubVoxel(
                        1, 0, 0, fullAir.chunkRevision(), fullAir.state(),
                        new LocalSubVoxelPosition(0, 0, 0), (byte) 7));
        assertEquals(ChunkDetailMutationOutcome.Status.APPLIED, placed.status());
        long revision = world.chunks().revision(key);
        ParentCellObservation before = world.observeCell(1, 0, 0)
                .observation().orElseThrow();
        AtomicInteger fullShapeSamples = new AtomicInteger();
        BlockRaycast raycast = new BlockRaycast(world, blockId -> {
            fullShapeSamples.incrementAndGet();
            return BlockCollisionShape.fullCube();
        });

        BlockRaycastHit hit = raycast.cast(
                new SimulationOrigin(key),
                new Vector3f(1.125f, 0.125f, 0.125f),
                new Vector3f(1, 0, 0), 0)
                .result().orElseThrow();

        assertInstanceOf(DetailRaycastTarget.class, hit.target());
        assertEquals(0, fullShapeSamples.get());
        assertEquals(revision, world.chunks().revision(key));
        assertEquals(before, world.observeCell(1, 0, 0)
                .observation().orElseThrow());
    }
}
