package com.overlord.renderer;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class RendererStructureTest {
    @Test
    void routesFramesThroughTheResourceBackedRenderPipeline()
            throws IOException {
        String source =
                Files.readString(
                        Path.of(
                                "src/main/java/com/overlord/renderer/"
                                        + "Renderer.java"));

        assertFalse(source.contains("#version"));
        assertFalse(source.contains("vertexSource"));
        assertFalse(source.contains("fragmentSource"));
        assertFalse(source.contains("new Shader("));
        assertTrue(source.contains("ShaderResourceLoader"));
        assertTrue(source.contains("ShaderProgram"));
        assertTrue(source.contains("RenderQueue"));
        assertTrue(source.contains("SkyRenderPass"));
        assertTrue(source.contains("WorldRenderPass"));
        assertTrue(source.contains("DebugRenderPass"));
        assertTrue(source.contains("RenderPipeline"));
        assertTrue(source.contains("renderFrame"));
    }

    @Test
    void removesTheInlineShaderImplementation() {
        assertFalse(
                Files.exists(
                        Path.of(
                                "src/main/java/com/overlord/renderer/"
                                        + "Shader.java")));
    }
}
