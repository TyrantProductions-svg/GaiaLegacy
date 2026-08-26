package com.gaia.save.codec;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.gaia.save.format.SaveSectionId;
import com.overlord.config.GameConfig;
import com.overlord.voxel.ChunkKey;
import com.overlord.voxel.ChunkRepositorySnapshot;
import com.overlord.voxel.ChunkSnapshot;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Test;

class ChunkSectionCodecTest {
    private static final int CHUNK_SIZE = GameConfig.Chunk.SIZE;
    private static final int MAX_CHUNK_COUNT = 17 * 17;
    private static final int VERSION_OFFSET = 4;
    private static final int WORLD_HEIGHT_OFFSET = 8;
    private static final int HIGH_WATER_OFFSET = 12;
    private static final int COUNT_OFFSET = 20;
    private static final int FIRST_X_OFFSET = 24;
    private static final int FIRST_Z_OFFSET = 28;
    private static final int FIRST_REVISION_OFFSET = 32;
    private static final int FIRST_BLOCK_LENGTH_OFFSET = 40;
    private static final int FIRST_BLOCK_OFFSET = 44;

    private final ChunkSectionCodec codec = new ChunkSectionCodec();

    @Test
    void exposesRequiredV1ChunksSectionAndExactBigEndianBytes() {
        ChunkRepositorySnapshot snapshot =
                snapshot(
                        1,
                        1L,
                        chunk(-2, 3, 1L, 1, new byte[canonicalBlockLength(1)]));

        byte[] encoded = codec.encode(snapshot);

        assertEquals(SaveSectionId.CHUNKS, codec.sectionId());
        assertEquals(1, codec.codecVersion());
        assertEquals(true, codec.required());
        assertEquals(
                "474c4348"
                        + "00000001"
                        + "00000001"
                        + "0000000000000001"
                        + "00000001"
                        + "fffffffe"
                        + "00000003"
                        + "0000000000000001"
                        + "00000100"
                        + "00".repeat(256),
                HexFormat.of().formatHex(encoded));
    }

    @Test
    void shuffledChunkInputEncodesToIdenticalBytesAndDecodesInStableOrder() {
        ChunkSnapshot a = markedChunk(4, -3, 7L, 2, 1, (byte) 0x41);
        ChunkSnapshot b = markedChunk(-2, 5, 9L, 2, 17, (byte) 0x5a);

        byte[] forward = codec.encode(snapshot(2, 12L, a, b));
        byte[] shuffled = codec.encode(snapshot(2, 12L, b, a));
        ChunkRepositorySnapshot decoded = codec.decode(forward);

        assertArrayEquals(forward, shuffled);
        assertEquals(List.of(b, a), decoded.chunks());
        assertEquals(2, decoded.worldHeight());
        assertEquals(12L, decoded.revisionHighWater());
    }

    @Test
    void changingOneCanonicalBlockByteChangesEncodingAndSha256() {
        byte[] blocks = new byte[canonicalBlockLength(2)];
        ChunkRepositorySnapshot before =
                snapshot(2, 4L, chunk(0, 0, 4L, 2, blocks));

        blocks[37] = (byte) 0xa5;
        ChunkRepositorySnapshot after =
                snapshot(2, 4L, chunk(0, 0, 4L, 2, blocks));
        blocks[37] = 0;

        byte[] beforeBytes = codec.encode(before);
        byte[] afterBytes = codec.encode(after);

        assertFalse(Arrays.equals(beforeBytes, afterBytes));
        assertNotEquals(sha256(beforeBytes), sha256(afterBytes));
        assertEquals(0, Byte.toUnsignedInt(before.chunks().get(0).copyBlocks()[37]));
        assertEquals(0xa5, Byte.toUnsignedInt(after.chunks().get(0).copyBlocks()[37]));
    }

