package com.gaia.save.streaming;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gaia.save.archive.SaveDiagnostic;
import com.gaia.save.codec.SaveCodecException;
import com.gaia.save.format.SaveCodecRegistry;
import com.gaia.save.format.SaveFormatVersion;
import com.gaia.save.format.SaveGameId;
import com.gaia.save.format.SaveSectionDescriptor;
import com.gaia.save.format.SaveSectionId;
import com.overlord.voxel.ChunkCoordinatePolicy;
import com.overlord.voxel.ChunkKey;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Test;

class StreamedChunkCodecTest {
    private static final SaveGameId SAVE_ID =
            SaveGameId.parse("123e4567-e89b-12d3-a456-426614174000");
    private static final String GENERATOR_VERSION = "v15";
    private static final String BASE_HASH = "11".repeat(32);
    private static final String PAYLOAD_HASH = "22".repeat(32);
    private static final int VOXEL_LENGTH = 16 * 1 * 16;
    private static final int EXACT_VOXEL_OFFSET = 187;
    private static final int EXACT_EXTENSION_LENGTH_OFFSET = 151;

    private final StreamedChunkCodec codec = new StreamedChunkCodec();
    private final StreamedChunkIndexCodec indexCodec =
            new StreamedChunkIndexCodec();

    @Test
    void requiredExtensionSupportIsInjectedInsteadOfHardCodedInTask4Codec() {
        SaveSectionId future = new SaveSectionId("future-runtime");
        StreamedChunkPayload payload = payload(
                new ChunkKey(2, -3),
                3L,
                2L,
                new byte[VOXEL_LENGTH],
                List.of(extension(future, true, new byte[] {7})));
        StreamedExtensionSupportRegistry registry =
                StreamedExtensionSupportRegistry.builder()
                        .supportRequired(future, 1)
                        .build();

        StreamedChunkCodec injected = new StreamedChunkCodec(registry);
        assertEquals(StreamedChunkCodec.DecodeResult.Status.VALID,
                injected.decode(injected.encode(payload)).status());
        assertThrows(SaveCodecException.class,
                () -> new StreamedChunkCodec(
                        StreamedExtensionSupportRegistry.empty()).encode(payload));
    }

    @Test
    void legacyV2PayloadLiteralDecodesWithBothPersistenceFlagsTrue() {
        byte[] voxels = new byte[VOXEL_LENGTH];
        voxels[0] = 1;
        voxels[voxels.length - 1] = 0x7f;
        StreamedChunkPayload payload = payload(
                new ChunkKey(-2, ChunkCoordinatePolicy.MAX_SAFE_CHUNK_COORDINATE),
                9L,
                8L,
                voxels,
                List.of(extension(SaveSectionId.DETAIL_BLOCKS, false,
                        new byte[] {1, 2, 3})));

        String expectedHex =
                "474c4332"
                        + "00000002"
                        + "123e4567e89b12d3a456426614174000"
                        + "fffffffe"
                        + "07ffffff"
                        + "00000003"
                        + "763135"
                        + "11".repeat(32)
                        + "0000000000000009"
                        + "0000000000000008"
                        + "01"
                        + "00000001"
                        + "00000100"
                        + "26f496f90511737bb50b1b5753c34a7f"
                        + "534a509eaa7fe52aa73192c8ce823ec0"
                        + "0000000d"
                        + "64657461696c2d626c6f636b73"
                        + "00000001"
                        + "00"
                        + "00000003"
                        + "039058c6f2c0cb492c533b0a4d14ef77"
                        + "cc0f78abccced5287d84a1a2011cfb81"
                        + "01"
                        + "00".repeat(254)
                        + "7f"
                        + "010203";
        StreamedChunkCodec.DecodeResult decoded =
                codec.decode(HexFormat.of().parseHex(expectedHex));
        assertEquals(StreamedChunkCodec.DecodeResult.Status.VALID, decoded.status());
        assertTrue(decoded.diagnostics().isEmpty());
        assertExactPayload(payload, decoded.payload().orElseThrow());
        assertTrue(decoded.payload().orElseThrow().persistenceRequired());
        assertTrue(decoded.payload().orElseThrow().voxelModified());
    }

