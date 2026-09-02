package com.gaia.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.overlord.renderer.ui.UiColor;
import java.util.List;
import org.junit.jupiter.api.Test;

class GaiaUiThemeTest {
    @Test
    void exposesApprovedCompactQuietMembraneGeometryAndTiming() {
        assertEquals(46, GaiaUiTheme.HAND_SLOT_SIZE);
        assertEquals(38, GaiaUiTheme.MOUTH_SLOT_SIZE);
        assertEquals(12, GaiaUiTheme.BOTTOM_MARGIN);
        assertEquals(List.of(2, 4, 6, 8, 12, 16), GaiaUiTheme.SPACING);
        assertThrows(UnsupportedOperationException.class,
                () -> GaiaUiTheme.SPACING.add(20));

        assertEquals(0.150, GaiaUiTheme.SLOT_TRANSITION_SECONDS);
        assertEquals(1.5, GaiaUiTheme.ITEM_NAME_DURATION_SECONDS);
        assertEquals(0.250, GaiaUiTheme.ITEM_NAME_FADE_SECONDS);
        assertEquals(1.25, GaiaUiTheme.MODE_NOTICE_DURATION_SECONDS);
        assertEquals(0.250, GaiaUiTheme.MODE_NOTICE_FADE_SECONDS);
    }

    @Test
    void exposesExactAstralMembranePalette() {
        assertEquals(rgba(0x07, 0x10, 0x19, 0xD9), GaiaUiTheme.VOID_BACKGROUND);
        assertEquals(rgb(0xEA, 0xF6, 0xF4), GaiaUiTheme.PRIMARY_TEXT);
        assertEquals(rgb(0x70, 0x8D, 0x94), GaiaUiTheme.INACTIVE_RIM);
        assertEquals(rgb(0x8F, 0xDC, 0xCF), GaiaUiTheme.ACTIVE_PRIMARY_RIM);
        assertEquals(rgb(0x9B, 0x83, 0xCF), GaiaUiTheme.ACTIVE_SECONDARY_HALO);
        assertEquals(rgb(0xE7, 0xD8, 0x9D), GaiaUiTheme.CREATIVE_ACCENT);
        assertEquals(rgb(0xE1, 0x5C, 0x64), GaiaUiTheme.FAILURE_TEXT);
        assertEquals(rgba(0x05, 0x09, 0x0D, 0xD9), GaiaUiTheme.DEBUG_BACKGROUND);
        assertEquals(rgb(0xD6, 0xE0, 0xE3), GaiaUiTheme.DEBUG_TEXT);
    }

    @Test
    void worldFirstShellTokensRetainContrastAfterFinalAlphaComposition() {
        assertEquals(rgb(0x7C, 0xE7, 0xFF), GaiaUiTheme.GAIA_CYAN);
        assertEquals(rgb(0x8D, 0x6F, 0xE8), GaiaUiTheme.LEGACY_VIOLET);
        assertEquals(rgb(0xE6, 0xC9, 0x78), GaiaUiTheme.SPECIAL_GOLD);
        assertEquals(rgb(0xE1, 0x64, 0x6C), GaiaUiTheme.DESTRUCTIVE_RED);
        assertEquals(rgba(0x06, 0x11, 0x1E, 0xD1), GaiaUiTheme.HERO_LEFT_OVERLAY);
        assertEquals(rgba(0x06, 0x11, 0x1E, 0xE6), GaiaUiTheme.SECONDARY_PANEL);

        UiColor brightWorld = rgb(0xB8, 0xD9, 0xF2);
        UiColor darkWorld = rgb(0x12, 0x1D, 0x29);
        for (UiColor world : List.of(brightWorld, darkWorld)) {
            UiColor heroTextSurface = composite(GaiaUiTheme.HERO_LEFT_OVERLAY, world);
            UiColor panelTextSurface = composite(GaiaUiTheme.SECONDARY_PANEL, world);
            assertTrue(contrast(GaiaUiTheme.PRIMARY_TEXT, heroTextSurface) >= 4.5);
            assertTrue(contrast(GaiaUiTheme.PRIMARY_TEXT, panelTextSurface) >= 4.5);
            assertTrue(contrast(GaiaUiTheme.GAIA_CYAN, panelTextSurface) >= 4.5);
        }
    }

    @Test
    void activeEmptyAndLockedStatesHaveShapeAndTextDistinctionWithoutContinuousAnimation() {
        assertEquals(GaiaUiTheme.SlotShape.DOUBLE_RING, GaiaUiTheme.ACTIVE_SHAPE);
        assertEquals(GaiaUiTheme.SlotShape.EMPTY_OUTLINE, GaiaUiTheme.EMPTY_SHAPE);
        assertEquals(GaiaUiTheme.SlotShape.DASHED_COMPANION, GaiaUiTheme.LOCKED_SHAPE);
        assertEquals("ACTIVE", GaiaUiTheme.ACTIVE_LABEL);
        assertEquals("EMPTY", GaiaUiTheme.EMPTY_LABEL);
        assertEquals("LOCKED", GaiaUiTheme.LOCKED_LABEL);
        assertFalse(GaiaUiTheme.CONTINUOUS_SHIMMER);
        assertFalse(GaiaUiTheme.CONTINUOUS_BREATHING);
    }

    private static UiColor rgb(int red, int green, int blue) {
        return rgba(red, green, blue, 0xFF);
    }

    private static UiColor rgba(int red, int green, int blue, int alpha) {
        return new UiColor(red / 255.0f, green / 255.0f, blue / 255.0f, alpha / 255.0f);
    }

    private static UiColor composite(UiColor foreground, UiColor background) {
        float alpha = foreground.alpha();
        return new UiColor(
                foreground.red() * alpha + background.red() * (1.0f - alpha),
                foreground.green() * alpha + background.green() * (1.0f - alpha),
                foreground.blue() * alpha + background.blue() * (1.0f - alpha),
                1.0f);
    }

    private static double contrast(UiColor first, UiColor second) {
        double light = Math.max(luminance(first), luminance(second));
        double dark = Math.min(luminance(first), luminance(second));
        return (light + 0.05) / (dark + 0.05);
    }

    private static double luminance(UiColor color) {
        return 0.2126 * channel(color.red())
                + 0.7152 * channel(color.green())
                + 0.0722 * channel(color.blue());
    }

    private static double channel(float value) {
        return value <= 0.04045 ? value / 12.92
                : Math.pow((value + 0.055) / 1.055, 2.4);
    }
}
