package com.gaia.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.gaia.interaction.GameMode;
import com.gaia.ui.widget.BodyInventoryHud;
import com.gaia.ui.widget.BreakProgressWidget;
import com.gaia.ui.widget.CrosshairWidget;
import com.gaia.ui.widget.DebugHud;
import com.gaia.ui.widget.GameModeWidget;
import com.gaia.ui.widget.InteractionFailureWidget;
import com.gaia.ui.widget.DetailToolWidget;
import com.overlord.assets.AssetManager;
import com.overlord.assets.ResourceLocation;
import com.overlord.interaction.api.BlockFace;
import com.overlord.interaction.api.BlockHitResult;
import com.overlord.interaction.api.InteractionFailureReason;
import com.overlord.interaction.api.InteractionMode;
import com.overlord.inventory.api.BodySlot;
import com.overlord.renderer.RenderSurfaceMetrics;
import com.overlord.renderer.ui.TextRenderer;
import com.overlord.renderer.ui.BitmapFont;
import com.overlord.renderer.ui.BitmapGlyph;
import com.overlord.renderer.ui.TypographyCatalog;
import com.overlord.renderer.ui.TypographyRole;
import com.overlord.renderer.ui.UiDrawList;
import com.overlord.renderer.ui.UiLayoutContext;
import com.overlord.renderer.ui.UiTextureId;
import com.overlord.renderer.ui.UiUvRect;
import java.util.Map;
import java.util.Optional;
import java.util.EnumMap;
import org.junit.jupiter.api.Test;

class GaiaHudScreenIntegrationTest {
    @Test
    void composesEveryApprovedWidgetInRestrainedOrderWithoutASecondCrosshair() {
        AssetManager assets = new AssetManager(getClass().getClassLoader());
        GaiaUiAssets uiAssets = new GaiaUiAssetLoader(assets).load();
        TextRenderer text = new TextRenderer(uiAssets.renderAssets().glyphs());
        UiIconResolver icons = new UiIconResolver(uiAssets.icons());
        GaiaHudScreen screen = new GaiaHudScreen(icons, text);
        UiLayoutContext layout = new UiLayoutContext(
                new RenderSurfaceMetrics(1024, 768, 1024, 768, 1, 1));
        HudPresentationSnapshot snapshot = richSnapshot();

        UiDrawList expected = new UiDrawList();
        new BodyInventoryHud(icons, text).append(snapshot, layout, expected);
        new CrosshairWidget().append(snapshot, layout, expected);
        new BreakProgressWidget().append(snapshot, layout, expected);
        new GameModeWidget(text).append(snapshot, layout, expected);
        new InteractionFailureWidget(text).append(snapshot, layout, expected);
        new DetailToolWidget(text).append(snapshot, layout, expected);
        new DebugHud(text).append(snapshot, layout, expected);

        assertEquals(expected.seal(), screen.compose(snapshot, layout));
    }

    @Test
    void lightweightHudInventoryAndDetailTextUseTheApprovedInterHudRole() {
        AssetManager assets = new AssetManager(getClass().getClassLoader());
        GaiaUiAssets uiAssets = new GaiaUiAssetLoader(assets).load();
        GaiaHudScreen screen = new GaiaHudScreen(
                new UiIconResolver(uiAssets.icons()), hudRoleRenderer());
        UiLayoutContext layout = new UiLayoutContext(
                new RenderSurfaceMetrics(1024, 768, 1024, 768, 1, 1));

        var frame = screen.compose(richSnapshot(), layout);

        assertEquals(true, frame.commands().stream()
                .anyMatch(command -> command.texture() == UiTextureId.FONT_BODY));
        assertEquals(false, frame.commands().stream()
                .anyMatch(command -> command.texture() == UiTextureId.FONT_ATLAS));
        assertEquals(false, frame.commands().stream()
                .anyMatch(command -> command.texture() == UiTextureId.FONT_DISPLAY));
        assertEquals(false, frame.commands().stream()
                .anyMatch(command -> command.texture() == UiTextureId.SOLID
                        && command.framebufferBounds().equals(
                                layout.toFramebuffer(layout.safeArea()))));
    }

    private static TextRenderer hudRoleRenderer() {
        UiUvRect uv = new UiUvRect(0, 0, 1, 1);
        BitmapGlyph missing = new BitmapGlyph(0xfffd, uv, 8, 0, 8);
        BitmapFont font = new BitmapFont(8, 8, Map.of(), missing);
        TypographyCatalog.Face defaultFace = new TypographyCatalog.Face(
                font, UiTextureId.FONT_ATLAS);
        TypographyCatalog.Face hudFace = new TypographyCatalog.Face(
                font, UiTextureId.FONT_BODY);
        return new TextRenderer(new TypographyCatalog(
                Map.of(
                        TypographyRole.DISPLAY_TITLE, defaultFace,
                        TypographyRole.HEADING_LARGE, defaultFace,
                        TypographyRole.BODY, defaultFace,
                        TypographyRole.FUNCTIONAL, defaultFace,
                        TypographyRole.HUD, hudFace),
                TypographyRole.BODY));
    }

    private static HudPresentationSnapshot richSnapshot() {
        EnumMap<BodySlot, HudSlotSnapshot> slots = new EnumMap<>(BodySlot.class);
        for (BodySlot slot : BodySlot.values()) {
            slots.put(slot, HudSlotSnapshot.empty(slot, slot == BodySlot.LEFT_HAND));
        }
        ResourceLocation dirt = ResourceLocation.parse("gaia:dirt");
        BlockHitResult target = new BlockHitResult(
                1, 2, 3, 2, 2, 3, dirt, 1, 0, 0, 2, 2.5f, 3.5f, 2);
        return new HudPresentationSnapshot(
                slots,
                BodySlot.LEFT_HAND,
                false,
                Optional.empty(),
                Optional.empty(),
                GameMode.SURVIVAL,
                new HudPresentationSnapshot.InteractionPresentation(
                        Optional.of(target),
                        Optional.of(BlockFace.EAST),
                        0.5,
                        InteractionMode.BREAKING,
                        Optional.empty(),
                        Optional.of(new InteractionFailureReason(
                                ResourceLocation.parse("gaia:test_failure"))),
                        4),
                new HudVisibility(
                        true,
                        true,
                        true,
                        HudVisibility.Lifecycle.RUNNING,
                        HudVisibility.Reason.VISIBLE),
                Optional.empty(),
                Optional.empty(),
                Optional.of(new HudPresentationSnapshot.ModeNotice(
                        GameMode.SURVIVAL, 1, 1)),
                new HudDebugSnapshot(
                        Optional.empty(),
                        new HudDebugSnapshot.FeetPosition(1, 2, 3),
                        new HudDebugSnapshot.Counts(1, 1, 1, 1, 1, 1)));
    }
}