    @Test
    void v3PayloadUsesIndependentPersistenceAndVoxelFlagsAndRetainsRequiredPage() {
        byte[] pageBytes = {9, 8, 7};
        byte[] detailBytes = {1, 2, 3};
        StreamedChunkPayload payload = new StreamedChunkPayload(
                SAVE_ID,
                new ChunkKey(-1, 0),
                GENERATOR_VERSION,
                BASE_HASH,
                4L,
                3L,
                true,
                false,
                1,
                new byte[VOXEL_LENGTH],
                List.of(
                        extension(SaveSectionId.DETAIL_BLOCKS, false, detailBytes),
                        extension(SaveSectionId.WORLD_ITEM_PAGE, true, pageBytes)));

        byte[] encoded = codec.encode(payload);

        assertEquals("474c433200000003", HexFormat.of().formatHex(
                Arrays.copyOf(encoded, 8)));
        assertEquals(1, encoded[87]);
        assertEquals(0, encoded[88]);
        StreamedChunkPayload decoded = codec.decode(encoded).payload().orElseThrow();
        assertTrue(decoded.persistenceRequired());
        assertFalse(decoded.voxelModified());
        assertEquals(List.of(SaveSectionId.DETAIL_BLOCKS, SaveSectionId.WORLD_ITEM_PAGE),
                decoded.extensions().stream()
                        .map(StreamedChunkPayload.ExtensionDescriptor::sectionId)
                        .toList());
        assertArrayEquals(pageBytes, decoded.extensions().stream()
                .filter(value -> value.sectionId().equals(SaveSectionId.WORLD_ITEM_PAGE))
                .findFirst().orElseThrow().copyBytes());
    }

    @Test
    void persistenceRequiredExactlyMatchesVoxelChangeOrRequiredRuntimeExtension() {
        byte[] voxels = new byte[VOXEL_LENGTH];
        assertThrows(IllegalArgumentException.class, () -> new StreamedChunkPayload(
                SAVE_ID, new ChunkKey(0, 0), GENERATOR_VERSION, BASE_HASH,
                1L, 0L, false, true, 1, voxels, List.of()));
        assertThrows(IllegalArgumentException.class, () -> new StreamedChunkPayload(
                SAVE_ID, new ChunkKey(0, 0), GENERATOR_VERSION, BASE_HASH,
                1L, 0L, true, false, 1, voxels, List.of()));
        assertThrows(IllegalArgumentException.class, () -> new StreamedChunkPayload(
                SAVE_ID, new ChunkKey(0, 0), GENERATOR_VERSION, BASE_HASH,
                1L, 0L, false, false, 1, voxels,
                List.of(extension(SaveSectionId.WORLD_ITEM_PAGE, true, new byte[] {1}))));
        assertThrows(IllegalArgumentException.class, () -> new StreamedChunkPayload(
                SAVE_ID, new ChunkKey(0, 0), GENERATOR_VERSION, BASE_HASH,
                1L, 0L, true, false, 1, voxels,
                List.of(extension(SaveSectionId.DETAIL_BLOCKS, false, new byte[] {1}))));
    }

    @Test
    void unknownRequiredWorldItemPageCodecVersionFailsClosed() {
        StreamedChunkPayload.ExtensionDescriptor futurePage =
                new StreamedChunkPayload.ExtensionDescriptor(
                        SaveSectionId.WORLD_ITEM_PAGE,
                        2,
                        true,
                        new byte[] {1});
        StreamedChunkPayload payload = new StreamedChunkPayload(
                SAVE_ID, new ChunkKey(0, 0), GENERATOR_VERSION, BASE_HASH,
                1L, 0L, true, false, 1, new byte[VOXEL_LENGTH],
                List.of(futurePage));

        SaveCodecException failure = assertThrows(
                SaveCodecException.class, () -> codec.encode(payload));

        assertEquals("streamed-chunk.invalid-snapshot", failure.code());
    }

    @Test
    void payloadBytesAreDeterministicDetachedAndExtensionOrderIsCanonical() {
        byte[] voxels = markedVoxels(37, (byte) 0x5a);
        byte[] detail = {3, 2, 1};
        byte[] future = {9, 8};
        StreamedChunkPayload.ExtensionDescriptor detailBlocks = extension(
                SaveSectionId.DETAIL_BLOCKS, false, detail);
        StreamedChunkPayload.ExtensionDescriptor futureLighting = extension(
                new SaveSectionId("future-lighting"), false, future);
        StreamedChunkPayload first = payload(
                new ChunkKey(-17, 23), 31L, 30L, voxels,
                List.of(futureLighting, detailBlocks));
        StreamedChunkPayload second = payload(
                new ChunkKey(-17, 23), 31L, 30L, voxels,
                List.of(detailBlocks, futureLighting));

        byte[] firstBytes = codec.encode(first);
        byte[] secondBytes = codec.encode(second);

        assertArrayEquals(firstBytes, secondBytes);
        assertNotSame(firstBytes, codec.encode(first));
        voxels[37] = 0;
        detail[0] = 0;
        future[0] = 0;
        assertArrayEquals(firstBytes, codec.encode(first));
    }

