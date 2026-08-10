package com.overlord.renderer;

import static org.lwjgl.opengl.GL11C.glDisable;
import static org.lwjgl.opengl.GL30C.GL_FRAMEBUFFER_SRGB;
import static org.lwjgl.opengl.GL30C.glClearColor;
import static org.lwjgl.opengl.GL30C.glViewport;

import com.overlord.assets.AssetManager;
import com.overlord.assets.ResourceLocation;
import com.overlord.config.GameConfig;
import com.overlord.core.thread.MainThreadGuard;
import com.overlord.renderer.frustum.Frustum;
import com.overlord.renderer.feedback.InteractionFeedbackAssets;
import com.overlord.renderer.feedback.OpenGlStreamingTexturedCubeBatch;
import com.overlord.renderer.feedback.OpenGlUnitCubeMesh;
import com.overlord.renderer.feedback.StreamingTexturedCubeBatch;
import com.overlord.renderer.feedback.UnitCubeMesh;
import com.overlord.renderer.metrics.RenderMetrics;
import com.overlord.renderer.material.Material;
import com.overlord.renderer.pass.BlockDamageOverlayPass;
import com.overlord.renderer.pass.DebugRenderPass;
import com.overlord.renderer.pass.FirstPersonItemVisualPass;
import com.overlord.renderer.pass.ParticleRenderPass;
import com.overlord.renderer.pass.RenderContext;
import com.overlord.renderer.pass.RenderPass;
import com.overlord.renderer.pass.RenderPipeline;
import com.overlord.renderer.pass.SkyRenderPass;
import com.overlord.renderer.pass.TransientBlockVisualPass;
import com.overlord.renderer.pass.WorldItemVisualPass;
import com.overlord.renderer.pass.WorldRenderPass;
import com.overlord.renderer.pass.UiRenderPass;
import com.overlord.renderer.queue.RenderQueue;
import com.overlord.renderer.shader.ShaderProgram;
import com.overlord.renderer.shader.ShaderResourceLoader;
import com.overlord.renderer.shader.ShaderSourceSet;
import com.overlord.renderer.shader.ShaderBinding;
import com.overlord.renderer.shader.WorldShaderUniforms;
import com.overlord.renderer.state.OpenGlRenderStateBackend;
import com.overlord.renderer.texture.TextureImage;
import com.overlord.renderer.visual.RenderVisualSettings;
import com.overlord.renderer.ui.OpenGlUiGpuBackend;
import com.overlord.renderer.ui.UiAssetBundle;
import com.overlord.renderer.ui.UiRenderer;
import com.overlord.voxel.ChunkKey;
import com.overlord.voxel.ChunkMeshData;
import java.util.List;
import java.util.Objects;
import org.joml.Matrix4f;

public final class Renderer implements ChunkRenderBackend {
    private static final float MIN_FOV_DEGREES = 50.0f;
    private static final float MAX_FOV_DEGREES = 100.0f;

    private final MainThreadGuard mainThreadGuard;
    private final RenderAssets renderAssets;
    private final AssetManager assetManager;
    private final RenderVisualSettings visualSettings;
    private final UiInstallationFactory uiInstallationFactory;
    private final RenderMetricsCollector metricsCollector = new RenderMetricsCollector();

    private ShaderProgram worldShaderProgram;
    private ShaderProgram skyShaderProgram;
    private Camera camera;
    private Texture textureAtlas;
    private Material worldMaterial;
    private RenderQueue renderQueue;
    private RenderPipeline renderPipeline;
    private List<RenderPass> baseRenderPasses;
    private Matrix4f projectionMatrix;
    private FullscreenTriangle fullscreenTriangle;
    private FeedbackResources feedbackResources;
    private InstalledUi installedUi;
    private RenderSurfaceMetrics surfaceMetrics;
    private RenderSurfaceController surfaceController;
    private float fovDegrees = GameConfig.Rendering.FOV;

    public Renderer(
            MainThreadGuard mainThreadGuard,
            RenderAssets renderAssets) {
        this(
                mainThreadGuard,
                renderAssets,
                new AssetManager(Renderer.class.getClassLoader()),
                RenderVisualSettings.milestoneOneDefaults(),
                Renderer::createOpenGlUi);
    }

