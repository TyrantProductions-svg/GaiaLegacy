package com.overlord.renderer.state;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.lwjgl.opengl.GL30C.*;

import com.overlord.assets.ResourceLocation;
import com.overlord.core.thread.MainThreadGuard;
import com.overlord.renderer.RenderSurfaceMetrics;
import com.overlord.renderer.TextureBinding;
import com.overlord.renderer.feedback.FeedbackVisibility;
import com.overlord.renderer.feedback.InteractionFeedbackFrame;
import com.overlord.renderer.feedback.ParticleRenderBatch;
import com.overlord.renderer.feedback.UnitCubeMesh;
import com.overlord.renderer.feedback.WorldItemVisual;
import com.overlord.renderer.pass.RenderContext;
import com.overlord.renderer.pass.WorldItemVisualPass;
import com.overlord.renderer.queue.RenderQueue;
import com.overlord.renderer.shader.ShaderBinding;
import com.overlord.renderer.texture.TextureRegion;
import com.overlord.renderer.visual.RenderVisualSettings;
import com.overlord.worlditem.api.WorldItemId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3fc;
import org.junit.jupiter.api.Test;

class OpenGlRenderStateBackendTest {
    private static final RenderStateSpec REQUESTED =
            new RenderStateSpec(
                    true,
                    DepthFunction.LEQUAL,
                    false,
                    BlendMode.ALPHA,
                    false,
                    true,
                    1.25f,
                    2.5f);

    @Test
    void captureReadsEveryFieldAndPreservesTheIncomingActiveTextureUnit() {
        RenderStateSnapshot incoming = incoming(DepthFunction.EQUAL);
        RecordingGl gl = new RecordingGl(incoming);
        OpenGlRenderStateBackend backend = backend(gl);

        RenderStateSnapshot captured = backend.capture();

        assertEquals(incoming, captured);
        assertEquals(
                List.of(
                        "isEnabled:" + GL_DEPTH_TEST,
                        "getInteger:" + GL_DEPTH_FUNC,
                        "getBoolean:" + GL_DEPTH_WRITEMASK,
                        "isEnabled:" + GL_BLEND,
                        "getInteger:" + GL_BLEND_SRC_RGB,
                        "getInteger:" + GL_BLEND_DST_RGB,
                        "getInteger:" + GL_BLEND_SRC_ALPHA,
                        "getInteger:" + GL_BLEND_DST_ALPHA,
                        "getInteger:" + GL_BLEND_EQUATION_RGB,
                        "getInteger:" + GL_BLEND_EQUATION_ALPHA,
                        "isEnabled:" + GL_CULL_FACE,
                        "getInteger:" + GL_VERTEX_ARRAY_BINDING,
                        "getInteger:" + GL_ARRAY_BUFFER_BINDING,
                        "getInteger:" + GL_ELEMENT_ARRAY_BUFFER_BINDING,
                        "isEnabled:" + GL_POLYGON_OFFSET_FILL,
                        "getFloat:" + GL_POLYGON_OFFSET_FACTOR,
                        "getFloat:" + GL_POLYGON_OFFSET_UNITS,
                        "getInteger:" + GL_CURRENT_PROGRAM,
                        "getInteger:" + GL_ACTIVE_TEXTURE,
                        "activeTexture:" + GL_TEXTURE0,
                        "getInteger:" + GL_TEXTURE_BINDING_2D,
                        "activeTexture:" + incoming.activeTexture(),
                        "getIntegerv:" + GL_VIEWPORT),
                gl.calls);
    }

