package com.overlord.renderer.state;

import static org.lwjgl.opengl.GL30C.GL_ACTIVE_TEXTURE;
import static org.lwjgl.opengl.GL30C.GL_ALWAYS;
import static org.lwjgl.opengl.GL30C.GL_ARRAY_BUFFER;
import static org.lwjgl.opengl.GL30C.GL_ARRAY_BUFFER_BINDING;
import static org.lwjgl.opengl.GL30C.GL_BLEND;
import static org.lwjgl.opengl.GL30C.GL_BLEND_DST_ALPHA;
import static org.lwjgl.opengl.GL30C.GL_BLEND_DST_RGB;
import static org.lwjgl.opengl.GL30C.GL_BLEND_EQUATION_ALPHA;
import static org.lwjgl.opengl.GL30C.GL_BLEND_EQUATION_RGB;
import static org.lwjgl.opengl.GL30C.GL_BLEND_SRC_ALPHA;
import static org.lwjgl.opengl.GL30C.GL_BLEND_SRC_RGB;
import static org.lwjgl.opengl.GL30C.GL_COLOR_BUFFER_BIT;
import static org.lwjgl.opengl.GL30C.GL_CULL_FACE;
import static org.lwjgl.opengl.GL30C.GL_CURRENT_PROGRAM;
import static org.lwjgl.opengl.GL30C.GL_DEPTH_BUFFER_BIT;
import static org.lwjgl.opengl.GL30C.GL_DEPTH_FUNC;
import static org.lwjgl.opengl.GL30C.GL_DEPTH_TEST;
import static org.lwjgl.opengl.GL30C.GL_DEPTH_WRITEMASK;
import static org.lwjgl.opengl.GL30C.GL_EQUAL;
import static org.lwjgl.opengl.GL30C.GL_ELEMENT_ARRAY_BUFFER;
import static org.lwjgl.opengl.GL30C.GL_ELEMENT_ARRAY_BUFFER_BINDING;
import static org.lwjgl.opengl.GL30C.GL_FUNC_ADD;
import static org.lwjgl.opengl.GL30C.GL_GEQUAL;
import static org.lwjgl.opengl.GL30C.GL_GREATER;
import static org.lwjgl.opengl.GL30C.GL_LEQUAL;
import static org.lwjgl.opengl.GL30C.GL_LESS;
import static org.lwjgl.opengl.GL30C.GL_NEVER;
import static org.lwjgl.opengl.GL30C.GL_NOTEQUAL;
import static org.lwjgl.opengl.GL30C.GL_ONE_MINUS_SRC_ALPHA;
import static org.lwjgl.opengl.GL30C.GL_POLYGON_OFFSET_FACTOR;
import static org.lwjgl.opengl.GL30C.GL_POLYGON_OFFSET_FILL;
import static org.lwjgl.opengl.GL30C.GL_POLYGON_OFFSET_UNITS;
import static org.lwjgl.opengl.GL30C.GL_SRC_ALPHA;
import static org.lwjgl.opengl.GL30C.GL_TEXTURE0;
import static org.lwjgl.opengl.GL30C.GL_TEXTURE_2D;
import static org.lwjgl.opengl.GL30C.GL_TEXTURE_BINDING_2D;
import static org.lwjgl.opengl.GL30C.GL_VERTEX_ARRAY_BINDING;
import static org.lwjgl.opengl.GL30C.GL_VIEWPORT;
import com.overlord.core.thread.MainThreadGuard;
import java.util.Objects;

public final class OpenGlRenderStateBackend implements RenderStateBackend {
    private final MainThreadGuard mainThreadGuard;
    private final OpenGlRenderStateApi gl;

    public OpenGlRenderStateBackend(MainThreadGuard mainThreadGuard) {
        this(mainThreadGuard, new LwjglOpenGlRenderStateApi());
    }

    OpenGlRenderStateBackend(
            MainThreadGuard mainThreadGuard, OpenGlRenderStateApi gl) {
        this.mainThreadGuard = Objects.requireNonNull(mainThreadGuard, "mainThreadGuard");
        this.gl = Objects.requireNonNull(gl, "gl");
    }

