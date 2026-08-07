package com.gaia.interaction.feedback;

import com.gaia.worlditem.WorldItemPickupReceipt;
import com.overlord.interaction.api.BlockHitResult;
import com.overlord.renderer.feedback.WorldItemFaceRegions;
import com.overlord.renderer.feedback.ParticleTint;
import com.overlord.renderer.particle.ParticleCategory;
import com.overlord.renderer.particle.ParticleEmission;
import com.overlord.renderer.particle.ParticlePriority;
import com.overlord.renderer.particle.ParticleSystem;
import java.util.Objects;

/** Exact committed-event particle requests over the one bounded ParticleSystem. */
public final class GameplayParticleFeedback {
    private static final long ASTRAL_SALT = 0xD1B54A32D192ED03L;
    private static final ParticleTint ASTRAL_LILAC = new ParticleTint(
            0x9B / 255.0f, 0x83 / 255.0f, 0xCF / 255.0f, 0.68f);
    private static final ParticleTint PICKUP_AQUA = new ParticleTint(
            0x8F / 255.0f, 0xDC / 255.0f, 0xCF / 255.0f, 0.78f);
    private final ParticleSystem particles;

    public GameplayParticleFeedback(ParticleSystem particles) {
        this.particles = Objects.requireNonNull(particles, "particles");
    }

    public void onBreak(
            BlockHitResult target,
            WorldItemFaceRegions faces,
            long eventIdentity) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(faces, "faces");
        emit(
                ParticleCategory.BREAK_DEBRIS,
                target.blockX() + 0.5f,
                target.blockY() + 0.5f,
                target.blockZ() + 0.5f,
                faces,
                target.normalX(), target.normalY(), target.normalZ(),
                16,
                eventIdentity);
        emit(
                ParticleCategory.BREAK_ASTRAL,
                target.blockX() + 0.5f,
                target.blockY() + 0.5f,
                target.blockZ() + 0.5f,
                faces,
                target.normalX(), target.normalY(), target.normalZ(),
                4,
                eventIdentity ^ ASTRAL_SALT);
    }

    public void onPlacement(
            BlockHitResult target,
            WorldItemFaceRegions faces,
            long eventIdentity) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(faces, "faces");
        emit(
                ParticleCategory.PLACEMENT_DEBRIS,
                target.adjacentX() + 0.5f,
                target.adjacentY() + 0.5f,
                target.adjacentZ() + 0.5f,
                faces,
                target.normalX(), target.normalY(), target.normalZ(),
                6,
                eventIdentity);
        emit(
                ParticleCategory.PLACEMENT_ASTRAL,
                target.adjacentX() + 0.5f,
                target.adjacentY() + 0.5f,
                target.adjacentZ() + 0.5f,
                faces,
                target.normalX(), target.normalY(), target.normalZ(),
                2,
                eventIdentity ^ ASTRAL_SALT);
    }

    public void onPickup(WorldItemPickupReceipt receipt, WorldItemFaceRegions faces) {
        Objects.requireNonNull(receipt, "receipt");
        emit(
                ParticleCategory.PICKUP_COMMITTED,
                (float) receipt.positionX(),
                (float) receipt.positionY(),
                (float) receipt.positionZ(),
                Objects.requireNonNull(faces, "faces"),
                0, 0, 0,
                8,
                receipt.itemId().value() * 31L + receipt.tick());
    }

    private void emit(
            ParticleCategory category,
            float x,
            float y,
            float z,
            WorldItemFaceRegions faces,
            float normalX,
            float normalY,
            float normalZ,
            int count,
            long seed) {
        particles.emit(new ParticleEmission(
                category,
                ParticlePriority.HIGH,
                x, y, z,
                faces,
                tint(category),
                normalX, normalY, normalZ,
                count,
                seed));
    }

    private static ParticleTint tint(ParticleCategory category) {
        return switch (category) {
            case BREAK_ASTRAL, PLACEMENT_ASTRAL -> ASTRAL_LILAC;
            case PICKUP_COMMITTED -> PICKUP_AQUA;
            default -> ParticleTint.white();
        };
    }
}
