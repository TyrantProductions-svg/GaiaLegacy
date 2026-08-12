package com.gaia.save.format;

import java.util.Objects;

/** Shared validation for metadata fields owned by the v1 manifest and save snapshot. */
public final class SaveMetadataValidation {
    public static final int MIN_CHUNK_RADIUS = 2;
    public static final int MAX_CHUNK_RADIUS = 8;
    public static final int MAX_SUMMARY_CODE_POINTS = 280;

    private SaveMetadataValidation() {}

    public static SaveFormatVersion requireCurrentFormat(SaveFormatVersion formatVersion) {
        Objects.requireNonNull(formatVersion, "formatVersion");
        if (!SaveFormatVersion.CURRENT.equals(formatVersion)) {
            throw new IllegalArgumentException("Only save format v1 is valid");
        }
        return formatVersion;
    }

    public static String requireNonblank(String value, String field) {
        Objects.requireNonNull(value, field);
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }

    public static String requireDisplayName(String displayName) {
        SaveNameValidator.ValidationResult validatedName =
                SaveNameValidator.validate(displayName);
        if (!validatedName.valid()) {
            throw new IllegalArgumentException(
                    "Invalid save display name: " + validatedName.diagnostic());
        }
        return validatedName.displayName();
    }

    public static String requireGeneratorConfigFingerprint(String fingerprint) {
        requireNonblank(fingerprint, "generatorConfigFingerprint");
        if (!fingerprint.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(
                    "Generator configuration fingerprint must be lowercase SHA-256");
        }
        return fingerprint;
    }

    public static int requireSupportedChunkRadius(int chunkRadius) {
        if (chunkRadius < MIN_CHUNK_RADIUS || chunkRadius > MAX_CHUNK_RADIUS) {
            throw new IllegalArgumentException(
                    "Chunk radius must be within the supported finite-world range");
        }
        return chunkRadius;
    }

    public static int requirePositiveWorldHeight(int worldHeight) {
        if (worldHeight <= 0) {
            throw new IllegalArgumentException("World height must be positive");
        }
        return worldHeight;
    }

    public static long requireNonnegativeFixedTick(long fixedTick) {
        if (fixedTick < 0) {
            throw new IllegalArgumentException("Fixed tick must be nonnegative");
        }
        return fixedTick;
    }

    public static String requireSummaryWithinV1Bound(String summary) {
        if (summary != null
                && summary.codePointCount(0, summary.length())
                        > MAX_SUMMARY_CODE_POINTS) {
            throw new IllegalArgumentException("Summary exceeds the v1 bound");
        }
        return summary;
    }
}
