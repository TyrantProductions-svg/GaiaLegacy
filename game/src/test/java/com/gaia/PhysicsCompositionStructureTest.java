package com.gaia;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class PhysicsCompositionStructureTest {
    @Test
    void bootstrapOwnsExplicitSharedPhysicsComposition()
            throws IOException {
        String source =
                Files.readString(
                        Path.of(
                                "src/main/java/com/gaia/"
                                        + "GameBootstrap.java"));
        String compact = source.replaceAll("\\s+", "");

        assertTrue(
                compact.contains(
                        "newCollisionWorld("
                                + "engine.getWorld(),shapes)"));
        assertTrue(
                compact.contains(
                        "newPlayerController("));
        assertTrue(compact.contains("newPhysicsWorld("));
        assertEquals(
                1,
                occurrences(
                        compact,
                        "newBlockRaycast("
                                + "engine.getWorld(),shapes)"));
        assertTrue(
                compact.contains(
                        "MAX_FIXED_STEPS_PER_FRAME=8;"));
        assertFalse(source.contains("PhysicsManager"));
    }

    @Test
    void productionHasNoLegacyPhysicsManagerReference()
            throws IOException {
        Path engineSources = Path.of("../engine/src/main/java");
        Path gameSources = Path.of("src/main/java");

        List<Path> offenders;
        try (Stream<Path> sources =
                Stream.concat(
                        javaSources(engineSources),
                        javaSources(gameSources))) {
            offenders =
                    sources.filter(
                                    source ->
                                            source.getFileName()
                                                            .toString()
                                                            .equals(
                                                                    "PhysicsManager.java")
                                                    || read(source)
                                                            .contains(
                                                                    "PhysicsManager"))
                            .toList();
        }

        assertTrue(
                offenders.isEmpty(),
                "PhysicsManager remains in production: " + offenders);
    }

    private static long occurrences(
            String source, String fragment) {
        long count = 0;
        int offset = 0;
        while ((offset = source.indexOf(fragment, offset)) >= 0) {
            count++;
            offset += fragment.length();
        }
        return count;
    }

    private static Stream<Path> javaSources(Path root)
            throws IOException {
        return Files.walk(root)
                .filter(Files::isRegularFile)
                .filter(path -> path.toString().endsWith(".java"));
    }

    private static String read(Path source) {
        try {
            return Files.readString(source);
        } catch (IOException failure) {
            throw new IllegalStateException(
                    "Could not read " + source, failure);
        }
    }
}
