package com.overlord.renderer;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class SingleCrosshairAuthorityTest {
    @Test
    void oldDedicatedCrosshairPassAndShadersAreAbsentAfterUiParityMigration() {
        assertFalse(Files.exists(Path.of(
                "src/main/java/com/overlord/renderer/pass/CrosshairRenderPass.java")));
        assertFalse(Files.exists(Path.of(
                "src/main/resources/assets/overlord/shaders/feedback/crosshair.vert")));
        assertFalse(Files.exists(Path.of(
                "src/main/resources/assets/overlord/shaders/feedback/crosshair.frag")));
        assertFalse(read(Path.of("build.gradle")).contains("feedback/crosshair"));
        assertFalse(read(Path.of("../game/build.gradle")).contains("feedback/crosshair"));
    }

    @Test
    void rendererRegistersUiAsTheOnlyFinalScreenSpacePass() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/com/overlord/renderer/Renderer.java"));

        assertTrue(source.contains("UiRenderPass"));
        assertTrue(source.contains("installUiAssets"));
        assertFalse(source.contains("CrosshairRenderPass"));
        assertFalse(source.contains("createCrosshairBatch"));
    }

    private static String read(Path path) {
        try {
            return Files.readString(path);
        } catch (java.io.IOException failure) {
            throw new AssertionError(failure);
        }
    }
}
