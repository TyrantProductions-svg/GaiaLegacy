package com.overlord.renderer.ui;

import static org.lwjgl.opengl.GL30C.*;

import com.overlord.core.thread.MainThreadGuard;
import com.overlord.renderer.state.OpenGlRenderStateBackend;
import com.overlord.renderer.state.RenderStateBackend;
import com.overlord.renderer.state.RenderStateSnapshot;
import com.overlord.renderer.state.ScissorBox;
import java.util.Objects;
import java.util.Optional;

/** OpenGL 4.1-compatible implementation of the UI GPU boundary. */
public final class OpenGlUiGpuBackend implements UiGpuBackend {
    private static final int VERTEX_STRIDE_BYTES = 8 * Float.BYTES;
    private static final int POSITION_OFFSET_BYTES = 0;
    private static final int UV_OFFSET_BYTES = 2 * Float.BYTES;
    private static final int TINT_OFFSET_BYTES = 4 * Float.BYTES;

    private final MainThreadGuard guard;
    private final RenderStateBackend stateBackend;
    private final OpenGlUiApi gl;

    public OpenGlUiGpuBackend(MainThreadGuard guard) {
        this(
                Objects.requireNonNull(guard, "guard"),
                new OpenGlRenderStateBackend(guard),
                new LwjglOpenGlUiApi());
    }

    OpenGlUiGpuBackend(
            MainThreadGuard guard,
            RenderStateBackend stateBackend,
            OpenGlUiApi gl) {
        this.guard = Objects.requireNonNull(guard, "guard");
        this.stateBackend = Objects.requireNonNull(stateBackend, "stateBackend");
        this.gl = Objects.requireNonNull(gl, "gl");
    }

    @Override
    public int createProgram(String vertexSource, String fragmentSource) {
        guard.assertMainThread("UI shader program creation");
        return gl.createProgram(
                Objects.requireNonNull(vertexSource, "vertexSource"),
                Objects.requireNonNull(fragmentSource, "fragmentSource"));
    }

    @Override
    public void useProgram(int program) {
        guard.assertMainThread("UI shader program use");
        gl.useProgram(program);
    }

    @Override
    public void setFramebufferSize(int program, float width, float height) {
        guard.assertMainThread("UI framebuffer-size upload");
        gl.uniform2f(requireUniform(program, "framebufferSize"), width, height);
    }

    @Override
    public void setTextureSampler(int program, int textureUnit) {
        guard.assertMainThread("UI sampler upload");
        gl.uniform1i(requireUniform(program, "uiTexture"), textureUnit);
    }

    @Override
    public void setTextureSamplingEnabled(int program, boolean enabled) {
        guard.assertMainThread("UI texture-mode upload");
        gl.uniform1i(requireUniform(program, "textureSamplingEnabled"), enabled ? 1 : 0);
    }

    @Override
    public void deleteProgram(int program) {
        guard.assertMainThread("UI shader program cleanup");
        gl.deleteProgram(program);
    }

    @Override
    public int createTexture(UiTextureData texture) {
        guard.assertMainThread("UI texture creation");
        return gl.createTexture(
                Objects.requireNonNull(texture, "texture"),
                GL_NEAREST,
                GL_NEAREST,
                GL_CLAMP_TO_EDGE,
                GL_CLAMP_TO_EDGE,
                0,
                0);
    }

    @Override
    public void bindTextureUnitZero(int texture) {
        guard.assertMainThread("UI texture bind");
        gl.activeTexture(GL_TEXTURE0);
        gl.bindTexture2d(texture);
    }

    @Override
    public void deleteTexture(int texture) {
        guard.assertMainThread("UI texture cleanup");
        gl.deleteTexture(texture);
    }

    @Override
    public int createVertexArray() {
        guard.assertMainThread("UI vertex array creation");
        return gl.createVertexArray();
    }

    @Override
    public int createBuffer() {
        guard.assertMainThread("UI buffer creation");
        return gl.createBuffer();
    }

    @Override
    public void configureBatch(int vertexArray, int vertexBuffer, int elementBuffer) {
        guard.assertMainThread("UI batch configuration");
        gl.configureBatch(
                vertexArray,
                vertexBuffer,
                elementBuffer,
                VERTEX_STRIDE_BYTES,
                0,
                POSITION_OFFSET_BYTES,
                1,
                UV_OFFSET_BYTES,
                2,
                TINT_OFFSET_BYTES);
    }

    @Override
    public void uploadBatch(
            int vertexArray,
            int vertexBuffer,
            int elementBuffer,
            float[] vertices,
            int[] indices) {
        guard.assertMainThread("UI batch upload");
        gl.uploadBatch(vertexArray, vertexBuffer, elementBuffer, vertices, indices);
    }

