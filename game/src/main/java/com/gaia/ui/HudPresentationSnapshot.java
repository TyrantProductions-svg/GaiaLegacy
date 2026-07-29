package com.gaia.ui;

import com.gaia.interaction.GameMode;
import com.overlord.assets.ResourceLocation;
import com.overlord.interaction.api.BlockFace;
import com.overlord.interaction.api.BlockHitResult;
import com.overlord.interaction.api.InteractionFailureReason;
import com.overlord.interaction.api.InteractionMode;
import com.overlord.inventory.api.BodySlot;
import com.overlord.inventory.api.ItemStack;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** The sole immutable read-only HUD projection consumed by Phase 10 widgets. */
public record HudPresentationSnapshot(
        Map<BodySlot, HudSlotSnapshot> slots,
        BodySlot activeSlot,
        boolean twoHanded,
        Optional<BodySlot> twoHandedAnchor,
        Optional<CreativeSelection> creative,
        GameMode mode,
        InteractionPresentation interaction,
        HudVisibility visibility,
        Optional<SlotTransition> slotTransition,
        Optional<TimedItemName> itemName,
        Optional<ModeNotice> modeNotice,
        HudDebugSnapshot debug) {
    public HudPresentationSnapshot {
        Objects.requireNonNull(slots, "slots");
        EnumMap<BodySlot, HudSlotSnapshot> copy = new EnumMap<>(BodySlot.class);
        for (BodySlot slot : BodySlot.values()) {
            HudSlotSnapshot projected = Objects.requireNonNull(slots.get(slot), "missing slot " + slot);
            if (projected.slot() != slot) {
                throw new IllegalArgumentException("slot projection key does not match its slot");
            }
            copy.put(slot, projected);
        }
        if (slots.size() != BodySlot.values().length) {
            throw new IllegalArgumentException("HUD snapshot must contain exactly the three physical slots");
        }
        slots = Collections.unmodifiableMap(copy);
        activeSlot = Objects.requireNonNull(activeSlot, "activeSlot");
        twoHandedAnchor = Objects.requireNonNull(twoHandedAnchor, "twoHandedAnchor");
        creative = Objects.requireNonNull(creative, "creative");
        mode = Objects.requireNonNull(mode, "mode");
        interaction = Objects.requireNonNull(interaction, "interaction");
        visibility = Objects.requireNonNull(visibility, "visibility");
        slotTransition = Objects.requireNonNull(slotTransition, "slotTransition");
        itemName = Objects.requireNonNull(itemName, "itemName");
        modeNotice = Objects.requireNonNull(modeNotice, "modeNotice");
        debug = Objects.requireNonNull(debug, "debug");
        if (twoHanded != twoHandedAnchor.isPresent()) {
            throw new IllegalArgumentException("two-handed truth and anchor presence must agree");
        }
        validateSlotTopology(slots, activeSlot, twoHandedAnchor);
        if (mode != GameMode.CREATIVE && creative.isPresent()) {
            throw new IllegalArgumentException("Creative selection cannot appear in Survival mode");
        }
    }

    public HudSlotSnapshot slot(BodySlot slot) {
        return slots.get(Objects.requireNonNull(slot, "slot"));
    }

    private static void validateSlotTopology(
            Map<BodySlot, HudSlotSnapshot> slots,
            BodySlot activeSlot,
            Optional<BodySlot> twoHandedAnchor) {
        if (twoHandedAnchor.isEmpty()) {
            for (BodySlot slot : BodySlot.values()) {
                HudSlotSnapshot projection = slots.get(slot);
                if (projection.lockedCompanion()
                        || projection.sharedAnchor().isPresent()
                        || projection.active() != (slot == activeSlot)) {
                    throw new IllegalArgumentException(
                            "non-two-handed slot topology must match the active slot");
                }
            }
            return;
        }

        BodySlot anchor = twoHandedAnchor.orElseThrow();
        if (anchor != BodySlot.LEFT_HAND && anchor != BodySlot.RIGHT_HAND) {
            throw new IllegalArgumentException("two-handed anchor must be a hand");
        }
        BodySlot companion = anchor == BodySlot.LEFT_HAND
                ? BodySlot.RIGHT_HAND
                : BodySlot.LEFT_HAND;
        HudSlotSnapshot anchorProjection = slots.get(anchor);
        HudSlotSnapshot companionProjection = slots.get(companion);
        if (anchorProjection.stack().isEmpty()
                || anchorProjection.lockedCompanion()
                || anchorProjection.sharedAnchor().isPresent()
                || companionProjection.stack().isPresent()
                || !companionProjection.lockedCompanion()
                || !companionProjection.sharedAnchor().equals(Optional.of(anchor))
                || companionProjection.active()) {
            throw new IllegalArgumentException(
                    "two-handed topology requires one stack anchor and one locked companion");
        }

        boolean activeHand = activeSlot == BodySlot.LEFT_HAND || activeSlot == BodySlot.RIGHT_HAND;
        if ((activeHand && (anchor != activeSlot || !anchorProjection.active()))
                || (!activeHand
                        && (anchor != BodySlot.LEFT_HAND || anchorProjection.active()))
                || slots.get(BodySlot.MOUTH).active() != (activeSlot == BodySlot.MOUTH)) {
            throw new IllegalArgumentException(
                    "two-handed presentation anchor must preserve the active body slot");
        }
    }

    public record CreativeSelection(ResourceLocation itemId, boolean infinite) {
        public CreativeSelection {
            itemId = Objects.requireNonNull(itemId, "itemId");
            if (!infinite) {
                throw new IllegalArgumentException("Creative selection must be explicitly infinite");
            }
        }
    }

    public record InteractionPresentation(
            Optional<BlockHitResult> target,
            Optional<BlockFace> hitFace,
            double progress,
            InteractionMode mode,
            Optional<ItemStack> activeItem,
            Optional<InteractionFailureReason> failureReason,
            int crackStage) {
        public InteractionPresentation {
            target = Objects.requireNonNull(target, "target");
            hitFace = Objects.requireNonNull(hitFace, "hitFace");
            mode = Objects.requireNonNull(mode, "mode");
            activeItem = Objects.requireNonNull(activeItem, "activeItem");
            failureReason = Objects.requireNonNull(failureReason, "failureReason");
            if (!Double.isFinite(progress) || progress < 0 || progress > 1) {
                throw new IllegalArgumentException("progress must be finite and within [0, 1]");
            }
            if (crackStage < 0 || crackStage > 9) {
                throw new IllegalArgumentException("crackStage must be within [0, 9]");
            }
            if (target.isPresent() != hitFace.isPresent()) {
                throw new IllegalArgumentException("target and hitFace must be both present or both absent");
            }
        }

        public static InteractionPresentation cleared() {
            return new InteractionPresentation(
                    Optional.empty(),
                    Optional.empty(),
                    0,
                    InteractionMode.NONE,
                    Optional.empty(),
                    Optional.empty(),
                    0);
        }
    }

    public record SlotTransition(BodySlot from, BodySlot to, double normalizedProgress) {
        public SlotTransition {
            from = Objects.requireNonNull(from, "from");
            to = Objects.requireNonNull(to, "to");
            if (from == to) {
                throw new IllegalArgumentException("slot transition endpoints must differ");
            }
            requireUnitInterval(normalizedProgress, "slot transition progress");
        }
    }

    public record TimedItemName(ResourceLocation itemId, double remainingSeconds, double opacity) {
        public TimedItemName {
            itemId = Objects.requireNonNull(itemId, "itemId");
            requirePositiveFinite(remainingSeconds, "item-name remaining time");
            requireUnitInterval(opacity, "item-name opacity");
        }
    }

    public record ModeNotice(GameMode mode, double remainingSeconds, double opacity) {
        public ModeNotice {
            mode = Objects.requireNonNull(mode, "mode");
            requirePositiveFinite(remainingSeconds, "mode-notice remaining time");
            requireUnitInterval(opacity, "mode-notice opacity");
        }
    }

    private static void requirePositiveFinite(double value, String label) {
        if (!Double.isFinite(value) || value <= 0) {
            throw new IllegalArgumentException(label + " must be finite and positive");
        }
    }

    private static void requireUnitInterval(double value, String label) {
        if (!Double.isFinite(value) || value < 0 || value > 1) {
            throw new IllegalArgumentException(label + " must be finite and within [0, 1]");
        }
    }
}
