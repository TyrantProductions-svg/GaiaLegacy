package com.overlord.renderer;

import static org.lwjgl.opengl.GL11C.glDisable;
import static org.lwjgl.opengl.GL30C.GL_FRAMEBUFFER_SRGB;
import static org.lwjgl.opengl.GL30C.glClearColor;
import static org.lwjgl.opengl.GL30C.glViewport;

import com.overlord.assets.AssetManager;
import com.overlord.config.GameConfig;
import com.overlord.core.thread.MainThreadGuard;
import com.overlord.renderer.frustum.Frustum;
import com.overlord.renderer.material.Material;
import com.overlord.renderer.pass.DebugRenderPass;
import com.overlord.renderer.pass.RenderContext;
import com.overlord.renderer.pass.RenderPipeline;
import com.overlord.renderer.pass.SkyRenderPass;
import com.overlord.renderer.pass.WorldRenderPass;
import com.overlord.renderer.queue.RenderQueue;
import com.overlord.renderer.shader.ShaderProgram;
import com.overlord.renderer.shader.ShaderResourceLoader;
import com.overlord.renderer.shader.ShaderSourceSet;
import com.overlord.renderer.state.OpenGlRenderStateBackend;
import com.overlord.renderer.visual.RenderVisualSettings;
import com.overlord.voxel.ChunkKey;
import com.overlord.voxel.ChunkMeshData;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import org.joml.Matrix4f;

public final class Renderer implements ChunkRenderBackend {
    private final MainThreadGuard mainThreadGuard;
    private final RenderAssets renderAssets;
    private final AssetManager assetManager;
    private final RenderVisualSettings visualSettings;

    private ShaderProgram worldShaderProgram;
    private ShaderProgram skyShaderProgram;
    private Camera camera;
    private Texture textureAtlas;
    private Material worldMaterial;
    private RenderQueue renderQueue;
    private RenderPipeline renderPipeline;
    private Matrix4f projectionMatrix;
    private FullscreenTriangle fullscreenTriangle;

    public Renderer(
            MainThreadGuard mainThreadGuard,
            RenderAssets renderAssets) {
        this(
                mainThreadGuard,
                renderAssets,
                new AssetManager(Renderer.class.getClassLoader()),
                RenderVisualSettings.milestoneOneDefaults());
    }

    public Renderer(
            MainThreadGuard mainThreadGuard,
            RenderAssets renderAssets,
            AssetManager assetManager) {
        this(
                mainThreadGuard,
                renderAssets,
                assetManager,
                RenderVisualSettings.milestoneOneDefaults());
    }

    public Renderer(
            MainThreadGuard mainThreadGuard,
            RenderAssets renderAssets,
            AssetManager assetManager,
            RenderVisualSettings visualSettings) {
        this.mainThreadGuard =
                Objects.requireNonNull(
                        mainThreadGuard, "mainThreadGuard");
        this.renderAssets =
                Objects.requireNonNull(renderAssets, "renderAssets");
        this.assetManager =
                Objects.requireNonNull(assetManager, "assetManager");
        this.visualSettings =
                Objects.requireNonNull(visualSettings, "visualSettings");
    }

    public void init(Camera camera, int width, int height) {
        mainThreadGuard.assertMainThread("renderer initialization");
        ensureNotInitialized();
        Camera initializedCamera = Objects.requireNonNull(camera, "camera");
        ShaderProgram initializedWorldProgram = null;
        ShaderProgram initializedSkyProgram = null;
        Texture initializedTexture = null;
        FullscreenTriangle initializedTriangle = null;
        try {
            ShaderSourceSet worldShaderSources =
                    new ShaderResourceLoader(assetManager)
                            .load(
                                    "world",
                                    renderAssets.worldVertexShader(),
                                    renderAssets.worldFragmentShader());
            initializedWorldProgram =
                    new ShaderProgram(
                            mainThreadGuard,
                            worldShaderSources,
                            List.of(
                                    "projection",
                                    "view",
                                    "model",
                                    "textureAtlas",
                                    "sunDirection",
                                    "ambientStrength",
                                    "directionalStrength",
                                    "fogColor",
                                    "fogStart",
                                    "fogEnd"));
            ShaderSourceSet skyShaderSources =
                    new ShaderResourceLoader(assetManager)
                            .load(
                                    "sky",
                                    renderAssets.skyVertexShader(),
                                    renderAssets.skyFragmentShader());
            initializedSkyProgram =
                    new ShaderProgram(
                            mainThreadGuard,
                            skyShaderSources,
                            List.of("skyHorizon", "skyTop"));
            initializedTexture =
                    new Texture(
                            mainThreadGuard,
                            renderAssets.blockAtlas());
            Material initializedWorldMaterial =
                    new Material(
                            renderAssets.worldMaterial(),
                            initializedWorldProgram,
                            initializedTexture);
            initializedTriangle =
                    new FullscreenTriangle(mainThreadGuard);
            OpenGlRenderStateBackend stateBackend =
                    new OpenGlRenderStateBackend(mainThreadGuard);
            SkyRenderPass skyPass =
                    new SkyRenderPass(
                            stateBackend,
                            initializedSkyProgram,
                            initializedTriangle::draw);
            WorldRenderPass worldPass =
                    new WorldRenderPass(stateBackend);
            DebugRenderPass debugPass = new DebugRenderPass();
            RenderPipeline initializedPipeline =
                    new RenderPipeline(
                            List.of(skyPass, worldPass, debugPass));
            RenderQueue initializedQueue = new RenderQueue();
            Matrix4f initializedProjection =
                    createProjection(width, height);

            glDisable(GL_FRAMEBUFFER_SRGB);
            glClearColor(0.1f, 0.1f, 0.15f, 1.0f);
            if (width > 0 && height > 0) {
                glViewport(0, 0, width, height);
            }

            this.camera = initializedCamera;
            worldShaderProgram = initializedWorldProgram;
            skyShaderProgram = initializedSkyProgram;
            textureAtlas = initializedTexture;
            worldMaterial = initializedWorldMaterial;
            renderQueue = initializedQueue;
            renderPipeline = initializedPipeline;
            projectionMatrix = initializedProjection;
            fullscreenTriangle = initializedTriangle;
        } catch (RuntimeException | Error failure) {
            clearInitializedFields();
            cleanupAfterInitializationFailure(
                    initializedTriangle,
                    initializedTexture,
                    initializedSkyProgram,
                    initializedWorldProgram,
                    failure);
            throw failure;
        }
    }

