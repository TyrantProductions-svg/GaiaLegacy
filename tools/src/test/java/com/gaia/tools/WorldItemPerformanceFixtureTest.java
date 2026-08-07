package com.gaia.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gaia.tools.WorldItemPerformanceFixture.Configuration;
import org.junit.jupiter.api.Test;

final class WorldItemPerformanceFixtureTest {
    @Test
    void shortFixtureIsDeterministicAndReportsBoundedMetrics() {
        WorldItemPerformanceFixture.Result first =
                WorldItemPerformanceFixture.run(new Configuration(7L, 32, 64, 120, 0));
        WorldItemPerformanceFixture.Result second =
                WorldItemPerformanceFixture.run(new Configuration(7L, 32, 64, 120, 0));

        assertEquals(first.simulationHash(), second.simulationHash());
        assertEquals(120, first.sampleSteps());
        assertEquals(0, first.warmupSteps());
        assertTrue(first.peakWorldItems() <= 32);
        assertTrue(first.peakParticles() <= 64);
        assertTrue(first.allocatedBytes() >= 0);
        assertTrue(first.gcCollectionDelta() >= 0);
        assertTrue(first.gcCollectionTimeMillis() >= 0);
        assertTrue(first.maximumGcPauseMillis() >= 0);
        assertTrue(first.format().startsWith("worldItems=32 particles=64 warmupSteps=0 sampleSteps=120"));
        assertTrue(first.format().contains(" simulationHash=" + first.simulationHash()));
    }
}
