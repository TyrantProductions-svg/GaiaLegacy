package com.overlord.renderer.state;

import static org.lwjgl.opengl.GL30C.GL_ACTIVE_TEXTURE;
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
import static org.lwjgl.opengl.GL30C.GL_DEPTH_TEST;
import static org.lwjgl.opengl.GL30C.GL_DEPTH_WRITEMASK;
import static org.lwjgl.opengl.GL30C.GL_FUNC_ADD;
import static org.lwjgl.opengl.GL30C.GL_ONE_MINUS_SRC_ALPHA;
import static org.lwjgl.opengl.GL30C.GL_SRC_ALPHA;
import static org.lwjgl.opengl.GL30C.GL_TEXTURE0;
import static org.lwjgl.opengl.GL30C.GL_TEXTURE_2D;
import static org.lwjgl.opengl.GL30C.GL_TEXTURE_BINDING_2D;
import static org.lwjgl.opengl.GL30C.glActiveTexture;
import static org.lwjgl.opengl.GL30C.glBindTexture;
import static org.lwjgl.opengl.GL30C.glBlendEquationSeparate;
import static org.lwjgl.opengl.GL30C.glBlendFuncSeparate;
import static org.lwjgl.opengl.GL30C.glClear;
import static org.lwjgl.opengl.GL30C.glDepthMask;
import static org.lwjgl.opengl.GL30C.glDisable;
import static org.lwjgl.opengl.GL30C.glEnable;
import static org.lwjgl.opengl.GL30C.glGetBoolean;
import static org.lwjgl.opengl.GL30C.glGetInteger;
import static org.lwjgl.opengl.GL30C.glIsEnabled;
import static org.lwjgl.opengl.GL30C.glUseProgram;

import com.overlord.core.thread.MainThreadGuard;
import java.util.Objects;

public final class OpenGlRenderStateBackend implements RenderStateBackend {
    private final MainThreadGuard mainThreadGuard;

    public OpenGlRenderStateBackend(MainThreadGuard mainThreadGuard) {
        this.mainThreadGuard = Objects.requireNonNull(mainThreadGuard, "mainThreadGuard");
    }

    @Override
    public RenderStateSnapshot capture() {
        mainThreadGuard.assertMainThread("capture OpenGL render state");

        boolean depthTest = glIsEnabled(GL_DEPTH_TEST);
        boolean depthWrite = glGetBoolean(GL_DEPTH_WRITEMASK);
        boolean blend = glIsEnabled(GL_BLEND);
        int blendSourceRgb = glGetInteger(GL_BLEND_SRC_RGB);
        int blendDestinationRgb = glGetInteger(GL_BLEND_DST_RGB);
        int blendSourceAlpha = glGetInteger(GL_BLEND_SRC_ALPHA);
        int blendDestinationAlpha = glGetInteger(GL_BLEND_DST_ALPHA);
        int blendEquationRgb = glGetInteger(GL_BLEND_EQUATION_RGB);
        int blendEquationAlpha = glGetInteger(GL_BLEND_EQUATION_ALPHA);
        boolean cullFace = glIsEnabled(GL_CULL_FACE);
        int currentProgram = glGetInteger(GL_CURRENT_PROGRAM);
        int activeTexture = glGetInteger(GL_ACTIVE_TEXTURE);
        int texture2dUnit0;
        glActiveTexture(GL_TEXTURE0);
        try {
            texture2dUnit0 = glGetInteger(GL_TEXTURE_BINDING_2D);
        } finally {
            glActiveTexture(activeTexture);
        }

        return new RenderStateSnapshot(
                depthTest,
                depthWrite,
                blend,
                blendSourceRgb,
                blendDestinationRgb,
                blendSourceAlpha,
                blendDestinationAlpha,
                blendEquationRgb,
                blendEquationAlpha,
                cullFace,
                currentProgram,
                activeTexture,
                texture2dUnit0);
    }

    @Override
    public void apply(RenderStateSpec state) {
        mainThreadGuard.assertMainThread("apply OpenGL render state");

        if (state.depthTest()) {
            glEnable(GL_DEPTH_TEST);
        } else {
            glDisable(GL_DEPTH_TEST);
        }
        glDepthMask(state.depthWrite());
        switch (state.blendMode()) {
            case DISABLED -> glDisable(GL_BLEND);
            case ALPHA -> {
                glEnable(GL_BLEND);
                glBlendFuncSeparate(
                        GL_SRC_ALPHA,
                        GL_ONE_MINUS_SRC_ALPHA,
                        GL_SRC_ALPHA,
                        GL_ONE_MINUS_SRC_ALPHA);
                glBlendEquationSeparate(GL_FUNC_ADD, GL_FUNC_ADD);
            }
        }
        if (state.cullFace()) {
            glEnable(GL_CULL_FACE);
        } else {
            glDisable(GL_CULL_FACE);
        }
    }

    @Override
    public void restore(RenderStateSnapshot snapshot) {
        mainThreadGuard.assertMainThread("restore OpenGL render state");

        if (snapshot.depthTest()) {
            glEnable(GL_DEPTH_TEST);
        } else {
            glDisable(GL_DEPTH_TEST);
        }
        glDepthMask(snapshot.depthWrite());
        glBlendFuncSeparate(
                snapshot.blendSourceRgb(),
                snapshot.blendDestinationRgb(),
                snapshot.blendSourceAlpha(),
                snapshot.blendDestinationAlpha());
        glBlendEquationSeparate(
                snapshot.blendEquationRgb(),
                snapshot.blendEquationAlpha());
        if (snapshot.blend()) {
            glEnable(GL_BLEND);
        } else {
            glDisable(GL_BLEND);
        }
        if (snapshot.cullFace()) {
            glEnable(GL_CULL_FACE);
        } else {
            glDisable(GL_CULL_FACE);
        }
        glUseProgram(snapshot.currentProgram());
        glActiveTexture(GL_TEXTURE0);
        try {
            glBindTexture(GL_TEXTURE_2D, snapshot.texture2dUnit0());
        } finally {
            glActiveTexture(snapshot.activeTexture());
        }
    }

    @Override
    public void clearColorAndDepth() {
        mainThreadGuard.assertMainThread("clear OpenGL color and depth buffers");
        glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
    }
}
