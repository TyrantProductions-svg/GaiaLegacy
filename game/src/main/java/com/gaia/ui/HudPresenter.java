package com.gaia.ui;

import com.gaia.world.streaming.ChunkStreamingMetrics;
import com.gaia.blocks.ItemFormDefinition;
import com.gaia.interaction.BlockInteractionViewModel;
import com.gaia.interaction.GameMode;
import com.overlord.assets.ResourceLocation;
import com.overlord.config.GameConfig;
import com.overlord.core.input.InputSnapshot;
import com.overlord.inventory.api.BodyInventoryViewModel;
import com.overlord.inventory.api.BodySlot;
import com.overlord.inventory.api.ItemStack;
import com.overlord.inventory.api.ItemStackView;
import com.overlord.renderer.metrics.RenderMetricsSnapshot;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/**
 * Captures the only HUD snapshot from read-only Phase 8/9 projections.
 * Its mutable fields are presentation timers and visibility toggles only.
 */
public final class HudPresenter {
    private static final double TIMER_EPSILON = 1.0e-12;

    private final Function<ResourceLocation, Optional<ItemFormDefinition>> itemForms;

    private boolean hudEnabled = true;
    private boolean debugEnabled;
    private long lastInputSample = Long.MIN_VALUE;
    private boolean initialized;
    private BodySlot lastActiveSlot;
    private BodySlot transitionFrom;
    private BodySlot transitionTo;
    private double transitionElapsed;
    private ResourceLocation displayedItem;
    private double itemNameRemaining;
    private GameMode lastMode;
    private double modeNoticeRemaining;
    private boolean interactionSuppressed;

    public HudPresenter(Map<ResourceLocation, ItemFormDefinition> itemForms) {
        Objects.requireNonNull(itemForms, "itemForms");
        for (Map.Entry<ResourceLocation, ItemFormDefinition> entry : itemForms.entrySet()) {
            ResourceLocation id = Objects.requireNonNull(entry.getKey(), "item form id");
            ItemFormDefinition definition =
                    Objects.requireNonNull(entry.getValue(), "item form definition");
            if (!id.equals(definition.id())) {
                throw new IllegalArgumentException("item form lookup key must match definition id");
            }
        }
        Map<ResourceLocation, ItemFormDefinition> copied = Map.copyOf(itemForms);
        this.itemForms = id -> Optional.ofNullable(copied.get(id));
    }

    public HudPresenter(
            Function<ResourceLocation, Optional<ItemFormDefinition>> itemForms) {
        this.itemForms = Objects.requireNonNull(itemForms, "itemForms");
    }

    public HudPresentationSnapshot capture(FrameInput input) {
        Objects.requireNonNull(input, "input");
        processPresentationInput(input);

        EnumMap<BodySlot, ItemStack> copiedStacks = copyStacks(input.inventory());
        BodySlot activeSlot = Objects.requireNonNull(input.inventory().activeSlot(), "activeSlot");
        GameMode gameMode = Objects.requireNonNull(input.interaction().gameMode(), "gameMode");
        boolean twoHanded = isTwoHanded(copiedStacks);
        BodySlot twoHandedAnchor = twoHanded
                ? (activeSlot == BodySlot.RIGHT_HAND
                        ? BodySlot.RIGHT_HAND
                        : BodySlot.LEFT_HAND)
                : null;
        EnumMap<BodySlot, HudSlotSnapshot> slots =
                projectSlots(copiedStacks, activeSlot, twoHandedAnchor);
        Optional<HudPresentationSnapshot.CreativeSelection> creative =
                creativeSelection(input.interaction(), gameMode);
        ResourceLocation currentItem = displayedItem(copiedStacks, activeSlot, creative);

        HudVisibility visibility = visibility(input);
        if (!visibility.interactionEligible()) {
            clearTransientPresentation(
                    activeSlot,
                    currentItem,
                    gameMode,
                    visibility.reason() != HudVisibility.Reason.HUD_DISABLED);
        } else {
            advancePresentation(activeSlot, currentItem, gameMode, input.frameDeltaSeconds());
        }

        HudPresentationSnapshot.InteractionPresentation interaction =
                interactionProjection(
                        input.interaction(),
                        visibility.interactionEligible()
                                || visibility.reason() == HudVisibility.Reason.HUD_DISABLED);
        initialized = true;

        return new HudPresentationSnapshot(
                slots,
                activeSlot,
                twoHanded,
                Optional.ofNullable(twoHandedAnchor),
                creative,
                gameMode,
                interaction,
                visibility,
                slotTransition(),
                timedItemName(),
                modeNotice(),
                new HudDebugSnapshot(
                        input.previousFrameMetrics(), input.feet(), input.counts(),
                        input.streamingMetrics()));
    }