    @Override
    public void drawBatch(int vertexArray, int indexCount) {
        guard.assertMainThread("UI batch draw");
        gl.drawBatch(vertexArray, indexCount, GL_TRIANGLES, GL_UNSIGNED_INT);
    }

    @Override
    public void deleteBuffer(int buffer) {
        guard.assertMainThread("UI buffer cleanup");
        gl.deleteBuffer(buffer);
    }

    @Override
    public void deleteVertexArray(int vertexArray) {
        guard.assertMainThread("UI vertex array cleanup");
        gl.deleteVertexArray(vertexArray);
    }

    @Override
    public RenderStateSnapshot captureState() {
        guard.assertMainThread("capture UI render state");
        return stateBackend.capture();
    }

    @Override
    public void applyUiState(int framebufferWidth, int framebufferHeight) {
        guard.assertMainThread("apply UI render state");
        gl.disable(GL_DEPTH_TEST);
        gl.depthMask(false);
        gl.disable(GL_CULL_FACE);
        gl.enable(GL_BLEND);
        gl.blendFuncSeparate(
                GL_SRC_ALPHA,
                GL_ONE_MINUS_SRC_ALPHA,
                GL_ONE,
                GL_ONE_MINUS_SRC_ALPHA);
        gl.blendEquationSeparate(GL_FUNC_ADD, GL_FUNC_ADD);
        gl.disable(GL_POLYGON_OFFSET_FILL);
        gl.disable(GL_FRAMEBUFFER_SRGB);
        gl.disable(GL_SCISSOR_TEST);
        gl.viewport(0, 0, framebufferWidth, framebufferHeight);
    }

    @Override
    public void setClip(Optional<ScissorBox> clip) {
        guard.assertMainThread("apply UI clip state");
        Optional<ScissorBox> checked = Objects.requireNonNull(clip, "clip");
        if (checked.isEmpty()) {
            gl.disable(GL_SCISSOR_TEST);
            return;
        }
        ScissorBox box = checked.orElseThrow();
        gl.scissor(box.x(), box.y(), box.width(), box.height());
        gl.enable(GL_SCISSOR_TEST);
    }

    @Override
    public void restoreState(RenderStateSnapshot snapshot) {
        guard.assertMainThread("restore UI render state");
        stateBackend.restore(Objects.requireNonNull(snapshot, "snapshot"));
    }

    private int requireUniform(int program, String name) {
        int location = gl.uniformLocation(program, name);
        if (location < 0) {
            throw new IllegalStateException("Required UI shader uniform is missing: " + name);
        }
        return location;
    }
}

interface OpenGlUiApi {
    int createProgram(String vertexSource, String fragmentSource);

    int uniformLocation(int program, String name);

    void useProgram(int program);

    void uniform2f(int location, float x, float y);

    void uniform1i(int location, int value);

    void deleteProgram(int program);

    int createTexture(
            UiTextureData texture,
            int minFilter,
            int magFilter,
            int wrapS,
            int wrapT,
            int baseLevel,
            int maxLevel);

    void activeTexture(int textureUnit);

    void bindTexture2d(int texture);

    void deleteTexture(int texture);

    int createVertexArray();

    int createBuffer();

    void configureBatch(
            int vertexArray,
            int vertexBuffer,
            int elementBuffer,
            int stride,
            int positionLocation,
            int positionOffset,
            int uvLocation,
            int uvOffset,
            int tintLocation,
            int tintOffset);

    void uploadBatch(
            int vertexArray,
            int vertexBuffer,
            int elementBuffer,
            float[] vertices,
            int[] indices);

    void drawBatch(int vertexArray, int indexCount, int primitive, int indexType);

    void deleteBuffer(int buffer);

    void deleteVertexArray(int vertexArray);

    void enable(int capability);

    void disable(int capability);

    void depthMask(boolean enabled);

    void blendFuncSeparate(
            int sourceRgb,
            int destinationRgb,
            int sourceAlpha,
            int destinationAlpha);

    void blendEquationSeparate(int rgb, int alpha);

    void viewport(int x, int y, int width, int height);

    void scissor(int x, int y, int width, int height);
}

interface OpenGlUiShaderApi {
    int createShader(int type);

    void setShaderSource(int shader, String source);

    void compileShader(int shader);

    boolean shaderCompileSucceeded(int shader);

    String shaderInfoLog(int shader);

    int createProgram();

    void attachShader(int program, int shader);

    void linkProgram(int program);

    boolean programLinkSucceeded(int program);

    String programInfoLog(int program);

    int uniformLocation(int program, String name);

    void deleteShader(int shader);

    void deleteProgram(int program);
}

