package com.gaia.interaction.feedback;

import com.gaia.worlditem.WorldItemPickupReceipt;
import com.gaia.worlditem.WorldItemPickupResult;
import com.overlord.assets.ResourceLocation;
import com.overlord.renderer.particle.ParticleCategory;
import com.overlord.renderer.particle.ParticleEmission;
import com.overlord.renderer.particle.ParticlePriority;
import com.overlord.renderer.particle.ParticleSystem;
import com.overlord.renderer.texture.TextureRegion;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;

/** Emits bounded presentation only from an immutable committed pickup receipt. */
public final class CommittedPickupVisualAdapter {
    public static final int COMMITTED_BURST_COUNT = 8;

    private final Function<ResourceLocation, TextureRegion> regionResolver;
    private final Consumer<ParticleEmission> particleSink;
    private final BiConsumer<WorldItemPickupReceipt, Throwable> diagnostics;

    public CommittedPickupVisualAdapter(
            Function<ResourceLocation, TextureRegion> regionResolver,
            ParticleSystem particles,
            BiConsumer<WorldItemPickupReceipt, Throwable> diagnostics) {
        this(regionResolver, Objects.requireNonNull(particles, "particles")::emit,
                diagnostics);
    }

    CommittedPickupVisualAdapter(
            Function<ResourceLocation, TextureRegion> regionResolver,
            Consumer<ParticleEmission> particleSink,
            BiConsumer<WorldItemPickupReceipt, Throwable> diagnostics) {
        this.regionResolver = Objects.requireNonNull(regionResolver, "regionResolver");
        this.particleSink = Objects.requireNonNull(particleSink, "particleSink");
        this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
    }

    public void onPickup(WorldItemPickupResult result) {
        Objects.requireNonNull(result, "result");
        result.committedReceipt().ifPresent(this::emitCommitted);
    }

    private void emitCommitted(WorldItemPickupReceipt receipt) {
        try {
            TextureRegion region = Objects.requireNonNull(
                    regionResolver.apply(receipt.picked().itemId()),
                    "resolved pickup texture region");
            particleSink.accept(new ParticleEmission(
                    ParticleCategory.PICKUP_COMMITTED,
                    ParticlePriority.HIGH,
                    (float) receipt.positionX(),
                    (float) receipt.positionY(),
                    (float) receipt.positionZ(),
                    region,
                    COMMITTED_BURST_COUNT,
                    deterministicSeed(receipt)));
        } catch (RuntimeException failure) {
            reportSafely(receipt, failure);
        } catch (Error failure) {
            reportSafely(receipt, failure);
            throw failure;
        }
    }

    private void reportSafely(WorldItemPickupReceipt receipt, Throwable failure) {
        try {
            diagnostics.accept(receipt, failure);
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

    private static long deterministicSeed(WorldItemPickupReceipt receipt) {
        return receipt.itemId().value() * 31L + receipt.tick();
    }
}
