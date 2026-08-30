package com.gaia.save.streaming;

import com.gaia.save.archive.SaveDiagnostic;
import com.gaia.save.format.SaveFormatVersion;
import com.gaia.save.format.SaveGameId;
import com.gaia.save.format.SaveSectionId;
import com.overlord.config.GameConfig;
import com.overlord.voxel.ChunkCoordinatePolicy;
import com.overlord.voxel.ChunkKey;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Deterministic bounded internal-v3 codec for one persistence-required Chunk. */
public final class StreamedChunkCodec {
    public static final long MAX_EXTENSION_BYTES = 1024L * 1024L;
    public static final long MAX_FILE_BYTES = 18L * 1024L * 1024L;
    private static final byte[] MAGIC = {'G', 'L', 'C', '2'};
    private static final int CODEC_VERSION = 3;
    private static final int LEGACY_CODEC_VERSION = SaveFormatVersion.STREAMED_CHUNKS.value();
    private static final int MAX_EXTENSION_COUNT = 16;
    private final StreamedExtensionSupportRegistry extensionSupport;

    public StreamedChunkCodec() {
        this(StreamedExtensionSupportRegistry.productionDefaults());
    }

    public StreamedChunkCodec(StreamedExtensionSupportRegistry extensionSupport) {
        this.extensionSupport = Objects.requireNonNull(
                extensionSupport, "extensionSupport");
    }

    public byte[] encode(StreamedChunkPayload payload) {
        try {
            return encodeValidated(Objects.requireNonNull(payload, "payload"));
        } catch (IOException | RuntimeException failure) {
            throw new com.gaia.save.codec.SaveCodecException(
                    "streamed-chunk.invalid-snapshot",
                    "The streamed Chunk payload is invalid",
                    failure);
        }
    }

    public DecodeResult decode(byte[] bytes) {
        try {
            return decodeValidated(Objects.requireNonNull(bytes, "bytes"));
        } catch (PayloadFailure failure) {
            return failure.unsupportedRequired
                    ? DecodeResult.unsupported(failure.diagnostic)
                    : DecodeResult.corrupt(failure.diagnostic);
        } catch (IOException | RuntimeException failure) {
            return DecodeResult.corrupt(diagnostic(
                    "streamed-chunk.invalid-payload",
                    "The streamed Chunk payload is truncated or malformed",
                    failure));
        }
    }

