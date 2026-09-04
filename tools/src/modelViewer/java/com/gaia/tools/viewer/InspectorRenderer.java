package com.gaia.tools.viewer;

import com.gaia.tools.model.ValidatedModelSnapshot;
import java.util.Objects;
import org.joml.Matrix4d;
import org.joml.Matrix4f;

/** The single draw-command authority for the read-only model-inspector window. */
public final class InspectorRenderer implements AutoCloseable {
    public record Visibility(boolean grid, boolean axes, boolean bounds, boolean wireframe) { }

    private final InspectorRenderBackend backend;

    InspectorRenderer(InspectorRenderBackend backend) {
        this.backend = Objects.requireNonNull(backend, "render backend");
    }

    public void render(InspectorGpuModel gpu, OrbitCamera camera, Visibility visibility,
            int framebufferWidth, int framebufferHeight) {
        Objects.requireNonNull(gpu, "GPU model");
        Objects.requireNonNull(camera, "camera");
        Objects.requireNonNull(visibility, "visibility");
        int width = Math.max(1, framebufferWidth);
        int height = Math.max(1, framebufferHeight);
        Matrix4d view = camera.view();
        backend.begin(toFloat(camera.projection()), width, height);
        boolean wireframe = false;
        try {
            if (visibility.wireframe()) {
                backend.wireframe(true);
                wireframe = true;
            }
            ViewerCpuModel cpu = gpu.cpu();
            for (ViewerCpuModel.Draw draw : cpu.draws()) {
                InspectorGpuModel.PrimitiveGpu primitive = gpu.primitives().get(draw.primitive());
                ValidatedModelSnapshot.Material material = cpu.material(draw.primitive());
                Integer textureHandle = null;
                Integer samplerHandle = null;
                if (material.baseColorTexture() >= 0) {
                    int textureIndex = material.baseColorTexture();
                    ValidatedModelSnapshot.Texture texture = cpu.textures().get(textureIndex);
                    textureHandle = gpu.imageTextures().get(texture.image());
                    samplerHandle = gpu.textureSamplers().get(textureIndex);
                }
                backend.triangles(primitive, modelView(view, draw.worldTransform()),
                        color(material.baseColor()), textureHandle, samplerHandle);
            }
            if (wireframe) {
                backend.wireframe(false);
                wireframe = false;
            }
            if (visibility.grid()) {
                ViewerGeometry.gridIfSafe(gpu.cpu().bounds()).ifPresent(grid -> drawLine(view, grid));
            }
            if (visibility.axes()) {
                double[] dimensions = gpu.cpu().bounds().dimensions();
                double axisLength = Math.max(1.0,
                        Math.max(dimensions[0], Math.max(dimensions[1], dimensions[2]))) * 1.25;
                for (ViewerGeometry.Lines axis : ViewerGeometry.axes(axisLength)) drawLine(view, axis);
            }
            if (visibility.bounds()) drawLine(view, ViewerGeometry.bounds(gpu.cpu().bounds()));
        } finally {
            if (wireframe) backend.wireframe(false);
            backend.end();
        }
    }

    static void requirePresentable(ViewerCpuModel cpu, int width, int height) {
        Objects.requireNonNull(cpu, "CPU model");
        OrbitCamera camera = new OrbitCamera(cpu.bounds(), width, height);
        Matrix4d view = camera.view();
        toFloat(camera.projection());
        for (ViewerCpuModel.Draw draw : cpu.draws()) {
            modelView(view, draw.worldTransform());
        }
        ViewerGeometry.gridIfSafe(cpu.bounds()).ifPresent(grid -> lineModelView(view, grid));
        double[] dimensions = cpu.bounds().dimensions();
        double axisLength = Math.max(1.0,
                Math.max(dimensions[0], Math.max(dimensions[1], dimensions[2]))) * 1.25;
        for (ViewerGeometry.Lines axis : ViewerGeometry.axes(axisLength)) {
            lineModelView(view, axis);
        }
        lineModelView(view, ViewerGeometry.bounds(cpu.bounds()));
    }

    private void drawLine(Matrix4d view, ViewerGeometry.Lines batch) {
        backend.lines(batch, lineModelView(view, batch));
    }

    private static Matrix4f modelView(Matrix4d view, double[] worldTransform) {
        return toFloat(new Matrix4d(view).mul(new Matrix4d().set(worldTransform)));
    }

    private static Matrix4f lineModelView(Matrix4d view, ViewerGeometry.Lines batch) {
        return modelView(view, batch.worldTransform());
    }

    private static Matrix4f toFloat(Matrix4d matrix) {
        Matrix4f packed = new Matrix4f().set(matrix);
        float[] values = packed.get(new float[16]);
        for (float value : values) {
            if (!Float.isFinite(value)) {
                throw new IllegalArgumentException("viewer transform is not GPU representable");
            }
        }
        return packed;
    }

    private static float[] color(double[] source) {
        return new float[]{ViewerCpuModel.gpuFloat(source[0]),
                ViewerCpuModel.gpuFloat(source[1]), ViewerCpuModel.gpuFloat(source[2])};
    }

    @Override public void close() { backend.close(); }
}
