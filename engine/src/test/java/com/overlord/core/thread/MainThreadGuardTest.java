package com.overlord.core.thread;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.overlord.core.input.InputManager;
import com.overlord.renderer.ChunkGpuMesh;
import com.overlord.renderer.Mesh;
import com.overlord.renderer.RenderAssets;
import com.overlord.renderer.Renderer;
import com.overlord.renderer.Texture;
import com.overlord.renderer.shader.ShaderProgram;
import com.overlord.renderer.shader.ShaderSourceSet;
import com.overlord.renderer.texture.TextureImage;
import com.overlord.assets.ResourceLocation;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class MainThreadGuardTest {
    @Test
    void meshImplementsChunkGpuMesh() {
        assertTrue(ChunkGpuMesh.class.isAssignableFrom(Mesh.class));
    }

    @Test
    void acceptsOwnerAndRejectsWorkerThreadOperations() throws InterruptedException {
        MainThreadGuard guard = MainThreadGuard.captureCurrentThread();
        assertDoesNotThrow(() -> guard.assertMainThread("event polling"));

        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            ExecutionException failure =
                    assertThrows(
                            ExecutionException.class,
                            () -> worker.submit(() -> guard.assertMainThread("GPU upload")).get());

            IllegalStateException cause =
                    assertInstanceOf(IllegalStateException.class, failure.getCause());
            assertTrue(cause.getMessage().contains("GPU upload"));
        } finally {
            worker.shutdownNow();
            assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void rendererRejectsWorkerBeforeCallingOpenGl() throws InterruptedException {
        Renderer renderer =
                new Renderer(
                        MainThreadGuard.captureCurrentThread(),
                        RenderAssets.missing());
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            ExecutionException failure =
                    assertThrows(
                            ExecutionException.class,
                            () -> worker.submit(() -> renderer.resizeFramebuffer(800, 600)).get());

            assertInstanceOf(IllegalStateException.class, failure.getCause());
        } finally {
            worker.shutdownNow();
            assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void textureRejectsWorkerBeforeUploadingToOpenGl() throws InterruptedException {
        MainThreadGuard guard = MainThreadGuard.captureCurrentThread();
        TextureImage image = TextureImage.missing();
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            ExecutionException failure =
                    assertThrows(
                            ExecutionException.class,
                            () -> worker.submit(() -> new Texture(guard, image)).get());

            assertInstanceOf(IllegalStateException.class, failure.getCause());
        } finally {
            worker.shutdownNow();
            assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void inputManagerRejectsWorkerBeforeInstallingGlfwCallbacks() throws InterruptedException {
        InputManager inputManager =
                new InputManager(MainThreadGuard.captureCurrentThread());
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            ExecutionException failure =
                    assertThrows(
                            ExecutionException.class,
                            () -> worker.submit(() -> inputManager.install(0)).get());

            assertInstanceOf(IllegalStateException.class, failure.getCause());
        } finally {
            worker.shutdownNow();
            assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void shaderProgramRejectsWorkerBeforeCallingOpenGl() throws InterruptedException {
        MainThreadGuard guard = MainThreadGuard.captureCurrentThread();
        ShaderSourceSet sources =
                new ShaderSourceSet(
                        "world",
                        ResourceLocation.of("overlord", "shaders/world.vert"),
                        "#version 410 core\nvoid main() {}",
                        ResourceLocation.of("overlord", "shaders/world.frag"),
                        "#version 410 core\nout vec4 color; void main() { color = vec4(1.0); }");
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            ExecutionException failure =
                    assertThrows(
                            ExecutionException.class,
                            () -> worker.submit(() -> new ShaderProgram(guard, sources, List.of())).get());

            assertInstanceOf(IllegalStateException.class, failure.getCause());
        } finally {
            worker.shutdownNow();
            assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS));
        }
    }
}
