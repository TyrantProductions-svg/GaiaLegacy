package com.overlord.voxel;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.overlord.config.GameConfig;
import java.io.IOException;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class DetailArchitectureContractTest {
    @Test
    void parentStateBoundaryIsSealedToFullAndNonemptyDetail() {
        assertTrue(ParentCellState.class.isSealed());
        assertArrayEquals(
                new Class<?>[] {FullCellState.class, DetailCellState.class},
                ParentCellState.class.getPermittedSubclasses());
    }

    @Test
    void meshInputRemainsExactlyNineDetachedChunkSnapshots() {
        assertTrue(ChunkMeshInput.class.isRecord());
        assertEquals(9, ChunkMeshInput.class.getRecordComponents().length);
        assertTrue(
                Arrays.stream(ChunkMeshInput.class.getRecordComponents())
                        .allMatch(
                                component ->
                                        component.getType()
                                                == ChunkSnapshot.class));
    }

    @Test
    void meshingClaimRemainsSeparateLifecycleCapability() {
        assertTrue(ChunkMeshingClaim.class.isRecord());
        assertArrayEquals(
                new String[] {"claimId", "key", "revision", "input"},
                Arrays.stream(ChunkMeshingClaim.class.getRecordComponents())
                        .map(component -> component.getName())
                        .toArray(String[]::new));
        assertEquals(
                ChunkMeshInput.class,
                ChunkMeshingClaim.class.getRecordComponents()[3].getType());
    }

    @Test
    void storageHasNoEmptySingletonRevisionOrDirtyAuthority() {
        assertFalse(Modifier.isPublic(DetailStorage.class.getModifiers()));
        assertTrue(
                Arrays.stream(DetailStorage.class.getDeclaredFields())
                        .noneMatch(
                                field ->
                                        Modifier.isStatic(field.getModifiers())));
        assertTrue(
                Arrays.stream(DetailStorage.class.getDeclaredFields())
                        .map(field -> field.getName().toLowerCase())
                        .noneMatch(
                                name ->
                                        name.contains("revision")
                                                || name.contains("dirty")));
        assertTrue(
                Arrays.stream(DetailChunkSnapshot.class.getDeclaredFields())
                        .map(field -> field.getName().toLowerCase())
                        .noneMatch(
                                name ->
                                        name.contains("revision")
                                                || name.contains("dirty")
                                                || name.contains("claim")));
    }

    @Test
    void publicWorldBoundaryExposesTypedObservationNotStorage() throws Exception {
        assertEquals(
                ParentCellObservationResult.class,
                World.class
                        .getMethod(
                                "observeCell",
                                int.class,
                                int.class,
                                int.class)
                        .getReturnType());
        assertTrue(
                Arrays.stream(World.class.getMethods())
                        .noneMatch(
                                method ->
                                        method.getReturnType()
                                                == DetailStorage.class));
    }

    @Test
    void rawFullSnapshotCopyIsInternalAndLegacyCopyFailsClosed()
            throws Exception {
        assertFalse(
                Modifier.isPublic(
                        ChunkSnapshot.class
                                .getDeclaredMethod("copyFullBlocks")
                                .getModifiers()));
        assertFalse(
                Modifier.isPublic(
                        ChunkGenerationData.class
                                .getDeclaredMethod("copyFullBlocks")
                                .getModifiers()));
    }

    @Test
    void worldHeightCannotExceedUnsignedParentIndexEnvelope() {
        int invalidHeight = GameConfig.Chunk.MAX_HEIGHT + 1;
        int blockCount =
                GameConfig.Chunk.SIZE
                        * invalidHeight
                        * GameConfig.Chunk.SIZE;

        assertThrows(
                IllegalArgumentException.class,
                () -> new Chunk(invalidHeight));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new ChunkRepository(
                                invalidHeight,
                                new ChunkDirtyTracker()));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        ChunkSnapshot.empty(
                                new ChunkKey(0, 0),
                                0L,
                                invalidHeight));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new ChunkGenerationData(
                                new ChunkKey(0, 0),
                                invalidHeight,
                                new byte[blockCount]));
    }

    @Test
    void detailAwareConsumersCannotInvokeLegacyByteApis()
            throws IOException {
        List<Path> sourceRoots =
                List.of(
                        Path.of("src/main/java"),
                        Path.of("../game/src/main/java"));
        for (Path sourceRoot : sourceRoots) {
            try (Stream<Path> sources = Files.walk(sourceRoot)) {
                List<Path> bypasses =
                        sources.filter(
                                        path ->
                                                path.toString()
                                                        .endsWith(".java"))
                                .filter(
                                        path ->
                                                !isQuarantinedSource(
                                                        sourceRoot, path))
                                .filter(
                                        path ->
                                                isDetailAwareConsumer(
                                                        sourceRoot, path))
                                .filter(
                                        path ->
                                                invokesLegacyByteApi(
                                                        read(path)))
                                .toList();
                assertTrue(
                        bypasses.isEmpty(),
                        "DETAIL-aware consumers must use typed parent state: "
                                + bypasses);
            }
        }
    }

    private static boolean isQuarantinedSource(
            Path sourceRoot, Path source) {
        String relative =
                sourceRoot.relativize(source)
                        .toString()
                        .replace('\\', '/');
        return relative.equals(
                "com/gaia/world/streaming/ChunkStreamingMetricsRecorder.java");
    }

    private static boolean isDetailAwareConsumer(
            Path sourceRoot, Path source) {
        String relative =
                sourceRoot.relativize(source)
                        .toString()
                        .replace('\\', '/');
        if (relative.startsWith("com/overlord/voxel/")) {
            String fileName = source.getFileName().toString();
            if (fileName.equals("Chunk.java")
                    || fileName.equals("ChunkRepository.java")
                    || fileName.equals("ChunkSnapshot.java")
                    || fileName.equals("ChunkGenerationData.java")
                    || fileName.equals("ChunkMeshInput.java")
                    || fileName.equals("World.java")) {
                return false;
            }
        }
        String sourceText = read(source);
        return sourceText.contains("ParentCellState")
                || sourceText.contains("DetailCellState")
                || sourceText.contains("DetailChunkSnapshot");
    }

    private static boolean invokesLegacyByteApi(String source) {
        String code = source.replaceAll("(?s)/\\*.*?\\*/|//[^\\r\\n]*", "");
        return code.matches(
                "(?s).*\\.(?:getBlock|setBlock|copyBlocks)\\s*\\(.*");
    }

    private static String read(Path source) {
        try {
            return Files.readString(source);
        } catch (IOException failure) {
            throw new AssertionError(failure);
        }
    }
}
