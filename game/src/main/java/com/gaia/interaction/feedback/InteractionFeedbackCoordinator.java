package com.gaia.interaction.feedback;

import com.gaia.interaction.BlockInteractionViewModel;
import com.gaia.interaction.GameMode;
import com.overlord.assets.ResourceLocation;
import com.overlord.interaction.api.BlockChangedEvent;
import com.overlord.interaction.api.BlockFace;
import com.overlord.interaction.api.BlockHitResult;
import com.overlord.interaction.api.InteractionMode;
import com.overlord.renderer.feedback.BlockDamageVisual;
import com.overlord.renderer.feedback.FeedbackVisibility;
import com.overlord.renderer.feedback.InteractionFeedbackFrame;
import com.overlord.renderer.particle.ParticleCategory;
import com.overlord.renderer.particle.ParticleEmission;
import com.overlord.renderer.particle.ParticleSystem;
import com.overlord.renderer.texture.TextureRegion;
import com.overlord.worlditem.api.WorldItemSnapshot;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/** Owns CPU-only transient interaction presentation state. */
public final class InteractionFeedbackCoordinator {
    public static final int CONTINUOUS_EMISSION_INTERVAL_STEPS = 10;

    private final CommittedBreakVisualAdapter committedBreaks;
    private final ParticleSystem particles;
    private final WorldItemVisualTracker worldItems;
    private final Function<ResourceLocation, TextureRegion> regionResolver;

    private TargetKey cadenceTarget;
    private int validBreakingSteps;
    private double cadenceProgress;
    private boolean interactionEnabled;
    private boolean transientSuppressed;

    public InteractionFeedbackCoordinator(
            CommittedBreakVisualAdapter committedBreaks,
            ParticleSystem particles,
            WorldItemVisualTracker worldItems,
            Function<ResourceLocation, TextureRegion> regionResolver) {
        this.committedBreaks = Objects.requireNonNull(committedBreaks, "committedBreaks");
        this.particles = Objects.requireNonNull(particles, "particles");
        this.worldItems = Objects.requireNonNull(worldItems, "worldItems");
        this.regionResolver = Objects.requireNonNull(regionResolver, "regionResolver");
    }

    public void onBlockChanged(BlockChangedEvent event) {
        committedBreaks.onBlockChanged(Objects.requireNonNull(event, "event"));
    }

    public void fixedUpdate(
            BlockInteractionViewModel view,
            boolean interactionEnabled,
            long tick) {
        Objects.requireNonNull(view, "view");
        if (tick < 0) {
            throw new IllegalArgumentException("tick must be non-negative");
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
        Objects.requireNonNull(view, "view");
        Objects.requireNonNull(worldItemSnapshots, "worldItemSnapshots");
        Objects.requireNonNull(visibility, "visibility");

        Optional<BlockDamageVisual> damage = Optional.empty();
        if (visibility.showGameplayFeedback()
                && interactionEnabled
                && !transientSuppressed
                && isActiveSurvivalBreak(view)) {
            BlockHitResult target = view.target().orElseThrow();
            damage = Optional.of(new BlockDamageVisual(
                    target.blockX(), target.blockY(), target.blockZ(), view.crackStage()));
        }
        return new InteractionFeedbackFrame(
                visibility,
                damage,
                worldItems.reconcile(List.copyOf(worldItemSnapshots)),
                particles.snapshot());
    }

    public void clearTransient() {
        resetCadence();
        interactionEnabled = false;
        transientSuppressed = true;
    }

    public void clearAll() {
        particles.clear();
        worldItems.clear();
        resetCadence();
        interactionEnabled = false;
        transientSuppressed = false;
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
