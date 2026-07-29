package com.gaia.ui.widget;

import com.gaia.ui.GaiaUiTheme;
import com.gaia.ui.HudDebugSnapshot;
import com.gaia.ui.HudPresentationSnapshot;
import com.overlord.renderer.metrics.RenderMetricsSnapshot;
import com.overlord.renderer.ui.TextRenderer;
import com.overlord.renderer.ui.UiDrawCommand;
import com.overlord.renderer.ui.UiDrawList;
import com.overlord.renderer.ui.UiLayoutContext;
import com.overlord.renderer.ui.UiRect;
import com.overlord.renderer.ui.UiTextureId;
import com.overlord.renderer.ui.UiUvRect;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

public final class DebugHud {
    private static final double MARGIN = 12;
    private static final double PADDING = 6;
    private static final double SCALE = 0.75;
    private static final double GLYPH_HEIGHT = 8;
    private static final double LINE_STEP = 9;
    private static final int LINE_COUNT = 12;
    private static final UiUvRect SOLID_UV = new UiUvRect(0, 0, 1, 1);

    private final TextRenderer text;

    public DebugHud(TextRenderer text) {
        this.text = Objects.requireNonNull(text, "text");
    }

    public void append(
            HudPresentationSnapshot snapshot,
            UiLayoutContext layout,
            UiDrawList out) {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(layout, "layout");
        Objects.requireNonNull(out, "out");
        if (!snapshot.visibility().debugVisible()) {
            return;
        }

        List<String> lines = lines(snapshot);
        double contentWidth = lines.stream()
                .mapToDouble(line -> text.measure(line, SCALE))
                .max()
                .orElseThrow();
        UiRect panel = new UiRect(
                MARGIN,
                MARGIN,
                MARGIN + contentWidth + PADDING * 2,
                MARGIN + PADDING * 2 + LINE_COUNT * LINE_STEP);
        if (panel.right() > layout.logicalWidth() - MARGIN
                || panel.bottom() > layout.logicalHeight() - MARGIN) {
            throw new IllegalArgumentException(
                    "framebuffer-derived logical UI surface cannot contain DebugHud");
        }

        out.append(new UiDrawCommand(
                UiTextureId.SOLID,
                layout.toFramebuffer(panel),
                SOLID_UV,
                GaiaUiTheme.DEBUG_BACKGROUND,
                Optional.empty()));
        double x = layout.snapX(panel.left() + PADDING);
        double framebufferScaleX = SCALE * layout.contentScaleX();
        double framebufferScaleY = SCALE * layout.contentScaleY();
        for (int index = 0; index < lines.size(); index++) {
            double baseline = panel.top() + PADDING + GLYPH_HEIGHT * SCALE + index * LINE_STEP;
            text.append(
                    lines.get(index),
                    x,
                    layout.snapY(baseline),
                    framebufferScaleX,
                    framebufferScaleY,
                    GaiaUiTheme.DEBUG_TEXT,
                    Optional.empty(),
                    out);
        }
    }

    private static List<String> lines(HudPresentationSnapshot snapshot) {
        HudDebugSnapshot debug = snapshot.debug();
        Optional<RenderMetricsSnapshot> previous = debug.previousFrameMetrics();
        HudDebugSnapshot.Counts counts = debug.counts();
        HudDebugSnapshot.FeetPosition feet = debug.feet();
        return List.of(
                "FRAME (PREV): FPS " + previous
                        .map(metrics -> formatOne(metrics.framesPerSecond())).orElse("N/A"),
                "FRAME TIME: " + previous
                        .map(metrics -> formatTwo(metrics.frameTimeMilliseconds())).orElse("N/A")
                        + " ms",
                "DRAW CALLS: " + previous
                        .map(metrics -> Integer.toString(metrics.drawCalls())).orElse("N/A"),
                "TRIANGLES: " + previous
                        .map(metrics -> Long.toString(metrics.triangles())).orElse("N/A"),
                "VISIBLE CHUNKS: " + previous
                        .map(metrics -> Integer.toString(metrics.visibleChunks())).orElse("N/A"),
                "LOADED CHUNKS: " + counts.loadedChunks(),
                "MESH QUEUE: " + previous
                        .map(metrics -> Integer.toString(metrics.meshQueueDepth())).orElse("N/A"),
                "PHYSICS BODIES: " + counts.physicsBodies(),
                "PLAYER FEET: " + formatTwo(feet.x()) + ", "
                        + formatTwo(feet.y()) + ", " + formatTwo(feet.z()),
                "WORLD ITEMS: " + counts.worldItems(),
                "TARGET: " + (snapshot.interaction().target().isPresent() ? "YES" : "NO"),
                "FEEDBACK: DAMAGE " + counts.blockDamageVisuals()
                        + " | ITEMS " + counts.feedbackWorldItems()
                        + " | PARTICLES " + counts.particles());
    }

    private static String formatOne(double value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }

    private static String formatTwo(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }
}