    private void processPresentationInput(FrameInput input) {
        if (input.inputSampleId() < lastInputSample) {
            throw new IllegalArgumentException(
                    "inputSampleId must be monotonically non-decreasing for the presenter lifetime");
        }
        if (input.inputSampleId() == lastInputSample) {
            return;
        }
        lastInputSample = input.inputSampleId();
        if (!input.firstFixedStep()
                || input.lifecycle() != HudVisibility.Lifecycle.RUNNING) {
            return;
        }
        if (input.input().isKeyPressed(GameConfig.Input.KEY_TOGGLE_HUD)) {
            hudEnabled = !hudEnabled;
        }
        if (input.input().isKeyPressed(GameConfig.Input.KEY_TOGGLE_DEBUG_HUD)) {
            debugEnabled = !debugEnabled;
        }
    }

    private static EnumMap<BodySlot, ItemStack> copyStacks(BodyInventoryViewModel inventory) {
        Objects.requireNonNull(inventory, "inventory");
        Objects.requireNonNull(inventory.inventory(), "inventory view");
        EnumMap<BodySlot, ItemStack> copied = new EnumMap<>(BodySlot.class);
        for (BodySlot slot : BodySlot.values()) {
            Optional<ItemStackView> projected = Objects.requireNonNull(
                    inventory.inventory().stack(slot), "stack projection for " + slot);
            projected.ifPresent(stack -> copied.put(slot, copyStack(stack)));
        }
        return copied;
    }

    private static ItemStack copyStack(ItemStackView projected) {
        Objects.requireNonNull(projected, "projected stack");
        return new ItemStack(
                Objects.requireNonNull(projected.itemId(), "projected item id"),
                projected.count());
    }

    private boolean isTwoHanded(EnumMap<BodySlot, ItemStack> stacks) {
        ItemStack left = stacks.get(BodySlot.LEFT_HAND);
        ItemStack right = stacks.get(BodySlot.RIGHT_HAND);
        if (left == null
                || right == null
                || !left.itemId().equals(right.itemId())
                || left.count() != right.count()) {
            return false;
        }
        Optional<ItemFormDefinition> form = Objects.requireNonNull(
                itemForms.apply(left.itemId()), "item form lookup result");
        return form.map(ItemFormDefinition::twoHanded).orElse(false);
    }

    private static EnumMap<BodySlot, HudSlotSnapshot> projectSlots(
            EnumMap<BodySlot, ItemStack> copiedStacks,
            BodySlot activeSlot,
            BodySlot twoHandedAnchor) {
        EnumMap<BodySlot, HudSlotSnapshot> projected = new EnumMap<>(BodySlot.class);
        if (twoHandedAnchor != null) {
            BodySlot companion = twoHandedAnchor == BodySlot.LEFT_HAND
                    ? BodySlot.RIGHT_HAND
                    : BodySlot.LEFT_HAND;
            projected.put(
                    twoHandedAnchor,
                    new HudSlotSnapshot(
                            twoHandedAnchor,
                            Optional.of(copiedStacks.get(twoHandedAnchor)),
                            activeSlot == twoHandedAnchor,
                            false,
                            Optional.empty()));
            projected.put(
                    companion,
                    new HudSlotSnapshot(
                            companion,
                            Optional.empty(),
                            false,
                            true,
                            Optional.of(twoHandedAnchor)));
        } else {
            projected.put(
                    BodySlot.LEFT_HAND,
                    slot(BodySlot.LEFT_HAND, copiedStacks.get(BodySlot.LEFT_HAND), activeSlot));
            projected.put(
                    BodySlot.RIGHT_HAND,
                    slot(BodySlot.RIGHT_HAND, copiedStacks.get(BodySlot.RIGHT_HAND), activeSlot));
        }
        projected.put(
                BodySlot.MOUTH,
                slot(BodySlot.MOUTH, copiedStacks.get(BodySlot.MOUTH), activeSlot));
        return projected;
    }

    private static HudSlotSnapshot slot(BodySlot slot, ItemStack stack, BodySlot activeSlot) {
        return new HudSlotSnapshot(
                slot,
                Optional.ofNullable(stack),
                slot == activeSlot,
                false,
                Optional.empty());
    }

    private static Optional<HudPresentationSnapshot.CreativeSelection> creativeSelection(
            BlockInteractionViewModel interaction, GameMode gameMode) {
        if (gameMode != GameMode.CREATIVE) {
            return Optional.empty();
        }
        Optional<ItemStackView> activeItem =
                Objects.requireNonNull(interaction.activeItem(), "activeItem");
        return activeItem.map(item -> new HudPresentationSnapshot.CreativeSelection(
                Objects.requireNonNull(item.itemId(), "Creative item id"), true));
    }

