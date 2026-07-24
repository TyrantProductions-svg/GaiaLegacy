package com.overlord.voxel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class BlockSizeTest {

    @Test
    void fromSuffixParsesValidSizes() {
        assertEquals(BlockSize.SIZE_2, BlockSize.fromSuffix("2"));
        assertEquals(BlockSize.SIZE_4, BlockSize.fromSuffix("4"));
        assertEquals(BlockSize.SIZE_8, BlockSize.fromSuffix("8"));
        assertEquals(BlockSize.SIZE_16, BlockSize.fromSuffix("16"));
    }

    @Test
    void fromSuffixRejectsInvalidSizes() {
        assertThrows(IllegalArgumentException.class, () -> BlockSize.fromSuffix("1"));
        assertThrows(IllegalArgumentException.class, () -> BlockSize.fromSuffix("32"));
        assertThrows(IllegalArgumentException.class, () -> BlockSize.fromSuffix("invalid"));
    }

    @Test
    void fromPixelsReturnsCorrectSize() {
        assertEquals(BlockSize.SIZE_2, BlockSize.fromPixels(2));
        assertEquals(BlockSize.SIZE_4, BlockSize.fromPixels(4));
        assertEquals(BlockSize.SIZE_8, BlockSize.fromPixels(8));
        assertEquals(BlockSize.SIZE_16, BlockSize.fromPixels(16));
    }

    @Test
    void fromPixelsRejectsInvalidValues() {
        assertThrows(IllegalArgumentException.class, () -> BlockSize.fromPixels(1));
        assertThrows(IllegalArgumentException.class, () -> BlockSize.fromPixels(32));
    }

    @Test
    void sizeUnitsMatchPixels() {
        assertEquals(0.125f, BlockSize.SIZE_2.units());
        assertEquals(0.25f, BlockSize.SIZE_4.units());
        assertEquals(0.5f, BlockSize.SIZE_8.units());
        assertEquals(1.0f, BlockSize.SIZE_16.units());
    }

    @Test
    void sizePixelsAreCorrect() {
        assertEquals(2, BlockSize.SIZE_2.pixels());
        assertEquals(4, BlockSize.SIZE_4.pixels());
        assertEquals(8, BlockSize.SIZE_8.pixels());
        assertEquals(16, BlockSize.SIZE_16.pixels());
    }
}