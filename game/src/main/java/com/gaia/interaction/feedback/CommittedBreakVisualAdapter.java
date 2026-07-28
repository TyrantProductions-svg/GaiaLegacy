package com.gaia.interaction.feedback;

import com.overlord.assets.ResourceLocation;
import com.overlord.interaction.api.BlockChangedEvent;
import com.overlord.interaction.api.InteractionAction;
import com.overlord.renderer.particle.ParticleCategory;
import com.overlord.renderer.particle.ParticleEmission;
import com.overlord.renderer.particle.ParticleSystem;
import com.overlord.renderer.texture.TextureRegion;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;

/** Converts committed block-break facts into optional presentation emissions. */
public final class CommittedBreakVisualAdapter {
    public static final int COMMITTED_BURST_COUNT = 24;

    private final ResourceLocation air;
    private final Function<ResourceLocation, TextureRegion> regionResolver;
    private final Consumer<ParticleEmission> particleSink;
    private final VisualFeedbackDiagnostics diagnostics;

    public CommittedBreakVisualAdapter(
            ResourceLocation air,
            Function<ResourceLocation, TextureRegion> regionResolver,
            ParticleSystem particles,
            VisualFeedbackDiagnostics diagnostics) {
        this(air, regionResolver, Objects.requireNonNull(particles, "particles")::emit,
                diagnostics);
    }

    CommittedBreakVisualAdapter(
            ResourceLocation air,
            Function<ResourceLocation, TextureRegion> regionResolver,
            Consumer<ParticleEmission> particleSink,
            VisualFeedbackDiagnostics diagnostics) {
        this.air = Objects.requireNonNull(air, "air");
        this.regionResolver = Objects.requireNonNull(regionResolver, "regionResolver");
        this.particleSink = Objects.requireNonNull(particleSink, "particleSink");
        this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
    }

    public void onBlockChanged(BlockChangedEvent event) {
        Objects.requireNonNull(event, "event");
        if (!isCommittedBreak(event)) {
            return;
        }
        try {
            TextureRegion region = Objects.requireNonNull(
                    regionResolver.apply(event.previousBlock()), "resolved texture region");
            particleSink.accept(new ParticleEmission(
                    ParticleCategory.BREAK_COMMITTED,
                    event.request().x() + 0.5f,
                    event.request().y() + 0.5f,
                    event.request().z() + 0.5f,
                    region,
                    COMMITTED_BURST_COUNT,
                    deterministicSeed(event)));
        } catch (RuntimeException failure) {
            reportSafely(event, failure);
        } catch (Error failure) {
            reportSafely(event, failure);
            throw failure;
        }
    }

    private void reportSafely(BlockChangedEvent event, Throwable failure) {
        try {
            diagnostics.report(event, failure);
        } catch (RuntimeException diagnosticFailure) {
            if (diagnosticFailure != failure) {
                failure.addSuppressed(diagnosticFailure);
            }
        } catch (Error diagnosticFailure) {
            if (failure instanceof Error) {
                if (diagnosticFailure != failure) {
                    failure.addSuppressed(diagnosticFailure);
                }
                return;
            }
            if (diagnosticFailure != failure) {
                diagnosticFailure.addSuppressed(failure);
            }
            throw diagnosticFailure;
        }
    }

    private boolean isCommittedBreak(BlockChangedEvent event) {
        return event.request().context().action() == InteractionAction.PRIMARY
                && !event.previousBlock().equals(air)
                && event.currentBlock().equals(air);
    }

    private static long deterministicSeed(BlockChangedEvent event) {
        long seed = event.request().context().tick();
        seed = seed * 31 + event.request().x();
        seed = seed * 31 + event.request().y();
        seed = seed * 31 + event.request().z();
        return seed * 31 + event.previousBlock().hashCode();
    }
}
