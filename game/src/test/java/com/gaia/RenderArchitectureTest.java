package com.gaia;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class RenderArchitectureTest {
    private static final Path MAIN = Path.of("src/main/java");

    @Test
    void gameSourcesDoNotCallOpenGlDirectly() throws IOException {
        List<String> forbidden =
                List.of(
                        "org.lwjgl",
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
                                            !forbiddenCodeTokens(read(source), forbidden).isEmpty()
                                                    || codeMatches(read(source), "\\bgl[A-Z]\\w*"))
                            .toList();
            assertTrue(
                    offenders.isEmpty(),
                    "Game sources call OpenGL directly: " + offenders);
        }
    }

    @Test
    void gameProductionContainsNoHudTextRendererOrUiClass() throws IOException {
        try (Stream<Path> sources = Files.walk(MAIN)) {
            List<Path> offenders =
                    sources.filter(Files::isRegularFile)
                            .filter(source -> source.toString().endsWith(".java"))
                            .filter(
                                    source ->
                                            codeMatches(
                                                    read(source),
                                                    "\\b(?:HUD|Hud|TextRenderer|UI)\\b"))
                            .toList();
            assertTrue(offenders.isEmpty(), "Game production introduces UI classes: " + offenders);
        }
        assertTrue(forbiddenCodeTokens("// glDraw\n\"org.lwjgl\"", List.of("org.lwjgl", "glDraw")).isEmpty());
        assertFalse(forbiddenCodeTokens("glDrawArrays();", List.of("glDraw")).isEmpty());
        assertTrue(codeMatches("glDrawArrays();", "\\bgl[A-Z]\\w*"));
    }

    private static List<String> forbiddenCodeTokens(String source, List<String> forbidden) {
        String code = sanitizeCode(source);
        return forbidden.stream().filter(code::contains).toList();
    }

    private static boolean codeMatches(String source, String pattern) {
        return Pattern.compile(pattern).matcher(sanitizeCode(source)).find();
    }

    private static String sanitizeCode(String source) {
        return source.replaceAll("(?s)/\\*.*?\\*/|//[^\\r\\n]*|\"(?:\\\\.|[^\"])*\"|'(?:\\\\.|[^'])*'", " ");
    }

    private static String read(Path source) {
        try {
            return Files.readString(source);
        } catch (IOException failure) {
            throw new AssertionError("Could not read " + source, failure);
        }
    }
}