    private static ResourceLocation displayedItem(
            EnumMap<BodySlot, ItemStack> copiedStacks,
            BodySlot activeSlot,
            Optional<HudPresentationSnapshot.CreativeSelection> creative) {
        if (creative.isPresent()) {
            return creative.orElseThrow().itemId();
        }
        ItemStack active = copiedStacks.get(activeSlot);
        return active == null ? null : active.itemId();
    }

    private HudVisibility visibility(FrameInput input) {
        HudVisibility.Reason reason;
        if (input.lifecycle() == HudVisibility.Lifecycle.SHUTDOWN) {
            reason = HudVisibility.Reason.SHUTDOWN;
        } else if (input.lifecycle() == HudVisibility.Lifecycle.LOADING) {
            reason = HudVisibility.Reason.LOADING;
        } else if (!input.focused()) {
            reason = HudVisibility.Reason.FOCUS_LOST;
        } else if (!input.cursorCaptured()) {
            reason = HudVisibility.Reason.CURSOR_RELEASED;
        } else if (input.blockingUi()) {
            reason = HudVisibility.Reason.BLOCKING_UI;
        } else if (!hudEnabled) {
            reason = HudVisibility.Reason.HUD_DISABLED;
        } else {
            reason = HudVisibility.Reason.VISIBLE;
        }

        boolean running = input.lifecycle() == HudVisibility.Lifecycle.RUNNING;
        boolean hudVisible = reason == HudVisibility.Reason.VISIBLE;
        boolean debugVisible = running
                && debugEnabled
                && (reason == HudVisibility.Reason.VISIBLE
                        || reason == HudVisibility.Reason.HUD_DISABLED);
        return new HudVisibility(
                hudVisible,
                debugVisible,
                hudVisible,
                input.lifecycle(),
                reason);
    }

    private void clearTransientPresentation(
            BodySlot activeSlot,
            ResourceLocation currentItem,
            GameMode gameMode,
            boolean suppressInteraction) {
        lastActiveSlot = activeSlot;
        transitionFrom = null;
        transitionTo = null;
        transitionElapsed = 0;
        displayedItem = currentItem;
        itemNameRemaining = 0;
        lastMode = gameMode;
        modeNoticeRemaining = 0;
        if (suppressInteraction) {
            interactionSuppressed = true;
        }
    }

    private void advancePresentation(
            BodySlot activeSlot,
            ResourceLocation currentItem,
            GameMode gameMode,
            double deltaSeconds) {
        if (!initialized) {
            lastActiveSlot = activeSlot;
        } else if (activeSlot != lastActiveSlot) {
            transitionFrom = lastActiveSlot;
            transitionTo = activeSlot;
            transitionElapsed = 0;
            lastActiveSlot = activeSlot;
        } else if (transitionFrom != null) {
            transitionElapsed += deltaSeconds;
            if (transitionElapsed + TIMER_EPSILON >= GaiaUiTheme.SLOT_TRANSITION_SECONDS) {
                transitionFrom = null;
                transitionTo = null;
                transitionElapsed = 0;
            }
        }

        if (!Objects.equals(currentItem, displayedItem)) {
            displayedItem = currentItem;
            itemNameRemaining = currentItem == null ? 0 : GaiaUiTheme.ITEM_NAME_DURATION_SECONDS;
        } else {
            itemNameRemaining = advanceTimer(itemNameRemaining, deltaSeconds);
        }

        if (!initialized) {
            lastMode = gameMode;
        } else if (gameMode != lastMode) {
            lastMode = gameMode;
            modeNoticeRemaining = GaiaUiTheme.MODE_NOTICE_DURATION_SECONDS;
        } else {
            modeNoticeRemaining = advanceTimer(modeNoticeRemaining, deltaSeconds);
        }
    }

    private HudPresentationSnapshot.InteractionPresentation interactionProjection(
            BlockInteractionViewModel interaction, boolean eligible) {
        if (!eligible) {
            if (interactionSuppressed && isNeutral(interaction)) {
                interactionSuppressed = false;
            }
            return HudPresentationSnapshot.InteractionPresentation.cleared();
        }
        if (interactionSuppressed) {
            if (isNeutral(interaction)) {
                interactionSuppressed = false;
            }
            return HudPresentationSnapshot.InteractionPresentation.cleared();
        }
        return new HudPresentationSnapshot.InteractionPresentation(
                Objects.requireNonNull(interaction.target(), "interaction target"),
                Objects.requireNonNull(interaction.hitFace(), "interaction hit face"),
                interaction.progress(),
                Objects.requireNonNull(interaction.mode(), "interaction mode"),
                Objects.requireNonNull(interaction.activeItem(), "interaction active item")
                        .map(HudPresenter::copyStack),
                Objects.requireNonNull(interaction.failureReason(), "interaction failure"),
                interaction.crackStage());
    }

