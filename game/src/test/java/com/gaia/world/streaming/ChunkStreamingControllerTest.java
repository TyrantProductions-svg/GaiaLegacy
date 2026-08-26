package com.gaia.world.streaming;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.overlord.voxel.ChunkKey;
import com.overlord.voxel.GlobalPosition;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ChunkStreamingControllerTest {
    @Test
    void exactDefaultSquaresUseAuthoritativePlayerChunkAtNegativeCoordinates() {
        ChunkStreamingController controller = controller();
        ChunkKey center = new ChunkKey(-11, -13);

        ChunkStreamingDecision decision = controller.update(
                position(center), observation(Set.of(), Set.of()));

        assertEquals(25, decision.desiredSets().simulation().size());
        assertEquals(81, decision.desiredSets().render().size());
        assertEquals(121, decision.desiredSets().preload().size());
        assertEquals(expectedSquare(center, 2), decision.desiredSets().simulation());
        assertEquals(expectedSquare(center, 4), decision.desiredSets().render());
        assertEquals(expectedSquare(center, 5), decision.desiredSets().preload());
        assertTrue(decision.desiredSets().simulation().contains(new ChunkKey(-13, -15)));
        assertTrue(decision.desiredSets().render().contains(new ChunkKey(-7, -9)));
        assertTrue(decision.desiredSets().preload().contains(new ChunkKey(-16, -18)));
        assertFalse(decision.desiredSets().simulation().contains(new ChunkKey(-14, -13)));
        assertEquals(1L, decision.desiredEpoch());
    }

    @Test
    void desiredEpochChangesOnlyWhenDesiredSetIdentityChanges() {
        ChunkStreamingController controller = controller();
        GlobalPosition stationary = position(new ChunkKey(0, 0));

        ChunkStreamingDecision first = controller.update(
                stationary, observation(Set.of(), Set.of()));
        ChunkStreamingDecision completedRequests = controller.update(
                stationary,
                observation(new HashSet<>(first.admissions()), Set.of()));
        ChunkStreamingDecision equivalentOrder = controller.update(
                new GlobalPosition(new ChunkKey(0, 0), 15.75, -8.0, 0.0),
                observation(shuffled(first.admissions()), shuffled(Set.of())));
        ChunkStreamingDecision east = controller.update(
                position(new ChunkKey(1, 0)), observation(Set.of(), Set.of()));
        ChunkStreamingDecision eastAgain = controller.update(
                position(new ChunkKey(1, 0)), observation(Set.of(), Set.of()));

        assertEquals(1L, first.desiredEpoch());
        assertEquals(first.desiredEpoch(), completedRequests.desiredEpoch());
        assertEquals(first.desiredEpoch(), equivalentOrder.desiredEpoch());
        assertEquals(2L, east.desiredEpoch());
        assertEquals(east.desiredEpoch(), eastAgain.desiredEpoch());
    }

    @Test
    void oneChunkMovementWorksInAllDirectionsAndAcrossNegativeZeroBoundary() {
        for (ChunkKey moved : List.of(
                new ChunkKey(1, 0), new ChunkKey(-1, 0),
                new ChunkKey(0, 1), new ChunkKey(0, -1))) {
            ChunkStreamingController controller = controller();
            ChunkStreamingDecision origin = controller.update(
                    position(new ChunkKey(0, 0)), observation(Set.of(), Set.of()));
            ChunkStreamingDecision next = controller.update(
                    position(moved), observation(Set.of(), Set.of()));

            assertEquals(1L, origin.desiredEpoch());
            assertEquals(2L, next.desiredEpoch());
            assertTrue(next.desiredSets().simulation().contains(moved));
            assertTrue(next.desiredSets().preload().contains(moved));
            assertEquals(121, next.desiredSets().preload().size());
        }
    }

    @Test
    void unloadRadiusProvidesHysteresisDuringBoundaryOscillation() {
        ChunkStreamingController controller = controller();
        Set<ChunkKey> resident = Set.of(
                new ChunkKey(-6, 0),
                new ChunkKey(-7, 0),
                new ChunkKey(-8, 0),
                new ChunkKey(7, 7));

        ChunkStreamingDecision origin = controller.update(
                position(new ChunkKey(0, 0)), observation(resident, Set.of()));
        ChunkStreamingDecision east = controller.update(
                position(new ChunkKey(1, 0)), observation(resident, Set.of()));
        ChunkStreamingDecision back = controller.update(
                position(new ChunkKey(0, 0)), observation(resident, Set.of()));

        assertEquals(List.of(new ChunkKey(-8, 0)), origin.unloadCandidates());
        assertEquals(
                List.of(new ChunkKey(-8, 0), new ChunkKey(-7, 0)),
                east.unloadCandidates());
        assertEquals(origin.unloadCandidates(), back.unloadCandidates());
        assertFalse(origin.unloadCandidates().contains(new ChunkKey(-6, 0)));
        assertFalse(origin.unloadCandidates().contains(new ChunkKey(7, 7)));
    }

    @Test
    void priorityIsStableAndQueueSaturationRejectsFartherWork() {
        ChunkStreamingPolicy oneSlot = ChunkStreamingPolicyTest.policy(2, 4, 5, 7, 1);
        ChunkStreamingController controller = new ChunkStreamingController(oneSlot);
        ChunkKey center = new ChunkKey(0, 0);

        ChunkStreamingDecision decision = controller.update(
                position(center), observation(Set.of(), Set.of()));

        assertEquals(List.of(center), decision.admissions());
        assertEquals(120, decision.rejections().size());
        assertTrue(decision.rejections().contains(new ChunkKey(5, 5)));
        assertEquals(
                new ChunkKey(-1, 0),
                decision.rejections().get(0),
                "distance ties use canonical x/z ordering");
        ChunkPriority firstRejected = ChunkPriority.of(
                center, decision.rejections().get(0), decision.desiredSets());
        ChunkPriority farCorner = ChunkPriority.of(
                center, new ChunkKey(5, 5), decision.desiredSets());
        assertTrue(firstRejected.compareTo(farCorner) < 0);
    }

    @Test
    void teleportCancelsOldRequestsAndAdmitsNewCenterWithoutExceedingBound() {
        ChunkStreamingController controller = controller();
        ChunkStreamingDecision initial = controller.update(
                position(new ChunkKey(-100, -100)), observation(Set.of(), Set.of()));
        Set<ChunkKey> requested = new LinkedHashSet<>(initial.admissions());

        ChunkStreamingDecision teleported = controller.update(
                position(new ChunkKey(100, 100)), observation(Set.of(), requested));

        assertEquals(2L, teleported.desiredEpoch());
        assertEquals(List.copyOf(requested).stream().sorted(
                com.overlord.voxel.ChunkCoordinatePolicy.canonicalComparator()).toList(),
                teleported.cancellations());
        assertEquals(32, teleported.admissions().size());
        assertEquals(new ChunkKey(100, 100), teleported.admissions().get(0));
        assertTrue(teleported.admissions().stream()
                .allMatch(teleported.desiredSets().preload()::contains));
    }

    @Test
    void residentCompletionDoesNotConsumeOutstandingRequestCapacity() {
        ChunkKey center = new ChunkKey(0, 0);
        ChunkStreamingDecision decision = controller().update(
                position(center), observation(Set.of(center), Set.of(center)));

        assertEquals(32, decision.admissions().size());
        assertEquals(88, decision.rejections().size());
        assertFalse(decision.admissions().contains(center));
        assertTrue(decision.cancellations().isEmpty());
    }

    @Test
    void residentHardBoundBackpressuresLoadsWhileSaveLaneCannotEvict() {
        ChunkKey oldCenter = new ChunkKey(-100, -100);
        ChunkKey nextCenter = new ChunkKey(100, 100);
        Set<ChunkKey> saturated = expectedSquare(oldCenter, 7);

        ChunkStreamingDecision blocked = controller().update(
                position(nextCenter), observation(saturated, Set.of()));

        assertEquals(225, saturated.size());
        assertTrue(blocked.admissions().isEmpty());
        assertEquals(121, blocked.rejections().size());

        Set<ChunkKey> oneSlot = new HashSet<>(saturated);
        oneSlot.remove(new ChunkKey(oldCenter.x() - 7, oldCenter.z() - 7));
        ChunkStreamingDecision oneAdmission = controller().update(
                position(nextCenter), observation(oneSlot, Set.of()));

        assertEquals(1, oneAdmission.admissions().size());
        assertEquals(nextCenter, oneAdmission.admissions().get(0));
    }

    @Test
    void observationAndDecisionAreDefensiveImmutableAndShuffleDeterministic() {
        List<ChunkKey> residentInput = new ArrayList<>(List.of(
                new ChunkKey(9, 9), new ChunkKey(-9, -9), new ChunkKey(0, 0)));
        List<ChunkKey> requestedInput = new ArrayList<>(List.of(
                new ChunkKey(4, 0), new ChunkKey(-4, 0)));
        Collections.shuffle(residentInput, new java.util.Random(17L));
        Collections.shuffle(requestedInput, new java.util.Random(23L));
        Set<ChunkKey> resident = new LinkedHashSet<>(residentInput);
        Set<ChunkKey> requested = new LinkedHashSet<>(requestedInput);
        ChunkStreamingObservation observation = observation(resident, requested);
        resident.clear();
        requested.clear();

        ChunkStreamingDecision first = controller().update(
                position(new ChunkKey(0, 0)), observation);
        ChunkStreamingDecision second = controller().update(
                position(new ChunkKey(0, 0)),
                observation(shuffled(observation.resident()),
                        shuffled(observation.requested())));

        assertEquals(3, observation.resident().size());
        assertEquals(2, observation.requested().size());
        assertEquals(first, second);
        assertThrows(UnsupportedOperationException.class,
                () -> observation.resident().add(new ChunkKey(1, 1)));
        assertThrows(UnsupportedOperationException.class,
                () -> first.desiredSets().preload().clear());
        assertThrows(UnsupportedOperationException.class,
                () -> first.admissions().add(new ChunkKey(1, 1)));
    }

    @Test
    void invalidObservationKeysAndUnsafeEnumerationFailClosed() {
        assertThrows(IllegalArgumentException.class,
                () -> observation(
                        Set.of(new ChunkKey(
                                com.overlord.voxel.ChunkCoordinatePolicy
                                        .MAX_SAFE_CHUNK_COORDINATE + 1,
                                0)),
                        Set.of()));

        ChunkStreamingController controller = controller();
        assertThrows(IllegalArgumentException.class,
                () -> controller.update(
                        position(new ChunkKey(
                                com.overlord.voxel.ChunkCoordinatePolicy
                                        .MAX_SAFE_CHUNK_COORDINATE,
                                0)),
                        observation(Set.of(), Set.of())));
        assertEquals(
                1L,
                controller.update(
                        position(new ChunkKey(0, 0)),
                        observation(Set.of(), Set.of())).desiredEpoch(),
                "failed checked enumeration must not consume an epoch");
    }

    private static ChunkStreamingController controller() {
        return new ChunkStreamingController(ChunkStreamingPolicy.productionDefaults());
    }

    private static GlobalPosition position(ChunkKey key) {
        return new GlobalPosition(key, 0.5, 64.0, 0.5);
    }

    private static ChunkStreamingObservation observation(
            Set<ChunkKey> resident, Set<ChunkKey> requested) {
        return new ChunkStreamingObservation(resident, requested);
    }

    private static Set<ChunkKey> shuffled(Iterable<ChunkKey> values) {
        List<ChunkKey> copy = new ArrayList<>();
        values.forEach(copy::add);
        Collections.shuffle(copy, new java.util.Random(99L));
        return new LinkedHashSet<>(copy);
    }

    private static Set<ChunkKey> expectedSquare(ChunkKey center, int radius) {
        Set<ChunkKey> result = new HashSet<>();
        for (int x = center.x() - radius; x <= center.x() + radius; x++) {
            for (int z = center.z() - radius; z <= center.z() + radius; z++) {
                result.add(new ChunkKey(x, z));
            }
        }
        return Set.copyOf(result);
    }
}
