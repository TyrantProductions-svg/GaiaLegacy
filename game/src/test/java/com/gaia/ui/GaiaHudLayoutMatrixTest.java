package com.gaia.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gaia.ui.widget.BodyInventoryHudTest;
import com.overlord.assets.ResourceLocation;
import com.overlord.inventory.api.BodySlot;
import com.overlord.inventory.api.ItemStack;
import com.overlord.renderer.RenderSurfaceMetrics;
import com.overlord.renderer.ui.UiDrawCommand;
import com.overlord.renderer.ui.UiFrame;
import com.overlord.renderer.ui.UiLayoutContext;
import com.overlord.renderer.ui.UiRect;
import com.overlord.renderer.ui.UiTextureId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class GaiaHudLayoutMatrixTest {
    private static final ResourceLocation DIRT = ResourceLocation.parse("gaia:dirt");
    private static final ResourceLocation STONE = ResourceLocation.parse("gaia:stone");
    private static final ResourceLocation GRASS = ResourceLocation.parse("gaia:grass");
    private static final ResourceLocation LONG = ResourceLocation.parse("gaia:long_name");

    @ParameterizedTest(name = "{0}")
    @MethodSource("surfaces")
    void staysCenteredCompactOrderedAndInBoundsAcrossTheFramebufferMatrix(
            String scenario, RenderSurfaceMetrics surface) {
        BodyInventoryHudTest.Fixtures fixtures = new BodyInventoryHudTest.Fixtures();
        UiLayoutContext layout = new UiLayoutContext(surface);
        ListGeometry geometry = expected(layout);

        for (SnapshotCase snapshotCase : representativeSnapshots(fixtures)) {
            String label = scenario + " / " + snapshotCase.name();
            UiFrame first = fixtures.screen.compose(snapshotCase.snapshot(), layout);
            UiFrame repeated = fixtures.screen.compose(snapshotCase.snapshot(), layout);

            assertEquals(first, repeated, label);
            assertTrue(first.commands().size() >= 3, label);
            assertEquals(geometry.left(), first.commands().get(0).framebufferBounds(), label);
            assertEquals(geometry.right(), first.commands().get(1).framebufferBounds(), label);
            assertEquals(geometry.mouth(), first.commands().get(2).framebufferBounds(), label);
            assertEquals(snapshotCase.expectedIcons(), first.commands().stream()
                    .filter(command -> command.texture() == UiTextureId.ICON_ATLAS).count(), label);
            assertEquals(layout.snapY(layout.logicalHeight() - 12), geometry.left().bottom(), label);
            assertEquals(8 * surface.contentScaleX(),
                    geometry.right().left() - geometry.left().right(), 1.0, label);
            assertTrue(geometry.left().right() <= geometry.right().left(), label);
            assertTrue(geometry.mouth().bottom() <= geometry.left().top(), label);
            assertEquals(46 * surface.contentScaleX(),
                    geometry.left().right() - geometry.left().left(), 1.0, label);
            assertEquals(38 * surface.contentScaleX(),
                    geometry.mouth().right() - geometry.mouth().left(), 1.0, label);
            assertFalse(first.commands().isEmpty(), label);
            for (UiDrawCommand command : first.commands()) {
                UiRect bounds = command.framebufferBounds();
                assertTrue(bounds.left() >= 0 && bounds.top() >= 0, label + " " + bounds);
                assertTrue(bounds.right() <= surface.framebufferWidth(), label + " " + bounds);
                assertTrue(bounds.bottom() <= surface.framebufferHeight(), label + " " + bounds);
            }
            verifySemanticTruth(fixtures, layout, geometry, snapshotCase, first.commands(), label);
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("transitions")
    void resizeMaximiseMismatchAndMonitorScaleTransitionsUseOnlyTheIncomingSurface(
            String scenario, RenderSurfaceMetrics before, RenderSurfaceMetrics after) {
        BodyInventoryHudTest.Fixtures fixtures = new BodyInventoryHudTest.Fixtures();
        HudPresentationSnapshot snapshot = fixtures.normal(BodySlot.RIGHT_HAND, Map.of());
        fixtures.screen.compose(snapshot, new UiLayoutContext(before));

        UiLayoutContext afterLayout = new UiLayoutContext(after);
        UiFrame transitioned = fixtures.screen.compose(snapshot, afterLayout);

        assertEquals(expected(afterLayout).left(), transitioned.commands().get(0).framebufferBounds(), scenario);
        assertEquals(expected(afterLayout).right(), transitioned.commands().get(1).framebufferBounds(), scenario);
        assertEquals(expected(afterLayout).mouth(), transitioned.commands().get(2).framebufferBounds(), scenario);
    }

    private static Stream<Arguments> surfaces() {
        return Stream.of(
                surface("800x600 4:3 100%", 800, 600, 1),
                surface("1024x768 4:3 125%", 1024, 768, 1.25f),
                surface("1280x720 16:9 150%", 1280, 720, 1.5f),
                surface("1920x1080 16:9 200%", 1920, 1080, 2),
                surface("2560x1440 16:9 100%", 2560, 1440, 1),
                surface("3840x2160 4K 125%", 3840, 2160, 1.25f),
                surface("2560x1600 16:10 150%", 2560, 1600, 1.5f),
                surface("3440x1440 ultrawide 200%", 3440, 1440, 2),
                surface("1001x751 odd 125%", 1001, 751, 1.25f),
                Arguments.of(
                        "logical-window framebuffer mismatch",
                        new RenderSurfaceMetrics(640, 480, 1600, 900, 1.25f, 1.25f)));
    }

    private static Stream<Arguments> transitions() {
        return Stream.of(
                Arguments.of("resize", metrics(800, 600, 1), metrics(1001, 751, 1)),
                Arguments.of("maximise equivalent", metrics(1280, 720, 1), metrics(2560, 1440, 1)),
                Arguments.of("monitor 100 to 150", metrics(1920, 1080, 1), metrics(2880, 1620, 1.5f)),
                Arguments.of(
                        "logical mismatch transition",
                        metrics(800, 600, 1),
                        new RenderSurfaceMetrics(640, 480, 1600, 900, 1.25f, 1.25f)));
    }

    private static List<SnapshotCase> representativeSnapshots(
            BodyInventoryHudTest.Fixtures fixtures) {
        Map<BodySlot, ItemStack> populated = Map.of(
                BodySlot.LEFT_HAND, new ItemStack(DIRT, 7),
                BodySlot.RIGHT_HAND, new ItemStack(STONE, 8),
                BodySlot.MOUTH, new ItemStack(GRASS, 9));
        return List.of(
                new SnapshotCase(
                        "populated normal",
                        fixtures.normal(BodySlot.LEFT_HAND, populated),
                        3,
                        SnapshotKind.NORMAL),
                new SnapshotCase(
                        "right-active two-handed shared core",
                        fixtures.twoHanded(BodySlot.RIGHT_HAND, BodySlot.RIGHT_HAND),
                        1,
                        SnapshotKind.RIGHT_TWO_HANDED),
                new SnapshotCase(
                        "mouth-active two-handed shared core",
                        fixtures.twoHanded(BodySlot.MOUTH, BodySlot.LEFT_HAND),
                        1,
                        SnapshotKind.MOUTH_TWO_HANDED),
                new SnapshotCase(
                        "long item name",
                        fixtures.normal(
                                BodySlot.LEFT_HAND,
                                Map.of(BodySlot.LEFT_HAND, new ItemStack(LONG, 1)),
                                Optional.of(new HudPresentationSnapshot.TimedItemName(LONG, 1, 1))),
                        1,
                        SnapshotKind.LONG_NAME),
                new SnapshotCase(
                        "Creative detached",
                        fixtures.creative(
                                BodySlot.RIGHT_HAND,
                                populated,
                                LONG,
                                Optional.of(new HudPresentationSnapshot.TimedItemName(LONG, 1, 1))),
                        4,
                        SnapshotKind.CREATIVE));
    }

    private static void verifySemanticTruth(
            BodyInventoryHudTest.Fixtures fixtures,
            UiLayoutContext layout,
            ListGeometry geometry,
            SnapshotCase snapshotCase,
            List<UiDrawCommand> commands,
            String label) {
        List<UiDrawCommand> icons = commands.stream()
                .filter(command -> command.texture() == UiTextureId.ICON_ATLAS).toList();
        List<BodyInventoryHudTest.Fixtures.TextRun> text = fixtures.textRuns(commands);
        switch (snapshotCase.kind()) {
            case NORMAL -> {
                assertEquals(
                        List.of(fixtures.uv(DIRT), fixtures.uv(STONE), fixtures.uv(GRASS)),
                        icons.stream().map(UiDrawCommand::uv).toList(), label);
                assertTextOnce(text, "7", label);
                assertTextOnce(text, "8", label);
                assertTextOnce(text, "9", label);
                assertTextOnce(text, "1 LEFT", label);
                assertTextOnce(text, "2 RIGHT", label);
                assertTextOnce(text, "3 MOUTH", label);
            }
            case RIGHT_TWO_HANDED -> {
                assertPrimaryTop(commands, logicalSlot(layout, BodySlot.RIGHT_HAND), layout, label);
                UiRect left = logicalSlot(layout, BodySlot.LEFT_HAND);
                assertTrue(commands.stream().anyMatch(command -> command.texture() == UiTextureId.SOLID
                        && command.framebufferBounds().equals(layout.toFramebuffer(new UiRect(
                                left.left() + 2, left.top(), left.left() + 8, left.top() + 1)))), label);
                assertEquals(1, icons.size(), label);
                assertTextOnce(text, "6", label);
            }
            case MOUTH_TWO_HANDED -> {
                assertPrimaryTop(commands, logicalSlot(layout, BodySlot.MOUTH), layout, label);
                assertFalse(hasPrimaryTop(
                        commands, logicalSlot(layout, BodySlot.LEFT_HAND), layout), label);
                assertFalse(hasPrimaryTop(
                        commands, logicalSlot(layout, BodySlot.RIGHT_HAND), layout), label);
                assertEquals(1, icons.size(), label);
                assertTextOnce(text, "6", label);
            }
            case LONG_NAME -> {
                List<BodyInventoryHudTest.Fixtures.TextRun> truncatedNames = text.stream()
                        .filter(run -> run.text().startsWith("ABC") && run.text().endsWith("..."))
                        .toList();
                assertEquals(1, truncatedNames.size(), label + " / truncated long item name");
                BodyInventoryHudTest.Fixtures.TextRun name = truncatedNames.get(0);
                assertTrue(name.bounds().bottom() <= geometry.mouth().top(), label);
                assertTrue(name.bounds().left() >= 0
                        && name.bounds().right() <= layout.framebufferWidth(), label);
            }
            case CREATIVE -> {
                double center = layout.logicalWidth() / 2;
                double height = layout.logicalHeight();
                UiRect creative = layout.toFramebuffer(new UiRect(
                        center - 19, height - 164, center + 19, height - 126));
                assertEquals(List.of(
                                layout.toFramebuffer(new UiRect(
                                        center - 19, height - 164, center + 19, height - 163)),
                                layout.toFramebuffer(new UiRect(
                                        center - 19, height - 127, center + 19, height - 126)),
                                layout.toFramebuffer(new UiRect(
                                        center - 19, height - 163, center - 18, height - 127)),
                                layout.toFramebuffer(new UiRect(
                                        center + 18, height - 163, center + 19, height - 127))),
                        commands.stream()
                                .filter(command -> command.texture() == UiTextureId.SOLID)
                                .filter(command -> command.tint().equals(
                                        GaiaUiTheme.CREATIVE_ACCENT))
                                .map(UiDrawCommand::framebufferBounds)
                                .toList(), label);

                UiDrawCommand selected = icons.get(icons.size() - 1);
                assertEquals(layout.toFramebuffer(new UiRect(
                        center - 9, height - 157, center + 9, height - 139)),
                        selected.framebufferBounds(), label);
                assertEquals(fixtures.uv(LONG), selected.uv(), label);
                assertEquals(GaiaUiTheme.CREATIVE_ACCENT, selected.tint(), label);

                assertTextRun(
                        text,
                        "∞",
                        rightAlignedTextBounds(
                                center + 12, height - 134, 0.5, layout),
                        GaiaUiTheme.CREATIVE_ACCENT,
                        label);
                assertTextRun(
                        text,
                        "CREATIVE",
                        centeredTextBounds(
                                "CREATIVE", center + 39, height - 143, 0.5, layout),
                        GaiaUiTheme.CREATIVE_ACCENT,
                        label);
                assertEquals(
                        List.of(
                                new BodyInventoryHudTest.Fixtures.TextRun(
                                        "PRESERVED",
                                        centeredTextBounds(
                                                "PRESERVED", center - 27,
                                                height - 7, 0.5, layout),
                                        GaiaUiTheme.INACTIVE_RIM),
                                new BodyInventoryHudTest.Fixtures.TextRun(
                                        "PRESERVED",
                                        centeredTextBounds(
                                                "PRESERVED", center + 27,
                                                height - 7, 0.5, layout),
                                        GaiaUiTheme.INACTIVE_RIM),
                                new BodyInventoryHudTest.Fixtures.TextRun(
                                        "PRESERVED",
                                        centeredTextBounds(
                                                "PRESERVED", center,
                                                height - 59, 0.5, layout),
                                        GaiaUiTheme.INACTIVE_RIM)),
                        text.stream().filter(run -> run.text().equals("PRESERVED")).toList(),
                        label);
                List<BodyInventoryHudTest.Fixtures.TextRun> truncatedNames = text.stream()
                        .filter(run -> run.text().startsWith("ABC") && run.text().endsWith("..."))
                        .toList();
                assertEquals(1, truncatedNames.size(), label + " / truncated long item name");
                BodyInventoryHudTest.Fixtures.TextRun name = truncatedNames.get(0);
                assertTrue(name.bounds().top() >= creative.bottom(), label);
            }
        }
    }

    private static void assertTextOnce(
            List<BodyInventoryHudTest.Fixtures.TextRun> runs, String expected, String label) {
        assertEquals(1, runs.stream().filter(run -> run.text().equals(expected)).count(), label);
    }

    private static BodyInventoryHudTest.Fixtures.TextRun textRun(
            List<BodyInventoryHudTest.Fixtures.TextRun> runs, String expected, String label) {
        List<BodyInventoryHudTest.Fixtures.TextRun> matches = runs.stream()
                .filter(run -> run.text().equals(expected)).toList();
        assertEquals(1, matches.size(), label);
        return matches.get(0);
    }

    private static void assertTextRun(
            List<BodyInventoryHudTest.Fixtures.TextRun> runs,
            String expectedText,
            UiRect expectedBounds,
            com.overlord.renderer.ui.UiColor expectedTint,
            String label) {
        assertTrue(runs.stream().anyMatch(run -> run.text().equals(expectedText)
                && run.bounds().equals(expectedBounds)
                && run.tint().equals(expectedTint)),
                label + " / " + expectedText + " at " + expectedBounds);
    }

    private static UiRect centeredTextBounds(
            String text,
            double logicalCenterX,
            double logicalBaseline,
            double logicalScale,
            UiLayoutContext layout) {
        double scaleX = expectedPixelGridScale(logicalScale, layout.contentScaleX());
        double scaleY = expectedPixelGridScale(logicalScale, layout.contentScaleY());
        double width = text.codePointCount(0, text.length()) * 8 * scaleX;
        double x = layout.snapX(logicalCenterX) - width / 2;
        double baseline = layout.snapY(logicalBaseline);
        return new UiRect(
                Math.round(x),
                Math.round(baseline - 8 * scaleY),
                Math.round(x + width),
                Math.round(baseline));
    }

    private static UiRect rightAlignedTextBounds(
            double logicalRight,
            double logicalBaseline,
            double logicalScale,
            UiLayoutContext layout) {
        double scaleX = expectedPixelGridScale(logicalScale, layout.contentScaleX());
        double scaleY = expectedPixelGridScale(logicalScale, layout.contentScaleY());
        double right = layout.snapX(logicalRight);
        double x = right - 8 * scaleX;
        double baseline = layout.snapY(logicalBaseline);
        return new UiRect(
                Math.round(x),
                Math.round(baseline - 8 * scaleY),
                Math.round(right),
                Math.round(baseline));
    }

    private static double expectedPixelGridScale(double logicalScale, float contentScale) {
        double requested = logicalScale * contentScale;
        if (contentScale >= 1.5f && requested < 1.0d) {
            return 1.0d;
        }
        if (requested < 0.75d) {
            return 0.5d;
        }
        return Math.max(1.0d, Math.floor(requested));
    }

    private static void assertPrimaryTop(
            List<UiDrawCommand> commands, UiRect bounds, UiLayoutContext layout, String label) {
        assertTrue(hasPrimaryTop(commands, bounds, layout), label);
    }

    private static boolean hasPrimaryTop(
            List<UiDrawCommand> commands, UiRect bounds, UiLayoutContext layout) {
        UiRect expected = layout.toFramebuffer(new UiRect(
                bounds.left(), bounds.top(), bounds.right(), bounds.top() + 1));
        return commands.stream().anyMatch(command -> command.texture() == UiTextureId.SOLID
                && command.tint().equals(GaiaUiTheme.ACTIVE_PRIMARY_RIM)
                && command.framebufferBounds().equals(expected));
    }

    private static UiRect logicalSlot(UiLayoutContext layout, BodySlot slot) {
        double center = layout.logicalWidth() / 2;
        double handBottom = layout.logicalHeight() - 12;
        double handTop = handBottom - 46;
        return switch (slot) {
            case LEFT_HAND -> new UiRect(center - 50, handTop, center - 4, handBottom);
            case RIGHT_HAND -> new UiRect(center + 4, handTop, center + 50, handBottom);
            case MOUTH -> new UiRect(center - 19, handTop - 44, center + 19, handTop - 6);
        };
    }

    private static Arguments surface(String name, int width, int height, float scale) {
        return Arguments.of(name, metrics(width, height, scale));
    }

    private static RenderSurfaceMetrics metrics(int width, int height, float scale) {
        return new RenderSurfaceMetrics(width, height, width, height, scale, scale);
    }

    private static ListGeometry expected(UiLayoutContext layout) {
        double center = layout.logicalWidth() / 2;
        double handBottom = layout.logicalHeight() - 12;
        double handTop = handBottom - 46;
        UiRect left = layout.toFramebuffer(new UiRect(center - 50, handTop, center - 4, handBottom));
        UiRect right = layout.toFramebuffer(new UiRect(center + 4, handTop, center + 50, handBottom));
        UiRect mouth = layout.toFramebuffer(
                new UiRect(center - 19, handTop - 44, center + 19, handTop - 6));
        return new ListGeometry(left, right, mouth);
    }

    private record ListGeometry(UiRect left, UiRect right, UiRect mouth) {}

    private record SnapshotCase(
            String name,
            HudPresentationSnapshot snapshot,
            long expectedIcons,
            SnapshotKind kind) {}

    private enum SnapshotKind {
        NORMAL,
        RIGHT_TWO_HANDED,
        MOUTH_TWO_HANDED,
        LONG_NAME,
        CREATIVE
    }
}