    @Test
    void encodedAndDecodedArraysNeverAliasCallerBuffers() {
        byte[] callerBlocks = new byte[canonicalBlockLength(1)];
        callerBlocks[0] = 11;
        ChunkRepositorySnapshot source =
                snapshot(1, 1L, chunk(0, 0, 1L, 1, callerBlocks));

        byte[] firstEncoding = codec.encode(source);
        byte[] secondEncoding = codec.encode(source);
        assertNotSame(firstEncoding, secondEncoding);
        firstEncoding[FIRST_BLOCK_OFFSET] = 44;
        assertEquals(11, Byte.toUnsignedInt(secondEncoding[FIRST_BLOCK_OFFSET]));

        ChunkRepositorySnapshot decoded = codec.decode(secondEncoding);
        secondEncoding[FIRST_BLOCK_OFFSET] = 55;
        byte[] firstCopy = decoded.chunks().get(0).copyBlocks();
        assertEquals(11, Byte.toUnsignedInt(firstCopy[0]));
        firstCopy[0] = 66;
        assertEquals(
                11,
                Byte.toUnsignedInt(decoded.chunks().get(0).copyBlocks()[0]));
    }

    @Test
    void encodeRejectsInvalidSnapshotsWithStableDiagnostic() {
        ChunkSnapshot valid = markedChunk(0, 0, 1L, 1, 0, (byte) 1);
        List<ChunkRepositorySnapshot> invalid =
                List.of(
                        new ChunkRepositorySnapshot(0, 0L, List.of()),
                        new ChunkRepositorySnapshot(-1, 0L, List.of()),
                        new ChunkRepositorySnapshot(
                                GameConfig.Chunk.MAX_HEIGHT + 1, 0L, List.of()),
                        new ChunkRepositorySnapshot(1, -1L, List.of()),
                        new ChunkRepositorySnapshot(1, Long.MAX_VALUE, List.of()),
                        snapshot(1, 1L, valid, valid),
                        snapshot(2, 1L, valid),
                        snapshot(1, 1L, ChunkSnapshot.empty(new ChunkKey(1, 0), 0L, 1)),
                        snapshot(1, 1L, ChunkSnapshot.empty(new ChunkKey(2, 0), -1L, 1)),
                        snapshot(1, 1L, ChunkSnapshot.empty(new ChunkKey(3, 0), 2L, 1)));

        for (ChunkRepositorySnapshot snapshot : invalid) {
            assertCodecFailure(
                    "chunks.invalid-snapshot", () -> codec.encode(snapshot));
        }
        assertCodecFailure("chunks.invalid-snapshot", () -> codec.encode(null));
    }

    @Test
    void supportsRadiusEightChunkCountAndRejectsOneMoreBeforeAllocation() {
        List<ChunkSnapshot> chunks = new ArrayList<>(MAX_CHUNK_COUNT);
        long revision = 0L;
        for (int x = -8; x <= 8; x++) {
            for (int z = -8; z <= 8; z++) {
                revision++;
                chunks.add(
                        markedChunk(
                                x,
                                z,
                                revision,
                                1,
                                Math.floorMod(x * 17 + z, canonicalBlockLength(1)),
                                (byte) revision));
            }
        }
        ChunkRepositorySnapshot maximum =
                new ChunkRepositorySnapshot(1, revision, chunks);

        assertEquals(maximum, codec.decode(codec.encode(maximum)));
        assertCodecFailure(
                "chunks.invalid-payload",
                () -> codec.decode(headerOnly(1, 0L, MAX_CHUNK_COUNT + 1)));
        assertCodecFailure(
                "chunks.invalid-payload",
                () -> codec.decode(headerOnly(1, 0L, Integer.MAX_VALUE)));
    }

    @Test
    void acceptsMaximumWorldHeightAndRejectsUnsupportedOrOverflowingHeights() {
        ChunkSnapshot maximumHeight =
                markedChunk(
                        0,
                        0,
                        1L,
                        GameConfig.Chunk.MAX_HEIGHT,
                        canonicalBlockLength(GameConfig.Chunk.MAX_HEIGHT) - 1,
                        (byte) 0x7f);
        ChunkRepositorySnapshot maximum =
                snapshot(GameConfig.Chunk.MAX_HEIGHT, 1L, maximumHeight);

        assertEquals(maximum, codec.decode(codec.encode(maximum)));

        byte[] valid = codec.encode(snapshot(1, 1L, markedChunk(0, 0, 1L, 1, 0, (byte) 1)));
        for (int invalidHeight :
                List.of(0, -1, GameConfig.Chunk.MAX_HEIGHT + 1, Integer.MAX_VALUE)) {
            assertCodecFailure(
                    "chunks.invalid-payload",
                    () -> codec.decode(withInt(valid, WORLD_HEIGHT_OFFSET, invalidHeight)));
        }
    }

