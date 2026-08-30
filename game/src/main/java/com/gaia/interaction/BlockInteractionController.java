package com.gaia.interaction;

import com.gaia.blocks.BlockDefinition;
import com.gaia.blocks.BlockRegistry;
import com.gaia.inventory.BodyInventoryService;
import com.gaia.interaction.feedback.CommittedGameplayFeedback;
import com.overlord.assets.ResourceLocation;
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
import com.overlord.physics.SpatialQueryResult;
import com.overlord.physics.DetailRaycastTarget;
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
    private final CommittedGameplayFeedback committedFeedback;
    private Optional<InteractionFailureReason> failure = Optional.empty();
    private Optional<UnavailableTargetObservation> unavailableTarget = Optional.empty();
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
        this(
                modes,
                targeting,
                chunks,
                blocks,
                inventory,
                owner,
                creativeSelection,
                breakTransaction,
                placementTransaction,
                baseBreakSpeed,
                CommittedGameplayFeedback.NONE);
    }

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
            double baseBreakSpeed,
            CommittedGameplayFeedback committedFeedback) {
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
        this.committedFeedback = Objects.requireNonNull(
                committedFeedback, "committedFeedback");
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

        SpatialQueryResult<BlockHitResult> targetQuery = interactionEnabled
                ? Objects.requireNonNull(targeting.target(), "targeting result")
                : SpatialQueryResult.available(Optional.empty());
        if (targetQuery.status() != SpatialQueryResult.Status.AVAILABLE) {
            unavailableTarget = Optional.of(new UnavailableTargetObservation(
                    targetQuery.status(), targetQuery.unavailableKey().orElseThrow()));
            breakTracker.clear();
            failure = Optional.empty();
            view = snapshot(Optional.empty(), InteractionMode.NONE);
            return;
        }
        unavailableTarget = Optional.empty();
        Optional<BlockHitResult> target = targetQuery.result();
        if (!interactionEnabled) {
            cancelAndSuppressMouseInteraction();
            view = snapshot(target, InteractionMode.NONE);
            return;
        }
        if (target.map(BlockHitResult::target)
                .filter(DetailRaycastTarget.class::isInstance)
                .isPresent()) {
            breakTracker.clear();
            if (interactionInput.isMouseButtonDown(
                            GameConfig.Input.MOUSE_PRIMARY)
                    || interactionInput.isMouseButtonPressed(
                            GameConfig.Input.MOUSE_SECONDARY)) {
                failure = Optional.of(BlockInteractionFailures.of(
                        "detail_target_unsupported"));
            }
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

    public Optional<UnavailableTargetObservation> unavailableTarget() {
        return unavailableTarget;
    }

    public void cancel() {
        breakTracker.clear();
        unavailableTarget = Optional.empty();
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
        if (isApplied(result.status())) {
            ResourceLocation visualItem = block.item() == null
                    ? block.name()
                    : block.item().id();
            committedFeedback.onBreakCommitted(
                    session.target(), visualItem,
                    eventIdentity(session.target(), tick, timestampNanos));
        }
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
        Optional<ItemStack> selected = activeItem();
        BlockPlacementResult result = placementTransaction.execute(
                target.orElseThrow(),
                selected,
                modes.mode(),
                activeSlot,
                tick,
                timestampNanos);
        if (isApplied(result.status())) {
            committedFeedback.onPlacementCommitted(
                    target.orElseThrow(),
                    selected.orElseThrow().itemId(),
                    eventIdentity(target.orElseThrow(), tick, timestampNanos));
        }
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

    private static boolean isApplied(BlockBreakResult.Status status) {
        return status == BlockBreakResult.Status.APPLIED
                || status == BlockBreakResult.Status.APPLIED_WITH_NOTIFICATION_FAILURE;
    }

    private static boolean isApplied(BlockPlacementResult.Status status) {
        return status == BlockPlacementResult.Status.APPLIED
                || status == BlockPlacementResult.Status.APPLIED_WITH_NOTIFICATION_FAILURE;
    }

    private static long eventIdentity(
            BlockHitResult target, long tick, long timestampNanos) {
        long coordinates = ((long) target.blockX() * 0x9E3779B97F4A7C15L)
                ^ ((long) target.blockY() * 0xC2B2AE3D27D4EB4FL)
                ^ ((long) target.blockZ() * 0x165667B19E3779F9L);
        return coordinates ^ Long.rotateLeft(tick, 17) ^ timestampNanos;
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

    public record UnavailableTargetObservation(
            SpatialQueryResult.Status status,
            ChunkKey key) {
        public UnavailableTargetObservation {
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(key, "key");
            if (status == SpatialQueryResult.Status.AVAILABLE) {
                throw new IllegalArgumentException(
                        "unavailable target status cannot be AVAILABLE");
            }
        }
    }
}
