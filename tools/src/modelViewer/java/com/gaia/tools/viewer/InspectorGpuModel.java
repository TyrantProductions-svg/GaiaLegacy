package com.gaia.tools.viewer;

import com.gaia.tools.model.ValidatedModelSnapshot;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Owner-thread GPU projection of one immutable, validated viewer model. */
public final class InspectorGpuModel implements ViewerReloadController.GpuResource {
    private static final int GL_LINEAR = 9729;
    private static final int GL_LINEAR_MIPMAP_LINEAR = 9987;

    public record PrimitiveGpu(int vao, int vbo, int ebo, int indexCount, int material) { }

    private enum HandleKind { VERTEX_ARRAY, BUFFER, TEXTURE, SAMPLER }

    private record Acquired(HandleKind kind, int handle) { }

    private final ViewerCpuModel cpu;
    private final ViewerGlResources resources;
    private final List<PrimitiveGpu> primitives;
    private final List<Integer> imageTextures;
    private final List<Integer> textureSamplers;
    private final List<Acquired> acquired;
    private final long estimatedBytes;
    private boolean closed;

    private InspectorGpuModel(ViewerCpuModel cpu, ViewerGlResources resources,
            List<PrimitiveGpu> primitives, List<Integer> imageTextures,
            List<Integer> textureSamplers, List<Acquired> acquired, long estimatedBytes) {
        this.cpu = cpu;
        this.resources = resources;
        this.primitives = List.copyOf(primitives);
        this.imageTextures = List.copyOf(imageTextures);
        this.textureSamplers = List.copyOf(textureSamplers);
        this.acquired = List.copyOf(acquired);
        this.estimatedBytes = estimatedBytes;
    }

    public static InspectorGpuModel upload(ViewerCpuModel cpu, ViewerGlResources resources) {
        Objects.requireNonNull(cpu, "cpu model");
        Objects.requireNonNull(resources, "GPU resources");
        resources.assertOwner("model viewer GPU upload");
        resources.assertNoError("begin model viewer GPU upload");
        List<Acquired> acquired = new ArrayList<>();
        List<PrimitiveGpu> primitives = new ArrayList<>();
        List<Integer> imageTextures = new ArrayList<>();
        List<Integer> textureSamplers = new ArrayList<>();
        long bytes = cpu.bufferBytes();
        try {
            for (ViewerCpuModel.Primitive primitive : cpu.primitives()) {
                int vao = acquire(resources, acquired, HandleKind.VERTEX_ARRAY);
                int vbo = acquire(resources, acquired, HandleKind.BUFFER);
                int ebo = acquire(resources, acquired, HandleKind.BUFFER);
                resources.uploadMesh(vao, vbo, ebo, primitive.vertices(), primitive.indices(),
                        ViewerCpuModel.FLOATS_PER_VERTEX * Float.BYTES,
                        0, 3 * Float.BYTES, 6 * Float.BYTES);
                resources.assertNoError("upload model viewer mesh");
                primitives.add(new PrimitiveGpu(
                        vao, vbo, ebo, primitive.indexCount(), primitive.material()));
            }
            for (ValidatedModelSnapshot.Image image : cpu.images()) {
                int texture = acquire(resources, acquired, HandleKind.TEXTURE);
                byte[] rgba = image.rgba();
                resources.uploadSrgbTexture(texture, image.width(), image.height(), rgba);
                resources.assertNoError("upload model viewer texture");
                imageTextures.add(texture);
                bytes = Math.addExact(bytes, rgba.length);
            }
            for (ValidatedModelSnapshot.Texture texture : cpu.textures()) {
                int sampler = acquire(resources, acquired, HandleKind.SAMPLER);
                int magFilter = texture.magFilter() == null ? GL_LINEAR : texture.magFilter();
                int minFilter = texture.minFilter() == null
                        ? GL_LINEAR_MIPMAP_LINEAR : texture.minFilter();
                resources.configureSampler(
                        sampler, magFilter, minFilter, texture.wrapS(), texture.wrapT());
                resources.assertNoError("configure model viewer sampler");
                textureSamplers.add(sampler);
            }
            boolean[] mipmapped = new boolean[imageTextures.size()];
            for (int textureIndex = 0; textureIndex < cpu.textures().size(); textureIndex++) {
                ValidatedModelSnapshot.Texture texture = cpu.textures().get(textureIndex);
                int minFilter = texture.minFilter() == null
                        ? GL_LINEAR_MIPMAP_LINEAR : texture.minFilter();
                int image = texture.image();
                if (usesMipmaps(minFilter) && !mipmapped[image]) {
                    resources.generateMipmaps(imageTextures.get(image));
                    resources.assertNoError("generate model viewer mipmaps");
                    bytes = Math.addExact(bytes, mipmapBytes(
                            cpu.images().get(image).width(), cpu.images().get(image).height()));
                    mipmapped[image] = true;
                }
            }
            return new InspectorGpuModel(cpu, resources, primitives, imageTextures,
                    textureSamplers, acquired, bytes);
        } catch (RuntimeException | Error failure) {
            throwUnchecked(cleanup(resources, acquired, failure));
            throw new AssertionError("unreachable");
        }
    }

