package com.gaia.tools.model;

import de.javagl.jgltf.model.AccessorFloatData;
import de.javagl.jgltf.model.GltfModels;
import de.javagl.jgltf.model.io.GltfAssetReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.atomic.AtomicInteger;

import static com.gaia.tools.model.GlbFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

class GlbAdmissionTest {
    @ParameterizedTest
    @ValueSource(strings = {
            "\"scene\":0.5", "\"scene\":0.0",
            "\"accessors\":[{\"componentType\":5126.5,\"count\":1,\"type\":\"VEC3\"}]",
            "\"accessors\":[{\"componentType\":5126,\"count\":1.75,\"type\":\"VEC3\"}]",
            "\"nodes\":[{\"children\":[0.5]}]",
            "\"meshes\":[{\"primitives\":[{\"attributes\":{\"POSITION\":0.5}}]}]"
    })
    void rejectsFloatingTokensForIntegerTargets(String fields) {
        assertMappingRejected(fields);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "\"nodes\":[{\"name\":123}]", "\"nodes\":[{\"name\":0.5}]",
            "\"nodes\":[{\"name\":false}]", "\"nodes\":[{\"name\":true}]"
    })
    void rejectsNonStringScalarsForTextualTargets(String fields) {
        assertMappingRejected(fields);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "\"scene\":\"0\"", "\"scene\":true",
            "\"accessors\":[{\"normalized\":\"true\"}]",
            "\"accessors\":[{\"normalized\":1}]",
            "\"accessors\":[{\"normalized\":0.5}]",
            "\"materials\":[{\"pbrMetallicRoughness\":{\"metallicFactor\":\"0.5\"}}]",
            "\"materials\":[{\"pbrMetallicRoughness\":{\"metallicFactor\":true}}]",
            "\"nodes\":[{\"translation\":[\"1\",0,0]}]",
            "\"nodes\":[{\"translation\":[true,0,0]}]",
            "\"accessors\":[{\"min\":[\"0\"]}]",
            "\"accessors\":[{\"min\":[false]}]"
    })
    void rejectsTypeIncompatibleNumericAndBooleanScalars(String fields) {
        assertMappingRejected(fields);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "\"nodes\":[{\"translation\":[\"NaN\",0,0]}]",
            "\"nodes\":[{\"translation\":[\"Infinity\",0,0]}]",
            "\"nodes\":[{\"translation\":[\"-Infinity\",0,0]}]",
            "\"nodes\":[{\"translation\":[\"INF\",0,0]}]",
            "\"materials\":[{\"pbrMetallicRoughness\":{\"metallicFactor\":\"NaN\"}}]"
    })
    void rejectsSpecialFloatingSpellingsThatAreStillJsonStrings(String fields) {
        assertMappingRejected(fields);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "\"scene\":\" \"",
            "\"meshes\":[{\"primitives\":[{\"attributes\":{\"POSITION\":\" \"}}]}]",
            "\"nodes\":[{\"translation\":[\" \",0,0]}]",
            "\"accessors\":[{\"min\":[\" \"]}]",
            "\"materials\":[{\"pbrMetallicRoughness\":{\"metallicFactor\":\" \"}}]",
            "\"accessors\":[{\"normalized\":\" \"}]"
    })
    void rejectsBlankStringsForNumericAndBooleanTargets(String fields) {
        assertMappingRejected(fields);
    }

    @Test
    void rejectsPackedStringForPrimitiveNumericArray() {
        assertMappingRejected("\"nodes\":[{\"translation\":\"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA\"}]");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "\"nodes\":[{\"translation\":[1.0,null,2.0]}]",
            "\"nodes\":[{\"rotation\":[0.0,null,0.0,1.0]}]",
            "\"nodes\":[{\"scale\":[1.0,null,1.0]}]",
            "\"nodes\":[{\"matrix\":[1,0,0,0,0,1,0,0,0,0,1,0,0,null,0,1]}]",
            "\"materials\":[{\"pbrMetallicRoughness\":{\"baseColorFactor\":[1,null,1,1]}}]",
            "\"nodes\":[{\"children\":[null]}]",
            "\"scenes\":[{\"nodes\":[null]}]"
    })
    void rejectsNullElementsInCoreNumericAndIndexArrays(String fields) {
        assertMappingRejected(fields);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "\"scene\":0,\"scenes\":[{\"nodes\":[0]}],\"nodes\":[{\"name\":\"GAIA_ASSET_ROOT\"}]",
            "\"nodes\":[{\"translation\":[0.25,1.5,-2.75]}]",
            "\"nodes\":[{\"translation\":[0,1,-2]}]",
            "\"nodes\":[{\"translation\":[1.0,0.0,2.0]}]",
            "\"nodes\":[{\"translation\":[1,0,2]}]",
            "\"materials\":[{\"doubleSided\":false,\"pbrMetallicRoughness\":{\"metallicFactor\":0.5,\"roughnessFactor\":1}}]",
            "\"accessors\":[{\"componentType\":5126,\"count\":1,\"type\":\"VEC3\",\"normalized\":false,\"min\":[0,0.5,-1]}]",
            "\"nodes\":[{\"name\":null}],\"materials\":[{\"doubleSided\":null,\"pbrMetallicRoughness\":{\"metallicFactor\":null}}]",
            "\"extensions\":{},\"extras\":{\"sample\":[1,0.5,true,false,null,\"note\"]}",
            "\"nodes\":[{\"name\":\"NaN\"},{\"name\":\"Infinity\"},{\"name\":\" \"}],\"extras\":[\"NaN\",\"Infinity\",\" \"]",
            "\"nodes\":[{\"translation\":null,\"weights\":[0,0.5]}]"
    })
    void preservesCompatibleScalarTokensAndOptionalNulls(String fields) throws Exception {
        var receipt = GlbAdmission.check(new ByteArrayInputStream(glb(withAsset(fields))));
        assertEquals("PREFLIGHT_AND_ASSET_MAPPING_ONLY", receipt.scope());
    }

    private static void assertMappingRejected(String fields) {
        var failure = assertThrows(PreflightException.class,
                () -> GlbAdmission.check(new ByteArrayInputStream(glb(withAsset(fields)))));
        assertEquals(PreflightException.Code.DECODE_REJECTED, failure.code());
        assertNull(failure.getCause());
        assertTrue(failure.getMessage().length() < 100);
    }

    private static String withAsset(String fields) {
        return "{\"asset\":{\"version\":\"2.0\"}," + fields + "}";
    }

    @Test
    void realJgltfAdmitsOwnedTriangleWithoutGraphicsOrRuntimeResources() throws Exception {
        byte[] bytes = triangle();
        var receipt = GlbAdmission.check(new ByteArrayInputStream(bytes));
        assertEquals("GAIA_GLB_HAND_TOOL_V0", receipt.profile());
        assertEquals("PREFLIGHT_AND_ASSET_MAPPING_ONLY", receipt.scope());
        assertEquals(bytes.length, receipt.byteLength());
        assertEquals(80, receipt.binaryByteLength());
        assertEquals(GlbPreflight.read(new ByteArrayInputStream(bytes)).sha256(), receipt.sha256());
    }

    @Test
    void actualLibraryCanDecodeKnownTriangleCoordinatesAndNormals() throws Exception {
        // This fixture is trusted test code. Gate A production must not expand arbitrary models.
        var asset = new GltfAssetReader().readWithoutReferences(new ByteArrayInputStream(triangle()));
        var model = GltfModels.create(asset);
        assertEquals(1, model.getMeshModels().size());
        assertEquals("GAIA_ASSET_ROOT", model.getNodeModels().get(0).getName());
        var primitive = model.getMeshModels().get(0).getMeshPrimitiveModels().get(0);
        assertEquals(4, primitive.getMode());
        assertEquals(3, primitive.getIndices().getCount());
        var positions = (AccessorFloatData) primitive.getAttributes().get("POSITION").getAccessorData();
        var normals = (AccessorFloatData) primitive.getAttributes().get("NORMAL").getAccessorData();
        assertEquals(1f, positions.get(1, 0));
        assertEquals(1f, positions.get(2, 1));
        assertEquals(0f, positions.get(2, 2));
        assertEquals(1f, normals.get(0, 2));
    }

    @Test
    void completeSecurityPassPrecedesEvenEarlierJgltfMappingErrors() {
        // buffers is deliberately the wrong shape. Decoding before finishing preflight
        // would produce DECODE_REJECTED instead of the security policy code.
        for (String suffix : new String[]{"\"extras\":{\"uri\":\"https://invalid.invalid/private\"}",
                "\"extensionsUsed\":[\"KHR_draco_mesh_compression\"]"}) {
            String json = "{\"asset\":{\"version\":\"2.0\"},\"buffers\":false," + suffix + "}";
            var failure = assertThrows(PreflightException.class,
                    () -> GlbAdmission.check(new ByteArrayInputStream(glb(json))));
            assertEquals(suffix.contains("uri") ? "URI_FORBIDDEN" : "EXTENSION_FORBIDDEN",
                    failure.code().name());
        }
    }

    @Test
    void decoderRejectionDoesNotEchoInputOrRetainAnUntrustedException() {
        byte[] invalid = glb("{\"asset\":{\"version\":\"2.0\"},\"buffers\":\"PRIVATE_MARKER\"}");
        var failure = assertThrows(PreflightException.class,
                () -> GlbAdmission.check(new ByteArrayInputStream(invalid)));
        assertEquals("DECODE_REJECTED", failure.code().name());
        assertFalse(failure.getMessage().contains("PRIVATE_MARKER"));
        assertNull(failure.getCause());
    }

    @Test
    void sourceIsReadOnceAndNotReopenedOrClosedByDecoder() throws Exception {
        AtomicInteger closes = new AtomicInteger();
        ByteArrayInputStream source = new ByteArrayInputStream(triangle()) {
            @Override public void close() { closes.incrementAndGet(); }
        };
        var receipt = GlbAdmission.check(source);
        assertEquals(0, source.available());
        assertEquals(0, closes.get());
        assertEquals(80, receipt.binaryByteLength());
    }

    @Test
    void sourceIoFailureRemainsAnIoFailureNotFalseAdmission() {
        InputStream source = new InputStream() {
            @Override public int read() throws IOException { throw new IOException("fixture IO"); }
        };
        var failure = assertThrows(IOException.class, () -> GlbAdmission.check(source));
        assertEquals("fixture IO", failure.getMessage());
    }
}
