package com.gaia.tools.model;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static com.gaia.tools.model.PrimitiveAttributesTest.*;
import static org.junit.jupiter.api.Assertions.*;

class MaterialTextureChecksTest {
    static ObjectNode material(SemanticFixtures f) { f.primitive().put("material",0);return f.json.putArray("materials").addObject(); }
    static ObjectNode textured(SemanticFixtures f) throws Exception {
        f.uv();f.json.putArray("textures").addObject().put("source",0);
        f.image(EmbeddedImagesTest.image("png",2,2),"image/png");
        return material(f).putObject("pbrMetallicRoughness").putObject("baseColorTexture").put("index",0);
    }
    @Test void validDefaultAndScalarPbrArePreservedWithoutRegistryLookup() throws Exception {
        var f=new SemanticFixtures();var m=material(f);m.put("name","not-a-Gaia-registry-id");
        var p=m.putObject("pbrMetallicRoughness");p.put("metallicFactor",0.2).put("roughnessFactor",0.7);
        p.putArray("baseColorFactor").add(0.1).add(0.2).add(0.3).add(1);
        var r=validate(f);assertEquals(ValidationReport.Outcome.PASS,r.report().outcome());
        var actual=r.snapshot().orElseThrow().materials().get(0);
        assertArrayEquals(new double[]{0.1,0.2,0.3,1},actual.baseColor());assertEquals(0.2,actual.metallic());assertEquals(0.7,actual.roughness());
        var defaults=new SemanticFixtures();material(defaults);
        assertEquals(1,validate(defaults).snapshot().orElseThrow().materials().get(0).metallic());
    }
    @ParameterizedTest @ValueSource(strings={"normalTexture","occlusionTexture","emissiveTexture","metallicRoughnessTexture","emissiveFactor","alphaMode","doubleSided"})
    void appearanceOutsideSubsetFails(String property) throws Exception {
        var f=new SemanticFixtures();var m=material(f);
        switch(property) {
            case "emissiveFactor" -> m.putArray(property).add(0.1).add(0).add(0);
            case "alphaMode" -> m.put(property,"BLEND");
            case "doubleSided" -> m.put(property,true);
            case "metallicRoughnessTexture" -> m.putObject("pbrMetallicRoughness").putObject(property).put("index",0);
            default -> m.putObject(property).put("index",0);
        }
        failure(validate(f),"UNSUPPORTED_MATERIAL","/materials/0");
    }
    @ParameterizedTest @ValueSource(ints={1,2,99})
    void nonzeroTextureCoordinateSetNeverFallsBackToUvZero(int set) throws Exception {
        var f=new SemanticFixtures();textured(f).put("texCoord",set);
        failure(validate(f),"UNSUPPORTED_TEXTURE_COORDINATE_SET","/materials/0/pbrMetallicRoughness/baseColorTexture/texCoord");
    }
    @Test void textureRequiresUvAndValidReferences() throws Exception {
        var f=new SemanticFixtures();textured(f);f.attributes().remove("TEXCOORD_0");
        failure(validate(f),"REQUIRED_ATTRIBUTE","/meshes/0/primitives/0/attributes/TEXCOORD_0");
        var invalid=new SemanticFixtures();textured(invalid).put("index",9);
        failure(validate(invalid),"INVALID_MATERIAL","/materials/0");
    }
    @ParameterizedTest @ValueSource(strings={"metallicFactor","roughnessFactor","baseColorFactor"})
    void outOfRangeFactorsRejectThroughAdmissionOrSemantics(String property) throws Exception {
        var f=new SemanticFixtures();var p=material(f).putObject("pbrMetallicRoughness");
        if(property.equals("baseColorFactor")) p.putArray(property).add(2).add(1).add(1).add(1);else p.put(property,-0.1);
        assertEquals(ValidationReport.Outcome.FAIL,validate(f).report().outcome());
    }
    @Test void badPrimitiveMaterialReferenceAndMaterialBudgetReject() throws Exception {
        var f=new SemanticFixtures();f.primitive().put("material",0);
        failure(validate(f),"INVALID_MATERIAL_REFERENCE","/meshes/0/primitives/0/material");
        var b=new SemanticFixtures();for(int i=0;i<9;i++)b.json.withArray("materials").addObject();
        failure(validate(b),"MATERIAL_LIMIT","/materials");
    }

