package com.gaia.ui.widget;

import com.gaia.interaction.GameMode;
import com.gaia.ui.HudDebugSnapshot;
import com.gaia.ui.HudPresentationSnapshot;
import com.gaia.ui.HudSlotSnapshot;
import com.gaia.ui.HudVisibility;
import com.overlord.assets.ResourceLocation;
import com.overlord.interaction.api.BlockFace;
import com.overlord.interaction.api.BlockHitResult;
import com.overlord.interaction.api.InteractionMode;
import com.overlord.inventory.api.BodySlot;
import com.overlord.renderer.RenderSurfaceMetrics;
import com.overlord.renderer.ui.UiLayoutContext;
import java.util.EnumMap;
import java.util.Optional;

final class WidgetTestSnapshots {
    private static final ResourceLocation DIRT = ResourceLocation.parse("gaia:dirt");

    private WidgetTestSnapshots() {}

    static UiLayoutContext layout(
            int logicalWidth,
            int logicalHeight,
            int framebufferWidth,
            int framebufferHeight,
            float contentScaleX,
            float contentScaleY) {
        return new UiLayoutContext(new RenderSurfaceMetrics(
                logicalWidth,
                logicalHeight,
                framebufferWidth,
                framebufferHeight,
                contentScaleX,
                contentScaleY));
    }

    static HudPresentationSnapshot visibleCleared() {
        return snapshot(
                GameMode.SURVIVAL,
                HudPresentationSnapshot.InteractionPresentation.cleared(),
                visible());
    }

    static HudPresentationSnapshot interaction(
            GameMode gameMode,
            boolean targetPresent,
            int targetX,
            double progress,
            InteractionMode interactionMode,
            HudVisibility visibility) {
        Optional<BlockHitResult> target = targetPresent
                ? Optional.of(target(targetX))
                : Optional.empty();
        Optional<BlockFace> face = targetPresent
                ? Optional.of(BlockFace.EAST)
                : Optional.empty();
        return snapshot(
                gameMode,
                new HudPresentationSnapshot.InteractionPresentation(
                        target,
                        face,
                        progress,
                        interactionMode,
                        Optional.empty(),
                        Optional.empty(),
                        0),
                visibility);
    }

    static HudPresentationSnapshot withVisibility(HudVisibility visibility) {
        return snapshot(
                GameMode.SURVIVAL,
                HudPresentationSnapshot.InteractionPresentation.cleared(),
                visibility);
    }

    static HudVisibility visible() {
        return new HudVisibility(
                true,
                false,
                true,
                HudVisibility.Lifecycle.RUNNING,
                HudVisibility.Reason.VISIBLE);
    }

    static HudVisibility hidden(HudVisibility.Reason reason) {
        HudVisibility.Lifecycle lifecycle = switch (reason) {
            case LOADING -> HudVisibility.Lifecycle.LOADING;
            case SHUTDOWN -> HudVisibility.Lifecycle.SHUTDOWN;
            default -> HudVisibility.Lifecycle.RUNNING;
        };
        return new HudVisibility(false, false, false, lifecycle, reason);
    }

    private static HudPresentationSnapshot snapshot(
            GameMode gameMode,
            HudPresentationSnapshot.InteractionPresentation interaction,
            HudVisibility visibility) {
        EnumMap<BodySlot, HudSlotSnapshot> slots = new EnumMap<>(BodySlot.class);
        for (BodySlot slot : BodySlot.values()) {
            slots.put(slot, HudSlotSnapshot.empty(slot, slot == BodySlot.LEFT_HAND));
        }
        return new HudPresentationSnapshot(
                slots,
                BodySlot.LEFT_HAND,
                false,
                Optional.empty(),
                Optional.empty(),
                gameMode,
                interaction,
                visibility,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                new HudDebugSnapshot(
                        Optional.empty(),
                        new HudDebugSnapshot.FeetPosition(0, 0, 0),
                        new HudDebugSnapshot.Counts(0, 0, 0, 0, 0, 0)));
    }

    private static BlockHitResult target(int blockX) {
        return new BlockHitResult(
                blockX,
                2,
                3,
                blockX + 1,
                2,
                3,
                DIRT,
                1,
                0,
                0,
                blockX + 1.0f,
                2.5f,
                3.5f,
                2.0f);
    }
}
