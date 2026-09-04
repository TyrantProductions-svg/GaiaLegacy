package com.gaia.tools.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static com.gaia.tools.model.GlbFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

class GlbJsonSecurityTest {
    @ParameterizedTest
    @ValueSource(strings = {"buffer.bin", "../secret", "file:///secret", "https://host/x",
            "//host/x", "data:application/octet-stream;base64,AAAA", "", "C:/secret"})
    void everyUriFormIsRejectedBeforeLibraryDecode(String uri) {
        rejectJson("{\"asset\":{\"version\":\"2.0\"},\"buffers\":[{\"uri\":\"" + uri + "\"}]}",
                "URI_FORBIDDEN");
    }

    @Test
    void escapedKeysNestedExtrasAndNonStringUriCannotBypassBoundary() {
        for (String body : new String[]{"\"uri\":null", "\"\\u0075ri\":\"hidden.bin\"",
                "\"uri\":{}", "\"uri\":123"}) {
            rejectJson("{\"asset\":{\"version\":\"2.0\"},\"extras\":{\"nested\":[{" + body + "}]}}",
                    "URI_FORBIDDEN");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"extensionsUsed\":[\"KHR_draco_mesh_compression\"]",
            "\"extensionsRequired\":[\"KHR_materials_unlit\"]", "\"extensions\":{\"ANY\":{}}",
            "\"extensionsUsed\":null", "\"extensionsRequired\":{}", "\"extensions\":[]",
            "\"extras\":{\"extensions\":{\"nested\":{}}}"})
    void unsupportedOrMalformedExtensionDeclarationIsRejected(String fields) {
        rejectJson("{\"asset\":{\"version\":\"2.0\"}," + fields + "}", "EXTENSION_FORBIDDEN");
    }

    @Test
    void emptyExtensionDeclarationsRemainLegalAndUnicodeNamesRemainLegal() throws Exception {
        String json = "{\"asset\":{\"version\":\"2.0\",\"minVersion\":\"2.0\"},"
                + "\"extensionsUsed\":[],\"extensionsRequired\":[],\"extensions\":{},"
                + "\"nodes\":[{\"name\":\"GAIA_ASSET_ROOT\",\"extras\":{\"note\":\"地形\"}}]}";
        assertNotNull(GlbPreflight.read(new ByteArrayInputStream(glb(json))));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"asset\":{}}", "{\"asset\":{\"version\":\"1.0\"}}",
            "{\"asset\":{\"version\":\"2.1\"}}", "{\"asset\":{\"version\":2}}",
            "{\"asset\":[]}", "{\"asset\":{\"version\":\"2.0\",\"minVersion\":\"2.1\"}}"})
    void onlyExplicitV2AssetVersionIsAdmitted(String json) { rejectJson(json, "ASSET_VERSION"); }

    @ParameterizedTest
    @ValueSource(strings = {"[]", "null", "{", "{\"asset\":{\"version\":\"2.0\",}}",
            "{\"asset\":{\"version\":\"2.0\",\"version\":\"2.0\"}}",
            "{\"asset\":{\"version\":\"2.0\"}} {}",
            "{/* comment */\"asset\":{\"version\":\"2.0\"}}",
            "{\"asset\":{\"version\":\"2.0\"},\"extras\":NaN}"})
    void rejectsAmbiguousOrMalformedJson(String json) { rejectJson(json, "JSON_INVALID"); }

    @Test
    void rejectsMalformedUtf8BomAndNulPadding() {
        reject(container(chunk(JSON, new byte[]{'{', '"', (byte) 0xC0, (byte) 0xAF, '"', ':', '0', '}'})),
                "JSON_INVALID");
        rejectJson("\uFEFF" + MINIMAL, "JSON_INVALID");
        rejectJson(MINIMAL + "\0", "JSON_INVALID");
    }

    @Test
    void boundsNestingTokenCountAndScalarSizeBeforeJgltf() {
        rejectJson("{\"asset\":{\"version\":\"2.0\"},\"extras\":"
                + "[".repeat(33) + "0" + "]".repeat(33) + "}", "JSON_LIMIT");
        rejectJson("{\"asset\":{\"version\":\"2.0\"},\"extras\":["
                + "0,".repeat(100_000) + "0]}", "JSON_LIMIT");
        rejectJson("{\"asset\":{\"version\":\"2.0\"},\"extras\":\""
                + "x".repeat(4097) + "\"}", "JSON_LIMIT");
        rejectJson("{\"asset\":{\"version\":\"2.0\"},\"" + "x".repeat(4097) + "\":0}",
                "JSON_LIMIT");
        rejectJson("{\"asset\":{\"version\":\"2.0\"},\"extras\":" + "9".repeat(65) + "}",
                "JSON_LIMIT");
    }

    @Test
    void toleratesUsefulBoundedExtrasWithoutImplementingSemanticValidation() throws Exception {
        String json = "{\"asset\":{\"version\":\"2.0\"},\"extras\":{\"note\":\""
                + "x".repeat(4096) + "\",\"sample\":[1,true,false,null]}}";
        assertNotNull(GlbPreflight.read(new ByteArrayInputStream(glb(json))));
    }

    private static void rejectJson(String json, String code) { reject(glb(json), code); }

    private static void reject(byte[] bytes, String code) {
        var failure = assertThrows(PreflightException.class,
                () -> GlbPreflight.read(new ByteArrayInputStream(bytes)));
        assertEquals(code, failure.code().name());
        assertTrue(failure.getMessage().length() < 100);
        assertNull(failure.getCause(), "Do not retain an exception containing untrusted JSON");
    }
}
