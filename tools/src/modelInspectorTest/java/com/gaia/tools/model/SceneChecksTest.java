package com.gaia.tools.model;

import de.javagl.jgltf.impl.v2.GlTF;
import de.javagl.jgltf.impl.v2.Node;
import de.javagl.jgltf.impl.v2.Scene;
import org.junit.jupiter.api.Test;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class SceneChecksTest {
    private static GlTF triangle() throws Exception { return GlbAdmission.admit(
            new ByteArrayInputStream(GlbFixtures.triangle())).document(); }
    private static ValidationReport check(GlTF doc) {
        var log=new ValidationReport.Collector(); SceneChecks.analyze(doc,log); return log.report();
    }

    @Test void rootAndRigidChildTransformsProduceInstances() throws Exception {
        var doc=triangle(); Node root=doc.getNodes().get(0); root.setMesh(null);
        root.setTranslation(new double[]{4,5,6}); root.setChildren(List.of(1));
        Node child=new Node(); child.setMesh(0); child.setRotation(new double[]{0,0,1,0}); doc.addNodes(child);
        var log=new ValidationReport.Collector(); var scene=SceneChecks.analyze(doc,log);
        assertEquals(ValidationReport.Outcome.PASS, log.report().outcome());
        assertEquals(2,scene.depth()); assertEquals(1,scene.instances().size());
        assertArrayEquals(new double[]{3,5,6},scene.instances().get(0).transform().point(1,0,0),1e-12);
    }

    @Test void repeatedMeshUsesTwoInstancesNotTwoMeshArrays() throws Exception {
        var doc=triangle(); Node child=new Node(); child.setMesh(0); doc.addNodes(child);
        doc.getNodes().get(0).setChildren(List.of(1));
        var log=new ValidationReport.Collector(); var scene=SceneChecks.analyze(doc,log);
        assertEquals(2,scene.instances().size());
        assertTrue(scene.instances().stream().allMatch(i -> i.mesh()==0));
        assertEquals(ValidationReport.Outcome.PASS,log.report().outcome());
    }

    @Test void missingAndInvalidSceneRootAndRootNameFail() throws Exception {
        for(int kind=0;kind<5;kind++) {
            var doc=triangle();
            switch(kind) {
                case 0 -> doc.setScene(null);
                case 1 -> doc.setScene(3);
                case 2 -> doc.getScenes().get(0).setNodes(null);
                case 3 -> doc.getScenes().get(0).setNodes(List.of(2));
                case 4 -> doc.getNodes().get(0).setName("not-root");
            }
            assertEquals(ValidationReport.Outcome.FAIL,check(doc).outcome());
        }
    }

    @Test void multipleRootsBadChildrenBadMeshesCyclesAndSharedParentsFail() throws Exception {
        for(int kind=0;kind<5;kind++) {
            var doc=triangle(); Node child=new Node(); child.setMesh(0); doc.addNodes(child);
            switch(kind) {
                case 0 -> doc.getScenes().get(0).setNodes(List.of(0,1));
                case 1 -> doc.getNodes().get(0).setChildren(List.of(5));
                case 2 -> doc.getNodes().get(0).setMesh(2);
                case 3 -> {doc.getNodes().get(0).setChildren(List.of(1)); child.setChildren(List.of(0));}
                case 4 -> doc.getNodes().get(0).setChildren(List.of(1,1));
            }
            assertEquals(ValidationReport.Outcome.FAIL,check(doc).outcome());
        }
    }

    @Test void depthExactlySixteenPassesAndSeventeenFails() throws Exception {
        var doc=chain(16); assertEquals(ValidationReport.Outcome.PASS,check(doc).outcome());
        assertEquals(ValidationReport.Outcome.FAIL,check(chain(17)).outcome());
    }

    @Test void nodeCountExactlySixtyFourPassesAndSixtyFiveFails() throws Exception {
        var doc=triangle(); var children=new ArrayList<Integer>();
        for(int i=1;i<64;i++){Node n=new Node();doc.addNodes(n);children.add(i);}
        doc.getNodes().get(0).setChildren(children);
        assertEquals(ValidationReport.Outcome.PASS,check(doc).outcome());
        doc.addNodes(new Node());children.add(64);
        assertEquals(ValidationReport.Outcome.FAIL,check(doc).outcome());
    }

    @Test void unreachableDeclarationsFailButReachableInstanceAccountingSurvives() throws Exception {
        var doc=triangle(); doc.addMeshes(doc.getMeshes().get(0)); doc.addNodes(new Node());
        var log=new ValidationReport.Collector(); var scene=SceneChecks.analyze(doc,log);
        assertNotNull(scene); assertEquals(1,scene.instances().size());
        assertEquals(ValidationReport.Outcome.FAIL,log.report().outcome());
        assertTrue(log.report().diagnostics().stream().anyMatch(d -> d.code().equals("UNREACHABLE_MESH")));
        assertTrue(log.report().diagnostics().stream().anyMatch(d -> d.code().equals("UNREACHABLE_NODE")));
    }

    private static GlTF chain(int size) throws Exception {
        var doc=triangle(); doc.getNodes().get(0).setMesh(null);
        for(int i=1;i<size;i++){doc.getNodes().get(i-1).setChildren(List.of(i)); doc.addNodes(new Node());}
        doc.getNodes().get(size-1).setMesh(0); return doc;
    }
    @Test void nondefaultSceneCannotDeclareAParentedNodeAsRoot() throws Exception {
        var f=new SemanticFixtures();f.node().remove("mesh");f.node().putArray("children").add(1);
        f.json.withArray("nodes").addObject().put("mesh",0);
        f.json.withArray("scenes").addObject().putArray("nodes").add(1);
        PrimitiveAttributesTest.failure(PrimitiveAttributesTest.validate(f),"SCENE_INVALID","/scenes");
    }
    @Test void defaultRootCannotHaveParentEvenOutsideDefaultScene() throws Exception {
        var f=new SemanticFixtures();f.json.withArray("nodes").addObject().putArray("children").add(0);
        PrimitiveAttributesTest.failure(PrimitiveAttributesTest.validate(f),"SCENE_INVALID","/scenes");
    }
    @Test void sharedParentlessRootInOtherSceneDoesNotExpandAgain() throws Exception {
        var f=new SemanticFixtures();f.json.withArray("scenes").addObject().putArray("nodes").add(0);
        var r=PrimitiveAttributesTest.validate(f);assertEquals(ValidationReport.Outcome.PASS,r.report().outcome());
        assertEquals(1,r.statistics().expandedTriangleCount());assertEquals(3,r.statistics().expandedVertexCount());
        assertEquals(1,r.snapshot().orElseThrow().nodes().size());
    }
    @Test void nondefaultRootListMustNotContainDuplicateIndices() throws Exception {
        var f=new SemanticFixtures();f.json.withArray("scenes").addObject().putArray("nodes").add(0).add(0);
        PrimitiveAttributesTest.failure(PrimitiveAttributesTest.validate(f),"SCENE_INVALID","/scenes");
    }
}
