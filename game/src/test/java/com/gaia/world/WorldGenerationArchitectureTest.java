package com.gaia.world;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class WorldGenerationArchitectureTest {
    private static final Path MAIN = Path.of("src/main/java");

    @Test
    void generationUsesDetachedRegionsAndRepositoryTransactions()
            throws IOException {
        String staged =
                Files.readString(
                        MAIN.resolve(
                                "com/gaia/world/generation/"
                                        + "StagedWorldGenerator.java"));
        String loader =
                Files.readString(
                        MAIN.resolve("com/gaia/world/WorldLoader.java"));

        assertTrue(staged.contains("new GenerationRegion("));
        assertTrue(staged.contains("region.freeze()"));
        assertTrue(loader.contains("chunks.beginGeneration("));
        assertTrue(loader.contains("chunks.commitGeneration("));
        assertTrue(loader.contains("ChunkGenerationMode.INITIAL"));
        assertTrue(loader.contains("ChunkGenerationMode.REBUILD"));
    }

    @Test
    void generationHasNoGameplayGpuOrGlobalNoiseCoupling()
            throws IOException {
        String sources;
        try (Stream<Path> paths =
                Files.walk(MAIN.resolve("com/gaia/world"))) {
            sources =
                    paths.filter(path -> path.toString().endsWith(".java"))
                            .map(WorldGenerationArchitectureTest::read)
                            .reduce("", (left, right) -> left + "\n" + right);
        }

        for (String forbidden :
                List.of(
                        "Renderer",
                        "Mesh",
                        "ChunkMeshManager",
                        "Gpu",
                        "org.lwjgl",
                        "GLFW",
                        "OpenGL",
                        "WorldMutationService",
                        "BlockChange",
                        "Inventory",
                        "WorldItem",
                        "new Random(",
                        "java.util.Random")) {
            assertFalse(
                    sources.contains(forbidden),
                    () -> "World generation references forbidden " + forbidden);
        }
        for (String forbiddenPattern :
                List.of(
                        "\\.\\s*setBlock\\s*\\(",
                        "\\bstatic(?:\\s+final)?\\s+PerlinNoise\\b")) {
            assertFalse(
                    Pattern.compile(forbiddenPattern)
                            .matcher(sources)
                            .find(),
                    () ->
                            "World generation matches forbidden pattern "
                                    + forbiddenPattern);
        }
    }

    private static String read(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException failure) {
            throw new AssertionError("Could not read " + path, failure);
        }
    }
}
