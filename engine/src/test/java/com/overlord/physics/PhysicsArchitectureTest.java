package com.overlord.physics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class PhysicsArchitectureTest {
    private static final Path ENGINE_PRODUCTION =
            Path.of("src/main/java");
    private static final Path GAME_PRODUCTION =
            Path.of("../game/src/main/java");
    private static final Pattern BLOCK_RAYCAST_DECLARATION =
            Pattern.compile("\\bclass\\s+BlockRaycast\\b");

    @Test
    void legacyPhysicsManagerHasNoProductionFileOrReference()
            throws IOException {
        List<Path> offenders;
        try (Stream<Path> sources = productionJavaSources()) {
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

    @Test
    void physicsPackageHasNoGraphicsOrRendererDependency()
            throws IOException {
        List<Path> offenders;
        try (Stream<Path> sources =
                javaSources(
                        ENGINE_PRODUCTION.resolve(
                                "com/overlord/physics"))) {
            offenders =
                    sources.filter(
                                    source -> {
                                        String text = read(source);
                                        return text.contains("org.lwjgl")
                                                || text.contains("org.opengl")
                                                || text.contains("GLFW")
                                                || text.contains("OpenGL")
                                                || text.contains(
                                                        "com.overlord.renderer");
                                    })
                            .toList();
        }

        assertTrue(
                offenders.isEmpty(),
                "Physics sources depend on graphics: " + offenders);
    }

    @Test
    void productionContainsExactlyOneBlockRaycastImplementation()
            throws IOException {
        long implementations;
        try (Stream<Path> sources = productionJavaSources()) {
            implementations =
                    sources.map(PhysicsArchitectureTest::read)
                            .filter(
                                    source ->
                                            BLOCK_RAYCAST_DECLARATION
                                                    .matcher(source)
                                                    .find())
                            .count();
        }

        assertEquals(1, implementations);
    }

    @Test
    void collisionAndControllerSourcesDoNotReadCamera()
            throws IOException {
        for (String type :
                List.of(
                        "CollisionWorld",
                        "PhysicsWorld",
                        "PlayerController")) {
            String source =
                    Files.readString(
                            ENGINE_PRODUCTION.resolve(
                                    "com/overlord/physics/"
                                            + type
                                            + ".java"));
            assertFalse(
                    source.contains("Camera"),
                    type + " must not read Camera");
            assertFalse(
                    source.contains("com.overlord.renderer"),
                    type + " must not depend on renderer");
        }
    }

    @Test
    void playerManagerDoesNotOwnWorldCollisionLoop()
            throws IOException {
        String source =
                Files.readString(
                        ENGINE_PRODUCTION.resolve(
                                "com/overlord/core/PlayerManager.java"));

        assertFalse(source.contains("com.overlord.voxel.World"));
        assertFalse(source.contains("getBlock("));
        assertFalse(source.contains("setBlock("));
        assertFalse(source.contains("CollisionWorld"));
    }

    @Test
    void modulesRemainConfiguredForJavaSeventeen()
            throws IOException {
        String engineBuild =
                Files.readString(Path.of("build.gradle"));
        String gameBuild =
                Files.readString(Path.of("../game/build.gradle"));

        assertTrue(
                engineBuild.contains(
                        "sourceCompatibility = JavaVersion.VERSION_17"));
        assertTrue(
                engineBuild.contains(
                        "targetCompatibility = JavaVersion.VERSION_17"));
        assertTrue(
                gameBuild.contains(
                        "sourceCompatibility = JavaVersion.VERSION_17"));
        assertTrue(
                gameBuild.contains(
                        "targetCompatibility = JavaVersion.VERSION_17"));
    }

    private static Stream<Path> productionJavaSources()
            throws IOException {
        return Stream.concat(
                javaSources(ENGINE_PRODUCTION),
                javaSources(GAME_PRODUCTION));
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