    @Test
    void decodeRejectsMagicVersionHighWaterAndCountCorruption() {
        byte[] valid = codec.encode(snapshot(1, 1L, markedChunk(0, 0, 1L, 1, 0, (byte) 1)));

        byte[] badMagic = valid.clone();
        badMagic[0] = 'X';
        assertCodecFailure("chunks.invalid-payload", () -> codec.decode(badMagic));
        assertCodecFailure(
                "chunks.invalid-payload",
                () -> codec.decode(withInt(valid, VERSION_OFFSET, 2)));
        assertCodecFailure(
                "chunks.invalid-payload",
                () -> codec.decode(withLong(valid, HIGH_WATER_OFFSET, -1L)));
        assertCodecFailure(
                "chunks.invalid-payload",
                () -> codec.decode(withLong(valid, HIGH_WATER_OFFSET, Long.MAX_VALUE)));
        assertCodecFailure(
                "chunks.invalid-payload",
                () -> codec.decode(withInt(valid, COUNT_OFFSET, -1)));
    }

    @Test
    void decodeRejectsNoncanonicalBlockLengthsBeforeReadingBlocks() {
        byte[] valid = codec.encode(snapshot(1, 1L, markedChunk(0, 0, 1L, 1, 0, (byte) 1)));

        assertCodecFailure(
                "chunks.invalid-payload",
                () -> codec.decode(withInt(valid, FIRST_BLOCK_LENGTH_OFFSET, 255)));
        assertCodecFailure(
                "chunks.invalid-payload",
                () -> codec.decode(withInt(valid, FIRST_BLOCK_LENGTH_OFFSET, 257)));
        assertCodecFailure(
                "chunks.invalid-payload",
                () -> codec.decode(withInt(valid, FIRST_BLOCK_LENGTH_OFFSET, Integer.MAX_VALUE)));
        assertCodecFailure(
                "chunks.invalid-payload",
                () -> codec.decode(withInt(valid, FIRST_BLOCK_LENGTH_OFFSET, -1)));
    }

    @Test
    void decodeRejectsDuplicateKeysAndInvalidRevisions() {
        ChunkSnapshot first = markedChunk(-1, 2, 1L, 1, 0, (byte) 1);
        ChunkSnapshot second = markedChunk(3, 4, 2L, 1, 1, (byte) 2);
        byte[] twoChunks = codec.encode(snapshot(1, 2L, first, second));
        int secondEntryOffset = FIRST_BLOCK_OFFSET + canonicalBlockLength(1);
        byte[] duplicateKey = twoChunks.clone();
        putInt(duplicateKey, secondEntryOffset, -1);
        putInt(duplicateKey, secondEntryOffset + Integer.BYTES, 2);

        assertCodecFailure(
                "chunks.invalid-payload", () -> codec.decode(duplicateKey));

        byte[] oneChunk = codec.encode(snapshot(1, 1L, first));
        assertCodecFailure(
                "chunks.invalid-payload",
                () -> codec.decode(withLong(oneChunk, FIRST_REVISION_OFFSET, 0L)));
        assertCodecFailure(
                "chunks.invalid-payload",
                () -> codec.decode(withLong(oneChunk, FIRST_REVISION_OFFSET, -1L)));
        assertCodecFailure(
                "chunks.invalid-payload",
                () -> codec.decode(withLong(oneChunk, FIRST_REVISION_OFFSET, 2L)));
    }

