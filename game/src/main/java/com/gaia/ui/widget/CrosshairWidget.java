package com.gaia.ui.widget;

import com.gaia.ui.HudPresentationSnapshot;
import com.overlord.renderer.feedback.CrosshairGeometry;
import com.overlord.renderer.ui.UiColor;
import com.overlord.renderer.ui.UiDrawCommand;
import com.overlord.renderer.ui.UiDrawList;
import com.overlord.renderer.ui.UiLayoutContext;
import com.overlord.renderer.ui.UiRect;
import com.overlord.renderer.ui.UiTextureId;
import com.overlord.renderer.ui.UiUvRect;
import java.util.Objects;
import java.util.Optional;

public final class CrosshairWidget {
    private static final UiColor WHITE = new UiColor(1, 1, 1, 1);
    private static final UiUvRect SOLID_UV = new UiUvRect(0, 0, 1, 1);

    public void append(
            HudPresentationSnapshot snapshot,
            UiLayoutContext layout,
            UiDrawList out) {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(layout, "layout");
        Objects.requireNonNull(out, "out");
        if (!snapshot.visibility().hudVisible()
                || !snapshot.visibility().interactionEligible()) {
            return;
        }

        CrosshairGeometry.quads(layout.framebufferWidth(), layout.framebufferHeight())
                .forEach(quad -> out.append(new UiDrawCommand(
                        UiTextureId.SOLID,
                        new UiRect(quad.xMin(), quad.yMin(), quad.xMax(), quad.yMax()),
                        SOLID_UV,
                        WHITE,
                        Optional.empty())));
    }
}
