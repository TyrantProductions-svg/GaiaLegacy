package com.gaia.world.generation;

import com.overlord.config.GameConfig;
import com.overlord.voxel.ChunkKey;
import java.util.Objects;
import java.util.Optional;

/** One deterministic anchor decision owned by a signed world-region cell. */
public record StableRegionAnchor(
        long regionX,
        long regionZ,
        long worldX,
        long worldZ,
        ChunkKey ownerChunk) {
    public StableRegionAnchor {
        Objects.requireNonNull(ownerChunk, "ownerChunk");
    }

    public static StableRegionAnchor sample(
            DeterministicCoordinateSampler sampler,
            GenerationStageContract contract,
            long regionX,
            long regionZ,
            int regionSize) {
        Objects.requireNonNull(sampler, "sampler");
        Objects.requireNonNull(contract, "contract");
        if (regionSize <= 0) {
            throw new IllegalArgumentException(
                    "regionSize must be positive");
        }
        long worldX =
                anchoredCoordinate(
                        sampler,
                        contract,
                        regionX,
                        regionZ,
                        regionSize,
                        0L,
                        regionX);
        long worldZ =
                anchoredCoordinate(
                        sampler,
                        contract,
                        regionX,
                        regionZ,
                        regionSize,
                        1L,
                        regionZ);
        long ownerX =
                Math.floorDiv(worldX, GameConfig.Chunk.SIZE);
        long ownerZ =
                Math.floorDiv(worldZ, GameConfig.Chunk.SIZE);
        return new StableRegionAnchor(
                regionX,
                regionZ,
                worldX,
                worldZ,
                new ChunkKey(
                        Math.toIntExact(ownerX),
                        Math.toIntExact(ownerZ)));
    }

    public static Optional<StableRegionAnchor> sampleIfSafe(
            DeterministicCoordinateSampler sampler,
            GenerationStageContract contract,
            long regionX,
            long regionZ,
            int regionSize) {
        try {
            return Optional.of(
                    sample(
                            sampler,
                            contract,
                            regionX,
                            regionZ,
                            regionSize));
        } catch (ArithmeticException outsideRepresentableOwner) {
            return Optional.empty();
        }
    }

    public boolean ownedBy(ChunkKey key) {
        return ownerChunk.equals(
                Objects.requireNonNull(key, "key"));
    }

    private static long anchoredCoordinate(
            DeterministicCoordinateSampler sampler,
            GenerationStageContract contract,
            long regionX,
            long regionZ,
            int regionSize,
            long salt,
            long baseRegion) {
        int offset =
                (int)
                        (sampler.unit(
                                        contract,
                                        regionX,
                                        0L,
                                        regionZ,
                                        salt)
                                * regionSize);
        return Math.addExact(
                Math.multiplyExact(baseRegion, (long) regionSize),
                offset);
    }
}
