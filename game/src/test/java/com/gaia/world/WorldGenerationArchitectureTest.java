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

        assertTrue(
                forbiddenCouplings(sources).isEmpty(),
                () ->
                        "World generation references forbidden coupling: "
                                + forbiddenCouplings(sources));
    }

    @Test
    void matcherRejectsRendererTextureImport() {
        assertFalse(
                forbiddenCouplings(
                                "import com.overlord.renderer.Texture;")
                        .isEmpty());
    }

    @Test
    void matcherRejectsChunkRenderBackendImport() {
        assertFalse(
                forbiddenCouplings(
                                "import com.overlord.renderer."
                                        + "ChunkRenderBackend;")
                        .isEmpty());
    }

    @Test
    void matcherRejectsEventBusImport() {
        assertFalse(
                forbiddenCouplings(
                                "import com.overlord.event.EventBus;")
                        .isEmpty());
    }

    @Test
    void matcherRejectsVolatileStaticPerlinNoise() {
        assertFalse(
                forbiddenCouplings(
                                "private static volatile PerlinNoise noise;")
                        .isEmpty());
    }

    @Test
    void matcherRejectsQualifiedStaticPerlinNoise() {
        assertFalse(
                forbiddenCouplings(
                                "private static final "
                                        + "com.overlord.voxel.PerlinNoise "
                                        + "NOISE;")
                        .isEmpty());
    }

    @Test
    void matcherAllowsDetachedRegionWrites() {
        assertTrue(
                forbiddenCouplings(
                                "region.writeBlock(x, y, z, block);")
                        .isEmpty());
    }

    @Test
    void matcherRejectsDottedSetBlockWithWhitespace() {
        assertFalse(
                forbiddenCouplings(
                                "repository . setBlock (x, y, z, block);")
                        .isEmpty());
    }

    private static List<String> forbiddenCouplings(String sources) {
        java.util.ArrayList<String> matches =
                new java.util.ArrayList<>();
        for (String forbidden :
                List.of(
                        "com.overlord.renderer.",
                        "com.overlord.event.",
                        "com.overlord.interaction.",
                        "com.overlord.inventory.",
                        "com.overlord.worlditem.",
                        "com.overlord.voxel.ChunkMeshManager",
                        "org.lwjgl.",
                        "new Random(",
                        "java.util.Random")) {
            if (sources.contains(forbidden)) {
                matches.add(forbidden);
            }
        }
        for (String forbiddenPattern :
                List.of(
                        "\\.\\s*setBlock\\s*\\(",
                        "\\bstatic\\b"
                                + "(?:\\s+(?:public|protected|private|final|"
                                + "transient|volatile|strictfp))*"
                                + "\\s+(?:[A-Za-z_$][\\w$]*\\.)*"
                                + "PerlinNoise\\b")) {
            if (Pattern.compile(forbiddenPattern)
                    .matcher(sources)
                    .find()) {
                matches.add(forbiddenPattern);
            }
        }
        return List.copyOf(matches);
    }

    private static String read(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException failure) {
            throw new AssertionError("Could not read " + path, failure);
        }
    }
}
