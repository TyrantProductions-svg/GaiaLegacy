package com.gaia.tools.model;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static com.gaia.tools.model.PrimitiveAttributesTest.*;
import static org.junit.jupiter.api.Assertions.*;

class GeometryChecksTest {
    @Test void indexedAndNonindexedTrianglesUseActualBoundsNotMetadata() throws Exception {
        for(boolean indexed:new boolean[]{true,false}) {
            var f=new SemanticFixtures();if(!indexed) f.primitive().remove("indices");
            f.accessor(0).putArray("min").add(-99).add(-99).add(-99);
            f.accessor(0).putArray("max").add(99).add(99).add(99);
            f.node().putArray("translation").add(4).add(5).add(6);
            var r=validate(f);assertEquals(ValidationReport.Outcome.PASS,r.report().outcome());
            assertEquals(1,r.statistics().uniqueTriangleCount());assertEquals(3,r.statistics().uniqueVertexCount());
            assertArrayEquals(new double[]{4,5,6},r.snapshot().orElseThrow().bounds().min());
            assertArrayEquals(new double[]{5,6,6},r.snapshot().orElseThrow().bounds().max());
        }
    }
    @ParameterizedTest @ValueSource(strings={"POSITION","NORMAL"})
    void requiredAttributesReject(String attr) throws Exception {
        var f=new SemanticFixtures();f.attributes().remove(attr);
        failure(validate(f),"REQUIRED_ATTRIBUTE","/meshes/0/primitives/0/attributes/"+attr);
    }
    @ParameterizedTest @ValueSource(ints={0,1,2,3,5,6})
    void nonTrianglesReject(int mode) throws Exception {
        var f=new SemanticFixtures();f.primitive().put("mode",mode);
        failure(validate(f),"UNSUPPORTED_PRIMITIVE_MODE","/meshes/0/primitives/0");
    }
    @ParameterizedTest @ValueSource(strings={"positionNaN","normalNaN","zeroNormal","nonUnitNormal","degenerate","winding","badIndex","countMismatch","fractionalTriangle","badUv"})
    void actualInvalidGeometryNeverPublishes(String kind) throws Exception {
        var f=new SemanticFixtures();
        switch(kind) {
            case "positionNaN" -> f.scalar(0,Float.NaN);
            case "normalNaN" -> f.scalar(36,Float.POSITIVE_INFINITY);
            case "zeroNormal" -> f.scalar(44,0);
            case "nonUnitNormal" -> f.scalar(44,2);
            case "degenerate" -> f.scalar(12,0);
            case "winding" -> { f.scalar(44,-1);f.scalar(56,-1);f.scalar(68,-1); }
            case "badIndex" -> f.bin[72]=3;
            case "countMismatch" -> f.accessor(1).put("count",2);
            case "fractionalTriangle" -> f.accessor(2).put("count",2);
            case "badUv" -> {f.uv();f.scalar(80,Float.NaN);}
        }
        assertEquals(ValidationReport.Outcome.FAIL,validate(f).report().outcome(),kind);
        assertTrue(validate(f).snapshot().isEmpty());
    }
    @ParameterizedTest @ValueSource(ints={3999,4000,4001,10000,10001})
    void actualUniqueTriangleBoundaries(int triangles) throws Exception {
        var f=new SemanticFixtures();f.repeatTriangles(triangles);var r=validate(f);
        assertEquals(triangles,r.statistics().uniqueTriangleCount());
        assertEquals(triangles>10000?ValidationReport.Outcome.FAIL:triangles>4000?ValidationReport.Outcome.PASS_WITH_WARNINGS:ValidationReport.Outcome.PASS,r.report().outcome());
    }
    @Test void sixThousandTwiceCannotBypassExpandedHardLimit() throws Exception {
        var f=new SemanticFixtures();f.repeatTriangles(6000);f.twoInstances();var r=validate(f);
        assertEquals(6000,r.statistics().uniqueTriangleCount());assertEquals(12000,r.statistics().expandedTriangleCount());
        failure(r,"EXPANDED_TRIANGLE_LIMIT","/");
        assertTrue(r.report().diagnostics().stream().anyMatch(d->d.code().equals("UNIQUE_TRIANGLE_WARNING")));
    }
    @Test void threeThousandTwiceWarnsOnlyExpanded() throws Exception {
        var f=new SemanticFixtures();f.repeatTriangles(3000);f.twoInstances();var r=validate(f);
        assertEquals(3000,r.statistics().uniqueTriangleCount());assertEquals(6000,r.statistics().expandedTriangleCount());
        assertEquals(ValidationReport.Outcome.PASS_WITH_WARNINGS,r.report().outcome());
        assertEquals("EXPANDED_TRIANGLE_WARNING",r.report().diagnostics().get(0).code());
    }
    @Test void sharedAccessorCountsPerPrimitiveAndUnreachableDeclarationStillCounts() throws Exception {
        var f=new SemanticFixtures();((com.fasterxml.jackson.databind.node.ArrayNode)f.json.at("/meshes/0/primitives")).add(f.primitive().deepCopy());
        f.json.withArray("meshes").add(f.json.at("/meshes/0").deepCopy());var r=validate(f);
        assertEquals(4,r.statistics().uniqueTriangleCount());assertEquals(12,r.statistics().uniqueVertexCount());
        assertEquals(2,r.statistics().expandedTriangleCount());assertEquals(6,r.statistics().expandedVertexCount());
        failure(r,"UNREACHABLE_MESH","/meshes/1");
    }
}
