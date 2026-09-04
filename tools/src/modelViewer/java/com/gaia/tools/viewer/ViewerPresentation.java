package com.gaia.tools.viewer;

import com.gaia.tools.model.ValidatedModelSnapshot;
import java.util.Objects;

/** Pure snapshot-to-presentation admission performed before a candidate may publish. */
final class ViewerPresentation {
    private ViewerPresentation() { }

    static ViewerCpuModel prepare(ValidatedModelSnapshot snapshot, int width, int height) {
        ViewerCpuModel cpu = ViewerCpuModel.from(Objects.requireNonNull(snapshot, "snapshot"));
        InspectorRenderer.requirePresentable(cpu, width, height);
        return cpu;
    }
}
