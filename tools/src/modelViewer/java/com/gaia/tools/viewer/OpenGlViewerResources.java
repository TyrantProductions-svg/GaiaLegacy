package com.gaia.tools.viewer;

import static org.lwjgl.opengl.GL11C.GL_FLOAT;
import static org.lwjgl.opengl.GL11C.GL_LINEAR;
import static org.lwjgl.opengl.GL11C.GL_NO_ERROR;
import static org.lwjgl.opengl.GL11C.GL_RGBA;
import static org.lwjgl.opengl.GL11C.GL_TEXTURE_2D;
import static org.lwjgl.opengl.GL11C.GL_TEXTURE_MAG_FILTER;
import static org.lwjgl.opengl.GL11C.GL_TEXTURE_MIN_FILTER;
import static org.lwjgl.opengl.GL11C.GL_TEXTURE_WRAP_S;
import static org.lwjgl.opengl.GL11C.GL_TEXTURE_WRAP_T;
import static org.lwjgl.opengl.GL11C.GL_UNPACK_ALIGNMENT;
import static org.lwjgl.opengl.GL11C.GL_UNSIGNED_BYTE;
import static org.lwjgl.opengl.GL11C.glBindTexture;
import static org.lwjgl.opengl.GL11C.glDeleteTextures;
import static org.lwjgl.opengl.GL11C.glGenTextures;
import static org.lwjgl.opengl.GL11C.glGetError;
import static org.lwjgl.opengl.GL11C.glPixelStorei;
import static org.lwjgl.opengl.GL11C.glTexImage2D;
import static org.lwjgl.opengl.GL15C.GL_ARRAY_BUFFER;
import static org.lwjgl.opengl.GL15C.GL_ELEMENT_ARRAY_BUFFER;
import static org.lwjgl.opengl.GL15C.GL_STATIC_DRAW;
import static org.lwjgl.opengl.GL15C.glBindBuffer;
import static org.lwjgl.opengl.GL15C.glBufferData;
import static org.lwjgl.opengl.GL15C.glDeleteBuffers;
import static org.lwjgl.opengl.GL15C.glGenBuffers;
import static org.lwjgl.opengl.GL20C.glEnableVertexAttribArray;
import static org.lwjgl.opengl.GL20C.glVertexAttribPointer;
import static org.lwjgl.opengl.GL30C.GL_SRGB8_ALPHA8;
import static org.lwjgl.opengl.GL30C.glBindVertexArray;
import static org.lwjgl.opengl.GL30C.glDeleteVertexArrays;
import static org.lwjgl.opengl.GL30C.glGenerateMipmap;
import static org.lwjgl.opengl.GL30C.glGenVertexArrays;
import static org.lwjgl.opengl.GL33C.glDeleteSamplers;
import static org.lwjgl.opengl.GL33C.glGenSamplers;
import static org.lwjgl.opengl.GL33C.glSamplerParameteri;

import com.overlord.core.thread.MainThreadGuard;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.Objects;
import java.util.function.IntSupplier;
import org.lwjgl.BufferUtils;

/** OpenGL 4.1 implementation of the viewer's narrow GPU resource boundary. */
final class OpenGlViewerResources implements ViewerGlResources {
    static final int MAX_GL_ERRORS_PER_DRAIN = 32;
    private final MainThreadGuard guard;

    OpenGlViewerResources(MainThreadGuard guard) {
        this.guard = Objects.requireNonNull(guard, "guard");
    }

    @Override public void assertOwner(String operation) { guard.assertMainThread(operation); }
    @Override public void assertNoError(String operation) {
        assertOwner(operation);
        assertNoErrors(operation, () -> glGetError());
    }