    @Test void textureDeclarationBoundaryCountsEveryEntryAndPreservesSharedImage() throws Exception {
        var atLimit=new SemanticFixtures();declareImages(atLimit);declareTextures(atLimit,8,false);
        var accepted=validate(atLimit);
        assertEquals(ValidationReport.Outcome.PASS,accepted.report().outcome());
        assertEquals(8,accepted.snapshot().orElseThrow().textures().size());
        assertEquals(1,accepted.snapshot().orElseThrow().images().size());

        var over=new SemanticFixtures();declareImages(over);declareTextures(over,9,false);
        failure(validate(over),"TEXTURE_LIMIT","/textures");
    }

    @Test void unusedNinthTextureStillFailsBeforeEntryProjection() throws Exception {
        var f=new SemanticFixtures();declareImages(f);declareTextures(f,9,false);
        f.uv();
        material(f).putObject("pbrMetallicRoughness").putObject("baseColorTexture").put("index",0);
        ((ObjectNode)f.json.withArray("textures").get(8)).put("source",99);

        var result=validate(f);
        failure(result,"TEXTURE_LIMIT","/textures");
        assertEquals(1,result.report().diagnostics().size(),
                "limit must reject before projecting individual texture entries");
    }

    @Test void omittedSamplerUsesDefaultWithoutDeclaringOne() throws Exception {
        var f=new SemanticFixtures();declareImages(f);declareTextures(f,1,false);
        var result=validate(f);
        assertEquals(ValidationReport.Outcome.PASS,result.report().outcome());
        assertNull(result.snapshot().orElseThrow().textures().get(0).minFilter());
    }

    @Test void samplerDeclarationBoundaryCountsReferencedAndUnusedEntries() throws Exception {
        var atLimit=new SemanticFixtures();declareImages(atLimit);declareSamplers(atLimit,8);declareTextures(atLimit,1,true);
        assertEquals(ValidationReport.Outcome.PASS,validate(atLimit).report().outcome());

        var over=new SemanticFixtures();declareImages(over);declareSamplers(over,9);declareTextures(over,1,true);
        failure(validate(over),"SAMPLER_LIMIT","/samplers");
    }

    @Test void combinedTextureAndSamplerBoundariesAreIndependent() throws Exception {
        var atLimit=new SemanticFixtures();declareImages(atLimit);declareSamplers(atLimit,8);declareTextures(atLimit,8,true);
        var accepted=validate(atLimit);
        assertEquals(ValidationReport.Outcome.PASS,accepted.report().outcome());
        assertEquals(8,accepted.snapshot().orElseThrow().textures().size());

        var textureOver=new SemanticFixtures();declareImages(textureOver);declareSamplers(textureOver,8);declareTextures(textureOver,9,true);
        failure(validate(textureOver),"TEXTURE_LIMIT","/textures");

        var samplerOver=new SemanticFixtures();declareImages(samplerOver);declareSamplers(samplerOver,9);declareTextures(samplerOver,8,true);
        failure(validate(samplerOver),"SAMPLER_LIMIT","/samplers");
    }

    private static void declareImages(SemanticFixtures fixture) throws Exception {
        fixture.image(EmbeddedImagesTest.image("png",1,1),"image/png");
    }

    private static void declareSamplers(SemanticFixtures fixture,int count) {
        var samplers=fixture.json.putArray("samplers");
        for(int i=0;i<count;i++) samplers.addObject();
    }

    private static void declareTextures(SemanticFixtures fixture,int count,boolean explicitSamplers) {
        var textures=fixture.json.putArray("textures");
        for(int i=0;i<count;i++) {
            var texture=textures.addObject().put("source",0);
            if(explicitSamplers) texture.put("sampler",Math.min(i,7));
        }
    }
}
