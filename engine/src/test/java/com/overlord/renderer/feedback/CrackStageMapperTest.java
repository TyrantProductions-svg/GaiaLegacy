package com.overlord.renderer.feedback;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class CrackStageMapperTest {
    @ParameterizedTest
    @CsvSource({
        "0.0,10,0",
        "0.0999,10,0",
        "0.1,10,1",
        "0.9,10,9",
        "1.0,10,9",
        "-1.0,10,0",
        "2.0,10,9",
        "0.875,8,7",
        "0.8889,9,8"
    })
    void mapsClampedProgressToExactStageBoundaries(
            double progress, int stageCount, int expectedStage) {
        assertEquals(expectedStage, CrackStageMapper.map(progress, stageCount));
    }

    @ParameterizedTest
    @CsvSource({"7", "11", "0", "-1"})
    void rejectsUnsupportedStageCounts(int stageCount) {
        assertThrows(
                IllegalArgumentException.class,
                () -> CrackStageMapper.map(0.5, stageCount));
    }

    @Test
    void rejectsNonFiniteProgress() {
        assertThrows(
                IllegalArgumentException.class,
                () -> CrackStageMapper.map(Double.NaN, 10));
        assertThrows(
                IllegalArgumentException.class,
                () -> CrackStageMapper.map(Double.POSITIVE_INFINITY, 10));
        assertThrows(
                IllegalArgumentException.class,
                () -> CrackStageMapper.map(Double.NEGATIVE_INFINITY, 10));
    }
}
