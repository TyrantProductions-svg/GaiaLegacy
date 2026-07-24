package com.overlord.renderer;

import com.overlord.config.GameConfig;
import com.overlord.core.thread.MainThreadGuard;
import com.overlord.voxel.ChunkKey;
import com.overlord.voxel.ChunkMeshData;
import java.util.Collection;
import java.util.Objects;
import org.joml.Matrix4f;

import static org.lwjgl.opengl.GL30C.*;

public class Renderer implements ChunkRenderBackend {
    private final MainThreadGuard mainThreadGuard;
    private final RenderAssets renderAssets;
    private Shader shader;
    private Camera camera;
    private Texture textureAtlas;
    
    private Matrix4f projectionMatrix;

    public Renderer(
            MainThreadGuard mainThreadGuard,
            RenderAssets renderAssets) {
        this.mainThreadGuard = Objects.requireNonNull(mainThreadGuard, "mainThreadGuard");
        this.renderAssets = Objects.requireNonNull(renderAssets, "renderAssets");
    }

    public void init(Camera camera, int width, int height) {
        mainThreadGuard.assertMainThread("renderer initialization");
        this.camera = camera;

        glEnable(GL_DEPTH_TEST);
        glClearColor(0.1f, 0.1f, 0.15f, 1.0f);

        rebuildProjection(width, height);
        glViewport(0, 0, width, height);
        
        String vertexSource = 
            "#version 410 core\n" +
            "layout (location = 0) in vec3 aPos;\n" +
            "layout (location = 1) in vec2 aTexCoord;\n" +
            "uniform mat4 projection;\n" +
            "uniform mat4 view;\n" +
            "uniform mat4 model;\n" +
            "out vec2 TexCoord;\n" +
            "void main() {\n" +
            "    gl_Position = projection * view * model * vec4(aPos, 1.0);\n" +
            "    TexCoord = aTexCoord;\n" +
            "}\n";
        
        String fragmentSource = 
            "#version 410 core\n" +
            "in vec2 TexCoord;\n" +
            "out vec4 FragColor;\n" +
            "uniform sampler2D textureAtlas;\n" +
            "void main() {\n" +
            "    FragColor = texture(textureAtlas, TexCoord);\n" +
            "}\n";
        
        shader = new Shader(mainThreadGuard, vertexSource, fragmentSource);

        textureAtlas =
                new Texture(
                        mainThreadGuard,
                        renderAssets.blockAtlas());
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

    public void clear() {
        mainThreadGuard.assertMainThread("framebuffer clear");
        glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
    }

    public void resizeFramebuffer(int width, int height) {
        mainThreadGuard.assertMainThread("framebuffer resize");
        if (width <= 0 || height <= 0) {
            return;
        }
        glViewport(0, 0, width, height);
        rebuildProjection(width, height);
    }

    public void renderChunks(Collection<ChunkRenderObject> chunks) {
        mainThreadGuard.assertMainThread("chunk rendering");
        Objects.requireNonNull(chunks, "chunks");

        shader.use();
        textureAtlas.bind(0);
        shader.setUniformMat4f("projection", projectionMatrix);
        shader.setUniformMat4f("view", camera.getViewMatrix());
        for (ChunkRenderObject chunk : chunks) {
            shader.setUniformMat4f("model", chunk.modelMatrix());
            chunk.mesh().draw();
        }
    }

    public void cleanup() {
        mainThreadGuard.assertMainThread("renderer cleanup");
        if (shader != null) {
            shader.cleanup();
            shader = null;
        }
        if (textureAtlas != null) {
            textureAtlas.cleanup();
            textureAtlas = null;
        }
        camera = null;
        projectionMatrix = null;
    }

    private void rebuildProjection(int width, int height) {
        if (width <= 0 || height <= 0) {
            return;
        }
        projectionMatrix =
                new Matrix4f()
                        .perspective(
                                (float) Math.toRadians(GameConfig.Rendering.FOV),
                                (float) width / height,
                                GameConfig.Rendering.NEAR_PLANE,
                                GameConfig.Rendering.FAR_PLANE);
    }
}