    @Test
    void scopeRestoresAllIncomingStateInSafeOrderIncludingEqualDepthFunction() {
        RenderStateSnapshot incoming = incoming(DepthFunction.EQUAL);
        RecordingGl gl = new RecordingGl(incoming);
        OpenGlRenderStateBackend backend = backend(gl);

        try (RenderStateScope ignored = RenderStateScope.open(backend, REQUESTED)) {
            // The production scope owns restoration.
        }

        assertEquals(
                List.of(
                        "enable:" + GL_DEPTH_TEST,
                        "depthFunc:" + GL_LEQUAL,
                        "depthMask:false",
                        "enable:" + GL_BLEND,
                        "blendFuncSeparate:"
                                + GL_SRC_ALPHA
                                + ":"
                                + GL_ONE_MINUS_SRC_ALPHA
                                + ":"
                                + GL_SRC_ALPHA
                                + ":"
                                + GL_ONE_MINUS_SRC_ALPHA,
                        "blendEquationSeparate:" + GL_FUNC_ADD + ":" + GL_FUNC_ADD,
                        "disable:" + GL_CULL_FACE,
                        "polygonOffset:1.25:2.5",
                        "enable:" + GL_POLYGON_OFFSET_FILL),
                applyCommandsBeforeRestoration(gl.calls, incoming));
        assertEquals(restoreCalls(incoming), restorationTail(gl.calls, incoming));
    }

    @Test
    void applyFailureRestoresAllIncomingStateIncludingAlwaysDepthFunction() {
        RenderStateSnapshot incoming = incoming(DepthFunction.ALWAYS);
        RecordingGl gl = new RecordingGl(incoming);
        IllegalStateException failure = new IllegalStateException("depth mask failed");
        gl.failOnce("depthMask:false", failure);
        OpenGlRenderStateBackend backend = backend(gl);

        IllegalStateException escaped =
                assertThrows(
                        IllegalStateException.class,
                        () -> RenderStateScope.open(backend, REQUESTED));

        assertSame(failure, escaped);
        assertEquals(restoreCalls(incoming), restorationTail(gl.calls, incoming));
    }

    @Test
    void worldItemDrawFailureRestoresProgramTextureVaoAndEveryCapturedField() {
        RenderStateSnapshot incoming = incoming(DepthFunction.ALWAYS);
        RecordingGl gl = new RecordingGl(incoming);
        OpenGlRenderStateBackend backend = backend(gl);
        IllegalStateException failure = new IllegalStateException("draw failed");
        WorldItemVisualPass pass =
                new WorldItemVisualPass(
                        backend,
                        shaderUsing(gl, 601),
                        textureUsing(gl, 602),
                        cubeUsing(gl, 603, 604, 605, failure));

        IllegalStateException escaped =
                assertThrows(
                        IllegalStateException.class,
                        () -> pass.render(context(), new RenderQueue()));

        assertSame(failure, escaped);
        assertEquals(
                List.of(
                        "useProgram:601",
                        "activeTexture:" + GL_TEXTURE0,
                        "bindTexture:" + GL_TEXTURE_2D + ":602",
                        "bindVertexArray:603",
                        "bindBuffer:" + GL_ARRAY_BUFFER + ":604",
                        "bindBuffer:" + GL_ELEMENT_ARRAY_BUFFER + ":605"),
                drawMutationCommandsBeforeRestoration(gl.calls, incoming));
        assertEquals(restoreCalls(incoming), restorationTail(gl.calls, incoming));
    }

    private static OpenGlRenderStateBackend backend(RecordingGl gl) {
        return new OpenGlRenderStateBackend(MainThreadGuard.captureCurrentThread(), gl);
    }

    private static RenderStateSnapshot incoming(DepthFunction depthFunction) {
        return new RenderStateSnapshot(
                false,
                depthFunction,
                true,
                true,
                101,
                102,
                103,
                104,
                105,
                106,
                true,
                201,
                202,
                203,
                true,
                3.25f,
                4.5f,
                301,
                GL_TEXTURE0 + 5,
                302,
                new Viewport(11, 12, 1300, 900));
    }

    private static List<String> restorationTail(
            List<String> calls, RenderStateSnapshot incoming) {
        int start = calls.lastIndexOf("bindVertexArray:" + incoming.vertexArray());
        return calls.subList(start, calls.size());
    }

    private static List<String> applyCommandsBeforeRestoration(
            List<String> calls, RenderStateSnapshot incoming) {
        int start = calls.indexOf("enable:" + GL_DEPTH_TEST);
        int end = calls.lastIndexOf("bindVertexArray:" + incoming.vertexArray());
        return calls.subList(start, end);
    }

    private static List<String> drawMutationCommandsBeforeRestoration(
            List<String> calls, RenderStateSnapshot incoming) {
        int start = calls.indexOf("useProgram:601");
        int end = calls.lastIndexOf("bindVertexArray:" + incoming.vertexArray());
        return calls.subList(start, end);
    }

