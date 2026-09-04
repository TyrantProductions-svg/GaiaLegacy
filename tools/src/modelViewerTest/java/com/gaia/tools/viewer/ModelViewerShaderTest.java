package com.gaia.tools.viewer;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class ModelViewerShaderTest {
    @Test
    void previewShadersRemainGlsl410AndExposeOnlyTheDiagnosticContract() throws IOException {
        String vertex = resource("/assets/gaia/model-viewer/preview.vert");
        String fragment = resource("/assets/gaia/model-viewer/preview.frag");

        assertTrue(vertex.contains("#version 410 core"));
        assertTrue(fragment.contains("#version 410 core"));
        assertTrue(vertex.contains("layout(location = 0) in vec3 position"));
        assertTrue(vertex.contains("layout(location = 1) in vec3 normal"));
        assertTrue(vertex.contains("layout(location = 2) in vec2 texCoord"));
        assertTrue(vertex.contains("uniform mat4 projection"));
        assertTrue(vertex.contains("uniform mat4 modelView"));
        assertTrue(fragment.contains("uniform sampler2D baseColorTexture"));
        assertTrue(fragment.contains("uniform int textured"));
        assertTrue(fragment.contains("uniform int unlit"));
        assertFalse(vertex.contains("430"));
        assertFalse(fragment.contains("430"));
        assertFalse(fragment.contains("discard"));
    }

    private static String resource(String path) throws IOException {
        try (var stream = ModelViewerShaderTest.class.getResourceAsStream(path)) {
            if (stream == null) throw new IOException("missing test resource " + path);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
