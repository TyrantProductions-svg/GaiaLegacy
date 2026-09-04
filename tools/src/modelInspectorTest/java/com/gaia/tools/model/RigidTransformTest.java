package com.gaia.tools.model;

import de.javagl.jgltf.impl.v2.Node;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class RigidTransformTest {
    @Test
    void identityAndTranslatedRotatedTrsAreNotRequiredToBeIdentity() {
        assertArrayEquals(new double[]{1,2,3}, RigidTransform.from(new Node()).point(1,2,3), 1e-12);
        Node translated = new Node(); translated.setTranslation(new double[]{4,5,6});
        assertArrayEquals(new double[]{4,5,6}, RigidTransform.from(translated).point(0,0,0), 1e-12);
        Node rotated = new Node(); rotated.setRotation(new double[]{0,0,Math.sqrt(.5),Math.sqrt(.5)});
        assertArrayEquals(new double[]{0,1,0}, RigidTransform.from(rotated).point(1,0,0), 1e-12);
        rotated.setTranslation(new double[]{4,5,6});
        assertArrayEquals(new double[]{4,6,6}, RigidTransform.from(rotated).point(1,0,0), 1e-12);
    }

    @ParameterizedTest @ValueSource(doubles = {1, 1.00005, .99995})
    void unitScaleAndNoiseInsideToleranceAreAcceptedWithoutSnapping(double scale) {
        Node node = new Node(); node.setScale(new double[]{scale,1,1});
        assertEquals(scale, RigidTransform.from(node).point(1,0,0)[0]);
        assertEquals(scale, node.getScale()[0]);
    }

    @ParameterizedTest @ValueSource(doubles = {2, 1.1, 1.0002, .9998, -1, 0,
            Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY})
    void scaleOutsideToleranceFails(double scale) {
        Node node = new Node(); node.setScale(new double[]{scale,scale,scale});
        assertThrows(IllegalArgumentException.class, () -> RigidTransform.from(node));
        node.setScale(new double[]{scale,1,1});
        assertThrows(IllegalArgumentException.class, () -> RigidTransform.from(node));
    }

    @ParameterizedTest @ValueSource(doubles = {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY})
    void nonfiniteTranslationAndRotationFail(double value) {
        Node node = new Node(); node.setTranslation(new double[]{value,0,0});
        assertThrows(IllegalArgumentException.class, () -> RigidTransform.from(node));
        node.setTranslation(null); node.setRotation(new double[]{0,0,0,1});
        // DTO setters reject infinities first; directly corrupt owned test data
        // to exercise the pure validator's defensive finite check too.
        node.getRotation()[2] = value;
        assertThrows(IllegalArgumentException.class, () -> RigidTransform.from(node));
    }

    @Test
    void quaternionNormToleranceDoesNotNormalizeAuthoredComponents() {
        Node node = new Node();
        double component = Math.sqrt(.5) * 1.00005;
        node.setRotation(new double[]{0,0,component,component});
        assertNotNull(RigidTransform.from(node));
        assertEquals(component, node.getRotation()[3]);
        for (double w : new double[]{0, 2, 1.0002, .9998}) {
            node.setRotation(new double[]{0,0,0,1});
            node.getRotation()[3] = w;
            assertThrows(IllegalArgumentException.class, () -> RigidTransform.from(node));
        }
    }

    @Test
    void matrixIdentityTranslationRotationAndCombinedAreLegal() {
        Node node = new Node(); double[] m = identity(); node.setMatrix(m);
        assertArrayEquals(new double[]{1,2,3}, RigidTransform.from(node).point(1,2,3), 1e-12);
        m[12]=4; m[13]=5; m[14]=6;
        assertArrayEquals(new double[]{4,5,6}, RigidTransform.from(node).point(0,0,0), 1e-12);
        m[0]=0; m[1]=1; m[4]=-1; m[5]=0;
        assertArrayEquals(new double[]{4,6,6}, RigidTransform.from(node).point(1,0,0), 1e-12);
        m[12]=0; m[13]=0; m[14]=0;
        assertArrayEquals(new double[]{0,1,0}, RigidTransform.from(node).point(1,0,0), 1e-12);
    }

    @Test
    void matrixScaleReflectionShearAndSingularityFail() {
        for (double[] basis : new double[][]{{2,2,2}, {2,1,1}, {-1,1,1}, {0,1,1}}) {
            double[] m=identity(); m[0]=basis[0]; m[5]=basis[1]; m[10]=basis[2];
            Node node=new Node(); node.setMatrix(m);
            assertThrows(IllegalArgumentException.class, () -> RigidTransform.from(node));
        }
        double[] m=identity(); m[4]=.01; Node node=new Node(); node.setMatrix(m);
        assertThrows(IllegalArgumentException.class, () -> RigidTransform.from(node));
    }

    @Test
    void matrixNoiseWithinAbsoluteToleranceIsNotCorrected() {
        double[] m=identity(); m[4]=.00005; m[3]=.00005;
        Node node=new Node(); node.setMatrix(m);
        assertEquals(.00005, RigidTransform.from(node).values()[4]);
        assertEquals(.00005, node.getMatrix()[3]);
        m[4]=.0002;
        assertThrows(IllegalArgumentException.class, () -> RigidTransform.from(node));
        m[4]=0; m[3]=.0002;
        assertThrows(IllegalArgumentException.class, () -> RigidTransform.from(node));
    }

    @Test
    void matrixNonfiniteComponentsAndIncorrectHomogeneousRowFail() {
        for (int i=0;i<16;i++) {
            for (double value : new double[]{Double.NaN,Double.POSITIVE_INFINITY}) {
                double[] m=identity(); m[i]=value; Node node=new Node(); node.setMatrix(m);
                assertThrows(IllegalArgumentException.class, () -> RigidTransform.from(node));
            }
        }
        for (int i : new int[]{3,7,11,15}) {
            double[] m=identity(); m[i]=2; Node node=new Node(); node.setMatrix(m);
            assertThrows(IllegalArgumentException.class, () -> RigidTransform.from(node));
        }
    }

    @Test
    void matrixAndEachAuthoredTrsComponentAreExclusive() {
        Node n=new Node(); n.setMatrix(identity()); n.setTranslation(new double[]{0,0,0});
        assertThrows(IllegalArgumentException.class, () -> RigidTransform.from(n));
        n.setTranslation(null); n.setRotation(new double[]{0,0,0,1});
        assertThrows(IllegalArgumentException.class, () -> RigidTransform.from(n));
        n.setRotation(null); n.setScale(new double[]{1,1,1});
        assertThrows(IllegalArgumentException.class, () -> RigidTransform.from(n));
    }

    @Test
    void compositionIsFiniteOrderedAndDefensivelyOwned() {
        Node parent=new Node(); parent.setTranslation(new double[]{4,0,0});
        Node child=new Node(); child.setRotation(new double[]{0,0,1,0});
        var composed=RigidTransform.from(parent).compose(RigidTransform.from(child));
        assertArrayEquals(new double[]{3,0,0}, composed.point(1,0,0), 1e-12);
        double[] copy=composed.values(); copy[12]=90;
        assertEquals(4, composed.point(0,0,0)[0]);
        parent.setTranslation(new double[]{Double.MAX_VALUE,0,0});
        var huge=RigidTransform.from(parent);
        assertThrows(IllegalArgumentException.class, () -> huge.compose(huge));
    }

    private static double[] identity() { return new double[]{1,0,0,0,0,1,0,0,0,0,1,0,0,0,0,1}; }
}
