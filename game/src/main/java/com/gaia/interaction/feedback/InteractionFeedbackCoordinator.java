package com.gaia.interaction.feedback;

import com.gaia.interaction.BlockInteractionViewModel;
import com.gaia.interaction.GameMode;
import com.gaia.interaction.DetailPlacementCandidate;
import com.gaia.interaction.DetailPrecisionTarget;
import com.overlord.assets.ResourceLocation;
import com.overlord.interaction.api.BlockChangedEvent;
import com.overlord.interaction.api.BlockFace;
import com.overlord.interaction.api.BlockHitResult;
import com.overlord.interaction.api.InteractionMode;
import com.overlord.renderer.feedback.BlockDamageVisual;
import com.overlord.renderer.feedback.BlockVisualCoordinate;
import com.overlord.renderer.feedback.FeedbackVisibility;
import com.overlord.renderer.feedback.FirstPersonItemVisual;
import com.overlord.renderer.feedback.InteractionFeedbackFrame;
import com.overlord.renderer.feedback.WorldItemFaceRegions;
import com.overlord.renderer.RenderOrigin;
import com.overlord.renderer.particle.ParticleCategory;
import com.overlord.renderer.particle.ParticleEmission;
import com.overlord.renderer.particle.ParticleSystem;
import com.overlord.renderer.texture.TextureRegion;
import com.overlord.worlditem.api.WorldItemSnapshot;
import com.gaia.worlditem.WorldItemPresentationSnapshot;
import com.overlord.voxel.ChunkKey;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;
import com.overlord.physics.SimulationOrigin;