    @Test
    void unknownOptionalExtensionIsChecksumValidatedThenSkippedWithBoundedDiagnostic() {
        StreamedChunkPayload source = payload(
                new ChunkKey(-3, 4),
                7L,
                6L,
                markedVoxels(11, (byte) 4),
                List.of(extension(
                        new SaveSectionId("future-lighting"),
                        false,
                        new byte[] {7, 8, 9})));

        StreamedChunkCodec.DecodeResult result = codec.decode(codec.encode(source));

        assertEquals(StreamedChunkCodec.DecodeResult.Status.VALID, result.status());
        assertTrue(result.payload().orElseThrow().extensions().isEmpty());
        assertEquals(
                List.of("streamed-chunk.unknown-optional-extension"),
                result.diagnostics().stream().map(SaveDiagnostic::code).toList());
        assertBounded(result.diagnostics());

        byte[] corrupt = codec.encode(source);
        corrupt[corrupt.length - 1] ^= 1;
        assertPayloadClosed(
                corrupt,
                StreamedChunkCodec.DecodeResult.Status.CORRUPT,
                "streamed-chunk.extension-checksum-mismatch");
    }

    @Test
    void unknownRequiredExtensionIsRejectedWithoutPublishingPayload() {
        SaveSectionId future = new SaveSectionId("future-lighting");
        byte[] encoded = codec.encode(payload(
                new ChunkKey(1, -1),
                4L,
                3L,
                markedVoxels(4, (byte) 7),
                List.of(extension(future, false, new byte[] {4, 5}))));
        int requiredOffset = firstDescriptorRequiredOffset(encoded);
        encoded[requiredOffset] = 1;

        assertPayloadClosed(
                encoded,
                StreamedChunkCodec.DecodeResult.Status.UNSUPPORTED_REQUIRED_EXTENSION,
                "streamed-chunk.unknown-required-extension");
    }

    @Test
    void duplicateTrailingOversizeAndCorruptPayloadsAreClosedWithoutPartialValue() {
        StreamedChunkPayload duplicateFixture = payload(
                new ChunkKey(5, -6),
                12L,
                11L,
                markedVoxels(8, (byte) 6),
                List.of(
                        extension(new SaveSectionId("future-alpha"), false,
                                new byte[] {1}),
                        extension(new SaveSectionId("future-bravo"), false,
                                new byte[] {2})));
        byte[] duplicate = codec.encode(duplicateFixture);
        replaceAsciiOccurrence(duplicate, "future-bravo", "future-alpha", 1);
        assertPayloadClosed(
                duplicate,
                StreamedChunkCodec.DecodeResult.Status.CORRUPT,
                "streamed-chunk.duplicate-extension");

        byte[] valid = codec.encode(exactPayload());
        assertPayloadClosed(
                concat(valid, new byte[] {99}),
                StreamedChunkCodec.DecodeResult.Status.CORRUPT,
                "streamed-chunk.trailing-bytes");
        assertPayloadClosed(
                new byte[Math.toIntExact(StreamedChunkCodec.MAX_FILE_BYTES + 1L)],
                StreamedChunkCodec.DecodeResult.Status.CORRUPT,
                "streamed-chunk.file-size-limit");

        byte[] badMagic = valid.clone();
        badMagic[0] = 'X';
        assertPayloadClosed(
                badMagic,
                StreamedChunkCodec.DecodeResult.Status.CORRUPT,
                "streamed-chunk.invalid-magic");
        byte[] badVersion = withInt(valid, 4, 4);
        assertPayloadClosed(
                badVersion,
                StreamedChunkCodec.DecodeResult.Status.CORRUPT,
                "streamed-chunk.unsupported-version");
        byte[] badVoxelHash = valid.clone();
        badVoxelHash[EXACT_VOXEL_OFFSET] ^= 1;
        assertPayloadClosed(
                badVoxelHash,
                StreamedChunkCodec.DecodeResult.Status.CORRUPT,
                "streamed-chunk.voxel-checksum-mismatch");
        assertPayloadClosed(
                withInt(
                        valid,
                        EXACT_EXTENSION_LENGTH_OFFSET,
                        Math.toIntExact(StreamedChunkCodec.MAX_EXTENSION_BYTES + 1L)),
                StreamedChunkCodec.DecodeResult.Status.CORRUPT,
                "streamed-chunk.extension-size-limit");
    }

