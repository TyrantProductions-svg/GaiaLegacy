package com.gaia.world.streaming;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.overlord.voxel.ChunkAvailability;
import com.overlord.voxel.ChunkKey;
import com.overlord.voxel.GlobalPosition;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class UnknownSpaceBarrierTest {
    @Test
    void movementAndWorldItemsStopAtUnknownOrFailedBoundaryWithCanonicalDiagnosticKey() {
        GlobalPosition current = new GlobalPosition(new ChunkKey(0, 0), 15.5, 64.0, 4.5);
        GlobalPosition east = new GlobalPosition(new ChunkKey(1, 0), 0.5, 64.0, 4.5);
        UnknownSpaceBarrier barrier =
                new UnknownSpaceBarrier(
                        key -> key.equals(new ChunkKey(1, 0))
                                ? ChunkAvailability.UNKNOWN
                                : key.equals(new ChunkKey(-1, 0))
                                        ? ChunkAvailability.FAILED
                                        : ChunkAvailability.AVAILABLE);

        UnknownSpaceBarrier.Decision player = barrier.movement(current, east);
        UnknownSpaceBarrier.Decision item =
                barrier.worldItemMotion(current, new GlobalPosition(new ChunkKey(-1, 0), 15.5, 64.0, 4.5));

        assertEquals(ChunkAvailability.UNKNOWN, player.availability());
        assertEquals(current, player.lastAvailablePosition());
        assertEquals(new ChunkKey(1, 0), player.unavailableKey().orElseThrow());
        assertEquals(ChunkAvailability.FAILED, item.availability());
        assertEquals(current, item.lastAvailablePosition());
        assertEquals(new ChunkKey(-1, 0), item.unavailableKey().orElseThrow());
    }

    @Test
    void unavailableEastDoesNotPauseMovementWithinAvailableSpace() {
        UnknownSpaceBarrier barrier =
                new UnknownSpaceBarrier(
                        key -> key.equals(new ChunkKey(1, 0))
                                ? ChunkAvailability.UNKNOWN
                                : ChunkAvailability.AVAILABLE);
        GlobalPosition current = new GlobalPosition(new ChunkKey(0, 0), 15.5, 64.0, 4.5);
        GlobalPosition north = new GlobalPosition(new ChunkKey(0, -1), 15.5, 64.0, 15.5);

        UnknownSpaceBarrier.Decision decision = barrier.movement(current, north);

        assertEquals(ChunkAvailability.AVAILABLE, decision.availability());
        assertEquals(north, decision.lastAvailablePosition());
        assertFalse(decision.unavailableKey().isPresent());
    }

    @Test
    void noclipWaitsForEveryDestinationRingKeyInCanonicalOrder() {
        ChunkKey destinationKey = new ChunkKey(10, -8);
        GlobalPosition destination = new GlobalPosition(destinationKey, 8.0, 72.0, 8.0);
        CountingAvailability availability = new CountingAvailability(destinationKey.southEast());
        UnknownSpaceBarrier barrier = new UnknownSpaceBarrier(availability);

        UnknownSpaceBarrier.Decision waiting =
                barrier.noclipOrTeleport(destination, 1, destination);

        assertEquals(ChunkAvailability.UNKNOWN, waiting.availability());
        assertEquals(destinationKey.southEast(), waiting.unavailableKey().orElseThrow());
        assertEquals(
                List.of(
                        new ChunkKey(9, -9),
                        new ChunkKey(9, -8),
                        new ChunkKey(9, -7),
                        new ChunkKey(10, -9),
                        new ChunkKey(10, -8),
                        new ChunkKey(10, -7),
                        new ChunkKey(11, -9),
                        new ChunkKey(11, -8),
                        new ChunkKey(11, -7)),
                availability.observedKeys);
    }

    @Test
    void motionChecksEveryCrossedChunkAndPreservesExplicitPriorSafePosition() {
        GlobalPosition current = new GlobalPosition(new ChunkKey(0, 0), 8, 64, 8);
        GlobalPosition target = new GlobalPosition(new ChunkKey(4, 0), 8, 64, 8);
        GlobalPosition priorSafe = new GlobalPosition(new ChunkKey(1, 0), 15, 64, 8);
        List<ChunkKey> observed = new ArrayList<>();
        UnknownSpaceBarrier barrier = new UnknownSpaceBarrier(key -> {
            observed.add(key);
            return key.equals(new ChunkKey(2, 0))
                    ? ChunkAvailability.UNKNOWN
                    : key.equals(new ChunkKey(3, 0))
                            ? ChunkAvailability.FAILED
                            : ChunkAvailability.AVAILABLE;
        });

        UnknownSpaceBarrier.Decision decision = barrier.movement(current, target, priorSafe);

        assertEquals(List.of(
                new ChunkKey(1, 0), new ChunkKey(2, 0),
                new ChunkKey(3, 0), new ChunkKey(4, 0)), observed);
        assertEquals(ChunkAvailability.FAILED, decision.availability());
        assertEquals(new ChunkKey(3, 0), decision.unavailableKey().orElseThrow());
        assertEquals(priorSafe, decision.lastAvailablePosition());
    }

    @Test
    void teleportRadiusIsBoundedAndFailedDominatesUnknownByCanonicalKey() {
        UnknownSpaceBarrier barrier = new UnknownSpaceBarrier(key ->
                key.equals(new ChunkKey(-1, -1)) ? ChunkAvailability.UNKNOWN
                        : key.equals(new ChunkKey(1, 1)) || key.equals(new ChunkKey(-1, 1))
                                ? ChunkAvailability.FAILED
                                : ChunkAvailability.AVAILABLE);
        GlobalPosition destination = new GlobalPosition(new ChunkKey(0, 0), 8, 70, 8);

        GlobalPosition priorSafe = new GlobalPosition(new ChunkKey(-2, 0), 15, 70, 8);
        UnknownSpaceBarrier.Decision result =
                barrier.noclipOrTeleport(destination, 1, priorSafe);

        assertEquals(ChunkAvailability.FAILED, result.availability());
        assertEquals(new ChunkKey(-1, 1), result.unavailableKey().orElseThrow());
        assertThrows(IllegalArgumentException.class, () ->
                barrier.noclipOrTeleport(
                        destination,
                        UnknownSpaceBarrier.MAX_TELEPORT_RADIUS + 1,
                        priorSafe));

        assertEquals(
                priorSafe,
                barrier.noclipOrTeleport(destination, 1, priorSafe).lastAvailablePosition());
    }

    @Test
    void oversizedMovementFailsBeforeAnyAvailabilityObservation() {
        List<ChunkKey> observed = new ArrayList<>();
        UnknownSpaceBarrier barrier = new UnknownSpaceBarrier(key -> {
            observed.add(key);
            return ChunkAvailability.AVAILABLE;
        });
        GlobalPosition current = new GlobalPosition(new ChunkKey(0, 0), 8, 64, 8);
        GlobalPosition tooFar = new GlobalPosition(
                new ChunkKey(UnknownSpaceBarrier.MAX_CROSSED_CHUNKS + 1, 0), 8, 64, 8);

        assertThrows(IllegalArgumentException.class, () -> barrier.movement(current, tooFar));
        assertTrue(observed.isEmpty());
    }

    private static final class CountingAvailability implements Function<ChunkKey, ChunkAvailability> {
        private final ChunkKey unknown;
        private final List<ChunkKey> observedKeys = new ArrayList<>();

        private CountingAvailability(ChunkKey unknown) {
            this.unknown = unknown;
        }

        @Override
        public ChunkAvailability apply(ChunkKey key) {
            observedKeys.add(key);
            return key.equals(unknown) ? ChunkAvailability.UNKNOWN : ChunkAvailability.AVAILABLE;
        }
    }
}
