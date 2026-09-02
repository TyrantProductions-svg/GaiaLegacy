package com.gaia.ui;

import com.overlord.renderer.ui.UiColor;
import java.util.List;

/** Immutable Quiet Membrane / Astral Membrane presentation tokens. */
public final class GaiaUiTheme {
    public static final int HAND_SLOT_SIZE = 46;
    public static final int MOUTH_SLOT_SIZE = 38;
    public static final int BOTTOM_MARGIN = 12;
    public static final List<Integer> SPACING = List.of(2, 4, 6, 8, 12, 16);

    public static final double SLOT_TRANSITION_SECONDS = 0.150;
    public static final double ITEM_NAME_DURATION_SECONDS = 1.5;
    public static final double ITEM_NAME_FADE_SECONDS = 0.250;
    public static final double MODE_NOTICE_DURATION_SECONDS = 1.25;
    public static final double MODE_NOTICE_FADE_SECONDS = 0.250;

    public static final UiColor VOID_BACKGROUND = rgba(0x07, 0x10, 0x19, 0xD9);
    public static final UiColor PRIMARY_TEXT = rgb(0xEA, 0xF6, 0xF4);
    public static final UiColor INACTIVE_RIM = rgb(0x70, 0x8D, 0x94);
    public static final UiColor ACTIVE_PRIMARY_RIM = rgb(0x8F, 0xDC, 0xCF);
    public static final UiColor ACTIVE_SECONDARY_HALO = rgb(0x9B, 0x83, 0xCF);
    public static final UiColor CREATIVE_ACCENT = rgb(0xE7, 0xD8, 0x9D);
    public static final UiColor FAILURE_TEXT = rgb(0xE1, 0x5C, 0x64);
    public static final UiColor DEBUG_BACKGROUND = rgba(0x05, 0x09, 0x0D, 0xD9);
    public static final UiColor DEBUG_TEXT = rgb(0xD6, 0xE0, 0xE3);
    public static final UiColor GAIA_CYAN = rgb(0x7C, 0xE7, 0xFF);
    public static final UiColor LEGACY_VIOLET = rgb(0x8D, 0x6F, 0xE8);
    public static final UiColor SPECIAL_GOLD = rgb(0xE6, 0xC9, 0x78);
    public static final UiColor DESTRUCTIVE_RED = rgb(0xE1, 0x64, 0x6C);
    public static final UiColor HERO_LEFT_OVERLAY = rgba(0x06, 0x11, 0x1E, 0xD1);
    public static final UiColor SECONDARY_PANEL = rgba(0x06, 0x11, 0x1E, 0xE6);
    public static final UiColor SECONDARY_PANEL_RIM = rgba(0x7C, 0xE7, 0xFF, 0x38);

    public static final SlotShape ACTIVE_SHAPE = SlotShape.DOUBLE_RING;
    public static final SlotShape EMPTY_SHAPE = SlotShape.EMPTY_OUTLINE;
    public static final SlotShape LOCKED_SHAPE = SlotShape.DASHED_COMPANION;
    public static final String ACTIVE_LABEL = "ACTIVE";
    public static final String EMPTY_LABEL = "EMPTY";
    public static final String LOCKED_LABEL = "LOCKED";
    public static final boolean CONTINUOUS_SHIMMER = false;
    public static final boolean CONTINUOUS_BREATHING = false;

    private GaiaUiTheme() {}

    public enum SlotShape {
        DOUBLE_RING,
        EMPTY_OUTLINE,
        DASHED_COMPANION
    }

    private static UiColor rgb(int red, int green, int blue) {
        return rgba(red, green, blue, 0xFF);
    }

    private static UiColor rgba(int red, int green, int blue, int alpha) {
        return new UiColor(red / 255.0f, green / 255.0f, blue / 255.0f, alpha / 255.0f);
    }
}
