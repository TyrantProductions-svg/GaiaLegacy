package com.gaia.save.streaming;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.gaia.save.codec.SaveCodecException;
import com.overlord.voxel.ChunkKey;
import com.overlord.worlditem.api.SaveIdentity;
import com.overlord.worlditem.api.WorldItemPageDescriptor;
import com.overlord.worlditem.api.WorldItemPagingCheckpoint;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class WorldItemPagingCheckpointCodecTest {
    private static final SaveIdentity SAVE = new SaveIdentity(
            UUID.fromString("123e4567-e89b-12d3-a456-426614174000"));
    private static final SaveIdentity FOREIGN_SAVE = new SaveIdentity(
            UUID.fromString("00000000-0000-0000-0000-000000000001"));
    private static final String HASH_A = "11".repeat(32);
    private static final String HASH_B = "22".repeat(32);

    private final WorldItemPagingCheckpointCodec codec =
            new WorldItemPagingCheckpointCodec();

    @Test
    void literalV1HeaderAndCanonicalPhysicalDescriptorsRoundTrip() {
        WorldItemPagingCheckpoint checkpoint = new WorldItemPagingCheckpoint(
                SAVE,
                9L,
                10_000L,
                101L,
                false,
                3,
                List.of(
                        descriptor(new ChunkKey(7, -2), 4L, HASH_B, 3, 0),
                        descriptor(new ChunkKey(-2, 7), 3L, HASH_A, 4, 3)));

        byte[] encoded = codec.encode(checkpoint);
        assertEquals("GLWC", new String(encoded, 0, 4, StandardCharsets.US_ASCII));
        assertArrayEquals(new byte[] {0, 0, 0, 1}, Arrays.copyOfRange(encoded, 4, 8));

        WorldItemPagingCheckpoint decoded = codec.decode(SAVE, encoded);
        assertEquals(checkpoint.saveIdentity(), decoded.saveIdentity());
        assertEquals(9L, decoded.checkpointRevision());
        assertEquals(10_000L, decoded.worldTick());
        assertEquals(101L, decoded.nextItemId());
        assertEquals(3, decoded.totalLiveItemCount());
        assertEquals(List.of(new ChunkKey(-2, 7), new ChunkKey(7, -2)),
                decoded.pages().stream().map(WorldItemPageDescriptor::chunkKey).toList());
        assertEquals(0, decoded.pages().get(1).expectedLiveCountAtCheckpointTick());
    }

    @Test
    void descriptorListAndEncodedBytesAreDetached() {
        List<WorldItemPageDescriptor> source = new ArrayList<>(List.of(
                descriptor(new ChunkKey(0, 0), 1L, HASH_A, 1, 1)));
        WorldItemPagingCheckpoint checkpoint = new WorldItemPagingCheckpoint(
                SAVE, 1L, 0L, 2L, false, 1, source);

        byte[] first = codec.encode(checkpoint);
        byte[] second = codec.encode(checkpoint);
        source.clear();

        assertArrayEquals(first, second);
        assertNotSame(first, second);
        assertEquals(1, codec.decode(SAVE, second).pages().size());
        assertThrows(UnsupportedOperationException.class,
                () -> checkpoint.pages().clear());
    }

    @Test
    void duplicateKeysSurvivorMismatchAndInvalidAllocatorFailBeforeEncoding() {
        WorldItemPageDescriptor first =
                descriptor(new ChunkKey(0, 0), 1L, HASH_A, 2, 1);
        WorldItemPageDescriptor second =
                descriptor(new ChunkKey(0, 0), 2L, HASH_B, 2, 1);

        assertThrows(IllegalArgumentException.class, () -> new WorldItemPagingCheckpoint(
                SAVE, 1L, 0L, 3L, false, 2, List.of(first, second)));
        assertThrows(IllegalArgumentException.class, () -> new WorldItemPagingCheckpoint(
                SAVE, 1L, 0L, 3L, false, 2, List.of(first)));
        assertThrows(IllegalArgumentException.class, () -> new WorldItemPagingCheckpoint(
                SAVE, 1L, 0L, 3L, true, 1, List.of(first)));
        assertThrows(IllegalArgumentException.class, () ->
                descriptor(new ChunkKey(0, 0), 1L, HASH_A, 1, 2));
    }

    @Test
    void physicalDescriptorAndLiveSurvivorBoundsAreIndependent() {
        List<WorldItemPageDescriptor> pages = new ArrayList<>();
        for (int index = 0; index < 1_024; index++) {
            pages.add(descriptor(new ChunkKey(index, -index),
                    index + 1L, HASH_A, 1, index == 0 ? 1 : 0));
        }
        WorldItemPagingCheckpoint full = new WorldItemPagingCheckpoint(
                SAVE, 1L, 0L, 2L, false, 1, pages);
        assertEquals(1_024, codec.decode(SAVE, codec.encode(full)).pages().size());

        pages.add(descriptor(new ChunkKey(1_025, -1_025), 1_025L, HASH_B, 1, 0));
        assertThrows(IllegalArgumentException.class, () -> new WorldItemPagingCheckpoint(
                SAVE, 2L, 0L, 2L, false, 1, pages));
    }

    @Test
    void wrongSaveCorruptionTruncationAndTrailingBytesFailClosed() {
        byte[] valid = codec.encode(new WorldItemPagingCheckpoint(
                SAVE,
                1L,
                100L,
                Long.MAX_VALUE,
                true,
                0,
                List.of(descriptor(new ChunkKey(0, 0), 1L, HASH_A, 1, 0))));

        assertCode("world-item-checkpoint.wrong-save",
                () -> codec.decode(FOREIGN_SAVE, valid));
        byte[] corrupt = valid.clone();
        corrupt[corrupt.length - 1] ^= 1;
        assertCode("world-item-checkpoint.checksum-mismatch",
                () -> codec.decode(SAVE, corrupt));
        assertThrows(SaveCodecException.class,
                () -> codec.decode(SAVE, Arrays.copyOf(valid, valid.length - 1)));
        assertCode("world-item-checkpoint.trailing-bytes",
                () -> codec.decode(SAVE, concat(valid, new byte[] {1})));
    }

    private static WorldItemPageDescriptor descriptor(
            ChunkKey key,
            long revision,
            String hash,
            int encodedCount,
            int expectedLiveCount) {
        return new WorldItemPageDescriptor(
                key, revision, hash, encodedCount, expectedLiveCount);
    }

    private static void assertCode(String expected, Runnable decode) {
        SaveCodecException failure = assertThrows(SaveCodecException.class, decode::run);
        assertEquals(expected, failure.code());
    }

    private static byte[] concat(byte[] first, byte[] second) {
        byte[] result = Arrays.copyOf(first, first.length + second.length);
        System.arraycopy(second, 0, result, first.length, second.length);
        return result;
    }
}
