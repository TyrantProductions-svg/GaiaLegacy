package com.gaia.tools.ui;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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

class RuntimeHeroAssetGeneratorTest {
    @Test
    void recordsBakedSphereAndOccludedRingGeometry(@TempDir Path output) throws Exception {
        new RuntimeHeroAssetGenerator().generate(output, heroSource());
        JsonObject root = JsonParser.parseString(Files.readString(
                output.resolve("hero-manifest.json"))).getAsJsonObject();
        JsonObject planet = root.getAsJsonObject("treatment").getAsJsonObject("ringedPlanet");
        assertNotNull(planet, "hero must contain a filled ringed planet, not the old arcs");
        assertEquals(200, planet.get("sphereDiameter").getAsInt());
        assertEquals(430, planet.get("ringMajorAxis").getAsInt());
        assertEquals(-15, planet.get("tiltDegrees").getAsInt());
        assertEquals("BACK_RING_SPHERE_FRONT_RING_THEN_ATMOSPHERE",
                planet.get("occlusion").getAsString());
        assertTrue(planet.get("baked").getAsBoolean());
    }
    private static final List<String> HEROES = List.of(
            "gaia-hero-dawn.png",
            "gaia-hero-highlands.png",
            "gaia-hero-twilight.png");

    @TempDir
    Path temporaryDirectory;

    @Test
    void generatesThreeDeterministicProjectOwnedAPlusHeroPages() throws Exception {
        Path output = temporaryDirectory.resolve("heroes");

        RuntimeHeroAssetGenerator.GenerationResult result =
                new RuntimeHeroAssetGenerator().generate(output, heroSource());

        assertEquals(List.of(
                "gaia-hero-dawn.png",
                "gaia-hero-highlands.png",
                "gaia-hero-twilight.png",
                "hero-manifest.json"), result.files());
        for (String name : HEROES) {
            BufferedImage image = ImageIO.read(output.resolve(name).toFile());
            assertEquals(1280, image.getWidth(), name);
            assertEquals(720, image.getHeight(), name);
            assertTrue(distinctRgbCount(image) > 1_000, name);
        }

        JsonObject manifest = JsonParser.parseString(Files.readString(
                output.resolve("hero-manifest.json"), StandardCharsets.UTF_8))
                .getAsJsonObject();
        assertEquals(1, manifest.get("version").getAsInt());
        assertEquals("docs/images/gaialegacy-hero.png",
                manifest.getAsJsonObject("source").get("repositoryPath").getAsString());
        assertEquals("66021ac3a9d197c8d9e52cab165019263eccfc688d402fe21391e930f87db262",
                manifest.getAsJsonObject("source").get("sha256").getAsString());
        assertEquals("PROJECT_OWNED_GAIALEGACY_RUNTIME_CAPTURE",
                manifest.getAsJsonObject("source").get("ownership").getAsString());
        assertEquals(3, manifest.getAsJsonArray("heroes").size());
        assertEquals("gaia:ui/hero/gaia-hero-dawn.png",
                manifest.getAsJsonArray("heroes").get(0).getAsJsonObject()
                        .get("image").getAsString());
        assertEquals("LINEAR", manifest.getAsJsonArray("heroes").get(0)
                .getAsJsonObject().get("sampling").getAsString());
        assertTrue(manifest.getAsJsonObject("treatment")
                .get("directionalLeftShade").getAsBoolean());
        assertTrue(manifest.getAsJsonObject("treatment")
                .get("celestialBody").getAsBoolean());
        assertTrue(manifest.getAsJsonObject("treatment")
                .get("brokenOrbitAccents").getAsBoolean());
        assertTrue(manifest.getAsJsonObject("treatment")
                .get("topographicDetailMotif").getAsBoolean());
    }

    @Test
    void independentRuntimeHeroGenerationsAreByteIdentical() throws Exception {
        Path first = temporaryDirectory.resolve("first");
        Path second = temporaryDirectory.resolve("second");
        RuntimeHeroAssetGenerator generator = new RuntimeHeroAssetGenerator();

        generator.generate(first, heroSource());
        generator.generate(second, heroSource());

        for (String name : List.of(
                "gaia-hero-dawn.png",
                "gaia-hero-highlands.png",
                "gaia-hero-twilight.png",
                "hero-manifest.json")) {
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
}
