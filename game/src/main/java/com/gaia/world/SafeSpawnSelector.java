package com.gaia.world;

import com.gaia.world.generation.WorldGenerationConfig;
import com.overlord.config.GameConfig;
import com.overlord.voxel.ChunkCoordinatePolicy;
import com.overlord.voxel.ChunkKey;
import com.overlord.voxel.World;
import java.util.Comparator;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.joml.Vector3f;

public final class SafeSpawnSelector {
    private static final Comparator<Candidate> CANDIDATE_ORDER =
            Comparator.comparingLong(Candidate::distanceSquared)
                    .thenComparingInt(Candidate::x)
                    .thenComparingInt(Candidate::z)
                    .thenComparingInt(Candidate::feetY);

    public Optional<Vector3f> find(
            World world,
            Set<ChunkKey> committedChunks,
            WorldGenerationConfig config) {
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(committedChunks, "committedChunks");
        Objects.requireNonNull(config, "config");

        long maximumRadius = config.spawn().maximumSearchRadiusBlocks();
        long maximumDistanceSquared = maximumRadius * maximumRadius;
        int effectiveClearance =
                Math.max(
                        2,
                        config.spawn().requiredEmptyBlocks());
        int worldHeight = world.chunks().worldHeight();
        Candidate best = null;

        for (ChunkKey key :
                committedChunks.stream()
                        .map(
                                chunk ->
                                        Objects.requireNonNull(
                                                chunk, "committed chunk"))
                        .sorted(
                                ChunkCoordinatePolicy.canonicalComparator())
                        .toList()) {
            if (!world.chunks().contains(key)) {
                continue;
            }
            long originX = ChunkCoordinatePolicy.worldOriginX(key);
            long originZ = ChunkCoordinatePolicy.worldOriginZ(key);
            for (int localX = 0;
                    localX < GameConfig.Chunk.SIZE;
                    localX++) {
                long candidateX = originX + localX;
                if (candidateX < Integer.MIN_VALUE
                        || candidateX > Integer.MAX_VALUE) {
                    continue;
                }
                for (int localZ = 0;
                        localZ < GameConfig.Chunk.SIZE;
                        localZ++) {
                    long candidateZ = originZ + localZ;
                    if (candidateZ < Integer.MIN_VALUE
                            || candidateZ > Integer.MAX_VALUE) {
                        continue;
                    }
                    long absoluteX = StrictMath.abs(candidateX);
                    long absoluteZ = StrictMath.abs(candidateZ);
                    if (absoluteX > maximumRadius
                            || absoluteZ > maximumRadius) {
                        continue;
                    }
                    long xSquared = absoluteX * absoluteX;
                    long zSquared = absoluteZ * absoluteZ;
                    if (zSquared
                            > maximumDistanceSquared - xSquared) {
                        continue;
                    }
                    long distanceSquared = xSquared + zSquared;
                    int x = (int) candidateX;
                    int z = (int) candidateZ;
                    int maximumSupportY =
                            worldHeight - effectiveClearance - 1;
                    for (int supportY = maximumSupportY;
                            supportY >= 0;
                            supportY--) {
                        if (world.getBlock(x, supportY, z) == 0
                                || !hasClearance(
                                        world,
                                        x,
                                        supportY + 1,
                                        z,
                                        effectiveClearance)) {
                            continue;
                        }
                        Candidate candidate =
                                new Candidate(
                                        x,
                                        supportY + 1,
                                        z,
                                        distanceSquared);
                        if (best == null
                                || CANDIDATE_ORDER.compare(candidate, best)
                                        < 0) {
                            best = candidate;
                        }
                        break;
                    }
                }
            }
        }

        if (best == null) {
            return Optional.empty();
        }
        return Optional.of(
                new Vector3f(
                        best.x() + 0.5f,
                        best.feetY(),
                        best.z() + 0.5f));
    }

    private static boolean hasClearance(
            World world,
            int x,
            int feetY,
            int z,
            int requiredEmptyBlocks) {
        for (int offset = 0;
                offset < requiredEmptyBlocks;
                offset++) {
            if (world.getBlock(x, feetY + offset, z) != 0) {
                return false;
            }
        }
        return true;
    }

    private record Candidate(
            int x, int feetY, int z, long distanceSquared) {}
}