    private byte[] encodeValidated(StreamedChunkPayload payload) throws IOException {
        List<StreamedChunkPayload.ExtensionDescriptor> extensions =
                new ArrayList<>(payload.extensions());
        extensions.sort(StreamedChunkPayload.EXTENSION_ORDER);
        requireExtensionCount(extensions.size());
        Set<SaveSectionId> observed = new HashSet<>();
        for (StreamedChunkPayload.ExtensionDescriptor extension : extensions) {
            if (!observed.add(extension.sectionId())) {
                throw new IllegalArgumentException("Duplicate extension section ID");
            }
            requireSupportedRequiredExtension(extension);
            requireExtensionSize(extension.copyBytes().length);
        }

        byte[] generator = payload.generatorVersion().getBytes(StandardCharsets.UTF_8);
        byte[] voxels = payload.copyCanonicalVoxels();
        long encodedLength = canonicalEncodedSize(payload);

        ByteArrayOutputStream bytes = new ByteArrayOutputStream(
                Math.toIntExact(encodedLength));
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.write(MAGIC);
            output.writeInt(CODEC_VERSION);
            writeUuid(output, payload.saveGameId());
            output.writeInt(payload.key().x());
            output.writeInt(payload.key().z());
            writeBytesWithLength(output, generator);
            output.write(HexFormat.of().parseHex(payload.baseHash()));
            output.writeLong(payload.revision());
            output.writeLong(payload.persistedRevision());
            output.writeBoolean(payload.persistenceRequired());
            output.writeBoolean(payload.voxelModified());
            output.writeInt(extensions.size());
            output.writeInt(voxels.length);
            output.write(sha256(voxels));
            for (StreamedChunkPayload.ExtensionDescriptor extension : extensions) {
                writeBytesWithLength(
                        output,
                        extension.sectionId().value().getBytes(StandardCharsets.UTF_8));
                output.writeInt(extension.codecVersion());
                output.writeBoolean(extension.required());
                byte[] extensionPayload = extension.copyBytes();
                output.writeInt(extensionPayload.length);
                output.write(sha256(extensionPayload));
            }
            output.write(voxels);
            for (StreamedChunkPayload.ExtensionDescriptor extension : extensions) {
                output.write(extension.copyBytes());
            }
        }
        byte[] result = bytes.toByteArray();
        if (result.length != encodedLength) {
            throw new IllegalStateException("Unexpected streamed Chunk encoded length");
        }
        return result;
    }

    private DecodeResult decodeValidated(byte[] bytes) throws IOException {
        if (bytes.length > MAX_FILE_BYTES) {
            throw corrupt(
                    "streamed-chunk.file-size-limit",
                    "The streamed Chunk file exceeds its bounded size");
        }
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytes))) {
            byte[] magic = readExact(input, MAGIC.length);
            if (!Arrays.equals(MAGIC, magic)) {
                throw corrupt(
                        "streamed-chunk.invalid-magic",
                        "The streamed Chunk file has invalid magic");
            }
            int version = input.readInt();
            if (version != LEGACY_CODEC_VERSION && version != CODEC_VERSION) {
                throw corrupt(
                        "streamed-chunk.unsupported-version",
                        "The streamed Chunk codec version is unsupported");
            }
            SaveGameId saveGameId = readUuid(input);
            ChunkKey key = ChunkCoordinatePolicy.requireSafe(
                    new ChunkKey(input.readInt(), input.readInt()));
            String generatorVersion = readBoundedText(
                    input,
                    StreamedChunkPayload.MAX_GENERATOR_VERSION_BYTES,
                    "generatorVersion");
            String baseHash = HexFormat.of().formatHex(readExact(input, 32));
            long revision = input.readLong();
            long persistedRevision = input.readLong();
            boolean persistenceRequired = readCanonicalBoolean(input);
            boolean voxelModified = version == LEGACY_CODEC_VERSION
                    ? persistenceRequired
                    : readCanonicalBoolean(input);
            int extensionCount = requireExtensionCount(input.readInt());
            int voxelLength = input.readInt();
            int worldHeight = requireCanonicalVoxelLength(voxelLength);
            byte[] voxelHash = readExact(input, 32);

            List<EncodedExtension> descriptors = new ArrayList<>(extensionCount);
            Set<SaveSectionId> observed = new HashSet<>();
            SaveSectionId previous = null;
            long expectedPayloadBytes = voxelLength;
            for (int index = 0; index < extensionCount; index++) {
                SaveSectionId id = new SaveSectionId(readBoundedText(
                        input,
                        StreamedChunkPayload.MAX_EXTENSION_ID_BYTES,
                        "extension section ID"));
                int codecVersion = input.readInt();
                if (codecVersion <= 0) {
                    throw corrupt(
                            "streamed-chunk.invalid-extension",
                            "A streamed Chunk extension codec version is invalid");
                }
                boolean required = readCanonicalBoolean(input);
                int payloadLength = input.readInt();
                requireExtensionSize(payloadLength);
                byte[] checksum = readExact(input, 32);
                if (!observed.add(id)) {
                    throw corrupt(
                            "streamed-chunk.duplicate-extension",
                            "The streamed Chunk repeats an extension descriptor");
                }
                if (previous != null && previous.value().compareTo(id.value()) >= 0) {
                    throw corrupt(
                            "streamed-chunk.noncanonical-extension-order",
                            "Streamed Chunk extensions are not in canonical order");
                }
                if (required && !extensionSupport.supports(id, codecVersion, true)) {
                    throw unsupported(
                            "streamed-chunk.unknown-required-extension",
                            "The streamed Chunk requires an unsupported extension");
                }
                expectedPayloadBytes = Math.addExact(
                        expectedPayloadBytes, payloadLength);
                if (expectedPayloadBytes > MAX_FILE_BYTES) {
                    throw corrupt(
                            "streamed-chunk.file-size-limit",
                            "The streamed Chunk file exceeds its bounded size");
                }
                descriptors.add(new EncodedExtension(
                        id, codecVersion, required, payloadLength, checksum));
                previous = id;
            }

            byte[] voxels = readExact(input, voxelLength);
            if (!MessageDigest.isEqual(voxelHash, sha256(voxels))) {
                throw corrupt(
                        "streamed-chunk.voxel-checksum-mismatch",
                        "The streamed Chunk voxel checksum does not match");
            }
            List<StreamedChunkPayload.ExtensionDescriptor> retained = new ArrayList<>();
            List<SaveDiagnostic> diagnostics = new ArrayList<>();
            for (EncodedExtension descriptor : descriptors) {
                byte[] extensionBytes = readExact(input, descriptor.payloadLength);
                if (!MessageDigest.isEqual(
                        descriptor.checksum, sha256(extensionBytes))) {
                    throw corrupt(
                            "streamed-chunk.extension-checksum-mismatch",
                            "A streamed Chunk extension checksum does not match");
                }
                if (descriptor.sectionId.equals(SaveSectionId.DETAIL_BLOCKS)
                        || extensionSupport.supports(
                                descriptor.sectionId,
                                descriptor.codecVersion,
                                descriptor.required)) {
                    retained.add(new StreamedChunkPayload.ExtensionDescriptor(
                            descriptor.sectionId,
                            descriptor.codecVersion,
                            descriptor.required,
                            extensionBytes));
                } else {
                    diagnostics.add(diagnostic(
                            "streamed-chunk.unknown-optional-extension",
                            "An optional streamed Chunk extension was safely skipped"));
                }
            }
            if (input.read() != -1) {
                throw corrupt(
                        "streamed-chunk.trailing-bytes",
                        "The streamed Chunk file contains trailing bytes");
            }
            StreamedChunkPayload payload = new StreamedChunkPayload(
                    saveGameId,
                    key,
                    generatorVersion,
                    baseHash,
                    revision,
                    persistedRevision,
                    persistenceRequired,
                    voxelModified,
                    worldHeight,
                    voxels,
                    retained);
            return DecodeResult.valid(payload, diagnostics);
        } catch (EOFException truncated) {
            throw corrupt(
                    "streamed-chunk.invalid-payload",
                    "The streamed Chunk payload is truncated or malformed",
                    truncated);
        } catch (ArithmeticException | IllegalArgumentException malformed) {
            throw corrupt(
                    "streamed-chunk.invalid-payload",
                    "The streamed Chunk payload is truncated or malformed",
                    malformed);
        }
    }

    private void requireSupportedRequiredExtension(
            StreamedChunkPayload.ExtensionDescriptor extension) {
        if (extension.required()
                && !extensionSupport.supports(
                        extension.sectionId(), extension.codecVersion(), true)) {
            throw new IllegalArgumentException("Unknown required extension");
        }
    }

    private static int requireExtensionCount(int count) {
        if (count < 0 || count > MAX_EXTENSION_COUNT) {
            throw new IllegalArgumentException("Extension count exceeds its bound");
        }
        return count;
    }

    private static int requireExtensionSize(long size) {
        if (size < 0 || size > MAX_EXTENSION_BYTES) {
            throw corrupt(
                    "streamed-chunk.extension-size-limit",
                    "A streamed Chunk extension exceeds its bounded size");
        }
        return Math.toIntExact(size);
    }

    private static int requireCanonicalVoxelLength(int length) {
        int layerSize = Math.multiplyExact(GameConfig.Chunk.SIZE, GameConfig.Chunk.SIZE);
        if (length <= 0 || length % layerSize != 0) {
            throw new IllegalArgumentException("Voxel length is not canonical");
        }
        int worldHeight = length / layerSize;
        if (worldHeight <= 0 || worldHeight > GameConfig.Chunk.MAX_HEIGHT) {
            throw new IllegalArgumentException("Voxel height is unsupported");
        }
        return worldHeight;
    }

    private static boolean readCanonicalBoolean(DataInputStream input)
            throws IOException {
        int value = input.readUnsignedByte();
        if (value != 0 && value != 1) {
            throw new IllegalArgumentException("Boolean field is not canonical");
        }
        return value == 1;
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
        return StreamedChunkPayload.requireBoundedText(value, field, maximumBytes);
    }

    private static byte[] readExact(DataInputStream input, int length)
            throws IOException {
        byte[] bytes = new byte[length];
        input.readFully(bytes);
        return bytes;
    }

    private static void writeBytesWithLength(DataOutputStream output, byte[] bytes)
            throws IOException {
        output.writeInt(bytes.length);
        output.write(bytes);
    }

    private static void writeUuid(DataOutputStream output, SaveGameId saveGameId)
            throws IOException {
        UUID uuid = UUID.fromString(saveGameId.value());
        output.writeLong(uuid.getMostSignificantBits());
        output.writeLong(uuid.getLeastSignificantBits());
    }

    private static SaveGameId readUuid(DataInputStream input) throws IOException {
        UUID uuid = new UUID(input.readLong(), input.readLong());
        return SaveGameId.parse(uuid.toString());
    }

    static byte[] sha256(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    static String sha256Hex(byte[] bytes) {
        return HexFormat.of().formatHex(sha256(bytes));
    }

    static long canonicalEncodedSize(StreamedChunkPayload payload) {
        StreamedChunkPayload checked = Objects.requireNonNull(payload, "payload");
        List<StreamedChunkPayload.ExtensionDescriptor> extensions =
                new ArrayList<>(checked.extensions());
        requireExtensionCount(extensions.size());
        Set<SaveSectionId> observed = new HashSet<>();
        long encodedLength = 126L
                + checked.generatorVersion().getBytes(StandardCharsets.UTF_8).length
                + checked.copyCanonicalVoxels().length;
        for (StreamedChunkPayload.ExtensionDescriptor extension : extensions) {
            if (!observed.add(extension.sectionId())) {
                throw new IllegalArgumentException("Duplicate extension section ID");
            }
            int payloadBytes = extension.copyBytes().length;
            if (payloadBytes > MAX_EXTENSION_BYTES) {
                throw new IllegalArgumentException(
                        "A streamed Chunk extension exceeds its bounded size");
            }
            encodedLength = Math.addExact(
                    encodedLength,
                    4L + extension.sectionId().value()
                                    .getBytes(StandardCharsets.UTF_8).length
                            + 4L + 1L + 4L + 32L + payloadBytes);
        }
        if (encodedLength > MAX_FILE_BYTES) {
            throw new IllegalArgumentException("Streamed Chunk file exceeds its bound");
        }
        return encodedLength;
    }

    private static SaveDiagnostic diagnostic(String code, String message) {
        return SaveDiagnostic.of(code, message);
    }

    private static SaveDiagnostic diagnostic(
            String code, String message, Throwable cause) {
        return SaveDiagnostic.of(code, message, cause);
    }

    private static PayloadFailure corrupt(String code, String message) {
        return new PayloadFailure(false, diagnostic(code, message));
    }

    private static PayloadFailure corrupt(
            String code, String message, Throwable cause) {
        return new PayloadFailure(false, diagnostic(code, message, cause));
    }

    private static PayloadFailure unsupported(String code, String message) {
        return new PayloadFailure(true, diagnostic(code, message));
    }

    private record EncodedExtension(
            SaveSectionId sectionId,
            int codecVersion,
            boolean required,
            int payloadLength,
            byte[] checksum) {
        private EncodedExtension {
            checksum = checksum.clone();
        }
    }

    private static final class PayloadFailure extends RuntimeException {
        private final boolean unsupportedRequired;
        private final SaveDiagnostic diagnostic;

        private PayloadFailure(
                boolean unsupportedRequired, SaveDiagnostic diagnostic) {
            super(diagnostic.message(), diagnostic.cause().orElse(null), false, false);
            this.unsupportedRequired = unsupportedRequired;
            this.diagnostic = diagnostic;
        }
    }

    /** Closed validation result that never publishes a partial payload. */
    public static final class DecodeResult {
        public enum Status {
            VALID,
            CORRUPT,
            UNSUPPORTED_REQUIRED_EXTENSION
        }

        private final Status status;
        private final StreamedChunkPayload payload;
        private final List<SaveDiagnostic> diagnostics;

        private DecodeResult(
                Status status,
                StreamedChunkPayload payload,
                List<SaveDiagnostic> diagnostics) {
            this.status = Objects.requireNonNull(status, "status");
            this.payload = payload;
            this.diagnostics = List.copyOf(
                    Objects.requireNonNull(diagnostics, "diagnostics"));
            if ((status == Status.VALID) != (payload != null)) {
                throw new IllegalArgumentException(
                        "Only VALID may publish a streamed Chunk payload");
            }
            if (status != Status.VALID && this.diagnostics.isEmpty()) {
                throw new IllegalArgumentException(
                        "A closed streamed Chunk failure requires a diagnostic");
            }
        }

        private static DecodeResult valid(
                StreamedChunkPayload payload, List<SaveDiagnostic> diagnostics) {
            return new DecodeResult(Status.VALID, payload, diagnostics);
        }

        private static DecodeResult corrupt(SaveDiagnostic diagnostic) {
            return new DecodeResult(Status.CORRUPT, null, List.of(diagnostic));
        }

        private static DecodeResult unsupported(SaveDiagnostic diagnostic) {
            return new DecodeResult(
                    Status.UNSUPPORTED_REQUIRED_EXTENSION,
                    null,
                    List.of(diagnostic));
        }

        public Status status() {
            return status;
        }

        public Optional<StreamedChunkPayload> payload() {
            return Optional.ofNullable(payload);
        }

        public List<SaveDiagnostic> diagnostics() {
            return diagnostics;
        }
    }
}