    private static boolean isNeutral(BlockInteractionViewModel interaction) {
        return Objects.requireNonNull(interaction.target(), "interaction target").isEmpty()
                && Objects.requireNonNull(interaction.hitFace(), "interaction hit face").isEmpty()
                && interaction.progress() == 0
                && interaction.mode() == com.overlord.interaction.api.InteractionMode.NONE
                && Objects.requireNonNull(interaction.failureReason(), "interaction failure").isEmpty();
    }

    private Optional<HudPresentationSnapshot.SlotTransition> slotTransition() {
        if (transitionFrom == null) {
            return Optional.empty();
        }
        return Optional.of(new HudPresentationSnapshot.SlotTransition(
                transitionFrom,
                transitionTo,
                transitionElapsed / GaiaUiTheme.SLOT_TRANSITION_SECONDS));
    }

    private Optional<HudPresentationSnapshot.TimedItemName> timedItemName() {
        if (displayedItem == null || itemNameRemaining <= 0) {
            return Optional.empty();
        }
        return Optional.of(new HudPresentationSnapshot.TimedItemName(
                displayedItem,
                itemNameRemaining,
                opacity(itemNameRemaining, GaiaUiTheme.ITEM_NAME_FADE_SECONDS)));
    }

    private Optional<HudPresentationSnapshot.ModeNotice> modeNotice() {
        if (lastMode == null || modeNoticeRemaining <= 0) {
            return Optional.empty();
        }
        return Optional.of(new HudPresentationSnapshot.ModeNotice(
                lastMode,
                modeNoticeRemaining,
                opacity(modeNoticeRemaining, GaiaUiTheme.MODE_NOTICE_FADE_SECONDS)));
    }

    private static double advanceTimer(double remaining, double deltaSeconds) {
        double next = remaining - deltaSeconds;
        return next <= TIMER_EPSILON ? 0 : next;
    }

    private static double opacity(double remaining, double fadeSeconds) {
        return Math.min(1, remaining / fadeSeconds);
    }

    public record FrameInput(
            BodyInventoryViewModel inventory,
            BlockInteractionViewModel interaction,
            Optional<RenderMetricsSnapshot> previousFrameMetrics,
            HudDebugSnapshot.FeetPosition feet,
            HudDebugSnapshot.Counts counts,
            ChunkStreamingMetrics streamingMetrics,
            InputSnapshot input,
            long inputSampleId,
            boolean firstFixedStep,
            double frameDeltaSeconds,
            HudVisibility.Lifecycle lifecycle,
            boolean focused,
            boolean cursorCaptured,
            boolean blockingUi) {
        public FrameInput(
                BodyInventoryViewModel inventory,
                BlockInteractionViewModel interaction,
                Optional<RenderMetricsSnapshot> previousFrameMetrics,
                HudDebugSnapshot.FeetPosition feet,
                HudDebugSnapshot.Counts counts,
                InputSnapshot input,
                long inputSampleId,
                boolean firstFixedStep,
                double frameDeltaSeconds,
                HudVisibility.Lifecycle lifecycle,
                boolean focused,
                boolean cursorCaptured,
                boolean blockingUi) {
            this(inventory, interaction, previousFrameMetrics, feet, counts,
                    ChunkStreamingMetrics.empty(), input, inputSampleId,
                    firstFixedStep, frameDeltaSeconds, lifecycle, focused,
                    cursorCaptured, blockingUi);
        }

        public FrameInput {
            inventory = Objects.requireNonNull(inventory, "inventory");
            interaction = Objects.requireNonNull(interaction, "interaction");
            previousFrameMetrics = Objects.requireNonNull(
                    previousFrameMetrics, "previousFrameMetrics");
            feet = Objects.requireNonNull(feet, "feet");
            counts = Objects.requireNonNull(counts, "counts");
            streamingMetrics = Objects.requireNonNull(
                    streamingMetrics, "streamingMetrics");
            input = Objects.requireNonNull(input, "input");
            lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
            if (inputSampleId < 0) {
                throw new IllegalArgumentException("inputSampleId must be non-negative");
            }
            if (!Double.isFinite(frameDeltaSeconds) || frameDeltaSeconds < 0) {
                throw new IllegalArgumentException("frameDeltaSeconds must be finite and non-negative");
            }
        }
    }
}