    static void assertNoErrors(String operation, IntSupplier errorSource) {
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(errorSource, "error source");
        StringBuilder observed = new StringBuilder();
        int errorCount = 0;
        while (errorCount < MAX_GL_ERRORS_PER_DRAIN) {
            int error = errorSource.getAsInt();
            if (error == GL_NO_ERROR) {
                if (errorCount != 0) {
                    throw new IllegalStateException(operation
                            + " failed with OpenGL errors " + observed);
                }
                return;
            }
            if (errorCount != 0) observed.append(',');
            observed.append("0x").append(Integer.toHexString(error));
            errorCount++;
        }
        int next = errorSource.getAsInt();
        if (next != GL_NO_ERROR) {
            throw new IllegalStateException(operation + " failed: OpenGL error queue did not drain within "
                    + MAX_GL_ERRORS_PER_DRAIN + " errors; observed " + observed + ",...");
        }
        throw new IllegalStateException(operation + " failed with OpenGL errors " + observed);
    }
    @Override public int createVertexArray() { assertOwner("create vertex array"); return glGenVertexArrays(); }
    @Override public int createBuffer() { assertOwner("create buffer"); return glGenBuffers(); }
    @Override public int createTexture() { assertOwner("create texture"); return glGenTextures(); }
    @Override public int createSampler() { assertOwner("create sampler"); return glGenSamplers(); }

    @Override
    public void uploadMesh(int vao, int vbo, int ebo, float[] vertices, int[] indices,
            int strideBytes, int positionOffsetBytes, int normalOffsetBytes, int uvOffsetBytes) {
        assertOwner("upload viewer mesh");
        FloatBuffer vertexData = BufferUtils.createFloatBuffer(vertices.length).put(vertices).flip();
        IntBuffer indexData = BufferUtils.createIntBuffer(indices.length).put(indices).flip();
        glBindVertexArray(vao);
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        glBufferData(GL_ARRAY_BUFFER, vertexData, GL_STATIC_DRAW);
        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, ebo);
        glBufferData(GL_ELEMENT_ARRAY_BUFFER, indexData, GL_STATIC_DRAW);
        glEnableVertexAttribArray(0);
        glVertexAttribPointer(0, 3, GL_FLOAT, false, strideBytes, positionOffsetBytes);
        glEnableVertexAttribArray(1);
        glVertexAttribPointer(1, 3, GL_FLOAT, false, strideBytes, normalOffsetBytes);
        glEnableVertexAttribArray(2);
        glVertexAttribPointer(2, 2, GL_FLOAT, false, strideBytes, uvOffsetBytes);
        glBindVertexArray(0);
        glBindBuffer(GL_ARRAY_BUFFER, 0);
    }

    @Override
    public void uploadSrgbTexture(int texture, int width, int height, byte[] rgba) {
        assertOwner("upload viewer texture");
        ByteBuffer pixels = BufferUtils.createByteBuffer(rgba.length).put(rgba).flip();
        glBindTexture(GL_TEXTURE_2D, texture);
        glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
        try {
            glTexImage2D(GL_TEXTURE_2D, 0, GL_SRGB8_ALPHA8, width, height, 0,
                    GL_RGBA, GL_UNSIGNED_BYTE, pixels);
        } finally {
            glPixelStorei(GL_UNPACK_ALIGNMENT, 4);
            glBindTexture(GL_TEXTURE_2D, 0);
        }
    }

    @Override
    public void generateMipmaps(int texture) {
        assertOwner("generate viewer texture mipmaps");
        glBindTexture(GL_TEXTURE_2D, texture);
        glGenerateMipmap(GL_TEXTURE_2D);
        glBindTexture(GL_TEXTURE_2D, 0);
    }

    @Override
    public void configureSampler(int sampler, int magFilter, int minFilter, int wrapS, int wrapT) {
        assertOwner("configure viewer sampler");
        glSamplerParameteri(sampler, GL_TEXTURE_MAG_FILTER, magFilter == 0 ? GL_LINEAR : magFilter);
        glSamplerParameteri(sampler, GL_TEXTURE_MIN_FILTER, minFilter);
        glSamplerParameteri(sampler, GL_TEXTURE_WRAP_S, wrapS);
        glSamplerParameteri(sampler, GL_TEXTURE_WRAP_T, wrapT);
    }

    @Override public void deleteVertexArray(int handle) { assertOwner("delete vertex array"); glDeleteVertexArrays(handle); }
    @Override public void deleteBuffer(int handle) { assertOwner("delete buffer"); glDeleteBuffers(handle); }
    @Override public void deleteTexture(int handle) { assertOwner("delete texture"); glDeleteTextures(handle); }
    @Override public void deleteSampler(int handle) { assertOwner("delete sampler"); glDeleteSamplers(handle); }
}