    @Test
    void everyTruncatedPayloadBoundaryIsClosedAndNeverPublishesPartialState() {
        byte[] valid = codec.encode(exactPayload());

        for (int length = 0; length < valid.length; length++) {
            byte[] truncated = Arrays.copyOf(valid, length);
            StreamedChunkCodec.DecodeResult result = codec.decode(truncated);
            assertEquals(
                    StreamedChunkCodec.DecodeResult.Status.CORRUPT,
                    result.status(),
                    "length=" + length);
            assertTrue(result.payload().isEmpty(), "length=" + length);
            assertFalse(result.diagnostics().isEmpty(), "length=" + length);
            assertBounded(result.diagnostics());
        }
    }

    @Test
    void v3PayloadPolicyRejectsFalsePersistenceWhenVoxelStateRequiresStorage() {
        byte[] voxels = markedVoxels(9, (byte) 4);

        assertThrows(
                IllegalArgumentException.class,
                () -> new StreamedChunkPayload(
                        SAVE_ID,
                        new ChunkKey(2, -3),
                        GENERATOR_VERSION,
                        BASE_HASH,
                        3L,
                        2L,
                        false,
                        true,
                        1,
                        voxels,
                        List.of()));

        byte[] encoded = codec.encode(payload(
                new ChunkKey(2, -3), 3L, 2L, voxels, List.of()));
        encoded[87] = 0;
        assertPayloadClosed(
                encoded,
                StreamedChunkCodec.DecodeResult.Status.CORRUPT,
                "streamed-chunk.invalid-payload");
    }

    @Test
    void legacyV2IndexLiteralDecodesWithBothPersistenceFlagsTrue() {
        StreamedChunkIndex.Entry first = new StreamedChunkIndex.Entry(
                new ChunkKey(-ChunkCoordinatePolicy.MAX_SAFE_CHUNK_COORDINATE, 5),
                GENERATOR_VERSION,
                BASE_HASH,
                9L,
                321L,
                PAYLOAD_HASH,
                true,
                true);
        StreamedChunkIndex.Entry second = new StreamedChunkIndex.Entry(
                new ChunkKey(7, -8),
                GENERATOR_VERSION,
                "33".repeat(32),
                10L,
                654L,
                "44".repeat(32),
                true,
                true);
        StreamedChunkIndex shuffled =
                new StreamedChunkIndex(SAVE_ID, List.of(second, first));
        StreamedChunkIndex canonical =
                new StreamedChunkIndex(SAVE_ID, List.of(first, second));

        String legacyHex = "474c4958"
                        + "00000002"
                        + "123e4567e89b12d3a456426614174000"
                        + "00"
                        + "00000002"
                        + "f8000001"
                        + "00000005"
                        + "00000003"
                        + "763135"
                        + "11".repeat(32)
                        + "0000000000000009"
                        + "0000000000000141"
                        + "22".repeat(32)
                        + "01"
                        + "00000007"
                        + "fffffff8"
                        + "00000003"
                        + "763135"
                        + "33".repeat(32)
                        + "000000000000000a"
                        + "000000000000028e"
                        + "44".repeat(32)
                        + "01";

        StreamedChunkIndex decoded = indexCodec.decode(HexFormat.of().parseHex(legacyHex));
        assertEquals(SAVE_ID, decoded.saveGameId());
        assertEquals(List.of(first, second), decoded.entries());
        assertTrue(decoded.entries().stream().allMatch(
                entry -> entry.persistenceRequired() && entry.voxelModified()));
        assertThrows(
                UnsupportedOperationException.class,
                () -> decoded.entries().add(first));
    }

    @Test
    void legacyV1EmptyIndexLiteralRemainsReadable() {
        byte[] legacy = HexFormat.of().parseHex(
                "474c4958"
                        + "00000001"
                        + "123e4567e89b12d3a456426614174000"
                        + "00000000");

        StreamedChunkIndex decoded = indexCodec.decode(legacy);

        assertEquals(SAVE_ID, decoded.saveGameId());
        assertTrue(decoded.entries().isEmpty());
        assertTrue(decoded.globalExtensions().isEmpty());
    }

