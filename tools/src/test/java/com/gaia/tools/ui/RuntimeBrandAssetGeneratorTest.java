package com.gaia.tools.ui;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RuntimeBrandAssetGeneratorTest {
    @TempDir Path temporary;

    @Test
    void producesSmoothPaddedStraightAlphaBrandPage() throws Exception {
        new RuntimeBrandAssetGenerator().generate(temporary);
        BufferedImage image = ImageIO.read(temporary.resolve("gaia-emblem.png").toFile());
        assertEquals(256, image.getWidth());
        assertEquals(256, image.getHeight());
        int partial = 0;
        int opaque = 0;
        for (int y = 0; y < 256; y++) {
            for (int x = 0; x < 256; x++) {
                int rgba = image.getRGB(x, y);
                int alpha = rgba >>> 24;
                // Constant edge RGB avoids dark/white fringes with straight-alpha LINEAR.
                assertEquals(0x91dce8, rgba & 0xffffff);
                if (x < 16 || y < 16 || x >= 240 || y >= 240) assertEquals(0, alpha);
                if (alpha > 0 && alpha < 255) partial++;
                if (alpha == 255) opaque++;
            }
        }
        assertTrue(partial > 300, "supersampled curved edges must have coverage alpha");
        assertTrue(opaque > 1000, "recognizable line art, not an empty asset");
        assertEquals(0, image.getRGB(128, 35) >>> 24, "outer ring has an intentional break");
        assertTrue(Files.readString(temporary.resolve("brand-manifest.json"))
                .contains("LINEAR"));
    }

    @Test
    void sourceByteReceiptsAreProtectedFromCheckoutLineEndingConversion() throws Exception {
        Path root = Files.isRegularFile(Path.of(".gitattributes")) ? Path.of(".") : Path.of("..");
        String attributes = Files.readString(root.resolve(".gitattributes"));
        for (String path : new String[] {
                "tools/src/main/java/com/gaia/tools/ui/RuntimeBrandAssetGenerator.java",
                "tools/src/main/java/com/gaia/tools/ui/RingedPlanetLayer.java",
                "game/src/main/resources/assets/gaia/ui/brand/brand-manifest.json",
                "game/src/main/resources/assets/gaia/ui/hero/hero-manifest.json"}) {
            assertTrue(attributes.contains("/" + path + " text eol=lf"), path);
        }
    }

    @Test
    void generationIsByteIdentical() throws Exception {
        Path first = temporary.resolve("first");
        Path second = temporary.resolve("second");
        new RuntimeBrandAssetGenerator().generate(first);
        new RuntimeBrandAssetGenerator().generate(second);
        for (String name : new String[] {"gaia-emblem.png", "brand-manifest.json"}) {
            assertEquals(-1L, Files.mismatch(first.resolve(name), second.resolve(name)));
        }
    }
}
