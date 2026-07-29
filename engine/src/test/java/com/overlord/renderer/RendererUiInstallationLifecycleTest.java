package com.overlord.renderer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.overlord.core.thread.MainThreadGuard;
import com.overlord.renderer.pass.RenderContext;
import com.overlord.renderer.pass.RenderPass;
import com.overlord.renderer.pass.RenderPipeline;
import com.overlord.renderer.queue.RenderQueue;
import com.overlord.renderer.ui.BitmapFont;
import com.overlord.renderer.ui.BitmapGlyph;
import com.overlord.renderer.ui.UiAssetBundle;
import com.overlord.renderer.ui.UiTextureData;
import com.overlord.renderer.ui.UiUvRect;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class RendererUiInstallationLifecycleTest {
    @Test
    void installAllocatesOnceAndReplacesBasePipelineTailWithUiPass() throws Exception {
        List<String> trace = new ArrayList<>();
        AtomicInteger cleanupCount = new AtomicInteger();
        Renderer renderer = renderer((assets, guard) -> {
            trace.add("create");
            return new Renderer.InstalledUi(
                    pass("ui", trace),
                    () -> {
                        trace.add("cleanup");
                        cleanupCount.incrementAndGet();
                    });
        });
        installBasePasses(renderer, trace);

        renderer.installUiAssets(assets());
        pipeline(renderer).render(new RenderContext(new org.joml.Matrix4f(), new org.joml.Matrix4f()), new RenderQueue());

        assertEquals(
                List.of(
                        "create", "sky", "world", "block-damage", "world-items",
                        "particles", "debug", "ui"),
                trace);
        assertEquals(
                List.of(
                        "sky", "world", "block-damage", "world-items",
                        "particles", "debug", "ui"),
                pipeline(renderer).passIds());

        assertThrows(IllegalStateException.class, () -> renderer.installUiAssets(assets()));
        assertEquals(1, trace.stream().filter("create"::equals).count());

        renderer.cleanup();
        renderer.cleanup();
        assertEquals(1, cleanupCount.get());
    }

    @Test
    void nonOwnerInstallRejectsBeforeAllocation() throws Exception {
        AtomicInteger allocations = new AtomicInteger();
        Renderer renderer = renderer((assets, guard) -> {
            allocations.incrementAndGet();
            return new Renderer.InstalledUi(pass("ui", new ArrayList<>()), () -> {});
        });
        installBasePasses(renderer, new ArrayList<>());
        AtomicReference<Throwable> failure = new AtomicReference<>();

        Thread worker = new Thread(
                () -> {
                    try {
                        renderer.installUiAssets(assets());
                    } catch (Throwable thrown) {
                        failure.set(thrown);
                    }
                },
                "renderer-ui-non-owner");
        worker.start();
        worker.join();

        assertTrue(failure.get() instanceof IllegalStateException);
        assertEquals(0, allocations.get());
        renderer.cleanup();
    }

    @Test
    void pipelineInstallFailureCleansCreatedUiExactlyOnceAndLeavesInstallRetryable()
            throws Exception {
        AtomicInteger allocations = new AtomicInteger();
        AtomicInteger cleanups = new AtomicInteger();
        Renderer renderer = renderer((assets, guard) -> {
            int allocation = allocations.incrementAndGet();
            String passId = allocation == 1 ? "debug" : "ui";
            return new Renderer.InstalledUi(
                    pass(passId, new ArrayList<>()), cleanups::incrementAndGet);
        });
        installBasePasses(renderer, new ArrayList<>());

        assertThrows(IllegalArgumentException.class, () -> renderer.installUiAssets(assets()));
        assertEquals(1, allocations.get());
        assertEquals(1, cleanups.get());

        renderer.installUiAssets(assets());
        assertEquals(2, allocations.get());
        assertEquals(
                List.of(
                        "sky", "world", "block-damage", "world-items",
                        "particles", "debug", "ui"),
                pipeline(renderer).passIds());

        renderer.cleanup();
        renderer.cleanup();
        assertEquals(2, cleanups.get());
    }

    @Test
    void pipelineInstallPreservesPrimaryFailureWhenCleanupThrowsTheSameInstance()
            throws Exception {
        RuntimeException sharedFailure = new RuntimeException("shared install failure");
        RenderPass failingPass = new RenderPass() {
            @Override
            public String id() {
                throw sharedFailure;
            }

            @Override
            public void render(RenderContext context, RenderQueue queue) {
                throw new AssertionError("failing pass must never render");
            }
        };
        Renderer renderer = renderer((assets, guard) -> new Renderer.InstalledUi(
                failingPass,
                () -> {
                    throw sharedFailure;
                }));
        installBasePasses(renderer, new ArrayList<>());

        RuntimeException thrown = assertThrows(
                RuntimeException.class,
                () -> renderer.installUiAssets(assets()));

        assertSame(sharedFailure, thrown);
        assertEquals(0, thrown.getSuppressed().length);
    }

    private static Renderer renderer(Renderer.UiInstallationFactory factory) {
        return new Renderer(
                MainThreadGuard.captureCurrentThread(),
                RenderAssets.missing(),
                new com.overlord.assets.AssetManager(
                        RendererUiInstallationLifecycleTest.class.getClassLoader()),
                com.overlord.renderer.visual.RenderVisualSettings.milestoneOneDefaults(),
                factory);
    }

    private static void installBasePasses(Renderer renderer, List<String> trace) throws Exception {
        List<RenderPass> passes = List.of(
                pass("sky", trace),
                pass("world", trace),
                pass("block-damage", trace),
                pass("world-items", trace),
                pass("particles", trace),
                pass("debug", trace));
        setField(renderer, "baseRenderPasses", passes);
        setField(renderer, "renderPipeline", new RenderPipeline(passes));
    }

    private static RenderPipeline pipeline(Renderer renderer) throws Exception {
        Field field = Renderer.class.getDeclaredField("renderPipeline");
        field.setAccessible(true);
        return (RenderPipeline) field.get(renderer);
    }

    private static void setField(Renderer renderer, String name, Object value) throws Exception {
        Field field = Renderer.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(renderer, value);
    }

    private static RenderPass pass(String id, List<String> trace) {
        return new RenderPass() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public void render(RenderContext context, RenderQueue queue) {
                trace.add(id);
            }
        };
    }

    private static UiAssetBundle assets() {
        ByteBuffer rgba = ByteBuffer.allocateDirect(4);
        rgba.put(new byte[] {(byte) 255, (byte) 255, (byte) 255, (byte) 255}).flip();
        UiTextureData pixel = new UiTextureData(1, 1, rgba);
        BitmapGlyph missing = new BitmapGlyph(
                '?', new UiUvRect(0, 0, 1, 1), 1, 0, 0);
        return new UiAssetBundle(pixel, pixel, new BitmapFont(1, 1, Map.of((int) '?', missing), missing));
    }
}
