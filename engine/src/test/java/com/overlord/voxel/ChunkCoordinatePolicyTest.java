package com.overlord.voxel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ChunkCoordinatePolicyTest {
    private static final int MAX_SAFE_CHUNK_COORDINATE = 134217727;
    private static final int MIN_SAFE_CHUNK_COORDINATE = -134217727;

    @Test
    void acceptsBothSafeEnvelopeEndpointsAndCalculatesLongOrigins() {
        ChunkKey minimum =
                new ChunkKey(MIN_SAFE_CHUNK_COORDINATE, MAX_SAFE_CHUNK_COORDINATE);
        ChunkKey maximum =
                new ChunkKey(MAX_SAFE_CHUNK_COORDINATE, MIN_SAFE_CHUNK_COORDINATE);

        assertEquals(MAX_SAFE_CHUNK_COORDINATE, ChunkCoordinatePolicy.MAX_SAFE_CHUNK_COORDINATE);
        assertEquals(minimum, ChunkCoordinatePolicy.requireSafe(minimum));
        assertEquals(maximum, ChunkCoordinatePolicy.requireSafe(maximum));
        assertEquals(-2147483632L, ChunkCoordinatePolicy.worldOriginX(minimum));
        assertEquals(2147483632L, ChunkCoordinatePolicy.worldOriginZ(minimum));
        assertEquals(2147483632L, ChunkCoordinatePolicy.worldOriginX(maximum));
        assertEquals(-2147483632L, ChunkCoordinatePolicy.worldOriginZ(maximum));
    }

    @Test
    void rejectsKeysOutsideTheSafeEnvelope() {
        assertThrows(
                IllegalArgumentException.class,
                () -> ChunkCoordinatePolicy.requireSafe(new ChunkKey(134217728, 0)));
        assertThrows(
                IllegalArgumentException.class,
                () -> ChunkCoordinatePolicy.requireSafe(new ChunkKey(0, -134217728)));
    }

    @Test
    void rejectsNeighborStepsThatLeaveTheSafeEnvelope() {
        assertEquals(
                new ChunkKey(MAX_SAFE_CHUNK_COORDINATE - 1, MIN_SAFE_CHUNK_COORDINATE + 1),
                ChunkCoordinatePolicy.neighbor(
                        new ChunkKey(MAX_SAFE_CHUNK_COORDINATE, MIN_SAFE_CHUNK_COORDINATE),
                        -1,
                        1));
        assertThrows(
                IllegalArgumentException.class,
                () -> ChunkCoordinatePolicy.neighbor(
                        new ChunkKey(MAX_SAFE_CHUNK_COORDINATE, 0), 1, 0));
        assertThrows(
                IllegalArgumentException.class,
                () -> ChunkCoordinatePolicy.neighbor(
                        new ChunkKey(0, MIN_SAFE_CHUNK_COORDINATE), 0, -1));
    }

    @Test
    void calculatesOppositeEndpointDistanceWithoutOverflow() {
        ChunkKey minimum =
                new ChunkKey(MIN_SAFE_CHUNK_COORDINATE, MIN_SAFE_CHUNK_COORDINATE);
        ChunkKey maximum =
                new ChunkKey(MAX_SAFE_CHUNK_COORDINATE, MAX_SAFE_CHUNK_COORDINATE);

        assertEquals(
                144115185928372232L,
                ChunkCoordinatePolicy.squaredDistance(minimum, maximum));
    }

    @Test
    void canonicalComparatorProducesTheSamePriorityForEveryInputOrder() {
        Comparator<ChunkKey> comparator = ChunkCoordinatePolicy.canonicalComparator();
        List<ChunkKey> expected =
                List.of(
                        new ChunkKey(MIN_SAFE_CHUNK_COORDINATE, MAX_SAFE_CHUNK_COORDINATE),
                        new ChunkKey(-1, 1),
                        new ChunkKey(0, -1),
                        new ChunkKey(0, 1),
                        new ChunkKey(MAX_SAFE_CHUNK_COORDINATE, MIN_SAFE_CHUNK_COORDINATE));
        Set<ChunkKey> shuffledHashSet =
                new HashSet<>(
                        List.of(expected.get(3), expected.get(0), expected.get(4), expected.get(1), expected.get(2)));
        List<ChunkKey> reverseInsertion =
                List.of(expected.get(4), expected.get(3), expected.get(2), expected.get(1), expected.get(0));

        assertEquals(expected, sorted(shuffledHashSet, comparator));
        assertEquals(expected, sorted(reverseInsertion, comparator));
        assertEquals(expected, sorted(expected, comparator));
    }

    private static List<ChunkKey> sorted(
            Iterable<ChunkKey> keys, Comparator<ChunkKey> comparator) {
        List<ChunkKey> sorted = new ArrayList<>();
        keys.forEach(sorted::add);
        sorted.sort(comparator);
        return sorted;
    }
}
