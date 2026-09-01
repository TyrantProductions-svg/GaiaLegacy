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
import com.overlord.renderer.ui.UiDrawList;
import com.overlord.renderer.ui.UiLayoutContext;
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
