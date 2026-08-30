package com.gaia.debug;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import com.overlord.voxel.DetailCellState;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DetailFixturePatternTest {
    @Test
    void fixturesHaveExactCanonicalMasks() {
        Map<DetailFixturePattern, Long> expected = Map.ofEntries(
                Map.entry(DetailFixturePattern.SINGLE_QUARTER, 0x0000000000000001L),
                Map.entry(DetailFixturePattern.QUARTER_SLAB, 0x000F000F000F000FL),
                Map.entry(DetailFixturePattern.THIN_WALL, 0x1111111111111111L),
                Map.entry(DetailFixturePattern.STAIRCASE, 0x8CEF8CEF8CEF8CEFL),
                Map.entry(DetailFixturePattern.HOLLOW_OPENING, 0x1111100110011111L),
                Map.entry(DetailFixturePattern.ASYMMETRIC, 0x0480010040000029L),
                Map.entry(DetailFixturePattern.CHECKERBOARD, 0x5A5AA5A55A5AA5A5L),
                Map.entry(DetailFixturePattern.UNIFORM_FULL, -1L),
                Map.entry(DetailFixturePattern.MIXED_MATERIAL, 0x000F000F000F000FL));

        expected.forEach((pattern, mask) ->
                assertEquals(mask.longValue(), pattern.state((byte) 1, (byte) 2).occupancyMask(), pattern.name()));
    }

    @Test
    void fixtureMaterialArraysAndHashesAreDeterministic() {
        for (DetailFixturePattern pattern : DetailFixturePattern.values()) {
            DetailCellState first = pattern.state((byte) 1, (byte) 2);
            DetailCellState second = pattern.state((byte) 1, (byte) 2);

            assertArrayEquals(first.copyBlockIds(), second.copyBlockIds(), pattern.name());
            assertEquals(
                    DetailFixturePattern.canonicalHash(first),
                    DetailFixturePattern.canonicalHash(second),
                    pattern.name());
        }

        DetailCellState mixed = DetailFixturePattern.MIXED_MATERIAL.state((byte) 1, (byte) 2);
        assertEquals(1, Byte.toUnsignedInt(mixed.blockIdAtIndex(0)));
        assertEquals(2, Byte.toUnsignedInt(mixed.blockIdAtIndex(1)));
        assertNotEquals(
                DetailFixturePattern.canonicalHash(mixed),
                DetailFixturePattern.canonicalHash(
                        DetailFixturePattern.MIXED_MATERIAL.state((byte) 1, (byte) 3)));
    }
}