    private static List<String> restoreCalls(RenderStateSnapshot incoming) {
        return List.of(
                "bindVertexArray:" + incoming.vertexArray(),
                "bindBuffer:" + GL_ELEMENT_ARRAY_BUFFER + ":" + incoming.elementArrayBuffer(),
                "bindBuffer:" + GL_ARRAY_BUFFER + ":" + incoming.arrayBuffer(),
                "useProgram:" + incoming.currentProgram(),
                "activeTexture:" + GL_TEXTURE0,
                "bindTexture:" + GL_TEXTURE_2D + ":" + incoming.texture2dUnit0(),
                "activeTexture:" + incoming.activeTexture(),
                "depthFunc:" + OpenGlRenderStateBackend.toGl(incoming.depthFunction()),
                "depthMask:" + incoming.depthWrite(),
                "disable:" + GL_DEPTH_TEST,
                "blendFuncSeparate:101:102:103:104",
                "blendEquationSeparate:105:106",
                "enable:" + GL_BLEND,
                "enable:" + GL_CULL_FACE,
                "polygonOffset:3.25:4.5",
                "enable:" + GL_POLYGON_OFFSET_FILL,
                "viewport:11:12:1300:900");
    }

    private static ShaderBinding shaderUsing(RecordingGl gl, int program) {
        return new ShaderBinding() {
            @Override
            public int programId() {
                return program;
            }

            @Override
            public void use() {
                gl.glUseProgram(program);
            }

            @Override
            public void setMatrix4(String uniform, Matrix4fc value) {}

            @Override
            public void setInt(String uniform, int value) {}

            @Override
            public void setFloat(String uniform, float value) {}

            @Override
            public void setVector3(String uniform, Vector3fc value) {}
        };
    }

    private static TextureBinding textureUsing(RecordingGl gl, int texture) {
        return unit -> {
            gl.glActiveTexture(GL_TEXTURE0 + unit);
            gl.glBindTexture(GL_TEXTURE_2D, texture);
        };
    }

