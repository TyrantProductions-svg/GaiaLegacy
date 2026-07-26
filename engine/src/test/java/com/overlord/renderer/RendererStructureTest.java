package com.overlord.renderer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.overlord.assets.AssetManager;
import com.overlord.core.thread.MainThreadGuard;
import com.overlord.renderer.queue.RenderQueue;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
import org.joml.Matrix4f;
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
    void initializesTheWorldShaderWithEveryRequiredUniformAndManualGammaState()
            throws IOException {
        String source =
                Files.readString(
                        Path.of(
                                "src/main/java/com/overlord/renderer/"
                                        + "Renderer.java"));

        for (String uniform :
                java.util.List.of(
                        "projection",
                        "view",
                        "model",
                        "textureAtlas",
                        "sunDirection",
                        "ambientStrength",
                        "directionalStrength",
                        "fogColor",
                        "fogStart",
                        "fogEnd")) {
            assertTrue(
                    source.contains("\"" + uniform + "\""),
                    "Missing required world uniform: " + uniform);
        }
        assertTrue(source.contains("glDisable(GL_FRAMEBUFFER_SRGB);"));
        assertFalse(source.contains("glEnable(GL_FRAMEBUFFER_SRGB)"));
        assertTrue(source.contains("visualSettings);"));
    }

    @Test
    void removesTheInlineShaderImplementation() {
        assertFalse(
                Files.exists(
                        Path.of(
                                "src/main/java/com/overlord/renderer/"
                                        + "Shader.java")));
    }

    @Test
    void activeRendererRejectsReinitializationWithoutClearingState()
            throws ReflectiveOperationException {
        Renderer renderer =
                new Renderer(
                        MainThreadGuard.captureCurrentThread(),
                        RenderAssets.missing(),
                        new AssetManager(
                                new FailingResourceClassLoader()));
        Field queueField =
                Renderer.class.getDeclaredField("renderQueue");
        queueField.setAccessible(true);
        RenderQueue activeQueue = new RenderQueue();
        queueField.set(renderer, activeQueue);

        assertThrows(
                IllegalStateException.class,
                () -> renderer.init(new Camera(), 800, 600));
        assertSame(activeQueue, queueField.get(renderer));

        renderer.cleanup();

        assertThrows(
                ResourceLookupAttempt.class,
                () -> renderer.init(new Camera(), 800, 600));
    }

    @Test
    void projectionClampsEachNonPositiveDimensionAndKeepsPositiveAspect()
            throws ReflectiveOperationException {
        assertProjection(0, 0, 1.0f);
        assertProjection(0, 5, 0.2f);
        assertProjection(5, 0, 5.0f);
        assertProjection(1600, 900, 16.0f / 9.0f);
    }

    private static void assertProjection(
            int width,
            int height,
            float expectedAspect)
            throws ReflectiveOperationException {
        Method createProjection =
                Renderer.class.getDeclaredMethod(
                        "createProjection", int.class, int.class);
        createProjection.setAccessible(true);
        Matrix4f projection =
                (Matrix4f) createProjection.invoke(null, width, height);

        assertTrue(
                projection.isFinite(),
                () ->
                        "Non-finite projection for "
                                + width
                                + "x"
                                + height);
        assertEquals(
                expectedAspect,
                projection.m11() / projection.m00(),
                0.0001f);
    }

    private static final class FailingResourceClassLoader
            extends ClassLoader {
        @Override
        public Enumeration<URL> getResources(String name) {
            throw new ResourceLookupAttempt();
        }
    }

    private static final class ResourceLookupAttempt extends Error {}
}