final class LwjglOpenGlUiApi implements OpenGlUiApi {
    private final OpenGlUiShaderApi shaderGl;

    LwjglOpenGlUiApi() {
        this(new LwjglOpenGlUiShaderApi());
    }

    LwjglOpenGlUiApi(OpenGlUiShaderApi shaderGl) {
        this.shaderGl = Objects.requireNonNull(shaderGl, "shaderGl");
    }

    @Override
    public int createProgram(String vertexSource, String fragmentSource) {
        int vertexShader = 0;
        int fragmentShader = 0;
        int program = 0;
        Throwable failure = null;
        try {
            vertexShader = shaderGl.createShader(GL_VERTEX_SHADER);
            requireHandle(vertexShader, "vertex shader");
            compileShader(vertexShader, "vertex", vertexSource);
            fragmentShader = shaderGl.createShader(GL_FRAGMENT_SHADER);
            requireHandle(fragmentShader, "fragment shader");
            compileShader(fragmentShader, "fragment", fragmentSource);
            program = shaderGl.createProgram();
            if (program == 0) {
                throw new IllegalStateException("OpenGL returned no UI shader program");
            }
            shaderGl.attachShader(program, vertexShader);
            shaderGl.attachShader(program, fragmentShader);
            shaderGl.linkProgram(program);
            if (!shaderGl.programLinkSucceeded(program)) {
                throw new IllegalStateException(
                        "Failed to link UI shader program: " + shaderGl.programInfoLog(program));
            }
            requireUniform(program, "framebufferSize");
            requireUniform(program, "uiTexture");
            requireUniform(program, "textureSamplingEnabled");
        } catch (RuntimeException | Error operationFailure) {
            failure = operationFailure;
        }

        failure = cleanupShader(fragmentShader, failure);
        failure = cleanupShader(vertexShader, failure);
        if (failure != null) {
            failure = cleanupProgram(program, failure);
            UiBatch.rethrow(failure);
        }
        return program;
    }

    @Override public int uniformLocation(int program, String name) { return shaderGl.uniformLocation(program, name); }
    @Override public void useProgram(int program) { glUseProgram(program); }
    @Override public void uniform2f(int location, float x, float y) { glUniform2f(location, x, y); }
    @Override public void uniform1i(int location, int value) { glUniform1i(location, value); }
    @Override public void deleteProgram(int program) { shaderGl.deleteProgram(program); }

