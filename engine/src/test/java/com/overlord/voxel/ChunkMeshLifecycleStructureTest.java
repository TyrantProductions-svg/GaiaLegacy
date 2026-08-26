package com.overlord.voxel;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.overlord.renderer.ChunkRenderObject;
import com.overlord.renderer.Mesh;
import com.overlord.renderer.Renderer;
import com.overlord.renderer.RenderFrameInput;
import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class ChunkMeshLifecycleStructureTest {
    @Test
    void rendererExposesOnlyChunkTerrainLifecycleApi()
            throws IOException, NoSuchMethodException {
        String renderer =
                readMainSource("com/overlord/renderer/Renderer.java");

        assertFalse(renderer.contains("replaceMesh("));
        assertFalse(renderer.contains("renderChunks("));
        assertFalse(renderer.contains("private Mesh mesh"));
        assertFalse(renderer.contains("fallbackMesh"));
        assertFalse(
                hasAnyPublicMethod(
                        Renderer.class, "replaceMesh"));
        assertFalse(
                hasAnyPublicMethod(
                        Renderer.class, "renderChunks"));
        assertFalse(
                hasPublicMethod(Renderer.class, "render"));
        assertFalse(
                Arrays.stream(Renderer.class.getDeclaredFields())
                        .anyMatch(
                                field ->
                                        field.getType()
                                                .equals(Mesh.class)));
        assertFalse(
                Arrays.stream(Renderer.class.getDeclaredFields())
                        .map(field -> field.getName())
                        .anyMatch(
                                name ->
                                        name.equals("mesh")
                                                || name.equals(
                                                        "fallbackMesh")));

        Method upload =
                Renderer.class.getMethod(
                        "upload", ChunkMeshData.class);
        Method release =
                Renderer.class.getMethod(
                        "release", ChunkRenderObject.class);
        Method renderFrame =
                Renderer.class.getMethod(
                        "renderFrame", RenderFrameInput.class);
        assertEquals(ChunkRenderObject.class, upload.getReturnType());
        assertEquals(void.class, release.getReturnType());
        assertEquals(void.class, renderFrame.getReturnType());
    }

    @Test
    void mesherAcceptsOnlyImmutableChunkMeshInput()
            throws IOException {
        String builder =
                readMainSource(
                        "com/overlord/voxel/ChunkMeshBuilder.java");

        assertFalse(builder.contains("World world"));
        assertFalse(builder.contains("Chunk chunk"));
        assertFalse(
                hasAnyPublicMethod(
                        ChunkMeshBuilder.class,
                        "buildChunkMeshData"));

        List<Method> publicMethods =
                Arrays.stream(
                                ChunkMeshBuilder.class
                                        .getDeclaredMethods())
                        .filter(
                                method ->
                                        Modifier.isPublic(
                                                method.getModifiers()))
                        .toList();
        assertEquals(
                1,
                publicMethods.size(),
                () ->
                        "Unexpected public meshing surface: "
                                + publicMethods.stream()
                                        .map(Method::toGenericString)
                                        .toList());
        Method build = publicMethods.get(0);
        assertFalse(build.isSynthetic());
        assertFalse(build.isBridge());
        assertEquals("build", build.getName());
        assertArrayEquals(
                new Class<?>[] {ChunkMeshInput.class},
                build.getParameterTypes());
        assertEquals(ChunkMeshData.class, build.getReturnType());
    }

    @Test
    void workerMeshingUsesOnlySnapshotsAndRepositoryOwnsDiagonalInvalidation()
            throws IOException {
        String builder = readMainSource("com/overlord/voxel/ChunkMeshBuilder.java");
        String manager = readMainSource("com/overlord/voxel/ChunkMeshManager.java");
        String repository = readMainSource("com/overlord/voxel/ChunkRepository.java");
        String workerMeshing =
                        manager.substring(
                                manager.indexOf("private void buildMesh("),
                                manager.indexOf("private DispatchOutcome dispatchOne("));

        assertNoCodeMatches(
                builder,
                List.of("\\bWorld\\b", "\\bRenderer\\b", "org\\.lwjgl", "\\bgl[A-Z]\\w*"));
        assertNoCodeMatches(
                workerMeshing,
                List.of("\\bWorld\\b", "\\bRenderer\\b", "org\\.lwjgl", "\\bgl[A-Z]\\w*"));
        assertNoCodeMatches(
                manager,
                List.of("dirtyChangedLoadedNeighbors", "ChangedMeshingBoundaries", "northEastChanged"));
        assertTrue(repository.contains("private void dirtyChangedLoadedNeighbors("));
        for (String diagonal : List.of("northEast", "southEast", "southWest", "northWest")) {
            assertTrue(repository.contains("key." + diagonal + "()"));
        }

        assertNoCodeMatches("// World Renderer org.lwjgl glDraw\n\"glDispatchCompute\"", List.of("\\bWorld\\b", "\\bRenderer\\b", "org\\.lwjgl", "\\bgl[A-Z]\\w*"));
        assertTrue(codeMatches("new Renderer()", "\\bRenderer\\b"));
    }

    @Test
    void chunkMeshInputCarriesCenterAndAllEightSnapshotNeighborsWithoutMutableChunks() {
        assertTrue(ChunkMeshInput.class.isRecord());
        assertEquals(
                List.of(
                        "center", "north", "northEast", "east", "southEast", "south", "southWest", "west", "northWest"),
                Arrays.stream(ChunkMeshInput.class.getRecordComponents())
                        .map(component -> component.getName())
                        .toList());
        assertTrue(
                Arrays.stream(ChunkMeshInput.class.getRecordComponents())
                        .allMatch(component -> component.getType().equals(ChunkSnapshot.class)));
    }

    @Test
    void worldAndChunkExposeRepositoryControlledOwnership()
            throws IOException {
        String world =
                readMainSource("com/overlord/voxel/World.java");
        String repository =
                readMainSource(
                        "com/overlord/voxel/ChunkRepository.java");

        assertFalse(world.contains("Map<String, Chunk>"));
        assertFalse(world.contains("computeIfAbsent"));
        assertFalse(
                repository.contains(
                        "mutableChunkForCompatibility"));
        assertFalse(hasAnyPublicMethod(World.class, "getChunk"));
        assertFalse(
                hasAnyPublicMethod(Chunk.class, "getSubChunks"));
        assertFalse(
                Arrays.stream(
                                ChunkRepository.class
                                        .getDeclaredMethods())
                        .anyMatch(
                                method ->
                                        method.getName()
                                                .equals(
                                                        "mutableChunkForCompatibility")));
    }

    private static String readMainSource(String relativePath)
            throws IOException {
        return Files.readString(
                Path.of("src/main/java").resolve(relativePath));
    }

    private static boolean hasPublicMethod(
            Class<?> type,
            String name,
            Class<?>... parameterTypes) {
        return Arrays.stream(type.getDeclaredMethods())
                .filter(
                        method ->
                                Modifier.isPublic(
                                        method.getModifiers()))
                .anyMatch(
                        method ->
                                method.getName().equals(name)
                                        && Arrays.equals(
                                                method
                                                        .getParameterTypes(),
                                                parameterTypes));
    }

    private static boolean hasAnyPublicMethod(
            Class<?> type, String name) {
        return Arrays.stream(type.getDeclaredMethods())
                .filter(
                        method ->
                                Modifier.isPublic(
                                        method.getModifiers()))
                .anyMatch(method -> method.getName().equals(name));
    }

    private static void assertNoCodeMatches(String source, List<String> patterns) {
        for (String pattern : patterns) {
            assertFalse(codeMatches(source, pattern), "Forbidden worker-meshing dependency: " + pattern);
        }
    }

    private static boolean codeMatches(String source, String pattern) {
        return Pattern.compile(pattern).matcher(sanitizeCode(source)).find();
    }

    private static String sanitizeCode(String source) {
        return source.replaceAll("(?s)/\\*.*?\\*/|//[^\\r\\n]*|\"(?:\\\\.|[^\"])*\"|'(?:\\\\.|[^'])*'", " ");
    }
}