    public Renderer(
            MainThreadGuard mainThreadGuard,
            RenderAssets renderAssets,
            AssetManager assetManager) {
        this(
                mainThreadGuard,
                renderAssets,
                assetManager,
                RenderVisualSettings.milestoneOneDefaults(),
                Renderer::createOpenGlUi);
    }

    public Renderer(
            MainThreadGuard mainThreadGuard,
            RenderAssets renderAssets,
            AssetManager assetManager,
            RenderVisualSettings visualSettings) {
        this(
                mainThreadGuard,
                renderAssets,
                assetManager,
                visualSettings,
                Renderer::createOpenGlUi);
    }

    Renderer(
            MainThreadGuard mainThreadGuard,
            RenderAssets renderAssets,
            AssetManager assetManager,
            RenderVisualSettings visualSettings,
            UiInstallationFactory uiInstallationFactory) {
        this.mainThreadGuard =
                Objects.requireNonNull(
                        mainThreadGuard, "mainThreadGuard");
        this.renderAssets =
                Objects.requireNonNull(renderAssets, "renderAssets");
        this.assetManager =
                Objects.requireNonNull(assetManager, "assetManager");
        this.visualSettings =
                Objects.requireNonNull(visualSettings, "visualSettings");
        this.uiInstallationFactory = Objects.requireNonNull(
                uiInstallationFactory, "uiInstallationFactory");
    }

    public void init(Camera camera, RenderSurfaceMetrics surfaceMetrics) {
        mainThreadGuard.assertMainThread("renderer initialization");
        ensureNotInitialized();
        Camera initializedCamera = Objects.requireNonNull(camera, "camera");
        ShaderProgram initializedWorldProgram = null;
        ShaderProgram initializedSkyProgram = null;
        Texture initializedTexture = null;
        FullscreenTriangle initializedTriangle = null;
        FeedbackResources initializedFeedbackResources = null;
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
                            WorldShaderUniforms.requiredUniforms());
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
            initializedFeedbackResources = FeedbackResources.create(
                    mainThreadGuard,
                    new OpenGlFeedbackResourceFactory(mainThreadGuard, assetManager),
                    renderAssets.feedback());
            OpenGlRenderStateBackend stateBackend =
                    new OpenGlRenderStateBackend(mainThreadGuard);
            SkyRenderPass skyPass =
                    new SkyRenderPass(
                            stateBackend,
                            initializedSkyProgram,
                            initializedTriangle::draw);
            WorldRenderPass worldPass =
                    new WorldRenderPass(stateBackend);
            BlockDamageOverlayPass damagePass =
                    new BlockDamageOverlayPass(
                            stateBackend,
                            initializedFeedbackResources.damageShader(),
                            initializedFeedbackResources.damageTexture(),
                            renderAssets.feedback().damageAtlas(),
                            initializedFeedbackResources.unitCube());
            WorldItemVisualPass worldItemPass =
                    new WorldItemVisualPass(
                            stateBackend,
                            initializedFeedbackResources.worldItemShader(),
                            initializedTexture,
                            initializedFeedbackResources.unitCube());
            TransientBlockVisualPass transientBlockPass =
                    new TransientBlockVisualPass(
                            stateBackend,
                            initializedFeedbackResources.worldItemShader(),
                            initializedTexture,
                            initializedFeedbackResources.unitCube());
            ParticleRenderPass particlePass =
                    new ParticleRenderPass(
                            stateBackend,
                            initializedFeedbackResources.particleShader(),
                            initializedTexture,
                            initializedFeedbackResources.particleBatch());
            FirstPersonItemVisualPass firstPersonItemPass =
                    new FirstPersonItemVisualPass(
                            stateBackend,
                            initializedFeedbackResources.worldItemShader(),
                            initializedTexture,
                            initializedFeedbackResources.unitCube());
            DebugRenderPass debugPass = new DebugRenderPass();
            List<RenderPass> initializedBasePasses = List.of(
                    skyPass,
                    worldPass,
                    damagePass,
                    transientBlockPass,
                    worldItemPass,
                    particlePass,
                    firstPersonItemPass,
                    debugPass);
            RenderPipeline initializedPipeline = new RenderPipeline(initializedBasePasses);
            RenderQueue initializedQueue = new RenderQueue();
            RenderSurfaceMetrics initializedSurfaceMetrics =
                    Objects.requireNonNull(surfaceMetrics, "surfaceMetrics");
            RenderSurfaceController initializedSurfaceController =
                    new RenderSurfaceController(initializedSurfaceMetrics);
            Matrix4f initializedProjection = projectionFor(
                    initializedSurfaceMetrics, fovDegrees);

