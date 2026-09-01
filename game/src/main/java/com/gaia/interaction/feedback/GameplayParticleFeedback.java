package com.gaia.interaction.feedback;

import com.gaia.worlditem.WorldItemPickupReceipt;
import com.gaia.interaction.DetailPlacementCandidate;
import com.gaia.interaction.DetailPrecisionTarget;
import com.overlord.interaction.api.BlockHitResult;
import com.overlord.renderer.feedback.WorldItemFaceRegions;
import com.overlord.renderer.feedback.ParticleTint;
import com.overlord.renderer.particle.ParticleCategory;
import com.overlord.renderer.particle.ParticleEmission;
import com.overlord.renderer.particle.ParticlePriority;
import com.overlord.renderer.particle.ParticleSystem;
import com.overlord.physics.SimulationOrigin;
import com.overlord.voxel.ChunkKey;
import com.overlord.voxel.GlobalPosition;
import java.util.Objects;
import java.util.function.Supplier;
import org.joml.Vector3f;

/** Exact committed-event particle requests over the one bounded ParticleSystem. */
public final class GameplayParticleFeedback {
    private static final long ASTRAL_SALT = 0xD1B54A32D192ED03L;
    private static final ParticleTint ASTRAL_LILAC = new ParticleTint(
            0x9B / 255.0f, 0x83 / 255.0f, 0xCF / 255.0f, 0.68f);
    private static final ParticleTint PICKUP_AQUA = new ParticleTint(
            0x8F / 255.0f, 0xDC / 255.0f, 0xCF / 255.0f, 0.78f);
    private final ParticleSystem particles;
    private final Supplier<SimulationOrigin> simulationOrigin;

    public GameplayParticleFeedback(ParticleSystem particles) {
        this(particles, () -> new SimulationOrigin(new ChunkKey(0, 0)));
    }

    public GameplayParticleFeedback(
            ParticleSystem particles,
            Supplier<SimulationOrigin> simulationOrigin) {
        this.particles = Objects.requireNonNull(particles, "particles");
        this.simulationOrigin = Objects.requireNonNull(
                simulationOrigin, "simulationOrigin");
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

    public void onDetailRemoval(
            DetailPrecisionTarget target,
            WorldItemFaceRegions faces,
            long eventIdentity) {
        Objects.requireNonNull(target, "target");
        Vector3f center = detailCenter(
                target.parentX(), target.parentY(), target.parentZ(),
                target.localPosition().x(), target.localPosition().y(),
                target.localPosition().z());
        emit(
                ParticleCategory.BREAK_DEBRIS,
                center.x, center.y, center.z,
                Objects.requireNonNull(faces, "faces"),
                target.face().normalX(), target.face().normalY(), target.face().normalZ(),
                4,
                eventIdentity);
    }

    public void onDetailPlacement(
            DetailPlacementCandidate candidate,
            WorldItemFaceRegions faces,
            long eventIdentity) {
        Objects.requireNonNull(candidate, "candidate");
        Vector3f center = detailCenter(
                candidate.parentX(), candidate.parentY(), candidate.parentZ(),
                candidate.localPosition().x(), candidate.localPosition().y(),
                candidate.localPosition().z());
        emit(
                ParticleCategory.PLACEMENT_DEBRIS,
                center.x, center.y, center.z,
                Objects.requireNonNull(faces, "faces"),
                candidate.source().face().normalX(),
                candidate.source().face().normalY(),
                candidate.source().face().normalZ(),
                3,
                eventIdentity);
    }

    private Vector3f detailCenter(
            int parentX,
            int parentY,
            int parentZ,
            int localX,
            int localY,
            int localZ) {
        ChunkKey key = ChunkKey.fromWorld(parentX, parentZ);
        return Objects.requireNonNull(
                        simulationOrigin.get(), "simulationOrigin value")
                .toLocal(new GlobalPosition(
                        key,
                        ChunkKey.localCoordinate(parentX) + (localX + 0.5) * 0.25,
                        parentY + (localY + 0.5) * 0.25,
                        ChunkKey.localCoordinate(parentZ) + (localZ + 0.5) * 0.25));
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
