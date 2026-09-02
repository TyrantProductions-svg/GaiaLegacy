package com.gaia.tools.ui;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MainMenuVisualConceptGeneratorTest {
    private static final List<String> EXPECTED_FILES = List.of(
            "concept-a-gaia-panorama.png",
            "concept-b-orbital-legacy.png",
            "concept-c-dark-signal.png",
            "measurement.json");
    private static final List<String> MENU_LABELS = List.of(
            "CONTINUE", "NEW WORLD", "WORLD ARCHIVE", "SETTINGS", "CONTROLS", "QUIT");

    @TempDir
    Path temporaryDirectory;

    @Test
    void createsThreeEqualContentWorldFirstConceptsWithClosedHeroProvenance()
            throws Exception {
        Path output = temporaryDirectory.resolve("concepts");

        MainMenuVisualConceptGenerator.GenerationResult result =
                new MainMenuVisualConceptGenerator().generate(output, heroSource());

        assertEquals(EXPECTED_FILES, result.files());
        for (String name : EXPECTED_FILES) {
            assertTrue(Files.size(output.resolve(name)) > 0, () -> "empty " + name);
        }
        for (String name : EXPECTED_FILES.subList(0, 3)) {
            BufferedImage image = ImageIO.read(output.resolve(name).toFile());
            assertEquals(1280, image.getWidth());
            assertEquals(720, image.getHeight());
            assertTrue(distinctRgbCount(image) > 1_000,
                    () -> name + " does not preserve a real environmental image");
        }
        assertNotEquals(result.sha256().get(EXPECTED_FILES.get(0)),
                result.sha256().get(EXPECTED_FILES.get(1)));
        assertNotEquals(result.sha256().get(EXPECTED_FILES.get(1)),
                result.sha256().get(EXPECTED_FILES.get(2)));

        JsonObject measurement = parse(output.resolve("measurement.json"));
        assertEquals("docs/images/gaialegacy-hero.png",
                measurement.getAsJsonObject("backgroundSource").get("repositoryPath").getAsString());
        assertEquals("66021ac3a9d197c8d9e52cab165019263eccfc688d402fe21391e930f87db262",
                measurement.getAsJsonObject("backgroundSource").get("sourceSha256").getAsString());
        assertEquals(MENU_LABELS,
                measurement.getAsJsonArray("menuLabels").asList().stream()
                        .map(element -> element.getAsString()).toList());
        assertEquals("Pixelify Sans + Inter",
                measurement.get("typographySystem").getAsString());
        assertEquals("PROJECT_OWNED_PLACEHOLDER",
                measurement.getAsJsonObject("wordmark").get("status").getAsString());
        assertEquals(3, measurement.getAsJsonArray("concepts").size());
    }

    @Test
    void independentConceptGenerationsAreByteIdentical() throws Exception {
        Path first = temporaryDirectory.resolve("first");
        Path second = temporaryDirectory.resolve("second");
        MainMenuVisualConceptGenerator generator = new MainMenuVisualConceptGenerator();

        MainMenuVisualConceptGenerator.GenerationResult firstResult =
                generator.generate(first, heroSource());
        MainMenuVisualConceptGenerator.GenerationResult secondResult =
                generator.generate(second, heroSource());

        assertEquals(firstResult.sha256(), secondResult.sha256());
        for (String name : EXPECTED_FILES) {
            assertArrayEquals(Files.readAllBytes(first.resolve(name)),
                    Files.readAllBytes(second.resolve(name)), name);
        }
    }

    private static Path heroSource() {
        Path working = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        Path direct = working.resolve("docs/images/gaialegacy-hero.png");
        return Files.isRegularFile(direct)
                ? direct
                : working.resolve("../docs/images/gaialegacy-hero.png").normalize();
    }

    private static int distinctRgbCount(BufferedImage image) {
        java.util.Set<Integer> colors = new java.util.HashSet<>();
        for (int y = 0; y < image.getHeight(); y += 4) {
            for (int x = 0; x < image.getWidth(); x += 4) {
                colors.add(image.getRGB(x, y) & 0x00ffffff);
            }
        }
        return colors.size();
    }

    private static JsonObject parse(Path path) throws Exception {
        return JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8))
                .getAsJsonObject();
    }
}
