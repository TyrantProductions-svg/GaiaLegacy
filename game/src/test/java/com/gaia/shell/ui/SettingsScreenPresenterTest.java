package com.gaia.shell.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gaia.interaction.GameMode;
import com.gaia.settings.SettingsDefaults;
import com.gaia.settings.SettingsDraftSnapshot;
import com.gaia.settings.SettingsSnapshot;
import com.gaia.shell.ModalId;
import com.gaia.shell.ProductShellSnapshot;
import com.gaia.shell.ScreenCommand;
import com.gaia.shell.ScreenId;
import com.gaia.shell.ScreenReturnTarget;
import com.gaia.shell.save.EmptySaveCatalog;
import com.overlord.core.input.UiInputSnapshot;
import com.overlord.renderer.ui.BitmapFont;
import com.overlord.renderer.ui.BitmapGlyph;
import com.overlord.renderer.ui.TextRenderer;
import com.overlord.renderer.ui.UiDrawCommand;
import com.overlord.renderer.ui.UiLayoutContext;
import com.overlord.renderer.ui.UiTextureId;
import com.overlord.renderer.ui.UiUvRect;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class SettingsScreenPresenterTest {
    private static final UiLayoutContext CONTEXT = ProductScreenPresenterTest.context();
    private static final ProductShellSnapshot SETTINGS = new ProductShellSnapshot(
            ScreenId.SETTINGS,
            Optional.empty(),
            Optional.of(ScreenReturnTarget.MAIN_MENU));

    @Test
    void rendersEveryApprovedRowAppliedAndDraftValueRangeAndApplicationTiming() {
        SettingsDraftSnapshot settings = dirtySettings();
        ProductUiLayout layout = presenter(() -> settings).present(SETTINGS, CONTEXT);
        List<String> lines = renderedLines(layout);

        assertRow(lines, "VSYNC", "APPLIED ON", "DRAFT OFF");
        assertRow(lines, "FOV", "APPLIED 70", "DRAFT 90", "RANGE 50-100 DEG");
        assertRow(
                lines,
                "MOUSE SENSITIVITY",
                "APPLIED 0.10",
                "DRAFT 0.25",
                "RANGE 0.02-0.50");
        assertRow(lines, "INVERT Y", "APPLIED OFF", "DRAFT ON");
        assertRow(
                lines,
                "RENDER DISTANCE",
                "APPLIED 4",
                "DRAFT 7",
                "RANGE 2-8",
                "NEXT NEW WORLD");
        assertRow(
                lines,
                "MASTER VOLUME",
                "APPLIED 100%",
                "DRAFT 80%",
                "RANGE 0-100%");
        assertRow(
                lines,
                "MUSIC VOLUME",
                "APPLIED 65%",
                "DRAFT 40%",
                "RANGE 0-100%");
        assertRow(
                lines,
                "SFX VOLUME",
                "APPLIED 100%",
                "DRAFT 60%",
                "RANGE 0-100%");
        assertRow(
                lines,
                "MUTE WHEN UNFOCUSED",
                "APPLIED ON",
                "DRAFT OFF");
        assertRow(
                lines,
                "DEFAULT GAME MODE",
                "APPLIED SURVIVAL",
                "DRAFT CREATIVE",
                "NEXT NEW WORLD");
        assertRow(
                lines,
                "DEBUG HUD DEFAULT",
                "APPLIED OFF",
                "DRAFT ON",
                "NEXT GAME SESSION");

        String allText = String.join("\n", lines);
        assertFalse(allText.contains("FULLSCREEN"));
        assertFalse(allText.contains("WINDOW MODE"));
        assertFalse(allText.contains("UI SCALE"));
    }

    @Test
    void exposesExactSettingsActionsAndEnablesApplyOnlyForDirtyState() {
        AtomicReference<SettingsDraftSnapshot> settings =
                new AtomicReference<>(cleanSettings());
        ProductScreenPresenter presenter = presenter(settings::get);

        ProductUiLayout clean = presenter.present(SETTINGS, CONTEXT);

        assertEquals(approvedSettingsActions(), actions(clean));
        assertFalse(clean.region(UiActionId.APPLY_SETTINGS).enabled());

        settings.set(dirtySettings());
        ProductUiLayout dirty = presenter.present(SETTINGS, CONTEXT);

        assertEquals(approvedSettingsActions(), actions(dirty));
        assertTrue(dirty.region(UiActionId.APPLY_SETTINGS).enabled());
    }

    @Test
    void dirtySettingsModalReplacesRowsWithExactlyApplyDiscardAndCancel() {
        ProductShellSnapshot modal = new ProductShellSnapshot(
                ScreenId.SETTINGS,
                Optional.of(ModalId.DIRTY_SETTINGS_CONFIRMATION),
                Optional.of(ScreenReturnTarget.PAUSED));

        ProductUiLayout layout = presenter(SettingsScreenPresenterTest::dirtySettings)
                .present(modal, CONTEXT);

        assertEquals(
                List.of(
                        UiActionId.APPLY_SETTINGS,
                        UiActionId.DISCARD_SETTINGS,
                        UiActionId.CANCEL_SETTINGS),
                actions(layout));
        assertEquals(actions(layout), enabledActions(layout));
        assertFalse(layout.hitRegions().stream()
                .anyMatch(region -> region.action() == UiActionId.BACK));
    }

    @Test
    void heldAdjustmentDoesNotRepeatUntilANewPressedSampleArrives() {
        ProductUiLayout layout = presenter(SettingsScreenPresenterTest::dirtySettings)
                .present(SETTINGS, CONTEXT);
        UiHitRegion increment = layout.region(UiActionId.FOV_INCREMENT);
        ProductScreenInputController input = new ProductScreenInputController();
        ScreenCommand expected = new ScreenCommand.AdjustSetting(
                ScreenCommand.AdjustmentTarget.FOV,
                ScreenCommand.AdjustmentDirection.INCREMENT);
        UiInputSnapshot firstPress = pointerSample(
                increment, true, true, 1L);

        assertEquals(expected, input.route(firstPress, layout).orElseThrow());
        assertTrue(input.route(firstPress, layout).isEmpty());
        assertTrue(input.route(
                        pointerSample(increment, true, false, 2L), layout)
                .isEmpty());
        assertEquals(
                expected,
                input.route(pointerSample(increment, true, true, 3L), layout)
                        .orElseThrow());
    }

    private static SettingsDraftSnapshot cleanSettings() {
        SettingsSnapshot defaults = SettingsDefaults.schemaV1();
        return new SettingsDraftSnapshot(
                defaults, defaults, false, Optional.empty());
    }

    private static SettingsDraftSnapshot dirtySettings() {
        SettingsSnapshot defaults = SettingsDefaults.schemaV1();
        SettingsSnapshot draft = new SettingsSnapshot(
                1,
                false,
                90.0,
                0.25,
                true,
                7,
                0.80,
                0.40,
                0.60,
                false,
                GameMode.CREATIVE,
                true);
        return new SettingsDraftSnapshot(
                defaults, draft, true, Optional.empty());
    }

    private static ProductScreenPresenter presenter(
            java.util.function.Supplier<SettingsDraftSnapshot> settings) {
        return new ProductScreenPresenter(
                new EmptySaveCatalog(), decodableTextRenderer(), settings);
    }

    private static List<UiActionId> approvedSettingsActions() {
        return List.of(
                UiActionId.VSYNC_TOGGLE,
                UiActionId.FOV_DECREMENT,
                UiActionId.FOV_INCREMENT,
                UiActionId.MOUSE_SENSITIVITY_DECREMENT,
                UiActionId.MOUSE_SENSITIVITY_INCREMENT,
                UiActionId.INVERT_Y_TOGGLE,
                UiActionId.CHUNK_RADIUS_DECREMENT,
                UiActionId.CHUNK_RADIUS_INCREMENT,
                UiActionId.MASTER_VOLUME_DECREMENT,
                UiActionId.MASTER_VOLUME_INCREMENT,
                UiActionId.MUSIC_VOLUME_DECREMENT,
                UiActionId.MUSIC_VOLUME_INCREMENT,
                UiActionId.SFX_VOLUME_DECREMENT,
                UiActionId.SFX_VOLUME_INCREMENT,
                UiActionId.MUTE_WHEN_UNFOCUSED_TOGGLE,
                UiActionId.DEFAULT_GAME_MODE_TOGGLE,
                UiActionId.DEBUG_HUD_DEFAULT_TOGGLE,
                UiActionId.APPLY_SETTINGS,
                UiActionId.BACK);
    }

    private static UiInputSnapshot pointerSample(
            UiHitRegion region,
            boolean mouseDown,
            boolean mousePressed,
            long sampleId) {
        Set<Integer> down = mouseDown ? Set.of(0) : Set.of();
        Set<Integer> pressed = mousePressed ? Set.of(0) : Set.of();
        return new UiInputSnapshot(
                Set.of(),
                Set.of(),
                down,
                pressed,
                List.of(),
                region.centerX(),
                region.centerY(),
                true,
                sampleId);
    }

    private static List<UiActionId> actions(ProductUiLayout layout) {
        return layout.hitRegions().stream().map(UiHitRegion::action).toList();
    }

    private static List<UiActionId> enabledActions(ProductUiLayout layout) {
        return layout.hitRegions().stream()
                .filter(UiHitRegion::enabled)
                .map(UiHitRegion::action)
                .toList();
    }

    private static void assertRow(
            List<String> lines, String label, String... expectedFragments) {
        String row = lines.stream()
                .filter(line -> line.contains(label))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing settings row: " + label));
        for (String fragment : expectedFragments) {
            assertTrue(row.contains(fragment), label + " missing " + fragment + " in " + row);
        }
    }

    private static List<String> renderedLines(ProductUiLayout layout) {
        Map<UiUvRect, Integer> codePoints = glyphCodePoints();
        Map<Double, List<UiDrawCommand>> rows = new TreeMap<>();
        layout.frame().commands().stream()
                .filter(command -> command.texture() == UiTextureId.FONT_ATLAS)
                .forEach(command -> rows
                        .computeIfAbsent(
                                command.framebufferBounds().top(),
                                ignored -> new ArrayList<>())
                        .add(command));

        List<String> lines = new ArrayList<>();
        for (List<UiDrawCommand> row : rows.values()) {
            row.sort(Comparator.comparingDouble(
                    command -> command.framebufferBounds().left()));
            StringBuilder decoded = new StringBuilder();
            double previousRight = Double.NaN;
            for (UiDrawCommand command : row) {
                if (Double.isFinite(previousRight)
                        && command.framebufferBounds().left() - previousRight > 0.5d) {
                    decoded.append(' ');
                }
                Integer codePoint = codePoints.get(command.uv());
                if (codePoint == null) {
                    throw new AssertionError("Unknown test glyph UV: " + command.uv());
                }
                decoded.appendCodePoint(codePoint);
                previousRight = command.framebufferBounds().right();
            }
            lines.add(decoded.toString());
        }
        return List.copyOf(lines);
    }

    private static TextRenderer decodableTextRenderer() {
        Map<Integer, BitmapGlyph> glyphs = new LinkedHashMap<>();
        for (int codePoint = 32; codePoint <= 126; codePoint++) {
            UiUvRect uv = glyphUv(codePoint);
            glyphs.put(codePoint, new BitmapGlyph(codePoint, uv, 1, 0, 1));
        }
        BitmapGlyph missing = new BitmapGlyph(127, glyphUv(127), 1, 0, 1);
        return new TextRenderer(new BitmapFont(128, 8, glyphs, missing));
    }

    private static Map<UiUvRect, Integer> glyphCodePoints() {
        Map<UiUvRect, Integer> codePoints = new LinkedHashMap<>();
        for (int codePoint = 32; codePoint <= 127; codePoint++) {
            codePoints.put(glyphUv(codePoint), codePoint);
        }
        return codePoints;
    }

    private static UiUvRect glyphUv(int codePoint) {
        return new UiUvRect(
                codePoint / 128.0f,
                0.0f,
                (codePoint + 1) / 128.0f,
                1.0f);
    }
}
