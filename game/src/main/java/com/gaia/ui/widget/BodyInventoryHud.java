package com.gaia.ui.widget;

import com.gaia.ui.GaiaUiTheme;
import com.gaia.ui.HudPresentationSnapshot;
import com.gaia.ui.HudSlotSnapshot;
import com.gaia.ui.UiIconDefinition;
import com.gaia.ui.UiIconResolver;
import com.overlord.inventory.api.BodySlot;
import com.overlord.inventory.api.ItemStack;
import com.overlord.renderer.ui.TextRenderer;
import com.overlord.renderer.ui.UiColor;
import com.overlord.renderer.ui.UiDrawCommand;
import com.overlord.renderer.ui.UiDrawList;
import com.overlord.renderer.ui.UiLayoutContext;
import com.overlord.renderer.ui.UiRect;
import com.overlord.renderer.ui.UiTextureId;
import com.overlord.renderer.ui.UiUvRect;
import java.util.EnumMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public final class BodyInventoryHud {
    private static final UiUvRect SOLID_UV = new UiUvRect(0, 0, 1, 1);
    private static final List<BodySlot> PHYSICAL_ORDER =
            List.of(BodySlot.LEFT_HAND, BodySlot.RIGHT_HAND, BodySlot.MOUTH);
    private static final double IDENTITY_SCALE = 0.5;
    private static final double STATE_SCALE = 0.5;
    private static final double NAME_SCALE = 1.0;

    private final UiIconResolver icons;
    private final TextRenderer text;

    public BodyInventoryHud(UiIconResolver icons, TextRenderer text) {
        this.icons = Objects.requireNonNull(icons, "icons");
        this.text = Objects.requireNonNull(text, "text");
    }

    public void append(
            HudPresentationSnapshot snapshot,
            UiLayoutContext layout,
            UiDrawList out) {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(layout, "layout");
        Objects.requireNonNull(out, "out");
        if (!snapshot.visibility().hudVisible()) {
            return;
        }

        Geometry geometry = Geometry.create(layout, snapshot.creative().isPresent());
        EnumMap<BodySlot, UiRect> slots = geometry.slots();

        appendBackgrounds(snapshot, geometry, layout, out);
        appendRims(snapshot, geometry, layout, out);
        appendActiveAndSharedShapes(snapshot, geometry, layout, out);

        EnumMap<BodySlot, ResolvedStack> resolved = resolvePhysicalStacks(snapshot);
        appendIcons(snapshot, geometry, resolved, layout, out);
        appendQuantities(snapshot, geometry, resolved, layout, out);
        appendIdentityAndStateLabels(snapshot, slots, layout, out);
        appendItemName(snapshot, geometry, layout, out);
    }

    private void appendBackgrounds(
            HudPresentationSnapshot snapshot,
            Geometry geometry,
            UiLayoutContext layout,
            UiDrawList out) {
        for (BodySlot slot : PHYSICAL_ORDER) {
            solid(geometry.slots().get(slot), GaiaUiTheme.VOID_BACKGROUND, layout, out);
        }
        if (snapshot.creative().isPresent()) {
            solid(geometry.creative(), GaiaUiTheme.VOID_BACKGROUND, layout, out);
        }
    }

    private void appendRims(
            HudPresentationSnapshot snapshot,
            Geometry geometry,
            UiLayoutContext layout,
            UiDrawList out) {
        boolean creative = snapshot.creative().isPresent();
        for (BodySlot slot : PHYSICAL_ORDER) {
            HudSlotSnapshot projected = snapshot.slot(slot);
            UiRect bounds = geometry.slots().get(slot);
            if (projected.lockedCompanion()) {
                dashedOutline(bounds, GaiaUiTheme.INACTIVE_RIM, layout, out);
            } else if (creative || !projected.active() || projected.stack().isEmpty()) {
                outline(bounds, 1, GaiaUiTheme.INACTIVE_RIM, layout, out);
            }
        }
        if (creative) {
            outline(geometry.creative(), 1, GaiaUiTheme.CREATIVE_ACCENT, layout, out);
        }
    }

    private void appendActiveAndSharedShapes(
            HudPresentationSnapshot snapshot,
            Geometry geometry,
            UiLayoutContext layout,
            UiDrawList out) {
        if (snapshot.creative().isEmpty()) {
            if (snapshot.twoHanded()) {
                UiRect left = geometry.slots().get(BodySlot.LEFT_HAND);
                UiRect right = geometry.slots().get(BodySlot.RIGHT_HAND);
                UiRect shared = new UiRect(left.left(), left.top(), right.right(), right.bottom());
                outline(shared, 1, GaiaUiTheme.ACTIVE_SECONDARY_HALO, layout, out);
                solid(new UiRect(left.right(), left.top() + 22, right.left(), left.top() + 24),
                        GaiaUiTheme.ACTIVE_SECONDARY_HALO, layout, out);
            }
            HudSlotSnapshot active = snapshot.slot(snapshot.activeSlot());
            if (active.active()) {
                doubleRing(
                        activeRingBounds(snapshot, geometry),
                        layout,
                        out);
            }
        }
        appendMouthArc(geometry.slots().get(BodySlot.MOUTH), layout, out);
    }

    private static UiRect activeRingBounds(
            HudPresentationSnapshot snapshot, Geometry geometry) {
        return snapshot.slotTransition()
                .map(transition -> {
                    double remaining = 1.0d - transition.normalizedProgress();
                    double eased = 1.0d - remaining * remaining * remaining;
                    return interpolate(
                            geometry.slots().get(transition.from()),
                            geometry.slots().get(transition.to()),
                            eased);
                })
                .orElseGet(() -> geometry.slots().get(snapshot.activeSlot()));
    }

    private static UiRect interpolate(UiRect from, UiRect to, double progress) {
        return new UiRect(
                lerp(from.left(), to.left(), progress),
                lerp(from.top(), to.top(), progress),
                lerp(from.right(), to.right(), progress),
                lerp(from.bottom(), to.bottom(), progress));
    }

    private static double lerp(double from, double to, double progress) {
        return from + (to - from) * progress;
    }

    private EnumMap<BodySlot, ResolvedStack> resolvePhysicalStacks(
            HudPresentationSnapshot snapshot) {
        EnumMap<BodySlot, ResolvedStack> resolved = new EnumMap<>(BodySlot.class);
        for (BodySlot slot : PHYSICAL_ORDER) {
            snapshot.slot(slot).stack().ifPresent(stack -> resolved.put(
                    slot, new ResolvedStack(stack, icons.resolve(stack.itemId()))));
        }
        return resolved;
    }

    private void appendIcons(
            HudPresentationSnapshot snapshot,
            Geometry geometry,
            EnumMap<BodySlot, ResolvedStack> resolved,
            UiLayoutContext layout,
            UiDrawList out) {
        if (snapshot.twoHanded()) {
            BodySlot anchor = snapshot.twoHandedAnchor().orElseThrow();
            ResolvedStack stack = resolved.get(anchor);
            icon(sharedIcon(geometry), stack.definition(), physicalIconTint(snapshot), layout, out);
        } else {
            for (BodySlot slot : PHYSICAL_ORDER) {
                ResolvedStack stack = resolved.get(slot);
                if (stack != null) {
                    icon(slotIcon(slot, geometry.slots().get(slot)), stack.definition(),
                            physicalIconTint(snapshot), layout, out);
                }
            }
        }
        snapshot.creative().ifPresent(selection -> icon(
                creativeIcon(geometry.creative()),
                icons.resolve(selection.itemId()),
                GaiaUiTheme.CREATIVE_ACCENT,
                layout,
                out));
    }

    private void appendQuantities(
            HudPresentationSnapshot snapshot,
            Geometry geometry,
            EnumMap<BodySlot, ResolvedStack> resolved,
            UiLayoutContext layout,
            UiDrawList out) {
        if (snapshot.twoHanded()) {
            BodySlot anchor = snapshot.twoHandedAnchor().orElseThrow();
            appendQuantity(
                    Integer.toString(resolved.get(anchor).stack().count()),
                    sharedSafeArea(geometry),
                    physicalIconTint(snapshot),
                    false,
                    layout,
                    out);
        } else {
            for (BodySlot slot : PHYSICAL_ORDER) {
                ResolvedStack stack = resolved.get(slot);
                if (stack != null) {
                    appendQuantity(
                            Integer.toString(stack.stack().count()),
                            iconSafeArea(slot, geometry.slots().get(slot)),
                            physicalIconTint(snapshot),
                            slot == BodySlot.MOUTH,
                            layout,
                            out);
                }
            }
        }
        if (snapshot.creative().isPresent()) {
            appendQuantity("\u221e", creativeSafeArea(geometry.creative()),
                    GaiaUiTheme.CREATIVE_ACCENT, false, layout, out);
        }
    }

    private void appendIdentityAndStateLabels(
            HudPresentationSnapshot snapshot,
            EnumMap<BodySlot, UiRect> slots,
            UiLayoutContext layout,
            UiDrawList out) {
        appendCentered("1 LEFT", centerX(slots.get(BodySlot.LEFT_HAND)),
                slots.get(BodySlot.LEFT_HAND).top() + 6, IDENTITY_SCALE,
                GaiaUiTheme.PRIMARY_TEXT, layout, out);
        appendCentered("2 RIGHT", centerX(slots.get(BodySlot.RIGHT_HAND)),
                slots.get(BodySlot.RIGHT_HAND).top() + 6, IDENTITY_SCALE,
                GaiaUiTheme.PRIMARY_TEXT, layout, out);
        appendCentered("3 MOUTH", centerX(slots.get(BodySlot.MOUTH)),
                slots.get(BodySlot.MOUTH).top() + 6, IDENTITY_SCALE,
                GaiaUiTheme.PRIMARY_TEXT, layout, out);

        if (snapshot.creative().isPresent()) {
            for (BodySlot slot : PHYSICAL_ORDER) {
                appendState(slot, "PRESERVED", 0, slots.get(slot),
                        GaiaUiTheme.INACTIVE_RIM, layout, out);
            }
            UiRect creative = Geometry.creativeFor(slots.get(BodySlot.MOUTH));
            appendCentered("CREATIVE", creative.right() + 20, centerY(creative) + 2,
                    STATE_SCALE, GaiaUiTheme.CREATIVE_ACCENT, layout, out);
            return;
        }

        for (BodySlot slot : PHYSICAL_ORDER) {
            HudSlotSnapshot projected = snapshot.slot(slot);
            if (projected.lockedCompanion()) {
                appendState(slot, GaiaUiTheme.LOCKED_LABEL, 0, slots.get(slot),
                        GaiaUiTheme.PRIMARY_TEXT, layout, out);
                continue;
            }
            int stateLine = 0;
            if (projected.active()) {
                appendState(slot, GaiaUiTheme.ACTIVE_LABEL, stateLine++, slots.get(slot),
                        GaiaUiTheme.PRIMARY_TEXT, layout, out);
            }
            if (projected.stack().isEmpty()) {
                appendState(slot, GaiaUiTheme.EMPTY_LABEL, stateLine, slots.get(slot),
                        GaiaUiTheme.PRIMARY_TEXT, layout, out);
            }
        }
    }

    private void appendItemName(
            HudPresentationSnapshot snapshot,
            Geometry geometry,
            UiLayoutContext layout,
            UiDrawList out) {
        snapshot.itemName().ifPresent(timed -> {
            UiIconDefinition definition = icons.resolve(timed.itemId());
            double maxWidth = Math.min(144, layout.logicalWidth() - 4);
            double nameScaleX = pixelGridScale(NAME_SCALE, layout.contentScaleX());
            String displayed = text.truncateToFit(
                    definition.displayName(), nameScaleX,
                    maxWidth * layout.contentScaleX());
            appendCentered(
                    displayed,
                    layout.logicalWidth() / 2,
                    geometry.slots().get(BodySlot.MOUTH).top() - 10,
                    NAME_SCALE,
                    withAlpha(GaiaUiTheme.PRIMARY_TEXT, timed.opacity()),
                    layout,
                    out);
        });
    }

    private void appendState(
            BodySlot slot,
            String label,
            int line,
            UiRect bounds,
            UiColor color,
            UiLayoutContext layout,
            UiDrawList out) {
        double baseline;
        if (slot == BodySlot.MOUTH) {
            baseline = line == 0 ? bounds.bottom() + 5 : bounds.bottom() - 8;
        } else {
            baseline = bounds.bottom() + 5 + line * 6;
        }
        appendCentered(label, centerX(bounds), baseline, STATE_SCALE, color, layout, out);
    }

    private void appendQuantity(
            String quantity,
            UiRect logicalSafeArea,
            UiColor color,
            boolean compactVerticalBand,
            UiLayoutContext layout,
            UiDrawList out) {
        double maxLogicalScale = compactVerticalBand ? 0.375 : 0.5;
        double availableLogicalWidth = logicalSafeArea.right() - logicalSafeArea.left();
        double logicalScale = Math.min(
                maxLogicalScale,
                availableLogicalWidth / text.measure(quantity, 1));
        double scaleX = pixelGridScale(logicalScale, layout.contentScaleX());
        double scaleY = pixelGridScale(logicalScale, layout.contentScaleY());
        double right = layout.snapX(logicalSafeArea.right());
        double x = right - text.measure(quantity, scaleX);
        double baseline = layout.snapY(
                logicalSafeArea.bottom() - (compactVerticalBand ? 0 : 1));
        text.append(quantity, x, baseline, scaleX, scaleY, color,
                Optional.empty(), out);
    }

    private void appendCentered(
            String label,
            double logicalCenterX,
            double logicalBaseline,
            double logicalScale,
            UiColor color,
            UiLayoutContext layout,
            UiDrawList out) {
        double scaleX = pixelGridScale(logicalScale, layout.contentScaleX());
        double scaleY = pixelGridScale(logicalScale, layout.contentScaleY());
        double x = layout.snapX(logicalCenterX) - text.measure(label, scaleX) / 2;
        text.append(label, x, layout.snapY(logicalBaseline), scaleX, scaleY, color,
                Optional.empty(), out);
    }

    private static double pixelGridScale(double logicalScale, float contentScale) {
        double requested = logicalScale * contentScale;
        if (contentScale >= 1.5f && requested < 1.0d) {
            return 1.0d;
        }
        if (requested < 0.75d) {
            return 0.5d;
        }
        return Math.max(1.0d, Math.floor(requested));
    }

    private static void appendMouthArc(
            UiRect mouth, UiLayoutContext layout, UiDrawList out) {
        double center = centerX(mouth);
        solid(new UiRect(center - 8, mouth.bottom() - 4, center - 2, mouth.bottom() - 3),
                GaiaUiTheme.INACTIVE_RIM, layout, out);
        solid(new UiRect(center - 3, mouth.bottom() - 3, center + 3, mouth.bottom() - 1),
                GaiaUiTheme.INACTIVE_RIM, layout, out);
        solid(new UiRect(center + 2, mouth.bottom() - 4, center + 8, mouth.bottom() - 3),
                GaiaUiTheme.INACTIVE_RIM, layout, out);
    }

    private static void doubleRing(UiRect bounds, UiLayoutContext layout, UiDrawList out) {
        outline(bounds, 1, GaiaUiTheme.ACTIVE_PRIMARY_RIM, layout, out);
        outline(inset(bounds, 3), 1, GaiaUiTheme.ACTIVE_SECONDARY_HALO, layout, out);
    }

    private static void dashedOutline(
            UiRect bounds, UiColor color, UiLayoutContext layout, UiDrawList out) {
        for (double x = bounds.left() + 2; x + 6 <= bounds.right() - 2; x += 12) {
            solid(new UiRect(x, bounds.top(), x + 6, bounds.top() + 1), color, layout, out);
            solid(new UiRect(x, bounds.bottom() - 1, x + 6, bounds.bottom()), color, layout, out);
        }
        for (double y = bounds.top() + 2; y + 6 <= bounds.bottom() - 2; y += 12) {
            solid(new UiRect(bounds.left(), y, bounds.left() + 1, y + 6), color, layout, out);
            solid(new UiRect(bounds.right() - 1, y, bounds.right(), y + 6), color, layout, out);
        }
    }

    private static void outline(
            UiRect bounds,
            double thickness,
            UiColor color,
            UiLayoutContext layout,
            UiDrawList out) {
        solid(new UiRect(bounds.left(), bounds.top(), bounds.right(), bounds.top() + thickness),
                color, layout, out);
        solid(new UiRect(bounds.left(), bounds.bottom() - thickness,
                bounds.right(), bounds.bottom()), color, layout, out);
        solid(new UiRect(bounds.left(), bounds.top() + thickness,
                bounds.left() + thickness, bounds.bottom() - thickness), color, layout, out);
        solid(new UiRect(bounds.right() - thickness, bounds.top() + thickness,
                bounds.right(), bounds.bottom() - thickness), color, layout, out);
    }

    private static void solid(
            UiRect logicalBounds,
            UiColor color,
            UiLayoutContext layout,
            UiDrawList out) {
        out.append(new UiDrawCommand(
                UiTextureId.SOLID,
                layout.toFramebuffer(logicalBounds),
                SOLID_UV,
                color,
                Optional.empty()));
    }

    private static void icon(
            UiRect logicalBounds,
            UiIconDefinition definition,
            UiColor tint,
            UiLayoutContext layout,
            UiDrawList out) {
        out.append(new UiDrawCommand(
                UiTextureId.ICON_ATLAS,
                layout.toFramebuffer(logicalBounds),
                definition.region(),
                tint,
                Optional.empty()));
    }

    private static UiColor physicalIconTint(HudPresentationSnapshot snapshot) {
        return snapshot.creative().isPresent()
                ? GaiaUiTheme.INACTIVE_RIM
                : GaiaUiTheme.PRIMARY_TEXT;
    }

    private static UiRect slotIcon(BodySlot slot, UiRect bounds) {
        if (slot == BodySlot.MOUTH) {
            return new UiRect(bounds.left() + 11, bounds.top() + 11,
                    bounds.left() + 27, bounds.top() + 27);
        }
        return new UiRect(bounds.left() + 11, bounds.top() + 7,
                bounds.left() + 35, bounds.top() + 31);
    }

    private static UiRect iconSafeArea(BodySlot slot, UiRect bounds) {
        double inset = slot == BodySlot.MOUTH ? 7 : 7;
        return inset(bounds, inset);
    }

    private static UiRect sharedIcon(Geometry geometry) {
        UiRect left = geometry.slots().get(BodySlot.LEFT_HAND);
        double center = geometry.centerX();
        return new UiRect(center - 12, left.top() + 7, center + 12, left.top() + 31);
    }

    private static UiRect sharedSafeArea(Geometry geometry) {
        UiRect left = geometry.slots().get(BodySlot.LEFT_HAND);
        double center = geometry.centerX();
        return new UiRect(center - 16, left.top() + 7, center + 16, left.top() + 39);
    }

    private static UiRect creativeIcon(UiRect bounds) {
        return new UiRect(bounds.left() + 10, bounds.top() + 7,
                bounds.left() + 28, bounds.top() + 25);
    }

    private static UiRect creativeSafeArea(UiRect bounds) {
        return inset(bounds, 7);
    }

    private static UiRect inset(UiRect bounds, double amount) {
        return new UiRect(bounds.left() + amount, bounds.top() + amount,
                bounds.right() - amount, bounds.bottom() - amount);
    }

    private static double centerX(UiRect bounds) {
        return (bounds.left() + bounds.right()) / 2;
    }

    private static double centerY(UiRect bounds) {
        return (bounds.top() + bounds.bottom()) / 2;
    }

    private static UiColor withAlpha(UiColor base, double alpha) {
        return new UiColor(base.red(), base.green(), base.blue(), (float) alpha);
    }

    private record ResolvedStack(ItemStack stack, UiIconDefinition definition) {}

    private record Geometry(
            double centerX,
            EnumMap<BodySlot, UiRect> slots,
            UiRect creative) {
        private static Geometry create(UiLayoutContext layout, boolean creativeVisible) {
            double minimumHeight = creativeVisible ? 164 : 120;
            double minimumWidth = creativeVisible ? 110 : 100;
            if (layout.logicalWidth() < minimumWidth || layout.logicalHeight() < minimumHeight) {
                throw new IllegalArgumentException(
                        "framebuffer-derived logical UI surface is smaller than the compact HUD");
            }
            double centerX = layout.logicalWidth() / 2;
            double handBottom = layout.logicalHeight() - GaiaUiTheme.BOTTOM_MARGIN;
            double handTop = handBottom - GaiaUiTheme.HAND_SLOT_SIZE;
            EnumMap<BodySlot, UiRect> slots = new EnumMap<>(BodySlot.class);
            slots.put(BodySlot.LEFT_HAND,
                    new UiRect(centerX - 50, handTop, centerX - 4, handBottom));
            slots.put(BodySlot.RIGHT_HAND,
                    new UiRect(centerX + 4, handTop, centerX + 50, handBottom));
            double mouthBottom = handTop - 6;
            slots.put(BodySlot.MOUTH,
                    new UiRect(centerX - 19, mouthBottom - 38, centerX + 19, mouthBottom));
            UiRect creative = creativeFor(slots.get(BodySlot.MOUTH));
            return new Geometry(centerX, slots, creative);
        }

        private static UiRect creativeFor(UiRect mouth) {
            double center = BodyInventoryHud.centerX(mouth);
            return new UiRect(center - 19, mouth.top() - 62,
                    center + 19, mouth.top() - 24);
        }
    }
}
