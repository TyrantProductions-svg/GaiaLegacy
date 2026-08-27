package com.gaia.session.streaming;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.overlord.physics.SimulationOrigin;
import com.overlord.voxel.ChunkKey;
import com.overlord.voxel.GlobalPosition;
import org.junit.jupiter.api.Test;

final class SimulationOriginRebasePolicyTest {
    private final SimulationOriginRebasePolicy policy =
            SimulationOriginRebasePolicy.productionDefaults();

    @Test
    void adjacentBoundaryOscillationDoesNotRequestFullParticipantRebase() {
        SimulationOrigin origin = origin(0, 0);

        for (int repeat = 0; repeat < 100; repeat++) {
            assertFalse(policy.requiresRebase(position(0, 0), origin));
            assertFalse(policy.requiresRebase(position(1, 0), origin));
            assertFalse(policy.requiresRebase(position(-1, 0), origin));
            assertFalse(policy.requiresRebase(position(0, 1), origin));
            assertFalse(policy.requiresRebase(position(0, -1), origin));
        }
    }

    @Test
    void exactConfiguredDistanceRequestsOneCheckedRebaseInEveryDirection() {
        SimulationOrigin origin = origin(7, -11);
        int threshold = policy.distanceChunks();

        assertFalse(policy.requiresRebase(position(7 + threshold - 1, -11), origin));
        assertFalse(policy.requiresRebase(position(7, -11 - threshold + 1), origin));
        assertTrue(policy.requiresRebase(position(7 + threshold, -11), origin));
        assertTrue(policy.requiresRebase(position(7 - threshold, -11), origin));
        assertTrue(policy.requiresRebase(position(7, -11 + threshold), origin));
        assertTrue(policy.requiresRebase(position(7, -11 - threshold), origin));
    }

    @Test
    void successfulRebaseCentersPolicyAndRapidReversalStaysDeterministic() {
        int threshold = policy.distanceChunks();
        SimulationOrigin oldOrigin = origin(-100, 200);
        GlobalPosition east = position(-100 + threshold, 200);
        assertTrue(policy.requiresRebase(east, oldOrigin));

        SimulationOrigin rebased = new SimulationOrigin(east.chunkKey());
        assertFalse(policy.requiresRebase(east, rebased));
        assertFalse(policy.requiresRebase(position(
                east.chunkKey().x() - 1, east.chunkKey().z()), rebased));
        assertTrue(policy.requiresRebase(position(-100, 200), rebased));
    }

    @Test
    void teleportAndDiagonalDistanceUseOverflowSafeChunkArithmetic() {
        SimulationOrigin origin = origin(-120_000_000, 120_000_000);
        int threshold = policy.distanceChunks();

        assertTrue(policy.requiresRebase(
                position(-120_000_000 + threshold, 120_000_000 + threshold),
                origin));
        assertTrue(policy.requiresRebase(
                position(120_000_000, -120_000_000), origin));
    }

    private static SimulationOrigin origin(int x, int z) {
        return new SimulationOrigin(new ChunkKey(x, z));
    }

    private static GlobalPosition position(int x, int z) {
        return new GlobalPosition(new ChunkKey(x, z), 0.0, 70.0, 0.0);
    }
}
