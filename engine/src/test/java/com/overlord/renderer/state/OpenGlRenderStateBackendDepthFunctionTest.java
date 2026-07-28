package com.overlord.renderer.state;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.lwjgl.opengl.GL30C.GL_ALWAYS;
import static org.lwjgl.opengl.GL30C.GL_EQUAL;
import static org.lwjgl.opengl.GL30C.GL_GEQUAL;
import static org.lwjgl.opengl.GL30C.GL_GREATER;
import static org.lwjgl.opengl.GL30C.GL_LEQUAL;
import static org.lwjgl.opengl.GL30C.GL_LESS;
import static org.lwjgl.opengl.GL30C.GL_NEVER;
import static org.lwjgl.opengl.GL30C.GL_NOTEQUAL;

import org.junit.jupiter.api.Test;

class OpenGlRenderStateBackendDepthFunctionTest {
    @Test
    void mapsEveryOpenGl41DepthFunctionWithoutRejectingValidCapturedState() {
        assertMapping(DepthFunction.NEVER, GL_NEVER);
        assertMapping(DepthFunction.LESS, GL_LESS);
        assertMapping(DepthFunction.EQUAL, GL_EQUAL);
        assertMapping(DepthFunction.LEQUAL, GL_LEQUAL);
        assertMapping(DepthFunction.GREATER, GL_GREATER);
        assertMapping(DepthFunction.NOTEQUAL, GL_NOTEQUAL);
        assertMapping(DepthFunction.GEQUAL, GL_GEQUAL);
        assertMapping(DepthFunction.ALWAYS, GL_ALWAYS);
    }

    private static void assertMapping(DepthFunction function, int glValue) {
        assertEquals(glValue, OpenGlRenderStateBackend.toGl(function));
        assertEquals(function, OpenGlRenderStateBackend.fromGl(glValue));
    }
}