/** Owns CPU-only transient interaction presentation state. */
public final class InteractionFeedbackCoordinator
        implements CommittedGameplayFeedback, AutoCloseable {
    public static final int CONTINUOUS_EMISSION_INTERVAL_STEPS = 10;

    private final CommittedBreakVisualAdapter committedBreaks;
    private final ParticleSystem particles;
    private final WorldItemVisualTracker worldItems;
    private final Function<ResourceLocation, TextureRegion> regionResolver;
    private final Function<ResourceLocation, WorldItemFaceRegions> faceResolver;
    private final FirstPersonActionAnimator firstPersonActions;
    private final FirstPersonMovementPresentation movementPresentation =
            new FirstPersonMovementPresentation();
    private final CameraImpulseController cameraImpulses;
    private final TransientBlockVisualSystem transientBlocks;
    private final GameplayParticleFeedback committedParticles;
    private final DetailPlacementGhostAdapter detailGhosts;

    private TargetKey cadenceTarget;
    private int validBreakingSteps;
    private double cadenceProgress;
    private boolean interactionEnabled;
    private boolean transientSuppressed;
    private boolean closed;

    public InteractionFeedbackCoordinator(
            CommittedBreakVisualAdapter committedBreaks,
            ParticleSystem particles,
            WorldItemVisualTracker worldItems,
            Function<ResourceLocation, TextureRegion> regionResolver) {
        this(
                committedBreaks,
                particles,
                worldItems,
                regionResolver,
                item -> WorldItemFaceRegions.uniform(regionResolver.apply(item)),
                new FirstPersonActionAnimator(),
                new CameraImpulseController(),
                new TransientBlockVisualSystem());
    }

    public InteractionFeedbackCoordinator(
            CommittedBreakVisualAdapter committedBreaks,
            ParticleSystem particles,
            WorldItemVisualTracker worldItems,
            Function<ResourceLocation, TextureRegion> regionResolver,
            Function<ResourceLocation, WorldItemFaceRegions> faceResolver,
            FirstPersonActionAnimator firstPersonActions,
            CameraImpulseController cameraImpulses,
            TransientBlockVisualSystem transientBlocks) {
        this(
                committedBreaks,
                particles,
                worldItems,
                regionResolver,
                faceResolver,
                firstPersonActions,
                cameraImpulses,
                transientBlocks,
                () -> new SimulationOrigin(new ChunkKey(0, 0)));
    }

    public InteractionFeedbackCoordinator(
            CommittedBreakVisualAdapter committedBreaks,
            ParticleSystem particles,
            WorldItemVisualTracker worldItems,
            Function<ResourceLocation, TextureRegion> regionResolver,
            Function<ResourceLocation, WorldItemFaceRegions> faceResolver,
            FirstPersonActionAnimator firstPersonActions,
            CameraImpulseController cameraImpulses,
            TransientBlockVisualSystem transientBlocks,
            Supplier<SimulationOrigin> simulationOrigin) {
        this.committedBreaks = Objects.requireNonNull(committedBreaks, "committedBreaks");
        this.particles = Objects.requireNonNull(particles, "particles");
        this.worldItems = Objects.requireNonNull(worldItems, "worldItems");
        this.regionResolver = Objects.requireNonNull(regionResolver, "regionResolver");
        this.faceResolver = Objects.requireNonNull(faceResolver, "faceResolver");
        this.firstPersonActions = Objects.requireNonNull(
                firstPersonActions, "firstPersonActions");
        this.cameraImpulses = Objects.requireNonNull(cameraImpulses, "cameraImpulses");
        this.transientBlocks = Objects.requireNonNull(transientBlocks, "transientBlocks");
        detailGhosts = new DetailPlacementGhostAdapter(this.faceResolver);
        committedParticles = new GameplayParticleFeedback(
                particles,
                Objects.requireNonNull(simulationOrigin, "simulationOrigin"));
    }

    public void onBlockChanged(BlockChangedEvent event) {
        if (closed) {
            return;
        }
        committedBreaks.onBlockChanged(Objects.requireNonNull(event, "event"));
    }

    @Override
    public void onPlacementCommitted(
            BlockHitResult target,
            ResourceLocation placedItem,
            long eventIdentity) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(placedItem, "placedItem");
        if (closed) {
            return;
        }
        safely(() -> {
            WorldItemFaceRegions faces = Objects.requireNonNull(
                    faceResolver.apply(placedItem), "placed item faces");
            transientBlocks.registerPlacement(
                    new BlockVisualCoordinate(
                            target.adjacentX(), target.adjacentY(), target.adjacentZ()),
                    faces,
                    eventIdentity);
            firstPersonActions.triggerPlacement(eventIdentity);
            cameraImpulses.triggerPlacement(eventIdentity);
            committedParticles.onPlacement(target, faces, eventIdentity);
        });
    }

    @Override
    public void onBreakCommitted(
            BlockHitResult target,
            ResourceLocation brokenItem,
            long eventIdentity) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(brokenItem, "brokenItem");
        if (closed) {
            return;
        }
        safely(() -> {
            WorldItemFaceRegions faces = Objects.requireNonNull(
                    faceResolver.apply(brokenItem), "broken item faces");
            transientBlocks.registerBreak(
                    new BlockVisualCoordinate(
                            target.blockX(), target.blockY(), target.blockZ()),
                    faces,
                    eventIdentity);
            firstPersonActions.triggerBreak(eventIdentity);
            cameraImpulses.triggerBreak(eventIdentity);
            committedParticles.onBreak(target, faces, eventIdentity);
        });
    }

    @Override
    public void onDetailRemovalCommitted(
            DetailPrecisionTarget target,
            ResourceLocation material,
            long eventIdentity) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(material, "material");
        if (closed) {
            return;
        }
        safely(() -> {
            WorldItemFaceRegions faces = Objects.requireNonNull(
                    faceResolver.apply(material), "detail material faces");
            firstPersonActions.triggerBreak(eventIdentity);
            cameraImpulses.triggerBreak(eventIdentity);
            committedParticles.onDetailRemoval(target, faces, eventIdentity);
        });
    }

    @Override
    public void onDetailPlacementCommitted(
            DetailPlacementCandidate candidate,
            long eventIdentity) {
        Objects.requireNonNull(candidate, "candidate");
        if (closed) {
            return;
        }
        safely(() -> {
            WorldItemFaceRegions faces = Objects.requireNonNull(
                    faceResolver.apply(candidate.material()), "detail material faces");
            firstPersonActions.triggerPlacement(eventIdentity);
            cameraImpulses.triggerPlacement(eventIdentity);
            committedParticles.onDetailPlacement(candidate, faces, eventIdentity);
        });
    }

    @Override
    public void onDropCommitted(ResourceLocation item, long eventIdentity) {
        Objects.requireNonNull(item, "item");
        if (closed) {
            return;
        }
        safely(() -> firstPersonActions.triggerDrop(eventIdentity));
    }

    @Override
    public void onPickupCommitted(com.gaia.worlditem.WorldItemPickupReceipt receipt) {
        Objects.requireNonNull(receipt, "receipt");
        if (closed) {
            return;
        }
        safely(() -> committedParticles.onPickup(
                receipt,
                Objects.requireNonNull(
                        faceResolver.apply(receipt.picked().itemId()), "pickup item faces")));
    }

    public void renderUpdate(double frameDeltaSeconds) {
        if (closed) {
            return;
        }
        firstPersonActions.update(frameDeltaSeconds);
        cameraImpulses.update(frameDeltaSeconds);
        if (transientBlocks.isOpen()) {
            transientBlocks.update(frameDeltaSeconds);
        }
    }

    public void fixedMovementUpdate(
            double fixedDeltaSeconds,
            FirstPersonMovementState state) {
        Objects.requireNonNull(state, "state");
        if (closed) {
            return;
        }
        movementPresentation.fixedUpdate(fixedDeltaSeconds, state);
    }

    public void fixedUpdate(
            BlockInteractionViewModel view,
            boolean interactionEnabled,
            long tick) {
        Objects.requireNonNull(view, "view");
        if (tick < 0) {
            throw new IllegalArgumentException("tick must be non-negative");
        }
        if (closed) {
            return;
        }
        particles.fixedUpdate(ParticleSystem.FIXED_STEP_SECONDS);
        this.interactionEnabled = interactionEnabled;

        boolean activeBreaking = isActiveSurvivalBreak(view);
        if (transientSuppressed) {
            resetCadence();
            if (!activeBreaking) {
                transientSuppressed = false;
            }
            return;
        }
        if (!interactionEnabled || !activeBreaking) {
            resetCadence();
            return;
        }

        BlockHitResult target = view.target().orElseThrow();
        TargetKey nextTarget = TargetKey.from(target, view.hitFace());
        if (!nextTarget.equals(cadenceTarget)) {
            cadenceTarget = nextTarget;
            validBreakingSteps = 0;
        } else if (view.progress() < cadenceProgress) {
            validBreakingSteps = 0;
        }
        cadenceProgress = view.progress();
        validBreakingSteps++;
        if (validBreakingSteps == CONTINUOUS_EMISSION_INTERVAL_STEPS) {
            particles.emit(new ParticleEmission(
                    ParticleCategory.BREAK_CONTINUOUS,
                    target.blockX() + 0.5f,
                    target.blockY() + 0.5f,
                    target.blockZ() + 0.5f,
                    Objects.requireNonNull(
                            regionResolver.apply(target.block()), "resolved texture region"),
                    1,
                    continuousSeed(target, tick)));
            validBreakingSteps = 0;
        }
    }

    public InteractionFeedbackFrame snapshot(
            BlockInteractionViewModel view,
            List<WorldItemSnapshot> worldItemSnapshots,
            FeedbackVisibility visibility) {
        return snapshot(
                view,
                worldItemSnapshots,
                visibility,
                new RenderOrigin(new ChunkKey(0, 0)));
    }

    public InteractionFeedbackFrame snapshot(
            BlockInteractionViewModel view,
            List<WorldItemSnapshot> worldItemSnapshots,
            FeedbackVisibility visibility,
            RenderOrigin renderOrigin) {
        Objects.requireNonNull(view, "view");
        Objects.requireNonNull(worldItemSnapshots, "worldItemSnapshots");
        Objects.requireNonNull(visibility, "visibility");
        Objects.requireNonNull(renderOrigin, "renderOrigin");
        if (closed) {
            return closedFrame(visibility);
        }

        return snapshotFrame(
                view,
                worldItems.reconcile(List.copyOf(worldItemSnapshots)),
                1.0f,
                visibility,
                renderOrigin);
    }

    public InteractionFeedbackFrame snapshotPhysical(
            BlockInteractionViewModel view,
            List<WorldItemPresentationSnapshot> worldItemSnapshots,
            float interpolationAlpha,
            FeedbackVisibility visibility) {
        return snapshotPhysical(
                view,
                worldItemSnapshots,
                interpolationAlpha,
                visibility,
                new RenderOrigin(new ChunkKey(0, 0)));
    }

    public InteractionFeedbackFrame snapshotPhysical(
            BlockInteractionViewModel view,
            List<WorldItemPresentationSnapshot> worldItemSnapshots,
            float interpolationAlpha,
            FeedbackVisibility visibility,
            RenderOrigin renderOrigin) {
        Objects.requireNonNull(view, "view");
        Objects.requireNonNull(worldItemSnapshots, "worldItemSnapshots");
        Objects.requireNonNull(visibility, "visibility");
        Objects.requireNonNull(renderOrigin, "renderOrigin");
        if (closed) {
            return closedFrame(visibility);
        }
        return snapshotFrame(
                view,
                worldItems.reconcilePhysical(
                        List.copyOf(worldItemSnapshots), interpolationAlpha),
                interpolationAlpha,
                visibility,
                renderOrigin);
    }

    private InteractionFeedbackFrame snapshotFrame(
            BlockInteractionViewModel view,
            List<com.overlord.renderer.feedback.WorldItemVisual> worldItemVisuals,
            float interpolationAlpha,
            FeedbackVisibility visibility,
            RenderOrigin renderOrigin) {
        Optional<BlockDamageVisual> damage = Optional.empty();
        if (visibility.showGameplayFeedback()
                && interactionEnabled
                && !transientSuppressed
                && isActiveSurvivalBreak(view)) {
            BlockHitResult target = view.target().orElseThrow();
            damage = Optional.of(new BlockDamageVisual(
                    localCoordinate(target.blockX(), renderOrigin.worldOriginX(), "x"),
                    target.blockY(),
                    localCoordinate(target.blockZ(), renderOrigin.worldOriginZ(), "z"),
                    view.crackStage()));
        }
        List<com.overlord.renderer.feedback.TransientBlockVisual> currentTransient =
                visibility.showGameplayFeedback() && transientBlocks.isOpen()
                        ? transientBlocks.snapshot(renderOrigin)
                        : List.of();
        if (visibility.showGameplayFeedback() && interactionEnabled) {
            List<com.overlord.renderer.feedback.TransientBlockVisual> preview =
                    detailGhosts.visuals(view.detailPreview(), renderOrigin);
            if (!preview.isEmpty()) {
                java.util.ArrayList<com.overlord.renderer.feedback.TransientBlockVisual> combined =
                        new java.util.ArrayList<>(currentTransient.size() + 1);
                combined.addAll(currentTransient);
                combined.addAll(preview);
                currentTransient = List.copyOf(combined);
            }
        }
        return new InteractionFeedbackFrame(
                visibility,
                damage,
                worldItemVisuals,
                particles.snapshot(),
                heldItem(view, visibility),
                visibility.showGameplayFeedback()
                        ? movementPresentation.snapshot(interpolationAlpha)
                        : com.overlord.renderer.feedback.FirstPersonMovementVisual.identity(),
                visibility.showGameplayFeedback()
                        ? cameraImpulses.snapshot()
                        : com.overlord.renderer.feedback.CameraImpulseVisual.identity(),
                currentTransient,
                visibility.showGameplayFeedback() && transientBlocks.isOpen()
                        ? transientBlocks.excludedCells(renderOrigin)
                        : List.of());
    }

    private static int localCoordinate(
            int canonical, long origin, String axis) {
        try {
            return Math.toIntExact(Math.subtractExact((long) canonical, origin));
        } catch (ArithmeticException outsideRenderEnvelope) {
            throw new IllegalArgumentException(
                    axis + " feedback coordinate is outside the render envelope",
                    outsideRenderEnvelope);
        }
    }

    private static InteractionFeedbackFrame closedFrame(FeedbackVisibility visibility) {
        return new InteractionFeedbackFrame(
                visibility,
                Optional.empty(),
                List.of(),
                new com.overlord.renderer.feedback.ParticleRenderBatch(List.of()),
                Optional.empty(),
                com.overlord.renderer.feedback.FirstPersonMovementVisual.identity(),
                com.overlord.renderer.feedback.CameraImpulseVisual.identity(),
                List.of(),
                List.of());
    }

    public void clearTransient() {
        resetCadence();
        interactionEnabled = false;
        transientSuppressed = true;
        firstPersonActions.reset();
        movementPresentation.reset();
        cameraImpulses.reset();
        if (transientBlocks.isOpen()) {
            transientBlocks.clear();
        }
    }

    public void clearAll() {
        worldItems.clear();
        particles.clear();
        resetCadence();
        interactionEnabled = false;
        transientSuppressed = false;
        firstPersonActions.reset();
        movementPresentation.reset();
        cameraImpulses.reset();
        if (transientBlocks.isOpen()) {
            transientBlocks.clear();
        }
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        clearAll();
        firstPersonActions.close();
        movementPresentation.close();
        cameraImpulses.close();
        transientBlocks.close();
        closed = true;
    }

    private Optional<FirstPersonItemVisual> heldItem(
            BlockInteractionViewModel view,
            FeedbackVisibility visibility) {
        if (!visibility.showGameplayFeedback()) {
            return Optional.empty();
        }
        if (closed) {
            return Optional.empty();
        }
        return view.activeItem().map(item -> new FirstPersonItemVisual(
                Objects.requireNonNull(
                        faceResolver.apply(item.itemId()), "held item faces"),
                firstPersonActions.snapshot()));
    }

    private static void safely(Runnable feedback) {
        try {
            Objects.requireNonNull(feedback, "feedback").run();
        } catch (RuntimeException failure) {
            System.err.println("[InteractionFeedback] committed feedback failure=" + failure);
        }
    }

    private static boolean isActiveSurvivalBreak(BlockInteractionViewModel view) {
        return view.gameMode() == GameMode.SURVIVAL
                && view.mode() == InteractionMode.BREAKING
                && view.progress() > 0
                && view.target().isPresent();
    }

    private void resetCadence() {
        cadenceTarget = null;
        validBreakingSteps = 0;
        cadenceProgress = 0;
    }

    private static long continuousSeed(BlockHitResult target, long tick) {
        long seed = tick;
        seed = seed * 31 + target.blockX();
        seed = seed * 31 + target.blockY();
        seed = seed * 31 + target.blockZ();
        return seed * 31 + target.block().hashCode();
    }

    private record TargetKey(
            int x,
            int y,
            int z,
            ResourceLocation block,
            Optional<BlockFace> face) {
        private static TargetKey from(
                BlockHitResult target,
                Optional<BlockFace> face) {
            return new TargetKey(
                    target.blockX(),
                    target.blockY(),
                    target.blockZ(),
                    target.block(),
                    Objects.requireNonNull(face, "face"));
        }
    }
}