    @Override
    public int createTexture(
            UiTextureData texture,
            int minFilter,
            int magFilter,
            int wrapS,
            int wrapT,
            int baseLevel,
            int maxLevel) {
        int created = glGenTextures();
        if (created == 0) {
            throw new IllegalStateException("OpenGL returned no UI texture");
        }
        try {
            glActiveTexture(GL_TEXTURE0);
            glBindTexture(GL_TEXTURE_2D, created);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, minFilter);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, magFilter);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, wrapS);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, wrapT);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_BASE_LEVEL, baseLevel);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAX_LEVEL, maxLevel);
            glTexImage2D(
                    GL_TEXTURE_2D,
                    0,
                    GL_RGBA8,
                    texture.width(),
                    texture.height(),
                    0,
                    GL_RGBA,
                    GL_UNSIGNED_BYTE,
                    texture.rgba());
            return created;
        } catch (RuntimeException | Error failure) {
            try {
                glDeleteTextures(created);
            } catch (RuntimeException | Error cleanupFailure) {
                UiBatch.appendFailure(failure, cleanupFailure);
            }
            throw failure;
        }
    }

    @Override public void activeTexture(int textureUnit) { glActiveTexture(textureUnit); }
    @Override public void bindTexture2d(int texture) { glBindTexture(GL_TEXTURE_2D, texture); }
    @Override public void deleteTexture(int texture) { glDeleteTextures(texture); }

    @Override
    public int createVertexArray() {
        int created = glGenVertexArrays();
        if (created == 0) {
            throw new IllegalStateException("OpenGL returned no UI vertex array");
        }
        return created;
    }

    @Override
    public int createBuffer() {
        int created = glGenBuffers();
        if (created == 0) {
            throw new IllegalStateException("OpenGL returned no UI buffer");
        }
        return created;
    }

    @Override
    public void configureBatch(
            int vertexArray,
            int vertexBuffer,
            int elementBuffer,
            int stride,
            int positionLocation,
            int positionOffset,
            int uvLocation,
            int uvOffset,
            int tintLocation,
            int tintOffset) {
        glBindVertexArray(vertexArray);
        glBindBuffer(GL_ARRAY_BUFFER, vertexBuffer);
        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, elementBuffer);
        glEnableVertexAttribArray(positionLocation);
        glVertexAttribPointer(positionLocation, 2, GL_FLOAT, false, stride, positionOffset);
        glEnableVertexAttribArray(uvLocation);
        glVertexAttribPointer(uvLocation, 2, GL_FLOAT, false, stride, uvOffset);
        glEnableVertexAttribArray(tintLocation);
        glVertexAttribPointer(tintLocation, 4, GL_FLOAT, false, stride, tintOffset);
    }

    @Override
    public void uploadBatch(
            int vertexArray,
            int vertexBuffer,
            int elementBuffer,
            float[] vertices,
            int[] indices) {
        glBindVertexArray(vertexArray);
        glBindBuffer(GL_ARRAY_BUFFER, vertexBuffer);
        glBufferData(GL_ARRAY_BUFFER, vertices, GL_DYNAMIC_DRAW);
        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, elementBuffer);
        glBufferData(GL_ELEMENT_ARRAY_BUFFER, indices, GL_DYNAMIC_DRAW);
    }

    @Override public void drawBatch(int vertexArray, int indexCount, int primitive, int indexType) {
        glBindVertexArray(vertexArray);
        glDrawElements(primitive, indexCount, indexType, 0L);
    }
    @Override public void deleteBuffer(int buffer) { glDeleteBuffers(buffer); }
    @Override public void deleteVertexArray(int vertexArray) { glDeleteVertexArrays(vertexArray); }
    @Override public void enable(int capability) { glEnable(capability); }
    @Override public void disable(int capability) { glDisable(capability); }
    @Override public void depthMask(boolean enabled) { glDepthMask(enabled); }
    @Override public void blendFuncSeparate(int sourceRgb, int destinationRgb, int sourceAlpha, int destinationAlpha) {
        glBlendFuncSeparate(sourceRgb, destinationRgb, sourceAlpha, destinationAlpha);
    }
    @Override public void blendEquationSeparate(int rgb, int alpha) { glBlendEquationSeparate(rgb, alpha); }
    @Override public void viewport(int x, int y, int width, int height) { glViewport(x, y, width, height); }
    @Override public void scissor(int x, int y, int width, int height) { glScissor(x, y, width, height); }

    private void compileShader(int shader, String label, String source) {
        shaderGl.setShaderSource(shader, source);
        shaderGl.compileShader(shader);
        if (!shaderGl.shaderCompileSucceeded(shader)) {
            throw new IllegalStateException(
                    "Failed to compile UI " + label + " shader: " + shaderGl.shaderInfoLog(shader));
        }
    }

    private void requireUniform(int program, String name) {
        if (shaderGl.uniformLocation(program, name) < 0) {
            throw new IllegalStateException("Required UI shader uniform is missing: " + name);
        }
    }

    private static void requireHandle(int handle, String resource) {
        if (handle == 0) {
            throw new IllegalStateException("OpenGL returned no UI " + resource);
        }
    }

    private Throwable cleanupShader(int shader, Throwable failure) {
        if (shader == 0) {
            return failure;
        }
        try {
            shaderGl.deleteShader(shader);
        } catch (RuntimeException | Error cleanupFailure) {
            return UiBatch.appendFailure(failure, cleanupFailure);
        }
        return failure;
    }

    private Throwable cleanupProgram(int program, Throwable failure) {
        if (program == 0) {
            return failure;
        }
        try {
            shaderGl.deleteProgram(program);
        } catch (RuntimeException | Error cleanupFailure) {
            return UiBatch.appendFailure(failure, cleanupFailure);
        }
        return failure;
    }
}

final class LwjglOpenGlUiShaderApi implements OpenGlUiShaderApi {
    @Override public int createShader(int type) { return glCreateShader(type); }
    @Override public void setShaderSource(int shader, String source) { glShaderSource(shader, source); }
    @Override public void compileShader(int shader) { glCompileShader(shader); }
    @Override public boolean shaderCompileSucceeded(int shader) { return glGetShaderi(shader, GL_COMPILE_STATUS) != 0; }
    @Override public String shaderInfoLog(int shader) { return glGetShaderInfoLog(shader); }
    @Override public int createProgram() { return glCreateProgram(); }
    @Override public void attachShader(int program, int shader) { glAttachShader(program, shader); }
    @Override public void linkProgram(int program) { glLinkProgram(program); }
    @Override public boolean programLinkSucceeded(int program) { return glGetProgrami(program, GL_LINK_STATUS) != 0; }
    @Override public String programInfoLog(int program) { return glGetProgramInfoLog(program); }
    @Override public int uniformLocation(int program, String name) { return glGetUniformLocation(program, name); }
    @Override public void deleteShader(int shader) { glDeleteShader(shader); }
    @Override public void deleteProgram(int program) { glDeleteProgram(program); }
}
