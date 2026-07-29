package com.gaia.tools.ui;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.overlord.assets.AssetManager;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class UiAssetGeneratorTest {
    @Test
    void extendedRunWritesExactlyBothGeneratedAtlases(@TempDir Path temporary)
            throws Exception {
        UiAssetGenerator.run(
                temporary,
                Path.of("ui_font.png"),
                Path.of("ui_font.json"),
                Path.of("ui_icons.png"),
                Path.of("ui_icons.json"),
                new AssetManager(getClass().getClassLoader()));

        assertEquals(List.of(
                        temporary.resolve("ui_font.json"),
                        temporary.resolve("ui_font.png"),
                        temporary.resolve("ui_icons.json"),
                        temporary.resolve("ui_icons.png")),
                regularFiles(temporary));
    }

    @Test
    void rejectsAbsoluteOutputPaths(@TempDir Path temporary) {
        assertThrows(IllegalArgumentException.class, () -> UiAssetGenerator.run(
                temporary, temporary.resolve("font.png"), Path.of("font.json")));
    }

    @Test
    void rejectsTraversalAndNonNormalizedAliases(@TempDir Path temporary) {
        assertThrows(IllegalArgumentException.class, () -> UiAssetGenerator.run(
                temporary, Path.of("../escaped.png"), Path.of("font.json")));
        assertThrows(IllegalArgumentException.class, () -> UiAssetGenerator.run(
                temporary, Path.of("nested/../font.png"), Path.of("font.json")));
    }

    @Test
    void writesNormalizedRelativeTargetsInsideTheExplicitRoot(@TempDir Path temporary)
            throws Exception {
        UiAssetGenerator.run(
                temporary, Path.of("nested/font.png"), Path.of("nested/font.json"));

        assertTrue(Files.isRegularFile(temporary.resolve("nested/font.png")));
        assertTrue(Files.isRegularFile(temporary.resolve("nested/font.json")));
        assertEquals(2, regularFiles(temporary).size());
    }

    @Test
    void rejectsSameAndFilesystemAliasTargets(@TempDir Path temporary) throws Exception {
        assertThrows(IllegalArgumentException.class, () -> UiAssetGenerator.run(
                temporary, Path.of("font.bin"), Path.of("font.bin")));

        Path shared = Files.write(temporary.resolve("shared.bin"), new byte[] {1});
        Path imageAlias = temporary.resolve("font.png");
        Path metadataAlias = temporary.resolve("font.json");
        try {
            Files.createLink(imageAlias, shared);
            Files.createLink(metadataAlias, shared);
        } catch (IOException | UnsupportedOperationException exception) {
            Assumptions.abort("filesystem aliases are unavailable: " + exception);
        }
        assertThrows(IllegalArgumentException.class, () -> UiAssetGenerator.run(
                temporary, Path.of("font.png"), Path.of("font.json")));
    }

    @Test
    void rejectsSymlinkedParentThatEscapesTheRootWhenSupported(@TempDir Path temporary)
            throws Exception {
        Path root = Files.createDirectory(temporary.resolve("root"));
        Path outside = Files.createDirectory(temporary.resolve("outside"));
        try {
            Files.createSymbolicLink(root.resolve("escape"), outside);
        } catch (IOException | UnsupportedOperationException exception) {
            Assumptions.abort("symbolic links are unavailable: " + exception);
        }

        assertThrows(IllegalArgumentException.class, () -> UiAssetGenerator.run(
                root, Path.of("escape/font.png"), Path.of("font.json")));
    }

    @Test
    void twoCliEquivalentRunsProduceOnlyTheApprovedBytes(@TempDir Path temporary)
            throws Exception {
        UiAssetGenerator.run(
                temporary, Path.of("first/ui_font.png"), Path.of("first/ui_font.json"));
        UiAssetGenerator.run(
                temporary, Path.of("second/ui_font.png"), Path.of("second/ui_font.json"));

        byte[] firstPng = Files.readAllBytes(temporary.resolve("first/ui_font.png"));
        byte[] firstJson = Files.readAllBytes(temporary.resolve("first/ui_font.json"));
        byte[] secondPng = Files.readAllBytes(temporary.resolve("second/ui_font.png"));
        byte[] secondJson = Files.readAllBytes(temporary.resolve("second/ui_font.json"));
        assertArrayEquals(firstPng, secondPng);
        assertArrayEquals(firstJson, secondJson);
        assertEquals("a6a27be503ff26fd119cfe3ab74375faf7fbc22e13e1ed5670e6f2d56f5fd1ca",
                sha256(firstPng));
        assertEquals("ec98df77b826b03df7fecfa2e77fadf540c47dc6e01e3b2664dd8aff35636ac4",
                sha256(firstJson));
        assertEquals(4, regularFiles(temporary).size());
    }

    private static List<Path> regularFiles(Path root) throws IOException {
        try (var paths = Files.walk(root)) {
            return paths.filter(Files::isRegularFile).sorted().toList();
        }
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
