package com.gaia.tools.ui;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RuntimeTypographyAssetGeneratorTest {
    @Test
    void generatesTheApprovedTwoPagePixelifyInterRuntimeAssets(@TempDir Path temporary)
            throws Exception {
        Path first = temporary.resolve("first");
        Path second = temporary.resolve("second");

        RuntimeTypographyAssetGenerator.GenerationResult generated =
                new RuntimeTypographyAssetGenerator().generate(first);
        new RuntimeTypographyAssetGenerator().generate(second);

        assertEquals(List.of(
                        "ui_font_body.png",
                        "ui_font_display.png",
                        "ui_typography.json"),
                generated.files());
        for (String file : generated.files()) {
            assertArrayEquals(Files.readAllBytes(first.resolve(file)),
                    Files.readAllBytes(second.resolve(file)), file);
        }
        assertEquals(256, ImageIO.read(first.resolve("ui_font_display.png").toFile()).getWidth());
        assertEquals(512, ImageIO.read(first.resolve("ui_font_display.png").toFile()).getHeight());
        assertEquals(256, ImageIO.read(first.resolve("ui_font_body.png").toFile()).getWidth());
        assertEquals(256, ImageIO.read(first.resolve("ui_font_body.png").toFile()).getHeight());

        JsonObject root = JsonParser.parseString(Files.readString(
                first.resolve("ui_typography.json"), StandardCharsets.UTF_8)).getAsJsonObject();
        assertEquals(1, root.get("version").getAsInt());
        assertEquals("BODY", root.get("defaultRole").getAsString());
        assertEquals(2, root.getAsJsonArray("pages").size());
        assertEquals(5, root.getAsJsonArray("faces").size());
        assertEquals(5, root.getAsJsonObject("roles").size());
        assertEquals("NEAREST", root.getAsJsonArray("pages").get(1)
                .getAsJsonObject().get("sampling").getAsString());
        assertTrue(generated.sha256().values().stream()
                .allMatch(hash -> hash.matches("[0-9a-f]{64}")));
    }
}
