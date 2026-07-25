package com.gaia;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class RenderArchitectureTest {
    private static final Path MAIN = Path.of("src/main/java");

    @Test
    void gameSourcesDoNotCallOpenGlDirectly() throws IOException {
        List<String> forbidden =
                List.of(
                        "org.lwjgl.opengl",
                        "glUseProgram",
                        "glBindTexture",
                        "glBindVertexArray",
                        "glDraw");

        try (Stream<Path> sources = Files.walk(MAIN)) {
            List<Path> offenders =
                    sources.filter(Files::isRegularFile)
                            .filter(source -> source.toString().endsWith(".java"))
                            .filter(
                                    source ->
                                            forbidden.stream()
                                                    .anyMatch(
                                                            token ->
                                                                    read(source)
                                                                            .contains(
                                                                                    token)))
                            .toList();
            assertTrue(
                    offenders.isEmpty(),
                    "Game sources call OpenGL directly: " + offenders);
        }
    }

    private static String read(Path source) {
        try {
            return Files.readString(source);
        } catch (IOException failure) {
            throw new AssertionError("Could not read " + source, failure);
        }
    }
}
