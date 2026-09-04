package com.gaia.tools.model;

import java.io.ByteArrayInputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class PrimitiveAttributesTest {
    @ParameterizedTest @ValueSource(strings={"_GAIA_TEST","_UV.custom","_温度","_"})
    void customPrefixIsValidSyntaxButNeverSupported(String semantic) throws Exception {
        var f=new SemanticFixtures();f.attributes().put(semantic,0);
        failure(validate(f),"UNSUPPORTED_ATTRIBUTE","/meshes/0/primitives/0/attributes/"+semantic);
    }
    static GaiaGlbValidator.Result validate(SemanticFixtures f) throws Exception {
        return GaiaGlbValidator.validate(new ByteArrayInputStream(f.bytes()));
    }
    static void failure(GaiaGlbValidator.Result result,String code,String path) {
        assertEquals(ValidationReport.Outcome.FAIL,result.report().outcome());
        assertTrue(result.snapshot().isEmpty());
        assertTrue(result.report().diagnostics().stream().anyMatch(d->d.code().equals(code)&&d.path().equals(path)),result.report().toString());
    }
    @Test void noColorValidTrianglePublishesGeometry() throws Exception {
        var r=validate(new SemanticFixtures());
        assertEquals(ValidationReport.Outcome.PASS,r.report().outcome());assertTrue(r.snapshot().isPresent());
    }
    @ParameterizedTest @ValueSource(strings={"COLOR_0","COLOR_1"})
    void colorsFailWithoutSnapshot(String color) throws Exception {
        var f=new SemanticFixtures();f.attributes().put("COLOR_0",1).put(color,1);
        failure(validate(f),"UNSUPPORTED_VERTEX_COLOR","/meshes/0/primitives/0/attributes/"+color);
    }
    @Test void colorWithOtherwiseValidBaseColorStillFails() throws Exception {
        var f=new SemanticFixtures();f.attributes().put("COLOR_0",1);f.primitive().put("material",0);
        f.json.putArray("materials").addObject().putObject("pbrMetallicRoughness").putArray("baseColorFactor").add(1).add(1).add(1).add(1);
        failure(validate(f),"UNSUPPORTED_VERTEX_COLOR","/meshes/0/primitives/0/attributes/COLOR_0");
    }
    @ParameterizedTest @ValueSource(strings={"TANGENT","TEXCOORD_1","JOINTS_0","WEIGHTS_0","_ATTRIBUTE"})
    void unsupportedSemanticsRejectBeforeAccessorExpansion(String semantic) throws Exception {
        var f=new SemanticFixtures();f.uv();f.attributes().put(semantic,Integer.MAX_VALUE);
        failure(validate(f),"UNSUPPORTED_ATTRIBUTE","/meshes/0/primitives/0/attributes/"+semantic);
    }
    @ParameterizedTest @ValueSource(strings={"COLOR_01","COLOR_-1","COLOR","TEXCOORD_X","WHAT"})
    void malformedStandardSemanticsAreInvalidRatherThanUnsupported(String semantic) throws Exception {
        var f=new SemanticFixtures();f.attributes().put(semantic,1);
        failure(validate(f),"INVALID_GLTF_ATTRIBUTE","/meshes/0/primitives/0/attributes/"+semantic);
    }
    @Test void validUnusedUvIsPreserved() throws Exception {
        var f=new SemanticFixtures();f.uv();var r=validate(f);
        assertEquals(ValidationReport.Outcome.PASS,r.report().outcome());
        assertArrayEquals(new double[]{0,0,1,0,0,1},r.snapshot().orElseThrow().primitives().get(0).texCoords());
    }
    @Test void diagnosticOrderDoesNotDependOnAttributeInsertionOrder() throws Exception {
        var a=new SemanticFixtures();a.attributes().put("COLOR_0",1).put("TANGENT",1);
        var b=new SemanticFixtures();b.attributes().put("TANGENT",1).put("COLOR_0",1);
        assertEquals(validate(a).report(),validate(b).report());
    }
}
