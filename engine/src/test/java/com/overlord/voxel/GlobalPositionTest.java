package com.overlord.voxel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.overlord.config.GameConfig;
import org.junit.jupiter.api.Test;

class GlobalPositionTest {
    @Test
    void acceptsOnlyCanonicalFiniteCoordinates() {
        ChunkKey key = new ChunkKey(-7, 9);
        GlobalPosition position = new GlobalPosition(
                key, 0.0, -42.5, Math.nextDown((double) GameConfig.Chunk.SIZE));

        assertEquals(key, position.chunkKey());
        assertEquals(0.0, position.localX());
        assertEquals(-42.5, position.y());
        assertEquals(Math.nextDown(16.0), position.localZ());

        assertThrows(IllegalArgumentException.class,
                () -> new GlobalPosition(key, -0.0001, 0.0, 0.0));
        assertThrows(IllegalArgumentException.class,
                () -> new GlobalPosition(key, 16.0, 0.0, 0.0));
        assertThrows(IllegalArgumentException.class,
                () -> new GlobalPosition(key, 0.0, 0.0, Double.NaN));
        assertThrows(IllegalArgumentException.class,
                () -> new GlobalPosition(key, 0.0, Double.POSITIVE_INFINITY, 0.0));
        assertThrows(IllegalArgumentException.class,
                () -> new GlobalPosition(
                        new ChunkKey(
                                ChunkCoordinatePolicy.MAX_SAFE_CHUNK_COORDINATE + 1,
                                0),
                        0.0, 0.0, 0.0));
    }

    @Test
    void recordEqualityIsExactAndDeterministic() {
        GlobalPosition first = new GlobalPosition(new ChunkKey(-1, -2), 15.5, 3.0, 0.25);
        GlobalPosition equal = new GlobalPosition(new ChunkKey(-1, -2), 15.5, 3.0, 0.25);
        GlobalPosition different = new GlobalPosition(
                new ChunkKey(-1, -2), 15.5, 3.0, Math.nextUp(0.25));

        assertEquals(first, equal);
        assertEquals(first.hashCode(), equal.hashCode());
        assertNotEquals(first, different);
    }

    @Test
    void canonicalizesSignedZeroToOneCoordinateIdentity() {
        ChunkKey key = new ChunkKey(4, -5);
        GlobalPosition positiveZero = new GlobalPosition(key, 0.0, 0.0, 0.0);
        GlobalPosition negativeZero = new GlobalPosition(key, -0.0, -0.0, -0.0);

        assertEquals(positiveZero, negativeZero);
        assertEquals(positiveZero.hashCode(), negativeZero.hashCode());
        assertEquals(Double.doubleToRawLongBits(0.0),
                Double.doubleToRawLongBits(negativeZero.localX()));
        assertEquals(Double.doubleToRawLongBits(0.0),
                Double.doubleToRawLongBits(negativeZero.y()));
        assertEquals(Double.doubleToRawLongBits(0.0),
                Double.doubleToRawLongBits(negativeZero.localZ()));
    }
}