    @Override
    public RenderStateSnapshot capture() {
        mainThreadGuard.assertMainThread("capture OpenGL render state");

        boolean depthTest = gl.glIsEnabled(GL_DEPTH_TEST);
        DepthFunction depthFunction = fromGl(gl.glGetInteger(GL_DEPTH_FUNC));
        boolean depthWrite = gl.glGetBoolean(GL_DEPTH_WRITEMASK);
        boolean blend = gl.glIsEnabled(GL_BLEND);
        int blendSourceRgb = gl.glGetInteger(GL_BLEND_SRC_RGB);
        int blendDestinationRgb = gl.glGetInteger(GL_BLEND_DST_RGB);
        int blendSourceAlpha = gl.glGetInteger(GL_BLEND_SRC_ALPHA);
        int blendDestinationAlpha = gl.glGetInteger(GL_BLEND_DST_ALPHA);
        int blendEquationRgb = gl.glGetInteger(GL_BLEND_EQUATION_RGB);
        int blendEquationAlpha = gl.glGetInteger(GL_BLEND_EQUATION_ALPHA);
        boolean cullFace = gl.glIsEnabled(GL_CULL_FACE);
        int vertexArray = gl.glGetInteger(GL_VERTEX_ARRAY_BINDING);
        int arrayBuffer = gl.glGetInteger(GL_ARRAY_BUFFER_BINDING);
        int elementArrayBuffer = gl.glGetInteger(GL_ELEMENT_ARRAY_BUFFER_BINDING);
        boolean polygonOffsetFill = gl.glIsEnabled(GL_POLYGON_OFFSET_FILL);
        float polygonOffsetFactor = gl.glGetFloat(GL_POLYGON_OFFSET_FACTOR);
        float polygonOffsetUnits = gl.glGetFloat(GL_POLYGON_OFFSET_UNITS);
        int currentProgram = gl.glGetInteger(GL_CURRENT_PROGRAM);
        int activeTexture = gl.glGetInteger(GL_ACTIVE_TEXTURE);
        int texture2dUnit0;
        gl.glActiveTexture(GL_TEXTURE0);
        try {
            texture2dUnit0 = gl.glGetInteger(GL_TEXTURE_BINDING_2D);
        } finally {
            gl.glActiveTexture(activeTexture);
        }
        int[] viewportValues = new int[4];
        gl.glGetIntegerv(GL_VIEWPORT, viewportValues);
        Viewport viewport =
                new Viewport(
                        viewportValues[0],
                        viewportValues[1],
                        viewportValues[2],
                        viewportValues[3]);

        return new RenderStateSnapshot(
                depthTest,
                depthFunction,
                depthWrite,
                blend,
                blendSourceRgb,
                blendDestinationRgb,
                blendSourceAlpha,
                blendDestinationAlpha,
                blendEquationRgb,
                blendEquationAlpha,
                cullFace,
                vertexArray,
                arrayBuffer,
                elementArrayBuffer,
                polygonOffsetFill,
                polygonOffsetFactor,
                polygonOffsetUnits,
                currentProgram,
                activeTexture,
                texture2dUnit0,
                viewport);
    }

    @Override
    public void apply(RenderStateSpec state) {
        mainThreadGuard.assertMainThread("apply OpenGL render state");

        if (state.depthTest()) {
            gl.glEnable(GL_DEPTH_TEST);
        } else {
            gl.glDisable(GL_DEPTH_TEST);
        }
        gl.glDepthFunc(toGl(state.depthFunction()));
        gl.glDepthMask(state.depthWrite());
        switch (state.blendMode()) {
            case DISABLED -> gl.glDisable(GL_BLEND);
            case ALPHA -> {
                gl.glEnable(GL_BLEND);
                gl.glBlendFuncSeparate(
                        GL_SRC_ALPHA,
                        GL_ONE_MINUS_SRC_ALPHA,
                        GL_SRC_ALPHA,
                        GL_ONE_MINUS_SRC_ALPHA);
                gl.glBlendEquationSeparate(GL_FUNC_ADD, GL_FUNC_ADD);
            }
        }
        if (state.cullFace()) {
            gl.glEnable(GL_CULL_FACE);
        } else {
            gl.glDisable(GL_CULL_FACE);
        }
        gl.glPolygonOffset(
                state.polygonOffsetFactor(), state.polygonOffsetUnits());
        if (state.polygonOffsetFill()) {
            gl.glEnable(GL_POLYGON_OFFSET_FILL);
        } else {
            gl.glDisable(GL_POLYGON_OFFSET_FILL);
        }
    }