    @Override
    public ChunkRenderObject upload(ChunkMeshData data) {
        mainThreadGuard.assertMainThread("chunk mesh GPU upload");
        Objects.requireNonNull(data, "data");
        if (data.isEmpty()) {
            throw new IllegalArgumentException(
                    "Empty chunk data does not allocate a GPU mesh");
        }
        ChunkKey key = Objects.requireNonNull(data.key(), "data.key()");
        long revision = data.revision();
        if (revision < 0) {
            throw new IllegalArgumentException(
                    "revision must not be negative");
        }
        AxisAlignedBounds localBounds =
                data.localBounds()
                        .orElseThrow(
                                () -> new IllegalArgumentException(
                                        "Non-empty chunk data must have local bounds"));

        Mesh gpuMesh = new Mesh(mainThreadGuard, data.vertices());
        try {
            return new ChunkRenderObject(
                    key,
                    revision,
                    gpuMesh,
                    localBounds);
        } catch (RuntimeException | Error failure) {
            try {
                gpuMesh.cleanup();
            } catch (RuntimeException | Error cleanupFailure) {
                if (cleanupFailure != failure) {
                    failure.addSuppressed(cleanupFailure);
                }
            }
            throw failure;
        }
    }

    @Override
    public void release(ChunkRenderObject object) {
        mainThreadGuard.assertMainThread("chunk mesh GPU release");
        Objects.requireNonNull(object, "object").mesh().cleanup();
    }

    public void resizeFramebuffer(int width, int height) {
        mainThreadGuard.assertMainThread("framebuffer resize");
        if (width <= 0 || height <= 0) {
            return;
        }
        glViewport(0, 0, width, height);
        rebuildProjection(width, height);
    }

    public void renderFrame(Collection<ChunkRenderObject> chunks) {
        mainThreadGuard.assertMainThread("frame rendering");
        RenderQueue frameQueue =
                requireInitialized(renderQueue, "render queue");
        frameQueue.clear();
        try {
            Objects.requireNonNull(chunks, "chunks");
            Material frameMaterial =
                    requireInitialized(worldMaterial, "world material");
            Matrix4f frameProjection =
                    requireInitialized(
                            projectionMatrix,
                            "projection matrix");
            Matrix4f frameView =
                    requireInitialized(camera, "camera")
                            .getViewMatrix();
            Frustum currentFrustum =
                    Frustum.from(frameProjection, frameView);
            for (ChunkRenderObject chunk : chunks) {
                Objects.requireNonNull(chunk, "chunk");
                if (currentFrustum.intersects(chunk.worldBounds())) {
                    frameQueue.submit(chunk, frameMaterial);
                }
            }
            RenderContext context =
                    new RenderContext(
                            frameProjection,
                            frameView,
                            visualSettings);
            requireInitialized(renderPipeline, "render pipeline")
                    .render(context, frameQueue);
        } finally {
            frameQueue.clear();
        }
    }

