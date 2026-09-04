package com.gaia.tools.viewer;

import java.security.MessageDigest;
import java.util.HexFormat;
import org.joml.Matrix4d;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ViewerCpuModelTest {
    @Test void packsPositionsNormalsAndAbsentUvWithoutChangingGeometry() throws Exception {
        var model = ViewerCpuModel.from(ViewerFixtures.snapshot(false,false));
        assertArrayEquals(new float[]{0,0,0,0,0,1,0,0, 1,0,0,0,0,1,0,0,
                0,1,0,0,0,1,0,0},model.primitives().get(0).vertices());
        assertArrayEquals(new int[]{0,1,2},model.primitives().get(0).indices());
        assertEquals(108,model.bufferBytes());
        assertEquals(1,model.triangleCount());
        assertFalse(model.textured(0));
    }

    @Test void preservesUvAndCanonicalImageWithIndependentSamplers() throws Exception {
        var model = ViewerCpuModel.from(ViewerFixtures.snapshot(true,false));
        assertArrayEquals(new float[]{0,0,0,0,0,1,0,0, 1,0,0,0,0,1,1,0,
                0,1,0,0,0,1,0,1},model.primitives().get(0).vertices());
        assertTrue(model.textured(0));
        assertEquals(1,model.images().size());
        assertArrayEquals(new byte[]{17,85,(byte)204,(byte)255},model.images().get(0).rgba());
        assertEquals(2,model.textures().size());
        assertEquals(9728,model.textures().get(0).magFilter());
        assertEquals(33071,model.textures().get(0).wrapS());
        assertEquals(33648,model.textures().get(0).wrapT());
        assertNull(model.textures().get(1).minFilter());
    }

    @Test void instancesReferencePackedGeometryAndKeepWorldTransforms() throws Exception {
        var model = ViewerCpuModel.from(ViewerFixtures.snapshot(false,true));
        assertEquals(1,model.primitives().size());
        assertEquals(2,model.draws().size());
        assertEquals(0,model.draws().get(0).primitive());
        assertEquals(0,model.draws().get(1).primitive());
        assertEquals(3,model.draws().get(0).worldTransform()[12]);
        assertEquals(2,model.draws().get(1).worldTransform()[13]);
        assertEquals(2,model.triangleCount());
        assertEquals(108,model.bufferBytes());
        assertArrayEquals(new double[]{3,0,0},model.bounds().min());
        assertArrayEquals(new double[]{4,3,0},model.bounds().max());
        var view = new Matrix4d().translation(-3,-2,-5);
        assertEquals(0,model.modelView(1,view).m30());
        assertEquals(0,model.modelView(1,view).m31());
        assertEquals(-5,model.modelView(1,view).m32());
    }

    @Test void multiplePrimitivesKeepMaterialAssignmentsWithoutInstanceCopies() throws Exception {
        var model = ViewerCpuModel.from(ViewerFixtures.twoPrimitives());
        assertEquals(2,model.primitives().size());
        assertEquals(4,model.draws().size());
        assertEquals(4,model.triangleCount());
        assertArrayEquals(new double[]{0.25,0.5,0.75,1},model.material(0).baseColor());
        assertArrayEquals(new double[]{1,0,0,1},model.material(1).baseColor());
        assertEquals(0,model.material(0).metallic());
        assertEquals(0.8,model.material(0).roughness(),1e-6);
    }

    @Test void projectionExposesNoMutableAliases() throws Exception {
        var snapshot = ViewerFixtures.snapshot(true,true);
        var model = ViewerCpuModel.from(snapshot);
        model.primitives().get(0).vertices()[0]=100;
        model.primitives().get(0).indices()[0]=2;
        model.draws().get(0).worldTransform()[12]=0;
        model.images().get(0).rgba()[0]=0;
        model.bounds().min()[0]=0;
        assertEquals(0,model.primitives().get(0).vertices()[0]);
        assertEquals(0,model.primitives().get(0).indices()[0]);
        assertEquals(3,model.draws().get(0).worldTransform()[12]);
        assertEquals(17,model.images().get(0).rgba()[0]);
        assertEquals(3,model.bounds().min()[0]);
        assertEquals(0,snapshot.primitives().get(0).positions()[0]);
        assertThrows(UnsupportedOperationException.class,()->model.primitives().clear());
    }

    @Test void currentIdentityIsHashOfTheValidatedBytes() throws Exception {
        byte[] bytes=ViewerFixtures.triangle(false,false,7);
        var model=ViewerCpuModel.from(ViewerFixtures.result(bytes).snapshot().orElseThrow());
        assertEquals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)),model.sourceSha256());
        assertArrayEquals(new double[]{7,0,0},model.bounds().min());
    }

    @Test void gateBProfileBoundsViewerTextureAndSamplerProjection() throws Exception {
        var accepted=ViewerFixtures.textureBudgetResult(8,8);
        assertEquals(8,ViewerCpuModel.from(accepted.snapshot().orElseThrow()).textures().size());

        assertTrue(ViewerFixtures.textureBudgetResult(9,8).snapshot().isEmpty());
        assertTrue(ViewerFixtures.textureBudgetResult(8,9).snapshot().isEmpty());
    }
}
