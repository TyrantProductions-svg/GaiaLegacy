package com.gaia.world;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class WorldGenerationArchitectureTest {
    private static final Path MAIN = Path.of("src/main/java");
    private static final Path GENERATION =
            MAIN.resolve("com/gaia/world/generation");

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
        String sources = generationBoundarySources().values().stream()
                .reduce("", (left, right) -> left + "\n" + right);

        assertTrue(
                forbiddenCouplings(sources).isEmpty(),
                () ->
                        "World generation references forbidden coupling: "
                                + forbiddenCouplings(sources));
    }

    @Test
    void onDemandGenerationHasNoLoadedWorldOrMutableEntropyInputs()
            throws IOException {
        Map<Path, String> sources = generationSources();

        assertTrue(
                forbiddenOnDemandInputs(sources).isEmpty(),
                () ->
                        "On-demand generation references forbidden input: "
                                + forbiddenOnDemandInputs(sources));
    }

    @Test
    void onDemandMatcherRejectsEveryForbiddenInputCategory() {
        List<String> forbiddenExamples =
                List.of(
                        "import com.overlord.voxel.ChunkRepository;",
                        "import com.gaia.world.World;",
                        "ChunkRepositorySnapshot neighbors;",
                        "LoadedChunkSnapshot loadedNeighbor;",
                        "new java.util.Random();",
                        "java.util.concurrent.ThreadLocalRandom.current();",
                        "System.currentTimeMillis();",
                        "System.nanoTime();",
                        "java.time.Instant.now();",
                        "Thread.currentThread().threadId();",
                        "region.sampleLocalOrAir(-1, y, z);");

        for (String source : forbiddenExamples) {
            assertFalse(
                    forbiddenOnDemandInputs(
                                    Map.of(
                                            Path.of("ExampleProvider.java"),
                                            source))
                            .isEmpty(),
                    () -> "Matcher accepted forbidden source: " + source);
        }
    }

    @Test
    void onDemandMatcherAllowsTheLocalSamplingDefinition() {
        assertTrue(
                forbiddenOnDemandInputs(
                                Map.of(
                                        Path.of("GenerationRegion.java"),
                                        "byte sampleLocalOrAir(int x, int y, int z)"))
                        .isEmpty());
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

    private static Map<Path, String> generationSources()
            throws IOException {
        Map<Path, String> sources = new HashMap<>();
        try (Stream<Path> paths = Files.walk(GENERATION)) {
            paths.filter(path -> path.toString().endsWith(".java"))
                    .forEach(path -> sources.put(path, read(path)));
        }
        return Map.copyOf(sources);
    }

    private static Map<Path, String> generationBoundarySources()
            throws IOException {
        Map<Path, String> sources = new HashMap<>(generationSources());
        for (String entrypoint : List.of(
                "com/gaia/world/WorldLoader.java",
                "com/gaia/world/GaiaWorldGenerator.java")) {
            Path path = MAIN.resolve(entrypoint);
            sources.put(path, read(path));
        }
        return Map.copyOf(sources);
    }

    private static List<String> forbiddenOnDemandInputs(
            Map<Path, String> sources) {
        java.util.ArrayList<String> matches =
                new java.util.ArrayList<>();
        for (Map.Entry<Path, String> source : sources.entrySet()) {
            String file = source.getKey().toString();
            String contents = source.getValue();
            for (String forbidden :
                    List.of(
                            "ChunkRepository",
                            "ChunkRepositorySnapshot",
                            "LoadedChunkSnapshot",
                            "loadedNeighbor",
                            "neighborSnapshot",
                            "java.util.Random",
                            "ThreadLocalRandom",
                            "SecureRandom",
                            "currentTimeMillis(",
                            "nanoTime(",
                            "Instant.now(",
                            "Clock.system",
                            "Thread.currentThread(")) {
                if (contents.contains(forbidden)) {
                    matches.add(file + ":" + forbidden);
                }
            }
            if (Pattern.compile(
                            "import\\s+[^;]*\\.World\\s*;")
                    .matcher(contents)
                    .find()) {
                matches.add(file + ":World");
            }
            if (!source.getKey()
                            .getFileName()
                            .toString()
                            .equals("GenerationRegion.java")
                    && contents.contains("sampleLocalOrAir(")) {
                matches.add(file + ":sampleLocalOrAir");
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
