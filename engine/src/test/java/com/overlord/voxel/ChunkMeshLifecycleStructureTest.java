package com.overlord.voxel;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.overlord.renderer.ChunkRenderObject;
import com.overlord.renderer.Mesh;
import com.overlord.renderer.Renderer;
import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import org.junit.jupiter.api.Test;

class ChunkMeshLifecycleStructureTest {
    @Test
    void rendererExposesOnlyChunkTerrainLifecycleApi()
            throws IOException, NoSuchMethodException {
        String renderer =
                readMainSource("com/overlord/renderer/Renderer.java");

        assertFalse(renderer.contains("replaceMesh("));
        assertFalse(renderer.contains("private Mesh mesh"));
        assertFalse(renderer.contains("fallbackMesh"));
        assertFalse(
                hasAnyPublicMethod(
                        Renderer.class, "replaceMesh"));
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
        Method renderChunks =
                Renderer.class.getMethod(
                        "renderChunks", Collection.class);
        assertEquals(ChunkRenderObject.class, upload.getReturnType());
        assertEquals(void.class, release.getReturnType());
        assertEquals(void.class, renderChunks.getReturnType());
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
}
