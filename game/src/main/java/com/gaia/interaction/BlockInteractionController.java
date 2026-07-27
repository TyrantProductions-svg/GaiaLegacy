package com.gaia.interaction;

import com.gaia.blocks.BlockDefinition;
import com.gaia.blocks.BlockRegistry;
import com.gaia.inventory.BodyInventoryService;
import com.overlord.core.input.InputSnapshot;
import com.overlord.config.GameConfig;
import com.overlord.interaction.api.BlockFace;
import com.overlord.interaction.api.BlockHitResult;
import com.overlord.interaction.api.EntityRef;
import com.overlord.interaction.api.InteractionFailureReason;
import com.overlord.interaction.api.InteractionMode;
import com.overlord.inventory.api.BodyInventoryViewModel;
import com.overlord.inventory.api.BodySlot;
import com.overlord.inventory.api.ItemStack;
import com.overlord.inventory.api.ItemStackView;
import com.overlord.voxel.ChunkKey;
import com.overlord.voxel.ChunkRepository;
import java.util.HashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Main-thread, fixed-step gameplay coordinator. */
public final class BlockInteractionController {
    private final GameModeManager modes;
    private final GameModeInputController modeInput;
    private final BlockTargetProvider targeting;
    private final ChunkRepository chunks;
    private final BlockRegistry blocks;
    private final BodyInventoryService inventory;
    private final EntityRef owner;
    private final CreativeSelection creativeSelection;
    private final BlockBreakTracker breakTracker;
    private final BlockBreakTransaction breakTransaction;
    private final BlockPlacementTransaction placementTransaction;
    private final double baseBreakSpeed;
    private Optional<InteractionFailureReason> failure = Optional.empty();
    private BlockInteractionSnapshot view;
    private boolean primarySuppressedUntilRelease;
    private boolean secondarySuppressedUntilRelease;

    public BlockInteractionController(
            GameModeManager modes,
            BlockTargetProvider targeting,
            ChunkRepository chunks,
            BlockRegistry blocks,
            BodyInventoryService inventory,
            EntityRef owner,
            CreativeSelection creativeSelection,
            BlockBreakTransaction breakTransaction,
            BlockPlacementTransaction placementTransaction,
            double baseBreakSpeed) {
        this.modes = Objects.requireNonNull(modes, "modes");
        modeInput = new GameModeInputController(modes);
        this.targeting = Objects.requireNonNull(targeting, "targeting");
        this.chunks = Objects.requireNonNull(chunks, "chunks");
        this.blocks = Objects.requireNonNull(blocks, "blocks");
        this.inventory = Objects.requireNonNull(inventory, "inventory");
        this.owner = Objects.requireNonNull(owner, "owner");
        this.creativeSelection = Objects.requireNonNull(
                creativeSelection, "creativeSelection");
        breakTracker = new BlockBreakTracker();
        this.breakTransaction = Objects.requireNonNull(
                breakTransaction, "breakTransaction");
        this.placementTransaction = Objects.requireNonNull(
                placementTransaction, "placementTransaction");
        if (!Double.isFinite(baseBreakSpeed) || baseBreakSpeed <= 0) {
            throw new IllegalArgumentException(
                    "baseBreakSpeed must be finite and positive");
        }
        this.baseBreakSpeed = baseBreakSpeed;
        view = snapshot(Optional.empty(), InteractionMode.NONE);
    }

    public void fixedUpdate(
            InputSnapshot input,
            double fixedDeltaSeconds,
            long tick,
            long timestampNanos,
            boolean interactionEnabled) {
        Objects.requireNonNull(input, "input");
        InputSnapshot interactionInput = suppressHeldMouseButtons(input);
        if (modeInput.handle(
                interactionInput, tick, this::cancelAndSuppressMouseInteraction)) {
            failure = Optional.empty();
            view = snapshot(Optional.empty(), InteractionMode.NONE);
            return;
        }

        Optional<BlockHitResult> target = interactionEnabled
                ? targeting.target()
                : Optional.empty();
        if (!interactionEnabled) {
            cancelAndSuppressMouseInteraction();
            view = snapshot(target, InteractionMode.NONE);
            return;
        }

        Optional<BreakRule> rule = target.flatMap(hit ->
                blocks.find(hit.block()).map(definition ->
                        BlockInteractionPolicy.forMode(modes.mode())
                                .breakRule(definition, baseBreakSpeed)));
        long revision = target.map(hit -> chunks.revision(
                        ChunkKey.fromWorld(hit.blockX(), hit.blockZ())))
                .orElse(0L);
        if (target.isPresent() && (revision <= 0 || rule.isEmpty())) {
            target = Optional.empty();
            rule = Optional.empty();
            revision = 0;
        }

        boolean primaryBreakInput = modes.mode() == GameMode.SURVIVAL
                ? interactionInput.isMouseButtonDown(GameConfig.Input.MOUSE_PRIMARY)
                : interactionInput.isMouseButtonPressed(GameConfig.Input.MOUSE_PRIMARY);
        BreakTrackerResult breakResult = breakTracker.update(
                new BreakUpdate(
                        target,
                        revision,
                        modes.mode(),
                        primaryBreakInput,
                        false,
                        rule),
                fixedDeltaSeconds);
        if (breakResult.status() == BreakTrackerResult.Status.UNBREAKABLE) {
            failure = Optional.of(BlockInteractionFailures.of("unbreakable"));
        }
        if (breakResult.status() == BreakTrackerResult.Status.COMPLETED) {
            completeBreak(
                    breakResult.session().orElseThrow(), activeSlot(), tick, timestampNanos);
            breakTracker.clear();
            view = snapshot(target, InteractionMode.NONE);
            return;
        }
        if (breakTracker.session().isPresent()) {
            view = snapshot(target, InteractionMode.BREAKING);
            return;
        }

        if (interactionInput.isMouseButtonPressed(GameConfig.Input.MOUSE_SECONDARY)) {
            completePlacement(target, activeSlot(), tick, timestampNanos);
            view = snapshot(target, InteractionMode.PLACING);
            return;
        }
        view = snapshot(target, InteractionMode.NONE);
    }