    @Test
    void indexRejectsDuplicateTrailingOversizeCorruptAndEveryTruncatedBoundary() {
        StreamedChunkIndex.Entry first = indexEntry(new ChunkKey(-5, 7), 3L);
        StreamedChunkIndex.Entry second = indexEntry(new ChunkKey(9, -11), 4L);
        byte[] valid = indexCodec.encode(
                new StreamedChunkIndex(SAVE_ID, List.of(first, second)));
        byte[] duplicate = valid.clone();
        int secondEntryOffset = 29 + 97;
        putInt(duplicate, secondEntryOffset, first.key().x());
        putInt(duplicate, secondEntryOffset + Integer.BYTES, first.key().z());

        assertIndexFailure("streamed-chunk-index.duplicate-key", duplicate);
        assertIndexFailure(
                "streamed-chunk-index.trailing-bytes",
                concat(valid, new byte[] {1}));
        assertIndexFailure(
                "streamed-chunk-index.file-size-limit",
                new byte[Math.toIntExact(StreamedChunkIndexCodec.MAX_FILE_BYTES + 1L)]);
        assertIndexFailure(
                "streamed-chunk-index.invalid-magic",
                withInt(valid, 0, 0x584c4958));
        assertIndexFailure(
                "streamed-chunk-index.unsupported-version",
                withInt(valid, 4, 4));

        for (int length = 0; length < valid.length; length++) {
            assertIndexFailure(
                    "streamed-chunk-index.invalid-payload",
                    Arrays.copyOf(valid, length));
        }
    }