    private static int acquire(ViewerGlResources resources, List<Acquired> acquired,
            HandleKind kind) {
        int handle = switch (kind) {
            case VERTEX_ARRAY -> resources.createVertexArray();
            case BUFFER -> resources.createBuffer();
            case TEXTURE -> resources.createTexture();
            case SAMPLER -> resources.createSampler();
        };
        if (handle == 0) {
            throw new IllegalStateException("OpenGL returned handle zero");
        }
        acquired.add(new Acquired(kind, handle));
        resources.assertNoError("create model viewer " + kind.name().toLowerCase());
        return handle;
    }

    private static long mipmapBytes(int width, int height) {
        long bytes = 0;
        int levelWidth = width;
        int levelHeight = height;
        while (levelWidth > 1 || levelHeight > 1) {
            levelWidth = Math.max(1, levelWidth / 2);
            levelHeight = Math.max(1, levelHeight / 2);
            bytes = Math.addExact(bytes,
                    Math.multiplyExact(Math.multiplyExact((long) levelWidth, levelHeight), 4L));
        }
        return bytes;
    }

    private static boolean usesMipmaps(int minFilter) {
        return minFilter >= 9984 && minFilter <= 9987;
    }

    public ViewerCpuModel cpu() { return cpu; }

    public List<PrimitiveGpu> primitives() { return primitives; }

    public List<Integer> imageTextures() { return imageTextures; }

    public List<Integer> textureSamplers() { return textureSamplers; }

    public long estimatedBytes() { return estimatedBytes; }

    public int handleCount() { return closed ? 0 : acquired.size(); }

    @Override
    public void close() {
        resources.assertOwner("model viewer GPU close");
        if (closed) {
            return;
        }
        closed = true;
        Throwable failure = cleanup(resources, acquired, null);
        if (failure != null) {
            throwUnchecked(failure);
        }
    }

    private static Throwable cleanup(ViewerGlResources resources,
            List<Acquired> acquired, Throwable failure) {
        for (int index = acquired.size() - 1; index >= 0; index--) {
            Acquired handle = acquired.get(index);
            try {
                switch (handle.kind()) {
                    case VERTEX_ARRAY -> resources.deleteVertexArray(handle.handle());
                    case BUFFER -> resources.deleteBuffer(handle.handle());
                    case TEXTURE -> resources.deleteTexture(handle.handle());
                    case SAMPLER -> resources.deleteSampler(handle.handle());
                }
            } catch (RuntimeException | Error cleanupFailure) {
                failure = addFailure(failure, cleanupFailure);
            }
        }
        try {
            resources.assertNoError("cleanup model viewer GPU resources");
        } catch (RuntimeException | Error cleanupFailure) {
            failure = addFailure(failure, cleanupFailure);
        }
        return failure;
    }

    private static Throwable addFailure(Throwable failure, Throwable additional) {
        if (failure == null) return additional;
        if (failure != additional) failure.addSuppressed(additional);
        return failure;
    }

    private static void throwUnchecked(Throwable failure) {
        if (failure instanceof RuntimeException runtimeException) {
            throw runtimeException;
        }
        if (failure instanceof Error error) {
            throw error;
        }
        throw new AssertionError(failure);
    }
}