    private static UnitCubeMesh cubeUsing(
            RecordingGl gl,
            int vao,
            int arrayBuffer,
            int elementBuffer,
            RuntimeException failure) {
        return new UnitCubeMesh() {
            @Override
            public void draw() {
                gl.glBindVertexArray(vao);
                gl.glBindBuffer(GL_ARRAY_BUFFER, arrayBuffer);
                gl.glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, elementBuffer);
                throw failure;
            }

            @Override
            public void cleanup() {}
        };
    }

    private static RenderContext context() {
        TextureRegion region =
                new TextureRegion(
                        ResourceLocation.parse("gaia:stone_top"), 0, 0, 16, 16, 16, 16);
        return new RenderContext(
                new Matrix4f(),
                new Matrix4f(),
                RenderVisualSettings.milestoneOneDefaults(),
                ignored -> {},
                new RenderSurfaceMetrics(1024, 768, 1024, 768, 1, 1),
                new InteractionFeedbackFrame(
                        new FeedbackVisibility(true, true, true, false),
                        Optional.empty(),
                        List.of(new WorldItemVisual(new WorldItemId(1), 0, 1, 2, 3, region)),
                        new ParticleRenderBatch(List.of())));
    }

    private static final class RecordingGl implements OpenGlRenderStateApi {
        private final RenderStateSnapshot incoming;
        private final List<String> calls = new ArrayList<>();
        private int activeTexture;
        private String failingCall;
        private RuntimeException failure;

        private RecordingGl(RenderStateSnapshot incoming) {
            this.incoming = incoming;
            activeTexture = incoming.activeTexture();
        }

        private void failOnce(String call, RuntimeException failure) {
            failingCall = call;
            this.failure = failure;
        }

        private void record(String call) {
            calls.add(call);
            if (call.equals(failingCall)) {
                failingCall = null;
                throw failure;
            }
        }

        @Override
        public boolean glIsEnabled(int capability) {
            record("isEnabled:" + capability);
            return switch (capability) {
                case GL_DEPTH_TEST -> incoming.depthTest();
                case GL_BLEND -> incoming.blend();
                case GL_CULL_FACE -> incoming.cullFace();
                case GL_POLYGON_OFFSET_FILL -> incoming.polygonOffsetFill();
                default -> throw new AssertionError(capability);
            };
        }

        @Override
        public int glGetInteger(int parameter) {
            record("getInteger:" + parameter);
            return switch (parameter) {
                case GL_DEPTH_FUNC -> OpenGlRenderStateBackend.toGl(incoming.depthFunction());
                case GL_BLEND_SRC_RGB -> incoming.blendSourceRgb();
                case GL_BLEND_DST_RGB -> incoming.blendDestinationRgb();
                case GL_BLEND_SRC_ALPHA -> incoming.blendSourceAlpha();
                case GL_BLEND_DST_ALPHA -> incoming.blendDestinationAlpha();
                case GL_BLEND_EQUATION_RGB -> incoming.blendEquationRgb();
                case GL_BLEND_EQUATION_ALPHA -> incoming.blendEquationAlpha();
                case GL_VERTEX_ARRAY_BINDING -> incoming.vertexArray();
                case GL_ARRAY_BUFFER_BINDING -> incoming.arrayBuffer();
                case GL_ELEMENT_ARRAY_BUFFER_BINDING -> incoming.elementArrayBuffer();
                case GL_CURRENT_PROGRAM -> incoming.currentProgram();
                case GL_ACTIVE_TEXTURE -> activeTexture;
                case GL_TEXTURE_BINDING_2D -> {
                    assertEquals(GL_TEXTURE0, activeTexture);
                    yield incoming.texture2dUnit0();
                }
                default -> throw new AssertionError(parameter);
            };
        }

        @Override
        public boolean glGetBoolean(int parameter) {
            record("getBoolean:" + parameter);
            if (parameter != GL_DEPTH_WRITEMASK) {
                throw new AssertionError(parameter);
            }
            return incoming.depthWrite();
        }

        @Override
        public float glGetFloat(int parameter) {
            record("getFloat:" + parameter);
            return switch (parameter) {
                case GL_POLYGON_OFFSET_FACTOR -> incoming.polygonOffsetFactor();
                case GL_POLYGON_OFFSET_UNITS -> incoming.polygonOffsetUnits();
                default -> throw new AssertionError(parameter);
            };
        }

        @Override
        public void glGetIntegerv(int parameter, int[] values) {
            record("getIntegerv:" + parameter);
            if (parameter != GL_VIEWPORT) {
                throw new AssertionError(parameter);
            }
            Viewport viewport = incoming.viewport();
            values[0] = viewport.x();
            values[1] = viewport.y();
            values[2] = viewport.width();
            values[3] = viewport.height();
        }

        @Override
        public void glActiveTexture(int texture) {
            record("activeTexture:" + texture);
            activeTexture = texture;
        }

        @Override public void glBindBuffer(int target, int buffer) { record("bindBuffer:" + target + ":" + buffer); }
        @Override public void glBindTexture(int target, int texture) { record("bindTexture:" + target + ":" + texture); }
        @Override public void glBindVertexArray(int vao) { record("bindVertexArray:" + vao); }
        @Override public void glBlendEquationSeparate(int rgb, int alpha) { record("blendEquationSeparate:" + rgb + ":" + alpha); }
        @Override public void glBlendFuncSeparate(int srcRgb, int dstRgb, int srcAlpha, int dstAlpha) { record("blendFuncSeparate:" + srcRgb + ":" + dstRgb + ":" + srcAlpha + ":" + dstAlpha); }
        @Override public void glClear(int mask) { record("clear:" + mask); }
        @Override public void glDepthFunc(int function) { record("depthFunc:" + function); }
        @Override public void glDepthMask(boolean write) { record("depthMask:" + write); }
        @Override public void glDisable(int capability) { record("disable:" + capability); }
        @Override public void glEnable(int capability) { record("enable:" + capability); }
        @Override public void glPolygonOffset(float factor, float units) { record("polygonOffset:" + factor + ":" + units); }
        @Override public void glUseProgram(int program) { record("useProgram:" + program); }
        @Override public void glViewport(int x, int y, int width, int height) { record("viewport:" + x + ":" + y + ":" + width + ":" + height); }
    }
}
