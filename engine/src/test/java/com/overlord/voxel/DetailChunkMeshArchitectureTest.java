package com.overlord.voxel;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class DetailChunkMeshArchitectureTest {
    @Test
    void meshPayloadRemainsExactlyNineDetachedSnapshotsWithoutCapabilities() {
        assertTrue(ChunkMeshInput.class.isRecord());
        assertEquals(9, ChunkMeshInput.class.getRecordComponents().length);
        assertTrue(Arrays.stream(ChunkMeshInput.class.getRecordComponents())
                .allMatch(component -> component.getType() == ChunkSnapshot.class));
        assertTrue(Arrays.stream(ChunkMeshInput.class.getDeclaredFields())
                .filter(field -> !Modifier.isStatic(field.getModifiers()))
                .allMatch(field -> field.getType() == ChunkSnapshot.class));

        assertArrayEquals(
                new String[] {"claimId", "key", "revision", "input"},
                Arrays.stream(ChunkMeshingClaim.class.getRecordComponents())
                        .map(component -> component.getName())
                        .toArray(String[]::new));
    }

    @Test
    void detailMesherOwnsOnlyDetachedInputSamplingAndRenderResolution() {
        assertArrayEquals(
                new Class<?>[] {BlockRenderResolver.class},
                instanceFieldTypes(ChunkMeshBuilder.class));
        assertArrayEquals(
                new Class<?>[] {ChunkMeshInput.class},
                instanceFieldTypes(QuarterVoxelSampler.class));
        assertFalse(Arrays.asList(instanceFieldTypes(ChunkMeshBuilder.class))
                .contains(ChunkRepository.class));
        assertFalse(Arrays.asList(instanceFieldTypes(QuarterVoxelSampler.class))
                .contains(Chunk.class));
    }

    @Test
    void fullOnlyCompatibilityReadIsInternalAndFailClosed() throws Exception {
        assertFalse(Modifier.isPublic(ChunkMeshInput.class
                .getDeclaredMethod(
                        "fullOnlyBlock", int.class, int.class, int.class)
                .getModifiers()));
    }

    @Test
    void detachedCpuMeshCarriesContentButNoClaimOrGpuLifecycleAuthority() {
        assertArrayEquals(
                new Class<?>[] {
                    byte[].class,
                    float[].class,
                    ChunkKey.class,
                    Optional.class,
                    long.class
                },
                instanceFieldTypes(ChunkMeshData.class));
        assertTrue(Arrays.stream(ChunkMeshData.class.getDeclaredFields())
                .map(field -> field.getName().toLowerCase())
                .noneMatch(name -> name.contains("claim")
                        || name.contains("upload")
                        || name.contains("gpu")
                        || name.contains("repository")));
    }

    private static Class<?>[] instanceFieldTypes(Class<?> type) {
        return Arrays.stream(type.getDeclaredFields())
                .filter(field -> !Modifier.isStatic(field.getModifiers()))
                .map(java.lang.reflect.Field::getType)
                .sorted(java.util.Comparator.comparing(Class::getName))
                .toArray(Class<?>[]::new);
    }
}
