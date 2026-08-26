package com.gaia.save.streaming;

import com.gaia.save.codec.SaveCodecException;
import com.gaia.save.format.SaveGameId;
import com.gaia.save.format.SaveSectionCodec;
import com.gaia.save.format.SaveSectionId;
import com.overlord.voxel.ChunkCoordinatePolicy;
import com.overlord.voxel.ChunkKey;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Deterministic bounded internal-v3 codec for the streamed-Chunk index. */
public final class StreamedChunkIndexCodec
        implements SaveSectionCodec<StreamedChunkIndex> {
    public static final long MAX_FILE_BYTES = 8L * 1024L * 1024L;
    private static final byte[] MAGIC = {'G', 'L', 'I', 'X'};
    private static final int CODEC_VERSION = 3;
    private static final int LEGACY_CODEC_VERSION_2 = 2;
    private static final int LEGACY_CODEC_VERSION_1 = 1;
    private static final int MAX_ENTRY_COUNT = 65_536;
    private static final int MAX_GLOBAL_EXTENSION_COUNT = 1_024;
    private static final int FIXED_ENTRY_BYTES = 94;

    @Override
    public SaveSectionId sectionId() {
        return SaveSectionId.STREAMED_CHUNKS;
    }

    @Override
    public int codecVersion() {
        return CODEC_VERSION;
    }

    @Override
    public boolean required() {
        return true;
    }

    @Override
    public byte[] encode(StreamedChunkIndex index) {
        try {
            return encodeValidated(Objects.requireNonNull(index, "index"));
        } catch (IOException | RuntimeException failure) {
            throw failure(
                    "streamed-chunk-index.invalid-snapshot",
                    "The streamed Chunk index is invalid",
                    failure);
        }
    }

    @Override
    public StreamedChunkIndex decode(byte[] bytes) {
        try {
            return decodeValidated(Objects.requireNonNull(bytes, "bytes"));
        } catch (SaveCodecException failure) {
            throw failure;
        } catch (IOException | RuntimeException failure) {
            throw failure(
                    "streamed-chunk-index.invalid-payload",
                    "The streamed Chunk index is truncated or malformed",
                    failure);
        }
    }

    private byte[] encodeValidated(StreamedChunkIndex index) throws IOException {
        requireEntryCount(index.entries().size());
        requireGlobalExtensionCount(index.globalExtensions().size());
        long length = 33L + (index.migrationCompatibility().isPresent() ? 64L : 0L);
        for (StreamedChunkIndex.Entry entry : index.entries()) {
            length = Math.addExact(
                    length,
                    FIXED_ENTRY_BYTES
                            + entry.generatorVersion()
                                    .getBytes(StandardCharsets.UTF_8).length);
        }
        for (StreamedGlobalExtension extension : index.globalExtensions()) {
            length = Math.addExact(length, extension.canonicalEncodedSize());
        }
        if (length > MAX_FILE_BYTES) {
            throw new IllegalArgumentException("Streamed Chunk index exceeds its bound");
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(Math.toIntExact(length));
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.write(MAGIC);
            output.writeInt(CODEC_VERSION);
            writeUuid(output, index.saveGameId());
            output.writeBoolean(index.migrationCompatibility().isPresent());
            if (index.migrationCompatibility().isPresent()) {
                StreamedChunkIndex.MigrationCompatibility compatibility =
                        index.migrationCompatibility().orElseThrow();
                output.write(HexFormat.of().parseHex(
                        compatibility.sourceArchiveSha256()));
                output.write(HexFormat.of().parseHex(
                        compatibility.migrationMarkerSha256()));
            }
            output.writeInt(index.entries().size());
            for (StreamedChunkIndex.Entry entry : index.entries()) {
                output.writeInt(entry.key().x());
                output.writeInt(entry.key().z());
                byte[] generator = entry.generatorVersion()
                        .getBytes(StandardCharsets.UTF_8);
                output.writeInt(generator.length);
                output.write(generator);
                output.write(HexFormat.of().parseHex(entry.baseHash()));
                output.writeLong(entry.revision());
                output.writeLong(entry.payloadSize());
                output.write(HexFormat.of().parseHex(entry.payloadHash()));
                output.writeBoolean(entry.persistenceRequired());
                output.writeBoolean(entry.voxelModified());
            }
            output.writeInt(index.globalExtensions().size());
            for (StreamedGlobalExtension extension : index.globalExtensions()) {
                writeBoundedText(output, extension.sectionId().value());
                output.writeInt(extension.codecVersion());
                output.writeBoolean(extension.required());
                output.writeBoolean(extension.dependency().isPresent());
                if (extension.dependency().isPresent()) {
                    RequiredChunkExtensionDependency dependency =
                            extension.dependency().orElseThrow();
                    writeBoundedText(output, dependency.chunkExtensionId().value());
                    output.writeInt(dependency.referenceCount());
                }
                byte[] payload = extension.copyPayloadBytes();
                output.writeInt(payload.length);
                output.write(payload);
            }
        }
        byte[] result = bytes.toByteArray();
        if (result.length != length) {
            throw new IllegalStateException("Unexpected streamed Chunk index length");
        }
        return result;
    }

    private StreamedChunkIndex decodeValidated(byte[] bytes) throws IOException {
        if (bytes.length > MAX_FILE_BYTES) {
            throw indexFailure(
                    "streamed-chunk-index.file-size-limit",
                    "The streamed Chunk index exceeds its bounded size");
        }
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytes))) {
            byte[] magic = new byte[MAGIC.length];
            input.readFully(magic);
            if (!Arrays.equals(MAGIC, magic)) {
                throw indexFailure(
                        "streamed-chunk-index.invalid-magic",
                        "The streamed Chunk index has invalid magic");
            }
            int version = input.readInt();
            if (version != LEGACY_CODEC_VERSION_1
                    && version != LEGACY_CODEC_VERSION_2
                    && version != CODEC_VERSION) {
                throw indexFailure(
                        "streamed-chunk-index.unsupported-version",
                        "The streamed Chunk index codec version is unsupported");
            }
            SaveGameId saveGameId = readUuid(input);
            StreamedChunkIndex.MigrationCompatibility compatibility = null;
            if (version >= LEGACY_CODEC_VERSION_2 && readBoolean(input)) {
                compatibility = new StreamedChunkIndex.MigrationCompatibility(
                        HexFormat.of().formatHex(readExact(input, 32)),
                        HexFormat.of().formatHex(readExact(input, 32)));
            }
            int entryCount = requireEntryCount(input.readInt());
            List<StreamedChunkIndex.Entry> entries = new ArrayList<>(entryCount);
            Set<ChunkKey> keys = new HashSet<>();
            ChunkKey previous = null;
            for (int index = 0; index < entryCount; index++) {
                ChunkKey key = ChunkCoordinatePolicy.requireSafe(
                        new ChunkKey(input.readInt(), input.readInt()));
                int generatorLength = input.readInt();
                if (generatorLength <= 0
                        || generatorLength
                                > StreamedChunkPayload.MAX_GENERATOR_VERSION_BYTES) {
                    throw new IllegalArgumentException(
                            "generatorVersion length exceeds its bound");
                }
                byte[] generatorBytes = new byte[generatorLength];
                input.readFully(generatorBytes);
                String generatorVersion = new String(
                        generatorBytes, StandardCharsets.UTF_8);
                if (!Arrays.equals(
                        generatorBytes,
                        generatorVersion.getBytes(StandardCharsets.UTF_8))) {
                    throw new IllegalArgumentException(
                            "generatorVersion is not canonical UTF-8");
                }
                String baseHash = HexFormat.of().formatHex(readExact(input, 32));
                long revision = input.readLong();
                long payloadSize = input.readLong();
                String payloadHash = HexFormat.of().formatHex(readExact(input, 32));
                boolean persistenceRequired = readBoolean(input);
                boolean voxelModified = version == CODEC_VERSION
                        ? readBoolean(input)
                        : persistenceRequired;
                if (!keys.add(key)) {
                    throw indexFailure(
                            "streamed-chunk-index.duplicate-key",
                            "The streamed Chunk index repeats a Chunk key");
                }
                if (previous != null
                        && ChunkCoordinatePolicy.canonicalComparator()
                                .compare(previous, key) >= 0) {
                    throw indexFailure(
                            "streamed-chunk-index.noncanonical-order",
                            "The streamed Chunk index is not in canonical order");
                }
                entries.add(new StreamedChunkIndex.Entry(
                        key,
                        generatorVersion,
                        baseHash,
                        revision,
                        payloadSize,
                        payloadHash,
                        persistenceRequired,
                        voxelModified));
                previous = key;
            }
            List<StreamedGlobalExtension> globalExtensions = new ArrayList<>();
            if (version == CODEC_VERSION) {
                int globalCount = requireGlobalExtensionCount(input.readInt());
                SaveSectionId previousGlobal = null;
                long aggregateBytes = 0L;
                for (int index = 0; index < globalCount; index++) {
                    SaveSectionId sectionId = new SaveSectionId(readBoundedText(
                            input,
                            StreamedChunkPayload.MAX_EXTENSION_ID_BYTES,
                            "globalExtensionId"));
                    if (previousGlobal != null
                            && previousGlobal.value().compareTo(sectionId.value()) >= 0) {
                        throw indexFailure(
                                "streamed-chunk-index.noncanonical-global-order",
                                "Global extensions are not in canonical order");
                    }
                    previousGlobal = sectionId;
                    int codecVersion = input.readInt();
                    boolean required = readBoolean(input);
                    boolean hasDependency = readBoolean(input);
                    java.util.Optional<RequiredChunkExtensionDependency> dependency =
                            java.util.Optional.empty();
                    if (hasDependency) {
                        SaveSectionId chunkExtensionId = new SaveSectionId(readBoundedText(
                                input,
                                StreamedChunkPayload.MAX_EXTENSION_ID_BYTES,
                                "dependencyExtensionId"));
                        dependency = java.util.Optional.of(
                                new RequiredChunkExtensionDependency(
                                        chunkExtensionId, input.readInt()));
                    }
                    int payloadLength = input.readInt();
                    if (payloadLength < 0
                            || payloadLength > StreamedGlobalExtension.MAX_CANONICAL_BYTES) {
                        throw new IllegalArgumentException(
                                "Global extension payload exceeds its bound");
                    }
                    long canonicalBytes = canonicalGlobalExtensionBytes(
                            sectionId, dependency, payloadLength);
                    if (canonicalBytes > StreamedGlobalExtension.MAX_CANONICAL_BYTES) {
                        throw new IllegalArgumentException(
                                "Global extension exceeds its canonical bound");
                    }
                    aggregateBytes = Math.addExact(aggregateBytes, canonicalBytes);
                    if (aggregateBytes > StreamedGlobalExtension.MAX_CANONICAL_BYTES) {
                        throw new IllegalArgumentException(
                                "Global extension bytes exceed their bound");
                    }
                    globalExtensions.add(new StreamedGlobalExtension(
                            sectionId,
                            codecVersion,
                            required,
                            dependency,
                            readExact(input, payloadLength)));
                }
            }
            if (input.read() != -1) {
                throw indexFailure(
                        "streamed-chunk-index.trailing-bytes",
                        "The streamed Chunk index contains trailing bytes");
            }
            return new StreamedChunkIndex(
                    saveGameId, compatibility, entries, globalExtensions);
        } catch (EOFException truncated) {
            throw indexFailure(
                    "streamed-chunk-index.invalid-payload",
                    "The streamed Chunk index is truncated or malformed",
                    truncated);
        } catch (ArithmeticException | IllegalArgumentException malformed) {
            throw indexFailure(
                    "streamed-chunk-index.invalid-payload",
                    "The streamed Chunk index is truncated or malformed",
                    malformed);
        }
    }

    private static int requireEntryCount(int count) {
        if (count < 0 || count > MAX_ENTRY_COUNT) {
            throw new IllegalArgumentException("Index entry count exceeds its bound");
        }
        return count;
    }

    private static int requireGlobalExtensionCount(int count) {
        if (count < 0 || count > MAX_GLOBAL_EXTENSION_COUNT) {
            throw new IllegalArgumentException(
                    "Global extension count exceeds its bound");
        }
        return count;
    }

    private static long canonicalGlobalExtensionBytes(
            SaveSectionId sectionId,
            java.util.Optional<RequiredChunkExtensionDependency> dependency,
            int payloadLength) {
        long bytes = 4L + sectionId.value().getBytes(StandardCharsets.UTF_8).length
                + 4L + 1L + 1L + 4L + payloadLength;
        if (dependency.isPresent()) {
            bytes = Math.addExact(
                    bytes,
                    4L + dependency.orElseThrow().chunkExtensionId().value()
                            .getBytes(StandardCharsets.UTF_8).length + 4L);
        }
        return bytes;
    }

    private static void writeBoundedText(DataOutputStream output, String value)
            throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= 0
                || bytes.length > StreamedChunkPayload.MAX_EXTENSION_ID_BYTES) {
            throw new IllegalArgumentException("Extension ID length exceeds its bound");
        }
        output.writeInt(bytes.length);
        output.write(bytes);
    }

    private static String readBoundedText(
            DataInputStream input, int maximumBytes, String field) throws IOException {
        int length = input.readInt();
        if (length <= 0 || length > maximumBytes) {
            throw new IllegalArgumentException(field + " length exceeds its bound");
        }
        byte[] bytes = readExact(input, length);
        String value = new String(bytes, StandardCharsets.UTF_8);
        if (!Arrays.equals(bytes, value.getBytes(StandardCharsets.UTF_8))) {
            throw new IllegalArgumentException(field + " is not canonical UTF-8");
        }
        return value;
    }

    private static boolean readBoolean(DataInputStream input) throws IOException {
        int value = input.readUnsignedByte();
        if (value != 0 && value != 1) {
            throw new IllegalArgumentException("Boolean field is not canonical");
        }
        return value == 1;
    }

    private static byte[] readExact(DataInputStream input, int length)
            throws IOException {
        byte[] bytes = new byte[length];
        input.readFully(bytes);
        return bytes;
    }

    private static void writeUuid(DataOutputStream output, SaveGameId id)
            throws IOException {
        UUID uuid = UUID.fromString(id.value());
        output.writeLong(uuid.getMostSignificantBits());
        output.writeLong(uuid.getLeastSignificantBits());
    }

    private static SaveGameId readUuid(DataInputStream input) throws IOException {
        return SaveGameId.parse(
                new UUID(input.readLong(), input.readLong()).toString());
    }

    private static SaveCodecException indexFailure(String code, String message) {
        return indexFailure(
                code,
                message,
                new IndexValidationFailure(message));
    }

    private static SaveCodecException indexFailure(
            String code, String message, Throwable cause) {
        return new SaveCodecException(code, message, cause);
    }

    private static SaveCodecException failure(
            String code, String message, Throwable cause) {
        return new SaveCodecException(code, message, cause);
    }

    private static final class IndexValidationFailure extends RuntimeException {
        private IndexValidationFailure(String message) {
            super(message, null, false, false);
        }
    }
}
