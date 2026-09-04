package com.gaia.tools.viewer;

import java.util.Objects;

/** Presentation-only interpretation of one consumed input frame. */
public final class ViewerControls {
    private static final double ORBIT_RADIANS_PER_PIXEL = 0.008;
    public record Intents(boolean reload, boolean close) {
        public static Intents none() { return new Intents(false, false); }
    }

    private boolean grid = true;
    private boolean axes = true;
    private boolean bounds = true;
    private boolean wireframe;

    public Intents apply(ViewerInput.Frame frame, OrbitCamera camera, int logicalHeight) {
        Objects.requireNonNull(frame, "input frame");
        Objects.requireNonNull(camera, "camera");
        camera.orbit(frame.orbitX() * ORBIT_RADIANS_PER_PIXEL,
                frame.orbitY() * ORBIT_RADIANS_PER_PIXEL);
        camera.pan(frame.panX(), frame.panY(), Math.max(1, logicalHeight));
        camera.zoom(frame.zoom());
        if (frame.frame()) camera.frameCurrent();
        if (frame.grid()) grid = !grid;
        if (frame.axes()) axes = !axes;
        if (frame.bounds()) bounds = !bounds;
        if (frame.wireframe()) wireframe = !wireframe;
        return new Intents(frame.reload(), frame.close());
    }

    public InspectorRenderer.Visibility visibility() {
        return new InspectorRenderer.Visibility(grid, axes, bounds, wireframe);
    }
}
