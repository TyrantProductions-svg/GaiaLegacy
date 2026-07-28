package com.overlord.renderer.state;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class OpenGlRenderStateBackendStructureTest {
    private static final Path SOURCE =
            Path.of(
                    "src/main/java/com/overlord/renderer/state/"
                            + "OpenGlRenderStateBackend.java");

    @Test
    void captureReadsEveryRequiredIncomingValue() throws IOException {
        String capture = methodBody(Files.readString(SOURCE), "capture");

        assertInOrder(
                capture,
                "mainThreadGuard.assertMainThread(",
                "glIsEnabled(GL_DEPTH_TEST)",
                "glGetInteger(GL_DEPTH_FUNC)",
                "glGetBoolean(GL_DEPTH_WRITEMASK)",
                "glIsEnabled(GL_BLEND)",
                "glIsEnabled(GL_CULL_FACE)",
                "glGetInteger(GL_VERTEX_ARRAY_BINDING)",
                "glGetInteger(GL_ARRAY_BUFFER_BINDING)",
                "glGetInteger(GL_ELEMENT_ARRAY_BUFFER_BINDING)",
                "glIsEnabled(GL_POLYGON_OFFSET_FILL)",
                "glGetFloat(GL_POLYGON_OFFSET_FACTOR)",
                "glGetFloat(GL_POLYGON_OFFSET_UNITS)",
                "glGetInteger(GL_CURRENT_PROGRAM)",
                "glGetInteger(GL_ACTIVE_TEXTURE)",
                "glGetInteger(GL_TEXTURE_BINDING_2D)",
                "glGetIntegerv(GL_VIEWPORT");
    }

    @Test
    void restoreRebindsElementBufferThroughItsCapturedVaoAndRestoresAllState()
            throws IOException {
        String restore = methodBody(Files.readString(SOURCE), "restore");

        assertInOrder(
                restore,
                "mainThreadGuard.assertMainThread(",
                "glBindVertexArray(snapshot.vertexArray())",
                "glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, snapshot.elementArrayBuffer())",
                "glBindBuffer(GL_ARRAY_BUFFER, snapshot.arrayBuffer())",
                "glUseProgram(snapshot.currentProgram())",
                "glActiveTexture(GL_TEXTURE0)",
                "glBindTexture(GL_TEXTURE_2D, snapshot.texture2dUnit0())",
                "glActiveTexture(snapshot.activeTexture())",
                "glDepthFunc(toGl(snapshot.depthFunction()))",
                "glDepthMask(snapshot.depthWrite())",
                "glBlendFuncSeparate(",
                "glBlendEquationSeparate(",
                "snapshot.cullFace()",
                "glPolygonOffset(",
                "snapshot.polygonOffsetFill()",
                "glViewport(");
    }

    @Test
    void applyAndViewportOperationsAreGuardedAndConfigureCompleteRequestedState()
            throws IOException {
        String source = Files.readString(SOURCE);
        String apply = methodBody(source, "apply");
        String setViewport = methodBody(source, "setViewport");

        assertInOrder(
                apply,
                "mainThreadGuard.assertMainThread(",
                "glDepthFunc(toGl(state.depthFunction()))",
                "glDepthMask(state.depthWrite())",
                "glPolygonOffset(state.polygonOffsetFactor(), state.polygonOffsetUnits())",
                "state.polygonOffsetFill()");
        assertInOrder(
                setViewport,
                "mainThreadGuard.assertMainThread(",
                "glViewport(viewport.x(), viewport.y(), viewport.width(), viewport.height())");
    }

    @Test
    void depthFunctionMappingCoversEveryOpenGl41DepthFunction() throws IOException {
        String source = Files.readString(SOURCE);
        String mapping = methodBody(source, "toGl");
        String captureMapping = methodBodyFromLastDeclaration(source, "fromGl");

        String compactMapping = withoutWhitespace(mapping);
        for (String mappingCase :
                new String[] {
                    "caseNEVER->GL_NEVER",
                    "caseLESS->GL_LESS",
                    "caseEQUAL->GL_EQUAL",
                    "caseLEQUAL->GL_LEQUAL",
                    "caseGREATER->GL_GREATER",
                    "caseNOTEQUAL->GL_NOTEQUAL",
                    "caseGEQUAL->GL_GEQUAL",
                    "caseALWAYS->GL_ALWAYS"
                }) {
            assertTrue(compactMapping.contains(mappingCase), mappingCase);
        }

        String compactCaptureMapping = withoutWhitespace(captureMapping);
        for (String mappingCase :
                new String[] {
                    "caseGL_NEVER->DepthFunction.NEVER",
                    "caseGL_LESS->DepthFunction.LESS",
                    "caseGL_EQUAL->DepthFunction.EQUAL",
                    "caseGL_LEQUAL->DepthFunction.LEQUAL",
                    "caseGL_GREATER->DepthFunction.GREATER",
                    "caseGL_NOTEQUAL->DepthFunction.NOTEQUAL",
                    "caseGL_GEQUAL->DepthFunction.GEQUAL",
                    "caseGL_ALWAYS->DepthFunction.ALWAYS"
                }) {
            assertTrue(compactCaptureMapping.contains(mappingCase), mappingCase);
        }
        assertTrue(
                compactCaptureMapping.contains(
                        "default->thrownewIllegalStateException(\"unsupportedOpenGLdepthfunction:\"+depthFunction)"),
                compactCaptureMapping);
    }

    private static String methodBody(String source, String methodName) {
        int methodNameIndex = source.indexOf(" " + methodName + "(");
        assertTrue(methodNameIndex >= 0, "Missing method " + methodName);
        int openingBrace = source.indexOf('{', methodNameIndex);
        assertTrue(openingBrace >= 0, "Missing body for " + methodName);
        int depth = 1;
        for (int index = openingBrace + 1; index < source.length(); index++) {
            char value = source.charAt(index);
            if (value == '{') {
                depth++;
            } else if (value == '}') {
                depth--;
                if (depth == 0) {
                    return source.substring(openingBrace + 1, index);
                }
            }
        }
        throw new AssertionError("Unclosed method " + methodName);
    }

    private static String methodBodyFromLastDeclaration(String source, String methodName) {
        int methodNameIndex = source.lastIndexOf(" " + methodName + "(");
        assertTrue(methodNameIndex >= 0, "Missing method " + methodName);
        int openingBrace = source.indexOf('{', methodNameIndex);
        assertTrue(openingBrace >= 0, "Missing body for " + methodName);
        int depth = 1;
        for (int index = openingBrace + 1; index < source.length(); index++) {
            char value = source.charAt(index);
            if (value == '{') {
                depth++;
            } else if (value == '}') {
                depth--;
                if (depth == 0) {
                    return source.substring(openingBrace + 1, index);
                }
            }
        }
        throw new AssertionError("Unclosed method " + methodName);
    }

    private static void assertInOrder(String source, String... tokens) {
        String compactSource = withoutWhitespace(source);
        int cursor = -1;
        for (String token : tokens) {
            int next =
                    compactSource.indexOf(
                            withoutWhitespace(token), cursor + 1);
            assertTrue(next >= 0, "Missing or out-of-order token: " + token);
            cursor = next;
        }
    }

    private static String withoutWhitespace(String value) {
        return value.replaceAll("\\s+", "");
    }
}
