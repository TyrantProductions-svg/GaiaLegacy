package com.gaia.save.format;

import java.util.Objects;

/** Verified size and digest metadata for one archive section. */
public record SaveSectionDescriptor(
        SaveSectionId sectionId,
        int codecVersion,
        boolean required,
        long uncompressedSize,
        String sha256) {
    private static final String SHA_256_HEX = "[0-9a-f]{64}";

    public SaveSectionDescriptor {
        Objects.requireNonNull(sectionId, "sectionId");
        Objects.requireNonNull(sha256, "sha256");
        if (SaveSectionId.isRequiredV1(sectionId) && !required) {
            throw new IllegalArgumentException("A required v1 section cannot be optional");
        }
        if (SaveSectionId.isReservedOptionalV1(sectionId) && required) {
            throw new IllegalArgumentException("A reserved optional v1 section cannot be required");
        }
        if (codecVersion <= 0) {
            throw new IllegalArgumentException("Codec version must be positive");
        }
        if (uncompressedSize < 0) {
            throw new IllegalArgumentException("Uncompressed section size must be nonnegative");
        }
        if (!sha256.matches(SHA_256_HEX)) {
            throw new IllegalArgumentException("Section SHA-256 must be 64 lowercase hexadecimal characters");
        }
    }
}