            glDisable(GL_FRAMEBUFFER_SRGB);
            glClearColor(0.1f, 0.1f, 0.15f, 1.0f);
            if (surfaceMetrics.framebufferWidth() > 0 && surfaceMetrics.framebufferHeight() > 0) {
                glViewport(0, 0, surfaceMetrics.framebufferWidth(), surfaceMetrics.framebufferHeight());
            }

            this.camera = initializedCamera;
            worldShaderProgram = initializedWorldProgram;
            skyShaderProgram = initializedSkyProgram;
            textureAtlas = initializedTexture;
            worldMaterial = initializedWorldMaterial;
            renderQueue = initializedQueue;
            renderPipeline = initializedPipeline;
            baseRenderPasses = initializedBasePasses;
            projectionMatrix = initializedProjection;
            fullscreenTriangle = initializedTriangle;
            feedbackResources = initializedFeedbackResources;
            this.surfaceMetrics = initializedSurfaceMetrics;
            this.surfaceController = initializedSurfaceController;
        } catch (RuntimeException | Error failure) {
            clearInitializedFields();
            cleanupAfterInitializationFailure(
                    initializedFeedbackResources,
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

    public void updateSurface(RenderSurfaceMetrics surfaceMetrics) {
        mainThreadGuard.assertMainThread("render surface update");
        RenderSurfaceMetrics next = Objects.requireNonNull(surfaceMetrics, "surfaceMetrics");
        this.surfaceMetrics = next;
        boolean rebuild = surfaceController.update(next);
        if (next.framebufferWidth() <= 0 || next.framebufferHeight() <= 0) return;
        if (rebuild) {
            glViewport(0, 0, next.framebufferWidth(), next.framebufferHeight());
            rebuildProjection();
        }
    }

    public void setFovDegrees(float fovDegrees) {
        mainThreadGuard.assertMainThread("renderer FOV application");
        float validated = requireValidFov(fovDegrees);
        this.fovDegrees = validated;
        if (surfaceMetrics != null) {
            projectionMatrix = projectionFor(surfaceMetrics, validated);
        }
    }

    public RenderMetrics metrics() {
        return metricsCollector;
    }

    /** Installs immutable CPU UI assets and creates their GPU resources on the GL owner thread. */
    public void installUiAssets(UiAssetBundle assets) {
        mainThreadGuard.assertMainThread("UI asset installation");
        Objects.requireNonNull(assets, "assets");
        if (installedUi != null) {
            throw new IllegalStateException("UI assets are already installed");
        }
        List<RenderPass> initializedBasePasses =
                requireInitialized(baseRenderPasses, "base render passes");
        InstalledUi created = Objects.requireNonNull(
                uiInstallationFactory.create(assets, mainThreadGuard),
                "created UI installation");
        try {
            RenderPipeline pipeline = createPipeline(
                    initializedBasePasses.get(0),
                    initializedBasePasses.get(1),
                    initializedBasePasses.get(2),
                    initializedBasePasses.get(3),
                    initializedBasePasses.get(4),
                    initializedBasePasses.get(5),
                    initializedBasePasses.get(6),
                    initializedBasePasses.get(7),
                    created.pass());
            renderPipeline = pipeline;
            installedUi = created;
        } catch (RuntimeException | Error failure) {
            try {
                created.cleanup();
            } catch (RuntimeException | Error cleanupFailure) {
                appendCleanupFailure(failure, cleanupFailure);
            }
            throw failure;
        }
    }

    public void renderFrame(RenderFrameInput frameInput) {
        mainThreadGuard.assertMainThread("frame rendering");
        Objects.requireNonNull(frameInput, "frameInput");
        RenderQueue frameQueue =
                requireInitialized(renderQueue, "render queue");
        frameQueue.clear();
        metricsCollector.beginFrame(
                frameInput.frameDeltaSeconds(), frameInput.meshQueueDepth());
        try {
            dispatchIfDrawable(
                    surfaceController,
                    () -> renderDrawableFrame(frameInput, frameQueue));
        } finally {
            frameQueue.clear();
            metricsCollector.finishFrame();
        }
    }

    static void dispatchIfDrawable(
            RenderSurfaceController surface,
            Runnable render) {
        Objects.requireNonNull(surface, "surface");
        Objects.requireNonNull(render, "render");
        if (surface.drawable()) {
            render.run();
        }
    }

    private void renderDrawableFrame(
            RenderFrameInput frameInput,
            RenderQueue frameQueue) {
        Material frameMaterial =
                requireInitialized(worldMaterial, "world material");
        Matrix4f frameProjection =
                requireInitialized(
                        projectionMatrix,
                        "projection matrix");
        Matrix4f canonicalView =
                requireInitialized(camera, "camera")
                        .getViewMatrix();
        Matrix4f frameView = applyFirstPersonPresentation(
                canonicalView,
                frameInput.feedback().movementVisual(),
                frameInput.feedback().cameraImpulse());
        Frustum currentFrustum =
                Frustum.from(frameProjection, canonicalView);
        int visibleChunks = 0;
        for (ChunkRenderObject chunk : frameInput.chunks()) {
            if (currentFrustum.intersects(chunk.worldBounds())) {
                frameQueue.submit(chunk, frameMaterial);
                visibleChunks++;
            }
        }
        metricsCollector.setVisibleChunks(visibleChunks);
        RenderContext context =
                new RenderContext(
                        frameProjection,
                        frameView,
                        visualSettings,
                        metricsCollector,
                        surfaceMetrics,
                        frameInput.feedback(),
                        frameInput.uiFrame());
        requireInitialized(renderPipeline, "render pipeline")
                .render(context, frameQueue);
    }

    public void cleanup() {
        mainThreadGuard.assertMainThread("renderer cleanup");
        FullscreenTriangle triangleToClean = fullscreenTriangle;
        FeedbackResources feedbackToClean = feedbackResources;
        InstalledUi uiToClean = installedUi;
        Texture textureToClean = textureAtlas;
        ShaderProgram skyProgramToClean = skyShaderProgram;
        ShaderProgram worldProgramToClean = worldShaderProgram;
        clearInitializedFields();

        Throwable failure = null;
        failure = runCleanup(uiToClean, failure);
        failure = runCleanup(feedbackToClean, failure);
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
        baseRenderPasses = null;
        camera = null;
        projectionMatrix = null;
        fullscreenTriangle = null;
        feedbackResources = null;
        installedUi = null;
        surfaceMetrics = null;
        surfaceController = null;
    }

    private void ensureNotInitialized() {
        if (worldShaderProgram != null
                || skyShaderProgram != null
                || textureAtlas != null
                || worldMaterial != null
                || renderQueue != null
                || renderPipeline != null
                || baseRenderPasses != null
                || camera != null
                || projectionMatrix != null
                || fullscreenTriangle != null
                || feedbackResources != null
                || installedUi != null
                || surfaceMetrics != null
                || surfaceController != null) {
            throw new IllegalStateException(
                    "Renderer is already initialized");
        }
    }

    private void rebuildProjection() {
        projectionMatrix = projectionFor(
                requireInitialized(surfaceMetrics, "surface metrics"),
                fovDegrees);
    }

    static RenderPipeline createPipeline(
            RenderPass sky,
            RenderPass world,
            RenderPass blockDamage,
            RenderPass worldItems,
            RenderPass particles,
            RenderPass debug,
            RenderPass ui) {
        return new RenderPipeline(
                List.of(
                        Objects.requireNonNull(sky, "sky"),
                        Objects.requireNonNull(world, "world"),
                        Objects.requireNonNull(blockDamage, "blockDamage"),
                        Objects.requireNonNull(worldItems, "worldItems"),
                        Objects.requireNonNull(particles, "particles"),
                        Objects.requireNonNull(debug, "debug"),
                        Objects.requireNonNull(ui, "ui")));
    }

    static RenderPipeline createPipeline(
            RenderPass sky,
            RenderPass world,
            RenderPass blockDamage,
            RenderPass transientBlocks,
            RenderPass worldItems,
            RenderPass particles,
            RenderPass firstPersonItem,
            RenderPass debug,
            RenderPass ui) {
        return new RenderPipeline(
                List.of(
                        Objects.requireNonNull(sky, "sky"),
                        Objects.requireNonNull(world, "world"),
                        Objects.requireNonNull(blockDamage, "blockDamage"),
                        Objects.requireNonNull(transientBlocks, "transientBlocks"),
                        Objects.requireNonNull(worldItems, "worldItems"),
                        Objects.requireNonNull(particles, "particles"),
                        Objects.requireNonNull(firstPersonItem, "firstPersonItem"),
                        Objects.requireNonNull(debug, "debug"),
                        Objects.requireNonNull(ui, "ui")));
    }

    static Matrix4f applyVisualCameraImpulse(
            org.joml.Matrix4fc canonicalView,
            com.overlord.renderer.feedback.CameraImpulseVisual impulse) {
        Objects.requireNonNull(canonicalView, "canonicalView");
        Objects.requireNonNull(impulse, "impulse");
        return new Matrix4f(canonicalView)
                .rotateX((float) Math.toRadians(impulse.pitchDegrees()))
                .rotateY((float) Math.toRadians(impulse.yawDegrees()))
                .translate(0.0f, impulse.translationY(), 0.0f);
    }

    /**
     * Composes view-only movement before action feedback without mutating the
     * authoritative camera matrix used for culling and gameplay targeting.
     */
    static Matrix4f applyFirstPersonPresentation(
            org.joml.Matrix4fc canonicalView,
            com.overlord.renderer.feedback.FirstPersonMovementVisual movement,
            com.overlord.renderer.feedback.CameraImpulseVisual actionImpulse) {
        Objects.requireNonNull(canonicalView, "canonicalView");
        Objects.requireNonNull(movement, "movement");
        Objects.requireNonNull(actionImpulse, "actionImpulse");
        Matrix4f movementView = new Matrix4f(canonicalView)
                .translate(
                        -movement.translationX(),
                        -movement.translationY(),
                        0.0f)
                .rotateZ((float) Math.toRadians(-movement.rollDegrees()));
        return applyVisualCameraImpulse(movementView, actionImpulse);
    }

    static Matrix4f projectionFor(
            RenderSurfaceMetrics surfaceMetrics, float fovDegrees) {
        RenderSurfaceMetrics surface = Objects.requireNonNull(
                surfaceMetrics, "surfaceMetrics");
        float validated = requireValidFov(fovDegrees);
        int projectionWidth = Math.max(1, surface.framebufferWidth());
        int projectionHeight = Math.max(1, surface.framebufferHeight());
        return new Matrix4f()
                .perspective(
                        (float) Math.toRadians(validated),
                        (float) projectionWidth / projectionHeight,
                        GameConfig.Rendering.NEAR_PLANE,
                        GameConfig.Rendering.FAR_PLANE);
    }

    private static float requireValidFov(float fovDegrees) {
        if (!Float.isFinite(fovDegrees)
                || fovDegrees < MIN_FOV_DEGREES
                || fovDegrees > MAX_FOV_DEGREES) {
            throw new IllegalArgumentException(
                    "FOV must be finite and between 50 and 100 degrees");
        }
        return fovDegrees;
    }

    private static void cleanupAfterInitializationFailure(
            FeedbackResources feedbackResources,
            FullscreenTriangle triangle,
            Texture texture,
            ShaderProgram skyProgram,
            ShaderProgram worldProgram,
            Throwable primaryFailure) {
        suppressCleanup(feedbackResources, primaryFailure);
        suppressCleanup(triangle, primaryFailure);
        suppressCleanup(texture, primaryFailure);
        suppressCleanup(skyProgram, primaryFailure);
        suppressCleanup(worldProgram, primaryFailure);
    }

    private static void suppressCleanup(
            FeedbackResources resources,
            Throwable primaryFailure) {
        if (resources == null) {
            return;
        }
        try {
            resources.cleanup();
        } catch (RuntimeException | Error cleanupFailure) {
            if (cleanupFailure != primaryFailure) {
                primaryFailure.addSuppressed(cleanupFailure);
            }
        }
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
            InstalledUi ui,
            Throwable firstFailure) {
        if (ui == null) {
            return firstFailure;
        }
        try {
            ui.cleanup();
        } catch (RuntimeException | Error cleanupFailure) {
            return appendCleanupFailure(firstFailure, cleanupFailure);
        }
        return firstFailure;
    }

    private static InstalledUi createOpenGlUi(
            UiAssetBundle assets,
            MainThreadGuard guard) {
        UiRenderer renderer = UiRenderer.create(
                assets,
                new OpenGlUiGpuBackend(guard),
                guard);
        return new InstalledUi(new UiRenderPass(renderer), renderer::close);
    }

    private static Throwable runCleanup(
            FeedbackResources resources,
            Throwable firstFailure) {
        if (resources == null) {
            return firstFailure;
        }
        try {
            resources.cleanup();
        } catch (RuntimeException | Error cleanupFailure) {
            return appendCleanupFailure(firstFailure, cleanupFailure);
        }
        return firstFailure;
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

    record Managed<T>(T value, Runnable cleanup) {
        Managed {
            Objects.requireNonNull(value, "value");
            Objects.requireNonNull(cleanup, "cleanup");
        }

        void release() {
            cleanup.run();
        }
    }

    interface FeedbackResourceFactory {
        Managed<ShaderBinding> createShader(
                String label,
                ResourceLocation vertex,
                ResourceLocation fragment,
                List<String> uniforms);

        Managed<TextureBinding> createDamageTexture(TextureImage image);

        UnitCubeMesh createUnitCube();

        StreamingTexturedCubeBatch createParticleBatch();

    }

    static final class FeedbackResources {
        private final MainThreadGuard guard;
        private final Managed<ShaderBinding> damageShader;
        private final Managed<ShaderBinding> worldItemShader;
        private final Managed<ShaderBinding> particleShader;
        private final Managed<TextureBinding> damageTexture;
        private final UnitCubeMesh unitCube;
        private final StreamingTexturedCubeBatch particleBatch;
        private boolean cleanedUp;

        private FeedbackResources(
                MainThreadGuard guard,
                Managed<ShaderBinding> damageShader,
                Managed<ShaderBinding> worldItemShader,
                Managed<ShaderBinding> particleShader,
                Managed<TextureBinding> damageTexture,
                UnitCubeMesh unitCube,
                StreamingTexturedCubeBatch particleBatch) {
            this.guard = guard;
            this.damageShader = damageShader;
            this.worldItemShader = worldItemShader;
            this.particleShader = particleShader;
            this.damageTexture = damageTexture;
            this.unitCube = unitCube;
            this.particleBatch = particleBatch;
        }

        static FeedbackResources create(
                MainThreadGuard guard,
                FeedbackResourceFactory factory,
                InteractionFeedbackAssets assets) {
            Objects.requireNonNull(guard, "guard")
                    .assertMainThread("interaction feedback resource creation");
            Objects.requireNonNull(factory, "factory");
            Objects.requireNonNull(assets, "assets");

            Managed<ShaderBinding> damageShader = null;
            Managed<ShaderBinding> worldItemShader = null;
            Managed<ShaderBinding> particleShader = null;
            Managed<TextureBinding> damageTexture = null;
            UnitCubeMesh unitCube = null;
            StreamingTexturedCubeBatch particleBatch = null;
            try {
                damageShader = factory.createShader(
                        "block-damage",
                        assets.damageVertexShader(),
                        assets.damageFragmentShader(),
                        List.of(
                                "projection", "view", "model", "damageAtlas",
                                "uMin", "uMax", "vMin", "vMax"));
                worldItemShader = factory.createShader(
                        "world-items",
                        assets.worldItemVertexShader(),
                        assets.worldItemFragmentShader(),
                        worldItemUniforms());
                particleShader = factory.createShader(
                        "particles",
                        assets.particleVertexShader(),
                        assets.particleFragmentShader(),
                        List.of("projection", "view", "blockAtlas"));
                damageTexture = factory.createDamageTexture(assets.damageAtlas().image());
                unitCube = Objects.requireNonNull(factory.createUnitCube(), "unit cube");
                particleBatch = Objects.requireNonNull(
                        factory.createParticleBatch(), "particle batch");
                return new FeedbackResources(
                        guard,
                        damageShader,
                        worldItemShader,
                        particleShader,
                        damageTexture,
                        unitCube,
                        particleBatch);
            } catch (RuntimeException | Error failure) {
                suppress(particleBatch, failure);
                suppress(unitCube, failure);
                suppress(damageTexture, failure);
                suppress(particleShader, failure);
                suppress(worldItemShader, failure);
                suppress(damageShader, failure);
                throw failure;
            }
        }

        private static List<String> worldItemUniforms() {
            List<String> uniforms = new java.util.ArrayList<>(
                    List.of(
                            "projection", "view", "model", "blockAtlas", "visualAlpha"));
            for (String component : List.of("uMin", "uMax", "vMin", "vMax")) {
                for (int face = 0; face < 6; face++) {
                    uniforms.add(component + "[" + face + "]");
                }
            }
            return List.copyOf(uniforms);
        }

        ShaderBinding damageShader() {
            return damageShader.value();
        }

        ShaderBinding worldItemShader() {
            return worldItemShader.value();
        }

        ShaderBinding particleShader() {
            return particleShader.value();
        }

        TextureBinding damageTexture() {
            return damageTexture.value();
        }

        UnitCubeMesh unitCube() {
            return unitCube;
        }

        StreamingTexturedCubeBatch particleBatch() {
            return particleBatch;
        }

        void cleanup() {
            guard.assertMainThread("interaction feedback resource cleanup");
            if (cleanedUp) {
                return;
            }
            cleanedUp = true;
            Throwable failure = null;
            failure = cleanup(particleBatch, failure);
            failure = cleanup(unitCube, failure);
            failure = cleanup(damageTexture, failure);
            failure = cleanup(particleShader, failure);
            failure = cleanup(worldItemShader, failure);
            failure = cleanup(damageShader, failure);
            if (failure != null) {
                rethrow(failure);
            }
        }

        private static void suppress(AutoCloseable resource, Throwable primary) {
            if (resource == null) {
                return;
            }
            try {
                resource.close();
            } catch (RuntimeException | Error failure) {
                if (failure != primary) {
                    primary.addSuppressed(failure);
                }
            } catch (Exception failure) {
                primary.addSuppressed(failure);
            }
        }

        private static void suppress(Managed<?> resource, Throwable primary) {
            if (resource == null) {
                return;
            }
            try {
                resource.release();
            } catch (RuntimeException | Error failure) {
                if (failure != primary) {
                    primary.addSuppressed(failure);
                }
            }
        }

        private static Throwable cleanup(AutoCloseable resource, Throwable first) {
            if (resource == null) {
                return first;
            }
            try {
                resource.close();
            } catch (Throwable failure) {
                return appendCleanupFailure(first, failure);
            }
            return first;
        }

        private static Throwable cleanup(Managed<?> resource, Throwable first) {
            if (resource == null) {
                return first;
            }
            try {
                resource.release();
            } catch (RuntimeException | Error failure) {
                return appendCleanupFailure(first, failure);
            }
            return first;
        }
    }

    private static final class OpenGlFeedbackResourceFactory
            implements FeedbackResourceFactory {
        private final MainThreadGuard guard;
        private final AssetManager assets;

        private OpenGlFeedbackResourceFactory(MainThreadGuard guard, AssetManager assets) {
            this.guard = Objects.requireNonNull(guard, "guard");
            this.assets = Objects.requireNonNull(assets, "assets");
        }

        @Override
        public Managed<ShaderBinding> createShader(
                String label,
                ResourceLocation vertex,
                ResourceLocation fragment,
                List<String> uniforms) {
            ShaderSourceSet sources = new ShaderResourceLoader(assets).load(label, vertex, fragment);
            ShaderProgram program = new ShaderProgram(guard, sources, uniforms);
            return new Managed<>(program, program::cleanup);
        }

        @Override
        public Managed<TextureBinding> createDamageTexture(TextureImage image) {
            Texture texture = new Texture(guard, image);
            return new Managed<>(texture, texture::cleanup);
        }

        @Override
        public UnitCubeMesh createUnitCube() {
            return new OpenGlUnitCubeMesh(guard);
        }

        @Override
        public StreamingTexturedCubeBatch createParticleBatch() {
            return new OpenGlStreamingTexturedCubeBatch(guard);
        }
    }

    interface UiInstallationFactory {
        InstalledUi create(UiAssetBundle assets, MainThreadGuard guard);
    }

    static final class InstalledUi {
        private final RenderPass pass;
        private final Runnable cleanup;
        private boolean cleanedUp;

        InstalledUi(RenderPass pass, Runnable cleanup) {
            this.pass = Objects.requireNonNull(pass, "pass");
            this.cleanup = Objects.requireNonNull(cleanup, "cleanup");
        }

        private RenderPass pass() {
            return pass;
        }

        private void cleanup() {
            if (cleanedUp) {
                return;
            }
            cleanedUp = true;
            cleanup.run();
        }
    }
}
