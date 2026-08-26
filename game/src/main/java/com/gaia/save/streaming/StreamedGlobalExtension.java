package com.gaia.save.streaming;

import com.gaia.save.format.SaveSectionId;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;

/** Generic bounded inline extension stored in the checksummed streamed index. */
public final class StreamedGlobalExtension {
    public static final int MAX_CANONICAL_BYTES = 1024 * 1024;

    private final SaveSectionId sectionId;
    private final int codecVersion;
    private final boolean required;
    private final Optional<RequiredChunkExtensionDependency> dependency;
    private final byte[] payloadBytes;

    public StreamedGlobalExtension(
            SaveSectionId sectionId,
            int codecVersion,
            boolean required,
            Optional<RequiredChunkExtensionDependency> dependency,
            byte[] payloadBytes) {
        this.sectionId = Objects.requireNonNull(sectionId, "sectionId");
        StreamedChunkPayload.requireBoundedText(
                sectionId.value(), "sectionId",
                StreamedChunkPayload.MAX_EXTENSION_ID_BYTES);
        if (codecVersion <= 0) {
            throw new IllegalArgumentException("codecVersion must be positive");
        }
        this.codecVersion = codecVersion;
        this.required = required;
        this.dependency = Objects.requireNonNull(dependency, "dependency");
        this.dependency.ifPresent(value -> StreamedChunkPayload.requireBoundedText(
                value.chunkExtensionId().value(),
                "dependencyExtensionId",
                StreamedChunkPayload.MAX_EXTENSION_ID_BYTES));
        byte[] checked = Objects.requireNonNull(payloadBytes, "payloadBytes");
        if (canonicalEncodedSize(sectionId, dependency, checked.length)
                > MAX_CANONICAL_BYTES) {
            throw new IllegalArgumentException(
                    "global extension exceeds its canonical encoded bound");
        }
        this.payloadBytes = checked.clone();
    }

    public SaveSectionId sectionId() { return sectionId; }

    public int codecVersion() { return codecVersion; }

    public boolean required() { return required; }

    public Optional<RequiredChunkExtensionDependency> dependency() { return dependency; }

    public byte[] copyPayloadBytes() { return payloadBytes.clone(); }

    int payloadSize() { return payloadBytes.length; }

    public long canonicalEncodedSize() {
        return canonicalEncodedSize(sectionId, dependency, payloadBytes.length);
    }

    private static long canonicalEncodedSize(
            SaveSectionId sectionId,
            Optional<RequiredChunkExtensionDependency> dependency,
            int payloadLength) {
        long bytes = 4L + utf8Length(sectionId) + 4L + 1L + 1L + 4L
                + payloadLength;
        if (dependency.isPresent()) {
            bytes = Math.addExact(
                    bytes,
                    4L + utf8Length(dependency.orElseThrow().chunkExtensionId()) + 4L);
        }
        return bytes;
    }

    private static int utf8Length(SaveSectionId id) {
        return id.value().getBytes(StandardCharsets.UTF_8).length;
    }

    @Override
    public boolean equals(Object value) {
        return this == value || value instanceof StreamedGlobalExtension other
                && codecVersion == other.codecVersion
                && required == other.required
                && sectionId.equals(other.sectionId)
                && dependency.equals(other.dependency)
                && Arrays.equals(payloadBytes, other.payloadBytes);
    }

    @Override
    public int hashCode() {
        return 31 * Objects.hash(sectionId, codecVersion, required, dependency)
                + Arrays.hashCode(payloadBytes);
    }
}