    public void cleanup() {
        mainThreadGuard.assertMainThread("renderer cleanup");
        FullscreenTriangle triangleToClean = fullscreenTriangle;
        Texture textureToClean = textureAtlas;
        ShaderProgram skyProgramToClean = skyShaderProgram;
        ShaderProgram worldProgramToClean = worldShaderProgram;
        clearInitializedFields();

        Throwable failure = null;
        failure = runCleanup(triangleToClean, failure);
        failure = runCleanup(textureToClean, failure);
        failure = runCleanup(skyProgramToClean, failure);
        failure = runCleanup(worldProgramToClean, failure);
        if (failure != null) {
            rethrow(failure);
        }
    }

    private void clearInitializedFields() {
        worldShaderProgram = null;
        skyShaderProgram = null;
        textureAtlas = null;
        worldMaterial = null;
        renderQueue = null;
        renderPipeline = null;
        camera = null;
        projectionMatrix = null;
        fullscreenTriangle = null;
    }

    private void ensureNotInitialized() {
        if (worldShaderProgram != null
                || skyShaderProgram != null
                || textureAtlas != null
                || worldMaterial != null
                || renderQueue != null
                || renderPipeline != null
                || camera != null
                || projectionMatrix != null
                || fullscreenTriangle != null) {
            throw new IllegalStateException(
                    "Renderer is already initialized");
        }
    }

    private void rebuildProjection(int width, int height) {
        projectionMatrix = createProjection(width, height);
    }

    private static Matrix4f createProjection(int width, int height) {
        int projectionWidth = Math.max(1, width);
        int projectionHeight = Math.max(1, height);
        return new Matrix4f()
                .perspective(
                        (float)
                                Math.toRadians(
                                        GameConfig.Rendering.FOV),
                        (float) projectionWidth / projectionHeight,
                        GameConfig.Rendering.NEAR_PLANE,
                        GameConfig.Rendering.FAR_PLANE);
    }

    private static void cleanupAfterInitializationFailure(
            FullscreenTriangle triangle,
            Texture texture,
            ShaderProgram skyProgram,
            ShaderProgram worldProgram,
            Throwable primaryFailure) {
        suppressCleanup(triangle, primaryFailure);
        suppressCleanup(texture, primaryFailure);
        suppressCleanup(skyProgram, primaryFailure);
        suppressCleanup(worldProgram, primaryFailure);
    }

    private static void suppressCleanup(
            FullscreenTriangle triangle,
            Throwable primaryFailure) {
        if (triangle == null) {
            return;
        }
        try {
            triangle.cleanup();
        } catch (RuntimeException | Error cleanupFailure) {
            if (cleanupFailure != primaryFailure) {
                primaryFailure.addSuppressed(cleanupFailure);
            }
        }
    }

    private static void suppressCleanup(
            Texture texture,
            Throwable primaryFailure) {
        if (texture == null) {
            return;
        }
        try {
            texture.cleanup();
        } catch (RuntimeException | Error cleanupFailure) {
            if (cleanupFailure != primaryFailure) {
                primaryFailure.addSuppressed(cleanupFailure);
            }
        }
    }

    private static void suppressCleanup(
            ShaderProgram program,
            Throwable primaryFailure) {
        if (program == null) {
            return;
        }
        try {
            program.cleanup();
        } catch (RuntimeException | Error cleanupFailure) {
            if (cleanupFailure != primaryFailure) {
                primaryFailure.addSuppressed(cleanupFailure);
            }
        }
    }

    private static Throwable runCleanup(
            FullscreenTriangle triangle,
            Throwable firstFailure) {
        if (triangle == null) {
            return firstFailure;
        }
        try {
            triangle.cleanup();
        } catch (RuntimeException | Error cleanupFailure) {
            return appendCleanupFailure(firstFailure, cleanupFailure);
        }
        return firstFailure;
    }

    private static Throwable runCleanup(
            Texture texture,
            Throwable firstFailure) {
        if (texture == null) {
            return firstFailure;
        }
        try {
            texture.cleanup();
        } catch (RuntimeException | Error cleanupFailure) {
            return appendCleanupFailure(firstFailure, cleanupFailure);
        }
        return firstFailure;
    }

    private static Throwable runCleanup(
            ShaderProgram program,
            Throwable firstFailure) {
        if (program == null) {
            return firstFailure;
        }
        try {
            program.cleanup();
        } catch (RuntimeException | Error cleanupFailure) {
            return appendCleanupFailure(firstFailure, cleanupFailure);
        }
        return firstFailure;
    }

    private static Throwable appendCleanupFailure(
            Throwable firstFailure,
            Throwable cleanupFailure) {
        if (firstFailure == null) {
            return cleanupFailure;
        }
        if (cleanupFailure != firstFailure) {
            firstFailure.addSuppressed(cleanupFailure);
        }
        return firstFailure;
    }

    private static void rethrow(Throwable failure) {
        if (failure instanceof RuntimeException runtimeException) {
            throw runtimeException;
        }
        throw (Error) failure;
    }

    private static <T> T requireInitialized(T value, String resource) {
        if (value == null) {
            throw new IllegalStateException(
                    "Renderer " + resource + " is not initialized");
        }
        return value;
    }
}