    @Override
    public void restore(RenderStateSnapshot snapshot) {
        mainThreadGuard.assertMainThread("restore OpenGL render state");

        gl.glBindVertexArray(snapshot.vertexArray());
        gl.glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, snapshot.elementArrayBuffer());
        gl.glBindBuffer(GL_ARRAY_BUFFER, snapshot.arrayBuffer());
        gl.glUseProgram(snapshot.currentProgram());
        gl.glActiveTexture(GL_TEXTURE0);
        try {
            gl.glBindTexture(GL_TEXTURE_2D, snapshot.texture2dUnit0());
        } finally {
            gl.glActiveTexture(snapshot.activeTexture());
        }
        gl.glDepthFunc(toGl(snapshot.depthFunction()));
        gl.glDepthMask(snapshot.depthWrite());
        if (snapshot.depthTest()) {
            gl.glEnable(GL_DEPTH_TEST);
        } else {
            gl.glDisable(GL_DEPTH_TEST);
        }
        gl.glBlendFuncSeparate(
                snapshot.blendSourceRgb(),
                snapshot.blendDestinationRgb(),
                snapshot.blendSourceAlpha(),
                snapshot.blendDestinationAlpha());
        gl.glBlendEquationSeparate(
                snapshot.blendEquationRgb(),
                snapshot.blendEquationAlpha());
        if (snapshot.blend()) {
            gl.glEnable(GL_BLEND);
        } else {
            gl.glDisable(GL_BLEND);
        }
        if (snapshot.cullFace()) {
            gl.glEnable(GL_CULL_FACE);
        } else {
            gl.glDisable(GL_CULL_FACE);
        }
        gl.glPolygonOffset(
                snapshot.polygonOffsetFactor(), snapshot.polygonOffsetUnits());
        if (snapshot.polygonOffsetFill()) {
            gl.glEnable(GL_POLYGON_OFFSET_FILL);
        } else {
            gl.glDisable(GL_POLYGON_OFFSET_FILL);
        }
        Viewport viewport = snapshot.viewport();
        gl.glViewport(
                viewport.x(), viewport.y(), viewport.width(), viewport.height());
    }

    @Override
    public void clearColorAndDepth() {
        mainThreadGuard.assertMainThread("clear OpenGL color and depth buffers");
        gl.glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
    }

    @Override
    public void setViewport(Viewport viewport) {
        mainThreadGuard.assertMainThread("set OpenGL viewport");
        gl.glViewport(
                viewport.x(), viewport.y(), viewport.width(), viewport.height());
    }

    static int toGl(DepthFunction depthFunction) {
        return switch (depthFunction) {
            case NEVER -> GL_NEVER;
            case LESS -> GL_LESS;
            case EQUAL -> GL_EQUAL;
            case LEQUAL -> GL_LEQUAL;
            case GREATER -> GL_GREATER;
            case NOTEQUAL -> GL_NOTEQUAL;
            case GEQUAL -> GL_GEQUAL;
            case ALWAYS -> GL_ALWAYS;
        };
    }

    static DepthFunction fromGl(int depthFunction) {
        return switch (depthFunction) {
            case GL_NEVER -> DepthFunction.NEVER;
            case GL_LESS -> DepthFunction.LESS;
            case GL_EQUAL -> DepthFunction.EQUAL;
            case GL_LEQUAL -> DepthFunction.LEQUAL;
            case GL_GREATER -> DepthFunction.GREATER;
            case GL_NOTEQUAL -> DepthFunction.NOTEQUAL;
            case GL_GEQUAL -> DepthFunction.GEQUAL;
            case GL_ALWAYS -> DepthFunction.ALWAYS;
            default ->
                    throw new IllegalStateException(
                            "unsupported OpenGL depth function: " + depthFunction);
        };
    }
}
