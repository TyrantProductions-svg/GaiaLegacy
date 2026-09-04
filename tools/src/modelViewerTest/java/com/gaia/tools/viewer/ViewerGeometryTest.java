package com.gaia.tools.viewer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gaia.tools.model.ValidatedModelSnapshot.Bounds;
import java.util.List;
import org.junit.jupiter.api.Test;

class ViewerGeometryTest {
    @Test
    void gridIsFiniteBoundedAndCenteredAroundValidatedBounds() {
        Bounds bounds = new Bounds(new double[]{100.0, -2.0, 200.0},
                new double[]{104.0, 6.0, 208.0});
        ViewerGeometry.Lines grid = ViewerGeometry.grid(bounds);
        assertTrue(grid.vertexCount() >= 8);
        assertTrue(grid.vertexCount() <= ViewerGeometry.MAX_GRID_VERTICES);
        assertEquals(102.0, grid.worldTransform()[12], 0.0);
        assertEquals(0.0, grid.worldTransform()[13], 0.0);
        assertEquals(204.0, grid.worldTransform()[14], 0.0);
        for (float value : grid.positions()) assertTrue(Float.isFinite(value));
    }

    @Test
    void axesContainExactlyThreeColoredLinesAtAssetOrigin() {
        List<ViewerGeometry.Lines> axes = ViewerGeometry.axes(2.0);
        assertEquals(3, axes.size());
        assertArrayEquals(new float[]{1.0f, 0.18f, 0.18f}, axes.get(0).color());
        assertArrayEquals(new float[]{0.18f, 1.0f, 0.18f}, axes.get(1).color());
        assertArrayEquals(new float[]{0.25f, 0.55f, 1.0f}, axes.get(2).color());
        assertArrayEquals(new float[]{0, 0, 0, 2, 0, 0}, axes.get(0).positions());
        assertArrayEquals(new float[]{0, 0, 0, 0, 2, 0}, axes.get(1).positions());
        assertArrayEquals(new float[]{0, 0, 0, 0, 0, 2}, axes.get(2).positions());
    }

    @Test
    void boundingBoxContainsExactlyTwelveEdgesAtActualValidatedBounds() {
        Bounds bounds = new Bounds(new double[]{-3.0, 2.0, 7.0},
                new double[]{5.0, 6.0, 11.0});
        ViewerGeometry.Lines box = ViewerGeometry.bounds(bounds);
        assertEquals(24, box.vertexCount());
        assertEquals(1.0, box.worldTransform()[12], 0.0);
        assertEquals(4.0, box.worldTransform()[13], 0.0);
        assertEquals(9.0, box.worldTransform()[14], 0.0);
        assertEquals(72, box.positions().length);
        assertEquals(12, distinctUndirectedEdges(box.positions()));
    }

    @Test
    void helperBatchesAreImmutableAndKeepHugeWorldTranslationOutOfFloats() {
        Bounds bounds = new Bounds(new double[]{1.0e20, 2.0e20, -3.0e20},
                new double[]{1.0e20 + 65_536.0, 2.0e20 + 65_536.0, -3.0e20 + 65_536.0});
        ViewerGeometry.Lines box = ViewerGeometry.bounds(bounds);
        float[] positions = box.positions();
        positions[0] = Float.NaN;
        double[] transform = box.worldTransform();
        transform[12] = 0.0;
        for (float value : box.positions()) assertTrue(Float.isFinite(value));
        assertTrue(Math.abs(box.worldTransform()[12]) > 1.0e19);
    }

    @Test
    void largeFiniteExtentDoesNotOverflowGridVertexIntermediates() {
        Bounds bounds = new Bounds(new double[]{-1.15e38, -1.0, -1.15e38},
                new double[]{1.15e38, 1.0, 1.15e38});

        ViewerGeometry.Lines grid = ViewerGeometry.grid(bounds);

        assertEquals(44, grid.vertexCount());
        for (float value : grid.positions()) {
            assertTrue(Float.isFinite(value), "grid vertex must remain finite");
        }
    }

    @Test
    void lineBatchRejectsNonFiniteGpuVertexData() {
        assertThrows(IllegalArgumentException.class, () -> new ViewerGeometry.Lines(
                new float[]{0, 0, 0, Float.POSITIVE_INFINITY, 0, 0},
                new double[]{1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1},
                new float[]{1, 1, 1}));
    }

    @Test
    void gridIsOmittedWhenFiniteBoundsCannotProduceRepresentableFloatVertices() {
        Bounds bounds = new Bounds(new double[]{-2.3e38, -1.0, -2.3e38},
                new double[]{2.3e38, 1.0, 2.3e38});

        assertTrue(ViewerGeometry.gridIfSafe(bounds).isEmpty());

        assertArrayEquals(new double[]{-2.3e38, -1.0, -2.3e38}, bounds.min());
        assertArrayEquals(new double[]{2.3e38, 1.0, 2.3e38}, bounds.max());
    }

    @Test
    void nearLimitGridDecisionIsDeterministicAndEveryGpuComponentIsFinite() {
        Bounds bounds = new Bounds(new double[]{-2.2e38, -1.0, -2.2e38},
                new double[]{2.2e38, 1.0, 2.2e38});

        ViewerGeometry.Lines first = ViewerGeometry.gridIfSafe(bounds).orElseThrow();
        ViewerGeometry.Lines second = ViewerGeometry.gridIfSafe(bounds).orElseThrow();

        assertArrayEquals(first.positions(), second.positions());
        assertArrayEquals(first.worldTransform(), second.worldTransform());
        for (float value : first.positions()) assertTrue(Float.isFinite(value));
    }

    private static int distinctUndirectedEdges(float[] positions) {
        java.util.Set<String> edges = new java.util.HashSet<>();
        for (int i = 0; i < positions.length; i += 6) {
            String first = point(positions, i);
            String second = point(positions, i + 3);
            edges.add(first.compareTo(second) <= 0 ? first + "|" + second : second + "|" + first);
        }
        return edges.size();
    }

    private static String point(float[] values, int offset) {
        return values[offset] + "," + values[offset + 1] + "," + values[offset + 2];
    }
}
