package com.gaia.world.generation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.overlord.assets.ResourceLocation;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class DeterministicCoordinateSamplerTest {
    private static final ResourceLocation CAVE =
            ResourceLocation.parse("gaia:cave");
    private static final ResourceLocation DECORATION =
            ResourceLocation.parse("gaia:decoration");

    @Test
    void coordinateSamplesIgnoreCallOrder() {
        DeterministicCoordinateSampler first = sampler(12345L, 1);
        double expected = first.unit(CAVE, -17, 42, 31, 9);

        first.unit(DECORATION, 999, 2, -400, 3);

        assertEquals(
                expected,
                first.unit(CAVE, -17, 42, 31, 9));
        assertNotEquals(
                expected,
                sampler(54321L, 1)
                        .unit(CAVE, -17, 42, 31, 9));
    }

    @Test
    void unitUsesFixedMixForNegativeCoordinates() {
        DeterministicCoordinateSampler sampler = sampler(12345L, 7);

        assertEquals(
                expectedUnit(
                        12345L, 7, CAVE, -17, -42, 31, 9L),
                sampler.unit(CAVE, -17, -42, 31, 9L));
    }

    @Test
    void stageSaltAndEachCoordinateAreSeparated() {
        DeterministicCoordinateSampler sampler = sampler(12345L, 1);
        Set<Double> values = new HashSet<>();

        values.add(sampler.unit(CAVE, 1, 2, 3, 4));
        values.add(sampler.unit(DECORATION, 1, 2, 3, 4));
        values.add(sampler.unit(CAVE, 1, 2, 3, 5));
        values.add(sampler.unit(CAVE, 2, 2, 3, 4));
        values.add(sampler.unit(CAVE, 1, 3, 3, 4));
        values.add(sampler.unit(CAVE, 1, 2, 4, 4));

        assertEquals(6, values.size());
        assertTrue(
                values.stream()
                        .allMatch(value -> value >= 0.0 && value < 1.0));
    }

    @Test
    void valueNoiseUsesStrictFloorAndQuinticInterpolation() {
        DeterministicCoordinateSampler sampler = sampler(12345L, 1);

        assertEquals(
                expectedNoise2D(
                        sampler, CAVE, -17.25, 31.75, 0.125, 81L),
                sampler.valueNoise2D(
                        CAVE, -17.25, 31.75, 0.125, 81L));
        assertEquals(
                expectedNoise3D(
                        sampler,
                        CAVE,
                        -17.25,
                        4.5,
                        31.75,
                        0.125,
                        82L),
                sampler.valueNoise3D(
                        CAVE,
                        -17.25,
                        4.5,
                        31.75,
                        0.125,
                        82L));
    }

    @Test
    void rejectsInvalidVersionScaleAndCoordinates() {
        assertThrows(
                IllegalArgumentException.class,
                () -> sampler(1L, 0));
        DeterministicCoordinateSampler sampler = sampler(1L, 1);
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        sampler.valueNoise2D(
                                CAVE, 1.0, 2.0, 0.0, 0L));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        sampler.valueNoise3D(
                                CAVE,
                                Double.NaN,
                                1.0,
                                2.0,
                                0.1,
                                0L));
    }

    private static DeterministicCoordinateSampler sampler(
            long seed, int version) {
        return new DeterministicCoordinateSampler(seed, version);
    }

    private static double expectedUnit(
            long seed,
            int version,
            ResourceLocation stageId,
            int x,
            int y,
            int z,
            long salt) {
        long value = mix64(seed);
        value =
                mix64(
                        value
                                ^ Integer.toUnsignedLong(version)
                                        * 0x9e3779b97f4a7c15L);
        value = mix64(value ^ stageFold(stageId));
        value = mix64(value ^ (long) x * 0x632be59bd9b4e019L);
        value = mix64(value ^ (long) y * 0x8cb92baa3f3d8dd7L);
        value = mix64(value ^ (long) z * 0x9e3779b185ebca87L);
        value = mix64(value ^ salt * 0xd1b54a32d192ed03L);
        return (value >>> 11) * 0x1.0p-53;
    }

    private static long stageFold(ResourceLocation stageId) {
        long hash = 0xcbf29ce484222325L;
        for (byte value :
                stageId.toString()
                        .getBytes(StandardCharsets.UTF_8)) {
            hash ^= Byte.toUnsignedInt(value);
            hash *= 0x100000001b3L;
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

    private static double expectedNoise2D(
            DeterministicCoordinateSampler sampler,
            ResourceLocation stage,
            double x,
            double z,
            double scale,
            long salt) {
        double scaledX = x * scale;
        double scaledZ = z * scale;
        int x0 = (int) StrictMath.floor(scaledX);
        int z0 = (int) StrictMath.floor(scaledZ);
        double tx = fade(scaledX - x0);
        double tz = fade(scaledZ - z0);
        double north =
                lerp(
                        sampler.unit(stage, x0, 0, z0, salt),
                        sampler.unit(stage, x0 + 1, 0, z0, salt),
                        tx);
        double south =
                lerp(
                        sampler.unit(stage, x0, 0, z0 + 1, salt),
                        sampler.unit(
                                stage, x0 + 1, 0, z0 + 1, salt),
                        tx);
        return lerp(north, south, tz);
    }

    private static double expectedNoise3D(
            DeterministicCoordinateSampler sampler,
            ResourceLocation stage,
            double x,
            double y,
            double z,
            double scale,
            long salt) {
        double scaledX = x * scale;
        double scaledY = y * scale;
        double scaledZ = z * scale;
        int x0 = (int) StrictMath.floor(scaledX);
        int y0 = (int) StrictMath.floor(scaledY);
        int z0 = (int) StrictMath.floor(scaledZ);
        double tx = fade(scaledX - x0);
        double ty = fade(scaledY - y0);
        double tz = fade(scaledZ - z0);
        double z00 =
                lerp(
                        sampler.unit(stage, x0, y0, z0, salt),
                        sampler.unit(
                                stage, x0 + 1, y0, z0, salt),
                        tx);
        double z10 =
                lerp(
                        sampler.unit(
                                stage, x0, y0 + 1, z0, salt),
                        sampler.unit(
                                stage,
                                x0 + 1,
                                y0 + 1,
                                z0,
                                salt),
                        tx);
        double z01 =
                lerp(
                        sampler.unit(
                                stage, x0, y0, z0 + 1, salt),
                        sampler.unit(
                                stage,
                                x0 + 1,
                                y0,
                                z0 + 1,
                                salt),
                        tx);
        double z11 =
                lerp(
                        sampler.unit(
                                stage,
                                x0,
                                y0 + 1,
                                z0 + 1,
                                salt),
                        sampler.unit(
                                stage,
                                x0 + 1,
                                y0 + 1,
                                z0 + 1,
                                salt),
                        tx);
        return lerp(
                lerp(z00, z10, ty),
                lerp(z01, z11, ty),
                tz);
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
}
