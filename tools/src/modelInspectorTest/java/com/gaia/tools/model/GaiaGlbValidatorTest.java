package com.gaia.tools.model;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayInputStream;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static com.gaia.tools.model.PrimitiveAttributesTest.*;
import static org.junit.jupiter.api.Assertions.*;

class GaiaGlbValidatorTest {
    @ParameterizedTest @ValueSource(strings={"skin","camera","nodeWeights","meshWeights","targets","animations","skins","cameras"})
    void deferredSceneFeaturesCannotBeDiscardedInSuccessfulSnapshot(String feature) throws Exception {
        var f=new SemanticFixtures();
        switch(feature) {
            case "skin","camera" -> f.node().put(feature,0);
            case "nodeWeights" -> f.node().putArray("weights").add(0.5);
            case "meshWeights" -> ((ObjectNode)f.json.at("/meshes/0")).putArray("weights").add(0.5);
            case "targets" -> f.primitive().putArray("targets").addObject().put("POSITION",0);
            default -> f.json.putArray(feature).addObject();
        }
        var r=validate(f);assertEquals(ValidationReport.Outcome.FAIL,r.report().outcome(),feature);assertTrue(r.snapshot().isEmpty());
    }
    @Test void requiredPositionBoundsAndMalformedAccessorBoundsReject() throws Exception {
        var f=new SemanticFixtures();f.accessor(0).remove("min");
        assertEquals(ValidationReport.Outcome.FAIL,validate(f).report().outcome());
        var bad=new SemanticFixtures();bad.accessor(1).putArray("max").add(1);
        assertEquals(ValidationReport.Outcome.FAIL,validate(bad).report().outcome());
    }
    @Test void incompatibleBufferViewUsesReject() throws Exception {
        var f=new SemanticFixtures();f.accessor(1).put("bufferView",0).put("byteOffset",36);
        ((ObjectNode)f.json.at("/bufferViews/0")).put("byteLength",72);
        // Two vertex accessors in one view require explicit byteStride under core glTF.
        assertEquals(ValidationReport.Outcome.FAIL,validate(f).report().outcome());
        var image=EmbeddedImagesTest.textured("png",0);image.accessor(0).put("bufferView",4);
        assertEquals(ValidationReport.Outcome.FAIL,validate(image).report().outcome());
    }
    @Test void normalsAndPositionCannotUseUnsignedIntOutsideIndices() throws Exception {
        var f=new SemanticFixtures();f.accessor(1).put("componentType",5125);
        assertEquals(ValidationReport.Outcome.FAIL,validate(f).report().outcome());
    }
    private static SemanticFixtures vertices(int count,int instances) throws Exception {
        var f=new SemanticFixtures();float[] pos=new float[count*3];pos[3]=1;pos[7]=1;
        int p=f.floats(pos,"VEC3",count);f.accessor(p).putArray("min").add(0).add(0).add(0);f.accessor(p).putArray("max").add(1).add(1).add(0);
        float[] normals=new float[count*3];for(int i=2;i<normals.length;i+=3)normals[i]=1;
        f.attributes().put("POSITION",p).put("NORMAL",f.floats(normals,"VEC3",count));
        if(instances==2)f.twoInstances();return f;
    }
    @Test void uniqueAndExpandedVertexBoundariesAreIndependent() throws Exception {
        var exact=validate(vertices(30000,1));assertEquals(ValidationReport.Outcome.PASS,exact.report().outcome());
        var over=validate(vertices(30001,1));failure(over,"UNIQUE_VERTEX_LIMIT","/");assertEquals(30001,over.statistics().uniqueVertexCount());
        assertEquals(ValidationReport.Outcome.PASS,validate(vertices(15000,2)).report().outcome());
        var expanded=validate(vertices(15001,2));failure(expanded,"EXPANDED_VERTEX_LIMIT","/");assertEquals(30002,expanded.statistics().expandedVertexCount());
    }
    @Test void actualRotatedBoundsIncludeTranslation() throws Exception {
        var f=new SemanticFixtures();f.node().putArray("rotation").add(0).add(0).add(Math.sqrt(.5)).add(Math.sqrt(.5));
        f.node().putArray("translation").add(4).add(5).add(6);
        var bounds=validate(f).snapshot().orElseThrow().bounds();
        assertArrayEquals(new double[]{3,5,6},bounds.min(),1e-12);assertArrayEquals(new double[]{4,6,6},bounds.max(),1e-12);
    }
    @Test void snapshotDefendsAllArraysAndCollectionsFromCallerMutation() throws Exception {
        var f=EmbeddedImagesTest.textured("png",0);var result=validate(f);var s=result.snapshot().orElseThrow();
        var primitive=s.primitives().get(0);primitive.positions()[3]=999;primitive.normals()[2]=999;primitive.texCoords()[2]=999;primitive.indices()[0]=99;
        s.nodes().get(0).worldTransform()[0]=999;s.bounds().min()[0]=999;s.materials().get(0).baseColor()[0]=999;s.images().get(0).rgba()[0]=0;
        assertEquals(1,primitive.positions()[3]);assertEquals(1,primitive.normals()[2]);assertEquals(1,primitive.texCoords()[2]);assertEquals(0,primitive.indices()[0]);
        assertEquals(1,s.nodes().get(0).worldTransform()[0]);assertEquals(0,s.bounds().min()[0]);assertEquals(1,s.materials().get(0).baseColor()[0]);assertEquals(64,s.images().get(0).rgba()[0]);
        assertThrows(UnsupportedOperationException.class,()->s.primitives().clear());assertThrows(UnsupportedOperationException.class,()->result.report().diagnostics().clear());
        Arrays.fill(f.bin,(byte)0);assertEquals(1,primitive.positions()[3]);
    }
    @Test void admissionFailureNeverReachesSemanticPublication() throws Exception {
        var r=GaiaGlbValidator.validate(new ByteArrayInputStream(GlbFixtures.glb("{\"asset\":{\"version\":\"2.0\"},\"extras\":{\"uri\":\"file:///no-read\"}}")));
        failure(r,"ADMISSION_URI_FORBIDDEN","/");
    }
    @Test void diagnosticFloodRetainsFailAndNoSnapshot() throws Exception {
        var f=new SemanticFixtures();for(int i=0;i<100;i++)f.attributes().put("_ATTR"+i,0);
        var r=validate(f);assertEquals(64,r.report().diagnostics().size());assertTrue(r.report().truncated());assertEquals(ValidationReport.Outcome.FAIL,r.report().outcome());assertTrue(r.snapshot().isEmpty());
    }
}
