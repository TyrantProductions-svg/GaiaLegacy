package com.gaia.tools.ui;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
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

class TypographySpecimenGeneratorTest {
    private static final List<String> EXPECTED_FILES = List.of(
            "atlas-composite.json",
            "atlas-composite.png",
            "atlas-split-body.json",
            "atlas-split-body.png",
            "atlas-split-display.json",
            "atlas-split-display.png",
            "measurement.json",
            "pixelify-inter.png",
            "pixelify-plex.png",
            "quiet-rune-baseline.png");

    @TempDir
    Path temporaryDirectory;

    @Test
    void writesEqualLayoutBaselineAndABSpecimensWithMeasuredAtlasOptions()
            throws Exception {
        Path output = temporaryDirectory.resolve("specimen");

        TypographySpecimenGenerator.GenerationResult result =
                new TypographySpecimenGenerator().generate(output);

        assertEquals(EXPECTED_FILES, result.files());
        for (String name : EXPECTED_FILES) {
            assertTrue(Files.size(output.resolve(name)) > 0, () -> "empty " + name);
        }
        for (String name : List.of(
                "quiet-rune-baseline.png", "pixelify-inter.png", "pixelify-plex.png")) {
            BufferedImage image = ImageIO.read(output.resolve(name).toFile());
            assertEquals(1280, image.getWidth());
            assertEquals(720, image.getHeight());
        }
        assertNotEquals(result.sha256().get("pixelify-inter.png"),
                result.sha256().get("pixelify-plex.png"));

        JsonObject measurement = parse(output.resolve("measurement.json"));
        assertEquals(1280, measurement.getAsJsonObject("canvas").get("width").getAsInt());
        assertEquals(720, measurement.getAsJsonObject("canvas").get("height").getAsInt());
        assertEquals("quiet-rune-5x7", measurement.get("baseline").getAsString());
        assertEquals(42, rasterHeight(measurement, "pixelify-bold-700"));
        assertEquals(28, rasterHeight(measurement, "pixelify-semibold-600"));
        assertEquals(18, rasterHeight(measurement, "inter-regular-400"));
        assertEquals(16, rasterHeight(measurement, "inter-medium-500"));
        assertEquals(18, rasterHeight(measurement, "inter-semibold-600"));

        JsonArray options = measurement.getAsJsonArray("atlasOptions");
        assertEquals(2, options.size());
        assertEquals(1, options.get(0).getAsJsonObject().get("pageCount").getAsInt());
        assertEquals(2, options.get(1).getAsJsonObject().get("pageCount").getAsInt());
        assertTrue(options.get(0).getAsJsonObject().get("totalBytes").getAsInt()
                <= 2 * 1024 * 1024);
        assertTrue(options.get(1).getAsJsonObject().get("totalBytes").getAsInt()
                <= 2 * 1024 * 1024);
        assertEquals(8, measurement.getAsJsonArray("sourceFiles").size());
        assertEquals(64, measurement.get("layoutSha256").getAsString().length());
        assertTrue(measurement.getAsJsonArray("textRuns").size() >= 8);
    }

    @Test
    void independentGenerationsAreByteIdentical() throws Exception {
        Path first = temporaryDirectory.resolve("first");
        Path second = temporaryDirectory.resolve("second");
        TypographySpecimenGenerator generator = new TypographySpecimenGenerator();

        TypographySpecimenGenerator.GenerationResult firstResult = generator.generate(first);
        TypographySpecimenGenerator.GenerationResult secondResult = generator.generate(second);

        assertEquals(firstResult.sha256(), secondResult.sha256());
        for (String name : EXPECTED_FILES) {
            assertArrayEquals(Files.readAllBytes(first.resolve(name)),
                    Files.readAllBytes(second.resolve(name)), name);
        }
    }

    private static int rasterHeight(JsonObject measurement, String id) {
        for (var element : measurement.getAsJsonArray("rasterFaces")) {
            JsonObject face = element.getAsJsonObject();
            if (face.get("sourceId").getAsString().equals(id)) {
                return face.get("pixelHeight").getAsInt();
            }
        }
        throw new AssertionError("missing raster face " + id);
    }

    private static JsonObject parse(Path path) throws Exception {
        return JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8))
                .getAsJsonObject();
    }
}