    @Test
    void encodeAndDecodeRejectKeysOutsideTheSafeGlobalAddressEnvelope() {
        ChunkSnapshot unsafe =
                markedChunk(134217728, 0, 1L, 1, 0, (byte) 1);
        assertCodecFailure(
                "chunks.invalid-snapshot", () -> codec.encode(snapshot(1, 1L, unsafe)));

        byte[] valid =
                codec.encode(
                        snapshot(
                                1,
                                1L,
                                markedChunk(0, 0, 1L, 1, 0, (byte) 1)));
        assertCodecFailure(
                "chunks.invalid-payload",
                () -> codec.decode(withInt(valid, FIRST_X_OFFSET, 134217728)));
        assertCodecFailure(
                "chunks.invalid-payload",
                () -> codec.decode(withInt(valid, FIRST_Z_OFFSET, -134217728)));
    }

    @Test
    void decodeRejectsTrailingBytesAndOversizedCountBeforeAllocation() {
        byte[] valid = codec.encode(snapshot(1, 1L, markedChunk(0, 0, 1L, 1, 0, (byte) 1)));

        assertCodecFailure(
                "chunks.invalid-payload",
                () -> codec.decode(headerOnly(1, 0L, Integer.MAX_VALUE)));
        assertCodecFailure(
                "chunks.invalid-payload",
                () -> codec.decode(concat(valid, new byte[] {1})));
    }

    @Test
    void decodeRejectsEveryTruncatedBoundaryAndNullInput() {
        byte[] valid = codec.encode(snapshot(1, 1L, markedChunk(0, 0, 1L, 1, 255, (byte) 3)));

        for (int length = 0; length < valid.length; length++) {
            byte[] truncated = Arrays.copyOf(valid, length);
            assertCodecFailure(
                    "chunks.invalid-payload", () -> codec.decode(truncated));
        }
        assertCodecFailure("chunks.invalid-payload", () -> codec.decode(null));
    }

    private static ChunkRepositorySnapshot snapshot(
            int worldHeight, long revisionHighWater, ChunkSnapshot... chunks) {
        return new ChunkRepositorySnapshot(
                worldHeight, revisionHighWater, List.of(chunks));
    }

    private static ChunkSnapshot markedChunk(
            int x,
            int z,
            long revision,
            int worldHeight,
            int blockIndex,
            byte value) {
        byte[] blocks = new byte[canonicalBlockLength(worldHeight)];
        blocks[blockIndex] = value;
        return chunk(x, z, revision, worldHeight, blocks);
    }

    private static ChunkSnapshot chunk(
            int x,
            int z,
            long revision,
            int worldHeight,
            byte[] blocks) {
        return ChunkSnapshot.of(
                new ChunkKey(x, z), revision, worldHeight, blocks);
    }

    private static int canonicalBlockLength(int worldHeight) {
        return Math.multiplyExact(Math.multiplyExact(CHUNK_SIZE, worldHeight), CHUNK_SIZE);
    }

    private static byte[] headerOnly(
            int worldHeight, long revisionHighWater, int chunkCount) {
        return ByteBuffer.allocate(24)
                .order(ByteOrder.BIG_ENDIAN)
                .put(new byte[] {'G', 'L', 'C', 'H'})
                .putInt(1)
                .putInt(worldHeight)
                .putLong(revisionHighWater)
                .putInt(chunkCount)
                .array();
    }

    private static byte[] withInt(byte[] source, int offset, int value) {
        byte[] result = source.clone();
        putInt(result, offset, value);
        return result;
    }

    private static byte[] withLong(byte[] source, int offset, long value) {
        byte[] result = source.clone();
        ByteBuffer.wrap(result).order(ByteOrder.BIG_ENDIAN).putLong(offset, value);
        return result;
    }

    private static void putInt(byte[] target, int offset, int value) {
        ByteBuffer.wrap(target).order(ByteOrder.BIG_ENDIAN).putInt(offset, value);
    }

    private static byte[] concat(byte[] first, byte[] second) {
        byte[] result = Arrays.copyOf(first, first.length + second.length);
        System.arraycopy(second, 0, result, first.length, second.length);
        return result;
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private static void assertCodecFailure(
            String expectedCode, ThrowingOperation operation) {
        SaveCodecException failure =
                assertThrows(SaveCodecException.class, operation::run);
        assertEquals(expectedCode, failure.code());
        assertNotNull(failure.getCause());
    }

    @FunctionalInterface
    private interface ThrowingOperation {
        void run();
    }
}
