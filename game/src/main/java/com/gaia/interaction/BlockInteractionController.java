package com.gaia.interaction;

import com.gaia.blocks.BlockDefinition;
import com.gaia.blocks.BlockRegistry;
import com.gaia.blocks.ItemCapability;
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
import com.overlord.voxel.DetailCellState;
import com.overlord.voxel.ParentCellObservation;
import java.util.HashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
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
    private final DetailTargetWorldView detailWorldView;
    private final Optional<CreativeDetailEditTransaction> creativeDetailEdits;
    private final Optional<SurvivalDetailEditTransaction> survivalDetailEdits;
    private final Optional<DetailParentBreakTransaction> detailParentBreaks;
    private final Optional<DetailActionPolicy> detailActionPolicy;
    private final CanonicalBlockInteractionRouteResolver routeResolver =
            new CanonicalBlockInteractionRouteResolver();
    private final DetailPreviewController detailPreviews = new DetailPreviewController();
    private final DetailMaterialSelection detailMaterials = new DetailMaterialSelection(
            ResourceLocation.parse("gaia:stone"), ResourceLocation.parse("gaia:dirt"));
    private BlockInteractionRouteDecision route =
            BlockInteractionRouteDecision.rejected("not_evaluated");
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
                CommittedGameplayFeedback.NONE,
                (x, y, z) -> com.overlord.voxel.ParentCellObservationResult.availableEmpty(),
                Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty());
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
        this(
                modes, targeting, chunks, blocks, inventory, owner, creativeSelection,
                breakTransaction, placementTransaction, baseBreakSpeed, committedFeedback,
                (x, y, z) -> com.overlord.voxel.ParentCellObservationResult.availableEmpty(),
                Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty());
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
            CommittedGameplayFeedback committedFeedback,
            DetailTargetWorldView detailWorldView) {
        this(
                modes, targeting, chunks, blocks, inventory, owner, creativeSelection,
                breakTransaction, placementTransaction, baseBreakSpeed, committedFeedback,
                detailWorldView, Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty());
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
            CommittedGameplayFeedback committedFeedback,
            DetailTargetWorldView detailWorldView,
            CreativeDetailEditTransaction creativeDetailEdits,
            DetailParentBreakTransaction detailParentBreaks) {
        this(
                modes, targeting, chunks, blocks, inventory, owner, creativeSelection,
                breakTransaction, placementTransaction, baseBreakSpeed, committedFeedback,
                detailWorldView,
                Optional.of(Objects.requireNonNull(creativeDetailEdits, "creativeDetailEdits")),
                Optional.empty(),
                Optional.of(Objects.requireNonNull(detailParentBreaks, "detailParentBreaks")),
                Optional.empty());
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
            CommittedGameplayFeedback committedFeedback,
            DetailTargetWorldView detailWorldView,
            CreativeDetailEditTransaction creativeDetailEdits,
            SurvivalDetailEditTransaction survivalDetailEdits,
            DetailParentBreakTransaction detailParentBreaks,
            DetailActionPolicy detailActionPolicy) {
        this(
                modes, targeting, chunks, blocks, inventory, owner, creativeSelection,
                breakTransaction, placementTransaction, baseBreakSpeed, committedFeedback,
                detailWorldView,
                Optional.of(Objects.requireNonNull(creativeDetailEdits, "creativeDetailEdits")),
                Optional.of(Objects.requireNonNull(survivalDetailEdits, "survivalDetailEdits")),
                Optional.of(Objects.requireNonNull(detailParentBreaks, "detailParentBreaks")),
                Optional.of(Objects.requireNonNull(detailActionPolicy, "detailActionPolicy")));
    }

    private BlockInteractionController(
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
            CommittedGameplayFeedback committedFeedback,
            DetailTargetWorldView detailWorldView,
            Optional<CreativeDetailEditTransaction> creativeDetailEdits,
            Optional<SurvivalDetailEditTransaction> survivalDetailEdits,
            Optional<DetailParentBreakTransaction> detailParentBreaks,
            Optional<DetailActionPolicy> detailActionPolicy) {
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
        this.detailWorldView = Objects.requireNonNull(detailWorldView, "detailWorldView");
        this.creativeDetailEdits =
                Objects.requireNonNull(creativeDetailEdits, "creativeDetailEdits");
        this.survivalDetailEdits =
                Objects.requireNonNull(survivalDetailEdits, "survivalDetailEdits");
        this.detailParentBreaks =
                Objects.requireNonNull(detailParentBreaks, "detailParentBreaks");
        this.detailActionPolicy =
                Objects.requireNonNull(detailActionPolicy, "detailActionPolicy");
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
        fixedUpdate(
                input,
                fixedDeltaSeconds,
                tick,
                timestampNanos,
                interactionEnabled,
                false);
    }

    public void fixedUpdate(
            InputSnapshot input,
            double fixedDeltaSeconds,
            long tick,
            long timestampNanos,
            boolean interactionEnabled,
            boolean pickupConsumed) {
        Objects.requireNonNull(input, "input");
        InputSnapshot interactionInput = suppressHeldMouseButtons(input);
        if (modeInput.handle(
                interactionInput, tick, this::cancelAndSuppressMouseInteraction)) {
            failure = Optional.empty();
            route = BlockInteractionRouteDecision.rejected("mode_changed");
            view = snapshot(Optional.empty(), InteractionMode.NONE);
            return;
        }

        SpatialQueryResult<BlockHitResult> targetQuery = interactionEnabled
                ? Objects.requireNonNull(targeting.target(), "targeting result")
                : SpatialQueryResult.available(Optional.empty());
        if (pickupConsumed) {
            Optional<ItemStack> selectedItem = activeItem();
            Set<ItemCapability> capabilities = selectedItem
                    .map(item -> blocks.itemCapabilities(item.itemId()))
                    .orElse(Set.of());
            BlockInteractionIntent intent = new BlockInteractionIntent(
                    interactionInput.isMouseButtonPressed(GameConfig.Input.MOUSE_PRIMARY),
                    interactionInput.isMouseButtonPressed(GameConfig.Input.MOUSE_SECONDARY));
            route = routeResolver.resolve(new BlockInteractionRouteRequest(
                    modes.mode(),
                    selectedItem.map(ItemStack::itemId),
                    capabilities,
                    targetQuery,
                    intent,
                    true));
            unavailableTarget = Optional.empty();
            breakTracker.clear();
            detailPreviews.clear();
            failure = Optional.empty();
            view = snapshot(Optional.empty(), InteractionMode.NONE);
            return;
        }
        if (targetQuery.status() != SpatialQueryResult.Status.AVAILABLE) {
            unavailableTarget = Optional.of(new UnavailableTargetObservation(
                    targetQuery.status(), targetQuery.unavailableKey().orElseThrow()));
            breakTracker.clear();
            detailPreviews.clear();
            failure = Optional.empty();
            route = BlockInteractionRouteDecision.unavailable(
                    targetQuery.status().name().toLowerCase());
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

        Optional<ItemStack> selectedItem = activeItem();
        Set<ItemCapability> capabilities = selectedItem
                .map(item -> blocks.itemCapabilities(item.itemId()))
                .orElse(Set.of());
        boolean precisionActive = capabilities.contains(ItemCapability.DETAIL_PRECISION);
        detailMaterials.handleCycle(
                precisionActive,
                interactionInput.isKeyPressed(GameConfig.Input.KEY_DETAIL_MATERIAL_CYCLE));
        BlockInteractionIntent intent = new BlockInteractionIntent(
                interactionInput.isMouseButtonPressed(GameConfig.Input.MOUSE_PRIMARY),
                interactionInput.isMouseButtonPressed(GameConfig.Input.MOUSE_SECONDARY));
        route = routeResolver.resolve(new BlockInteractionRouteRequest(
                modes.mode(), selectedItem.map(ItemStack::itemId), capabilities,
                targetQuery, intent, pickupConsumed));

        if (precisionActive) {
            breakTracker.clear();
            failure = Optional.empty();
            if (isPrecisionAction(route.route())
                    && (intent.primaryPressed() || intent.secondaryPressed())
                    && (creativeDetailEdits.isPresent() || survivalDetailEdits.isPresent())) {
                detailPreviews.clear();
                if (target.isEmpty()) {
                    failure = Optional.of(BlockInteractionFailures.of(
                            "detail_edit_unavailable"));
                } else if (modes.mode() == GameMode.SURVIVAL) {
                    if (route.route() == BlockInteractionRoute.DETAIL_PRECISION_REMOVE) {
                        completeSurvivalDetailRemoval(
                                target.orElseThrow(), selectedItem, activeSlot(),
                                tick, timestampNanos);
                    } else {
                        completeSurvivalDetailPlacement(
                                target.orElseThrow(), selectedItem, activeSlot(),
                                tick, timestampNanos);
                    }
                } else if (route.route()
                        == BlockInteractionRoute.DETAIL_PRECISION_REMOVE) {
                    completeDetailRemoval(
                            target.orElseThrow(), activeSlot(), tick, timestampNanos);
                } else {
                    completeDetailPlacement(
                            target.orElseThrow(), activeSlot(), tick, timestampNanos);
                }
                view = snapshot(target, InteractionMode.NONE);
                return;
            }
            if (pickupConsumed || target.isEmpty()) {
                detailPreviews.clear();
            } else {
                ResourceLocation tool = selectedItem.orElseThrow().itemId();
                switch (route.route()) {
                    case DETAIL_PRECISION_REMOVE -> detailPreviews.publish(
                            DetailPlacementPreview.forRemoval(
                                    tool,
                                    DetailTargeting.removalTarget(target.orElseThrow())));
                    case DETAIL_PRECISION_PLACE -> publishPlacementPreview(
                            tool, target.orElseThrow());
                    case REJECTED -> {
                        if (route.reason().filter("no_input_edge"::equals).isPresent()) {
                            publishPlacementPreview(tool, target.orElseThrow());
                        } else {
                            detailPreviews.clear();
                        }
                    }
                    default -> detailPreviews.clear();
                }
            }
            view = snapshot(target, InteractionMode.NONE);
            return;
        }
        detailPreviews.clear();
        if (target.map(BlockHitResult::target)
                .filter(DetailRaycastTarget.class::isInstance)
                .isPresent()) {
            if (modes.mode() == GameMode.SURVIVAL) {
                ParentCellObservation observed = detailWorldView.observeCell(
                                target.orElseThrow().blockX(),
                                target.orElseThrow().blockY(),
                                target.orElseThrow().blockZ())
                        .observation().orElse(null);
                Optional<BreakRule> detailRule = Optional.empty();
                if (observed != null && observed.state() instanceof DetailCellState detail) {
                    try {
                        double hardness = DetailCoarseHardness.resolve(
                                DetailParentComposition.fromSupported(detail, blocks));
                        detailRule = Optional.of(BlockInteractionPolicy.forMode(modes.mode())
                                .breakRule(hardness, baseBreakSpeed));
                    } catch (IllegalArgumentException ignored) {
                        detailRule = Optional.empty();
                    }
                }
                BreakTrackerResult detailBreak = breakTracker.update(
                        new BreakUpdate(
                                target,
                                target.orElseThrow().chunkRevision(),
                                modes.mode(),
                                interactionInput.isMouseButtonDown(
                                        GameConfig.Input.MOUSE_PRIMARY),
                                false,
                                detailRule),
                        fixedDeltaSeconds);
                if (detailBreak.status() == BreakTrackerResult.Status.COMPLETED) {
                    completeDetailCoarseRemoval(
                            target.orElseThrow(), selectedItem, activeSlot(), tick, timestampNanos);
                    breakTracker.clear();
                } else if (detailBreak.status() == BreakTrackerResult.Status.UNBREAKABLE) {
                    failure = Optional.of(BlockInteractionFailures.of("unbreakable"));
                }
                if (interactionInput.isMouseButtonPressed(GameConfig.Input.MOUSE_SECONDARY)) {
                    failure = Optional.of(BlockInteractionFailures.of(
                            "detail_target_unsupported"));
                }
                view = snapshot(
                        target,
                        breakTracker.session().isPresent()
                                ? InteractionMode.BREAKING
                                : InteractionMode.NONE);
                return;
            }
            breakTracker.clear();
            if (route.route() == BlockInteractionRoute.DETAIL_COARSE_REMOVE
                    && intent.primaryPressed()) {
                detailPreviews.clear();
                completeDetailCoarseRemoval(
                        target.orElseThrow(), selectedItem, activeSlot(), tick, timestampNanos);
                view = snapshot(target, InteractionMode.NONE);
                return;
            }
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

    private void publishPlacementPreview(
            ResourceLocation tool, BlockHitResult target) {
        detailPreviews.publish(DetailPlacementPreview.forPlacement(
                tool,
                DetailTargeting.placementCandidate(
                        target,
                        detailMaterials.selected(),
                        detailWorldView)));
    }

    private void completeDetailRemoval(
            BlockHitResult hit,
            BodySlot activeSlot,
            long tick,
            long timestampNanos) {
        DetailPrecisionTarget target = DetailTargeting.removalTarget(hit);
        DetailEditResult result = creativeDetailEdits.orElseThrow().executeRemove(
                target, activeSlot, tick, timestampNanos);
        if (result.feedbackEligible()) {
            committedFeedback.onDetailRemovalCommitted(
                    target,
                    target.material(),
                    eventIdentity(hit, tick, timestampNanos));
        }
        failure = detailFailure(result.status());
    }

    private void completeDetailPlacement(
            BlockHitResult hit,
            BodySlot activeSlot,
            long tick,
            long timestampNanos) {
        DetailPlacementCandidate candidate = DetailTargeting.placementCandidate(
                hit, detailMaterials.selected(), detailWorldView);
        DetailEditResult result = creativeDetailEdits.orElseThrow().executePlace(
                candidate, activeSlot, tick, timestampNanos);
        if (result.feedbackEligible()) {
            committedFeedback.onDetailPlacementCommitted(
                    candidate,
                    eventIdentity(hit, tick, timestampNanos));
        }
        failure = detailFailure(result.status());
    }

    private void completeSurvivalDetailRemoval(
            BlockHitResult hit,
            Optional<ItemStack> activeItem,
            BodySlot activeSlot,
            long tick,
            long timestampNanos) {
        if (survivalDetailEdits.isEmpty() || detailActionPolicy.isEmpty()) {
            failure = Optional.of(BlockInteractionFailures.of("detail_survival_deferred"));
            return;
        }
        DetailPrecisionTarget target = DetailTargeting.removalTarget(hit);
        Optional<BlockDefinition> material = blocks.find(target.material());
        if (material.isEmpty()) {
            failure = Optional.of(BlockInteractionFailures.of("detail_unsupported_material"));
            return;
        }
        DetailActionDecision decision = detailActionPolicy.orElseThrow().decide(
                GameMode.SURVIVAL,
                DetailAction.PRECISION_REMOVE,
                activeItem.map(ItemStack::itemId),
                material.orElseThrow(),
                false);
        SurvivalDetailEditResult result = survivalDetailEdits.orElseThrow()
                .removeRecoverable(
                        target,
                        decision,
                        activeSlot,
                        new GaiaInteractionContext(
                                owner, activeSlot,
                                com.overlord.interaction.api.InteractionAction.PRIMARY,
                                tick, timestampNanos));
        if (result.feedbackEligible()) {
            committedFeedback.onDetailRemovalCommitted(
                    target, target.material(), eventIdentity(hit, tick, timestampNanos));
        }
        failure = survivalDetailFailure(result.status());
    }

    private void completeSurvivalDetailPlacement(
            BlockHitResult hit,
            Optional<ItemStack> activeItem,
            BodySlot activeSlot,
            long tick,
            long timestampNanos) {
        if (survivalDetailEdits.isEmpty() || detailActionPolicy.isEmpty()) {
            failure = Optional.of(BlockInteractionFailures.of("detail_survival_deferred"));
            return;
        }
        DetailPlacementCandidate candidate = DetailTargeting.placementCandidate(
                hit, detailMaterials.selected(), detailWorldView);
        Optional<BlockDefinition> material = blocks.find(candidate.material());
        if (material.isEmpty()) {
            failure = Optional.of(BlockInteractionFailures.of("detail_unsupported_material"));
            return;
        }
        DetailActionDecision decision = detailActionPolicy.orElseThrow().decide(
                GameMode.SURVIVAL,
                DetailAction.PRECISION_PLACE,
                activeItem.map(ItemStack::itemId),
                material.orElseThrow(),
                false);
        SurvivalDetailEditResult result = survivalDetailEdits.orElseThrow().place(
                candidate,
                decision,
                activeSlot,
                new GaiaInteractionContext(
                        owner, activeSlot,
                        com.overlord.interaction.api.InteractionAction.SECONDARY,
                        tick, timestampNanos));
        if (result.feedbackEligible()) {
            committedFeedback.onDetailPlacementCommitted(
                    candidate, eventIdentity(hit, tick, timestampNanos));
        }
        failure = survivalDetailFailure(result.status());
    }

    private void completeDetailCoarseRemoval(
            BlockHitResult hit,
            Optional<ItemStack> activeItem,
            BodySlot activeSlot,
            long tick,
            long timestampNanos) {
        ParentCellObservation observed = detailWorldView.observeCell(
                        hit.blockX(), hit.blockY(), hit.blockZ())
                .observation().orElse(null);
        if (observed == null || !(observed.state() instanceof DetailCellState detail)) {
            failure = Optional.of(BlockInteractionFailures.of(
                    "detail_edit_unavailable"));
            return;
        }
        if (detailParentBreaks.isEmpty()) {
            failure = Optional.of(BlockInteractionFailures.of(
                    "detail_edit_unavailable"));
            return;
        }
        DetailParentBreakResult result = modes.mode() == GameMode.SURVIVAL
                ? detailParentBreaks.orElseThrow().executeSurvival(
                        hit,
                        detail,
                        activeItem.map(ItemStack::itemId),
                        activeSlot,
                        tick,
                        timestampNanos)
                : detailParentBreaks.orElseThrow().executeCreative(
                        hit, detail, activeSlot, tick, timestampNanos);
        if (result.feedbackEligible()) {
            DetailPrecisionTarget target = DetailTargeting.removalTarget(hit);
            committedFeedback.onDetailRemovalCommitted(
                    target,
                    target.material(),
                    eventIdentity(hit, tick, timestampNanos));
        }
        failure = result.feedbackEligible()
                ? Optional.empty()
                : Optional.of(BlockInteractionFailures.of("mutation_rejected"));
    }

    private static boolean isPrecisionAction(BlockInteractionRoute route) {
        return route == BlockInteractionRoute.DETAIL_PRECISION_REMOVE
                || route == BlockInteractionRoute.DETAIL_PRECISION_PLACE;
    }

    private static Optional<InteractionFailureReason> detailFailure(
            DetailEditResult.Status status) {
        return switch (status) {
            case APPLIED -> Optional.empty();
            case PLAYER_INTERSECTION -> Optional.of(
                    BlockInteractionFailures.of("player_intersection"));
            case INVALID_CANDIDATE -> Optional.of(
                    BlockInteractionFailures.of("invalid_detail_candidate"));
            case UNAVAILABLE -> Optional.of(
                    BlockInteractionFailures.of("detail_unavailable"));
            case MUTATION_REJECTED -> Optional.of(
                    BlockInteractionFailures.of("mutation_rejected"));
        };
    }

    private static Optional<InteractionFailureReason> survivalDetailFailure(
            SurvivalDetailEditResult.Status status) {
        return switch (status) {
            case APPLIED, APPLIED_WITH_NOTIFICATION_FAILURE -> Optional.empty();
            case INVENTORY_FULL -> Optional.of(
                    BlockInteractionFailures.of("detail_inventory_full"));
            case INVENTORY_ITEM_UNAVAILABLE -> Optional.of(
                    BlockInteractionFailures.of("detail_unit_unavailable"));
            case PLAYER_INTERSECTION -> Optional.of(
                    BlockInteractionFailures.of("player_intersection"));
            case INVALID_CANDIDATE -> Optional.of(
                    BlockInteractionFailures.of("invalid_detail_candidate"));
            case UNAVAILABLE -> Optional.of(
                    BlockInteractionFailures.of("detail_unavailable"));
            case ACTION_REJECTED -> Optional.of(
                    BlockInteractionFailures.of("detail_action_rejected"));
            case MUTATION_REJECTED -> Optional.of(
                    BlockInteractionFailures.of("mutation_rejected"));
        };
    }

    public BlockInteractionViewModel viewModel() {
        return view;
    }

    public Optional<UnavailableTargetObservation> unavailableTarget() {
        return unavailableTarget;
    }

    public void cancel() {
        breakTracker.clear();
        detailPreviews.clear();
        unavailableTarget = Optional.empty();
        route = BlockInteractionRouteDecision.rejected("cancelled");
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
        Optional<ResourceLocation> selectedDetailMaterial = active
                .filter(item -> blocks.itemCapabilities(item.itemId())
                        .contains(ItemCapability.DETAIL_PRECISION))
                .map(ignored -> detailMaterials.selected());
        OptionalInt availableDetailUnitCount = modes.mode() == GameMode.SURVIVAL
                && selectedDetailMaterial.isPresent()
                ? detailUnitCount(selectedDetailMaterial.orElseThrow())
                : OptionalInt.empty();
        return new BlockInteractionSnapshot(
                target,
                target.map(BlockFace::fromHit),
                progress,
                interactionMode,
                active,
                failure,
                crackStage,
                modes.mode(),
                route,
                detailPreviews.current(),
                selectedDetailMaterial,
                availableDetailUnitCount);
    }

    private OptionalInt detailUnitCount(ResourceLocation material) {
        Optional<ResourceLocation> unit = blocks.detailUnitForBlock(material);
        if (unit.isEmpty()) {
            return OptionalInt.of(0);
        }
        BodyInventoryViewModel model = inventory.viewModel(owner).orElseThrow();
        int count = 0;
        for (BodySlot slot : BodySlot.values()) {
            Optional<ItemStackView> stack = model.inventory().stack(slot);
            if (stack.isPresent() && stack.orElseThrow().itemId().equals(unit.orElseThrow())) {
                count = Math.addExact(count, stack.orElseThrow().count());
            }
        }
        return OptionalInt.of(count);
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