    public BlockInteractionViewModel viewModel() {
        return view;
    }

    public void cancel() {
        breakTracker.clear();
        view = snapshot(Optional.empty(), InteractionMode.NONE);
    }

    /** Cancels a mode-switch step and masks its already-consumed mouse hold in later batch steps. */
    public void cancelAndSuppressMouseInteraction() {
        cancel();
        primarySuppressedUntilRelease = true;
        secondarySuppressedUntilRelease = true;
    }

    private InputSnapshot suppressHeldMouseButtons(InputSnapshot input) {
        if (!input.isMouseButtonDown(GameConfig.Input.MOUSE_PRIMARY)) {
            primarySuppressedUntilRelease = false;
        }
        if (!input.isMouseButtonDown(GameConfig.Input.MOUSE_SECONDARY)) {
            secondarySuppressedUntilRelease = false;
        }
        if (!primarySuppressedUntilRelease && !secondarySuppressedUntilRelease) {
            return input;
        }

        Set<Integer> down = new HashSet<>(input.downMouseButtons());
        Set<Integer> pressed = new HashSet<>(input.pressedMouseButtons());
        if (primarySuppressedUntilRelease) {
            down.remove(GameConfig.Input.MOUSE_PRIMARY);
            pressed.remove(GameConfig.Input.MOUSE_PRIMARY);
        }
        if (secondarySuppressedUntilRelease) {
            down.remove(GameConfig.Input.MOUSE_SECONDARY);
            pressed.remove(GameConfig.Input.MOUSE_SECONDARY);
        }
        return new InputSnapshot(
                input.downKeys(), input.pressedKeys(), down, pressed, input.scrollDeltas());
    }

    private void completeBreak(
            BlockBreakSession session,
            BodySlot activeSlot,
            long tick,
            long timestampNanos) {
        BlockDefinition block = blocks.require(session.target().block());
        Optional<ItemStack> drop =
                BlockInteractionPolicy.forMode(modes.mode()).producesDrops()
                        && block.item() != null
                        ? Optional.of(new ItemStack(block.item().id(), 1))
                        : Optional.empty();
        BlockBreakResult result = breakTransaction.execute(
                session.target(), drop, activeSlot, tick, timestampNanos);
        failure = switch (result.status()) {
            case APPLIED -> Optional.empty();
            case APPLIED_WITH_NOTIFICATION_FAILURE ->
                    Optional.of(BlockInteractionFailures.of("notification_failed"));
            case RESERVATION_REJECTED ->
                    Optional.of(BlockInteractionFailures.of("drop_capacity"));
            case MUTATION_REJECTED ->
                    Optional.of(BlockInteractionFailures.of("mutation_rejected"));
        };
    }

    private void completePlacement(
            Optional<BlockHitResult> target,
            BodySlot activeSlot,
            long tick,
            long timestampNanos) {
        if (target.isEmpty()) {
            failure = Optional.of(BlockInteractionFailures.of("no_target"));
            return;
        }
        BlockPlacementResult result = placementTransaction.execute(
                target.orElseThrow(),
                activeItem(),
                modes.mode(),
                activeSlot,
                tick,
                timestampNanos);
        failure = switch (result.status()) {
            case APPLIED -> Optional.empty();
            case APPLIED_WITH_NOTIFICATION_FAILURE ->
                    Optional.of(BlockInteractionFailures.of("notification_failed"));
            case NO_ITEM -> Optional.of(BlockInteractionFailures.of("no_item"));
            case UNKNOWN_ITEM -> Optional.of(BlockInteractionFailures.of("not_placeable"));
            case CHUNK_NOT_LOADED -> Optional.of(BlockInteractionFailures.of("chunk_not_loaded"));
            case NOT_REPLACEABLE -> Optional.of(BlockInteractionFailures.of("not_replaceable"));
            case PLAYER_INTERSECTION -> Optional.of(BlockInteractionFailures.of("player_intersection"));
            case INVENTORY_REJECTED -> Optional.of(BlockInteractionFailures.of("inventory_rejected"));
            case MUTATION_REJECTED -> Optional.of(BlockInteractionFailures.of("mutation_rejected"));
        };
    }

    private BodySlot activeSlot() {
        return inventory.viewModel(owner).orElseThrow().activeSlot();
    }

    private Optional<ItemStack> activeItem() {
        if (modes.mode() == GameMode.CREATIVE) {
            return creativeSelection.selected();
        }
        BodyInventoryViewModel model = inventory.viewModel(owner).orElseThrow();
        return model.inventory().stack(model.activeSlot())
                .map(item -> new ItemStack(item.itemId(), item.count()));
    }

    private BlockInteractionSnapshot snapshot(
            Optional<BlockHitResult> target,
            InteractionMode interactionMode) {
        Optional<BlockBreakSession> session = breakTracker.session();
        double progress = session.map(BlockBreakSession::progress).orElse(0.0);
        int crackStage = session.map(BlockBreakSession::crackStage).orElse(0);
        Optional<ItemStackView> active = activeItem().map(item -> item);
        return new BlockInteractionSnapshot(
                target,
                target.map(BlockFace::fromHit),
                progress,
                interactionMode,
                active,
                failure,
                crackStage,
                modes.mode());
    }
}
