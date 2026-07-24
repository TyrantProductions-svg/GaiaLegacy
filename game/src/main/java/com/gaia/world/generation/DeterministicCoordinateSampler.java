package com.gaia.world.generation;

import com.overlord.assets.ResourceLocation;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

public final class DeterministicCoordinateSampler {
    private static final long VERSION_FACTOR =
            0x9e3779b97f4a7c15L;
    private static final long X_FACTOR =
            0x632be59bd9b4e019L;
    private static final long Y_FACTOR =
            0x8cb92baa3f3d8dd7L;
    private static final long Z_FACTOR =
            0x9e3779b185ebca87L;
    private static final long SALT_FACTOR =
            0xd1b54a32d192ed03L;
    private static final long FNV_OFFSET =
            0xcbf29ce484222325L;
    private static final long FNV_PRIME =
            0x100000001b3L;

    private final long seed;
    private final int algorithmVersion;

    public DeterministicCoordinateSampler(
            long seed, int algorithmVersion) {
        if (algorithmVersion <= 0) {
            throw new IllegalArgumentException(
                    "algorithmVersion must be positive");
        }
        this.seed = seed;
        this.algorithmVersion = algorithmVersion;
    }

    public double unit(
            ResourceLocation stageId,
            int worldX,
            int worldY,
            int worldZ,
            long salt) {
        Objects.requireNonNull(stageId, "stageId");
        long value = mix64(seed);
        value =
                mix64(
                        value
                                ^ Integer.toUnsignedLong(
                                                algorithmVersion)
                                        * VERSION_FACTOR);
        value = mix64(value ^ stageFold(stageId));
        value =
                mix64(
                        value
                                ^ (long) worldX * X_FACTOR);
        value =
                mix64(
                        value
                                ^ (long) worldY * Y_FACTOR);
        value =
                mix64(
                        value
                                ^ (long) worldZ * Z_FACTOR);
        value = mix64(value ^ salt * SALT_FACTOR);
        return unitDouble(value);
    }

    public double valueNoise2D(
            ResourceLocation stageId,
            double worldX,
            double worldZ,
            double scale,
            long salt) {
        requireFinite("worldX", worldX);
        requireFinite("worldZ", worldZ);
        requireScale(scale);
        double scaledX = worldX * scale;
        double scaledZ = worldZ * scale;
        int x0 = floorToInt("worldX", scaledX);
        int z0 = floorToInt("worldZ", scaledZ);
        int x1 = incrementLattice(x0);
        int z1 = incrementLattice(z0);
        double xFade = fade(scaledX - x0);
        double zFade = fade(scaledZ - z0);
        double north =
                lerp(
                        unit(stageId, x0, 0, z0, salt),
                        unit(stageId, x1, 0, z0, salt),
                        xFade);
        double south =
                lerp(
                        unit(stageId, x0, 0, z1, salt),
                        unit(stageId, x1, 0, z1, salt),
                        xFade);
        return lerp(north, south, zFade);
    }

    public double valueNoise3D(
            ResourceLocation stageId,
            double worldX,
            double worldY,
            double worldZ,
            double scale,
            long salt) {
        requireFinite("worldX", worldX);
        requireFinite("worldY", worldY);
        requireFinite("worldZ", worldZ);
        requireScale(scale);
        double scaledX = worldX * scale;
        double scaledY = worldY * scale;
        double scaledZ = worldZ * scale;
        int x0 = floorToInt("worldX", scaledX);
        int y0 = floorToInt("worldY", scaledY);
        int z0 = floorToInt("worldZ", scaledZ);
        int x1 = incrementLattice(x0);
        int y1 = incrementLattice(y0);
        int z1 = incrementLattice(z0);
        double xFade = fade(scaledX - x0);
        double yFade = fade(scaledY - y0);
        double zFade = fade(scaledZ - z0);
        double lowerNorth =
                lerp(
                        unit(stageId, x0, y0, z0, salt),
                        unit(stageId, x1, y0, z0, salt),
                        xFade);
        double upperNorth =
                lerp(
                        unit(stageId, x0, y1, z0, salt),
                        unit(stageId, x1, y1, z0, salt),
                        xFade);
        double lowerSouth =
                lerp(
                        unit(stageId, x0, y0, z1, salt),
                        unit(stageId, x1, y0, z1, salt),
                        xFade);
        double upperSouth =
                lerp(
                        unit(stageId, x0, y1, z1, salt),
                        unit(stageId, x1, y1, z1, salt),
                        xFade);
        return lerp(
                lerp(lowerNorth, upperNorth, yFade),
                lerp(lowerSouth, upperSouth, yFade),
                zFade);
    }

    private static long stageFold(ResourceLocation stageId) {
        long hash = FNV_OFFSET;
        for (byte value :
                stageId.toString()
                        .getBytes(StandardCharsets.UTF_8)) {
            hash ^= Byte.toUnsignedInt(value);
            hash *= FNV_PRIME;
        }
        return hash;
    }

    private static long mix64(long value) {
        value =
                (value ^ (value >>> 30))
                        * 0xbf58476d1ce4e5b9L;
        value =
                (value ^ (value >>> 27))
                        * 0x94d049bb133111ebL;
        return value ^ (value >>> 31);
    }

    private static double unitDouble(long bits) {
        return (bits >>> 11) * 0x1.0p-53;
    }

    private static double fade(double value) {
        return value
                * value
                * value
                * (value * (value * 6.0 - 15.0) + 10.0);
    }

    private static double lerp(
            double start, double end, double fraction) {
        return start + (end - start) * fraction;
    }

    private static int floorToInt(
            String name, double value) {
        double floor = StrictMath.floor(value);
        if (floor < Integer.MIN_VALUE
                || floor >= Integer.MAX_VALUE) {
            throw new IllegalArgumentException(
                    name + " exceeds the supported lattice");
        }
        return (int) floor;
    }

    private static int incrementLattice(int value) {
        return value + 1;
    }

    private static void requireScale(double scale) {
        if (!Double.isFinite(scale) || scale <= 0.0) {
            throw new IllegalArgumentException(
                    "scale must be finite and positive");
        }
    }

    private static void requireFinite(
            String name, double value) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(
                    name + " must be finite");
        }
    }
}