    @Test
    void modifiedOnlyIndexPolicyRejectsUnmodifiedConstructionAndDecoding() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new StreamedChunkIndex.Entry(
                        new ChunkKey(2, -3),
                        GENERATOR_VERSION,
                        BASE_HASH,
                        3L,
                        321L,
                        PAYLOAD_HASH,
                        false,
                        false));

        byte[] encoded = indexCodec.encode(new StreamedChunkIndex(
                SAVE_ID,
                List.of(indexEntry(new ChunkKey(2, -3), 3L))));
        // v3 appends the global-extension count after the two entry flags.
        encoded[encoded.length - Integer.BYTES - 2] = 0;
        assertIndexFailure("streamed-chunk-index.invalid-payload", encoded);
    }

    @Test
    void v2ManifestAndV3InternalIndexCodecRemainDistinct() {
        assertEquals(1, SaveFormatVersion.CURRENT.value());
        assertEquals(2, SaveFormatVersion.STREAMED_CHUNKS.value());
        assertEquals("streamed-chunks", SaveSectionId.STREAMED_CHUNKS.value());
        assertEquals(SaveSectionId.STREAMED_CHUNKS, indexCodec.sectionId());
        assertEquals(3, indexCodec.codecVersion());
        assertTrue(indexCodec.required());
        SaveSectionDescriptor descriptor = new SaveSectionDescriptor(
                SaveSectionId.STREAMED_CHUNKS,
                3,
                true,
                0L,
                "00".repeat(32));
        SaveCodecRegistry registry = SaveCodecRegistry.of(List.of(indexCodec));

        assertEquals(indexCodec, registry.resolve(descriptor).orElseThrow());
    }

    private StreamedChunkPayload exactPayload() {
        byte[] voxels = new byte[VOXEL_LENGTH];
        voxels[0] = 1;
        voxels[voxels.length - 1] = 0x7f;
        return payload(
                new ChunkKey(-2, ChunkCoordinatePolicy.MAX_SAFE_CHUNK_COORDINATE),
                9L,
                8L,
                voxels,
                List.of(extension(
                        SaveSectionId.DETAIL_BLOCKS,
                        false,
                        new byte[] {1, 2, 3})));
    }

    private static StreamedChunkPayload payload(
            ChunkKey key,
            long revision,
            long persistedRevision,
            byte[] voxels,
            List<StreamedChunkPayload.ExtensionDescriptor> extensions) {
        return new StreamedChunkPayload(
                SAVE_ID,
                key,
                GENERATOR_VERSION,
                BASE_HASH,
                revision,
                persistedRevision,
                true,
                true,
                1,
                voxels,
                extensions);
    }

    private static StreamedChunkPayload.ExtensionDescriptor extension(
            SaveSectionId id, boolean required, byte[] bytes) {
        return new StreamedChunkPayload.ExtensionDescriptor(id, 1, required, bytes);
    }

    private static StreamedChunkIndex.Entry indexEntry(ChunkKey key, long revision) {
        return new StreamedChunkIndex.Entry(
                key,
                GENERATOR_VERSION,
                BASE_HASH,
                revision,
                321L,
                PAYLOAD_HASH,
                true,
                true);
    }

    private static byte[] markedVoxels(int index, byte value) {
        byte[] voxels = new byte[VOXEL_LENGTH];
        voxels[index] = value;
        return voxels;
    }

    private static void assertExactPayload(
            StreamedChunkPayload expected, StreamedChunkPayload actual) {
        assertEquals(expected.saveGameId(), actual.saveGameId());
        assertEquals(expected.key(), actual.key());
        assertEquals(expected.generatorVersion(), actual.generatorVersion());
        assertEquals(expected.baseHash(), actual.baseHash());
        assertEquals(expected.revision(), actual.revision());
        assertEquals(expected.persistedRevision(), actual.persistedRevision());
        assertEquals(expected.persistenceRequired(), actual.persistenceRequired());
        assertEquals(expected.voxelModified(), actual.voxelModified());
        assertEquals(expected.worldHeight(), actual.worldHeight());
        assertArrayEquals(expected.copyCanonicalVoxels(), actual.copyCanonicalVoxels());
        assertEquals(expected.extensions().size(), actual.extensions().size());
        for (int index = 0; index < expected.extensions().size(); index++) {
            StreamedChunkPayload.ExtensionDescriptor expectedExtension =
                    expected.extensions().get(index);
            StreamedChunkPayload.ExtensionDescriptor actualExtension =
                    actual.extensions().get(index);
            assertEquals(expectedExtension.sectionId(), actualExtension.sectionId());
            assertEquals(expectedExtension.codecVersion(), actualExtension.codecVersion());
            assertEquals(expectedExtension.required(), actualExtension.required());
            assertArrayEquals(expectedExtension.copyBytes(), actualExtension.copyBytes());
        }
    }

    private void assertPayloadClosed(
            byte[] bytes,
            StreamedChunkCodec.DecodeResult.Status status,
            String diagnosticCode) {
        StreamedChunkCodec.DecodeResult result = codec.decode(bytes);
        assertEquals(status, result.status());
        assertTrue(result.payload().isEmpty());
        assertEquals(diagnosticCode, result.diagnostics().get(0).code());
        assertBounded(result.diagnostics());
    }

    private void assertIndexFailure(String expectedCode, byte[] bytes) {
        SaveCodecException failure =
                assertThrows(SaveCodecException.class, () -> indexCodec.decode(bytes));
        assertEquals(expectedCode, failure.code());
        assertTrue(failure.getCause() != null);
    }

    private static void assertBounded(List<SaveDiagnostic> diagnostics) {
        assertFalse(diagnostics.isEmpty());
        for (SaveDiagnostic diagnostic : diagnostics) {
            assertTrue(diagnostic.code().codePointCount(0, diagnostic.code().length()) <= 96);
            assertTrue(diagnostic.message().codePointCount(
                    0, diagnostic.message().length())
                    <= SaveDiagnostic.MAX_MESSAGE_CODE_POINTS);
        }
    }

    private static int firstDescriptorRequiredOffset(byte[] encoded) {
        ByteBuffer input = ByteBuffer.wrap(encoded).order(ByteOrder.BIG_ENDIAN);
        input.position(32);
        int generatorLength = input.getInt();
        input.position(Math.addExact(input.position(), generatorLength));
        input.position(Math.addExact(input.position(), 32 + 8 + 8 + 2));
        assertEquals(1, input.getInt());
        input.getInt();
        input.position(Math.addExact(input.position(), 32));
        int idLength = input.getInt();
        input.position(Math.addExact(input.position(), idLength + 4));
        return input.position();
    }

    private static void replaceAsciiOccurrence(
            byte[] bytes, String expected, String replacement, int occurrence) {
        byte[] from = expected.getBytes(StandardCharsets.US_ASCII);
        byte[] to = replacement.getBytes(StandardCharsets.US_ASCII);
        assertEquals(from.length, to.length);
        int found = 0;
        for (int offset = 0; offset <= bytes.length - from.length; offset++) {
            if (matches(bytes, offset, from) && ++found == occurrence) {
                System.arraycopy(to, 0, bytes, offset, to.length);
                return;
            }
        }
        throw new AssertionError("ASCII fixture not found: " + expected);
    }

    private static boolean matches(byte[] bytes, int offset, byte[] expected) {
        for (int index = 0; index < expected.length; index++) {
            if (bytes[offset + index] != expected[index]) {
                return false;
            }
        }
        return true;
    }

    private static byte[] withInt(byte[] source, int offset, int value) {
        byte[] result = source.clone();
        putInt(result, offset, value);
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
}
