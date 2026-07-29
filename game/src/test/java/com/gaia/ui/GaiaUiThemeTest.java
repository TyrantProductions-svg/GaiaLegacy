package com.gaia.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
}
