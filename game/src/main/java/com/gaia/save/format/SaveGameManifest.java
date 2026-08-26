package com.gaia.save.format;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Immutable, validated v1 metadata for a sectioned save archive. */
public record SaveGameManifest(
        SaveFormatVersion formatVersion,
        String gameVersion,
        SaveGameId saveGameId,
        String displayName,
        Instant createdAt,
        Instant modifiedAt,
        long worldSeed,
        String generatorVersion,
        String generatorConfigFingerprint,
        int chunkRadius,
        int worldHeight,
        long fixedTick,
        String summary,
        List<SaveSectionDescriptor> sections) {
    /** Exact Phase 14 v1 bound for an optional human-readable manifest summary. */
    public static final int MAX_SUMMARY_CODE_POINTS =
            SaveMetadataValidation.MAX_SUMMARY_CODE_POINTS;

    public SaveGameManifest {
        if (!SaveFormatVersion.CURRENT.equals(formatVersion)
                && !SaveFormatVersion.STREAMED_CHUNKS.equals(formatVersion)) {
            throw new IllegalArgumentException("Unsupported save manifest format");
        }
        gameVersion = SaveMetadataValidation.requireNonblank(gameVersion, "gameVersion");
        require(saveGameId, "saveGameId");
        displayName = SaveMetadataValidation.requireDisplayName(displayName);
        require(createdAt, "createdAt");
        require(modifiedAt, "modifiedAt");
        generatorVersion = SaveMetadataValidation.requireNonblank(
                generatorVersion, "generatorVersion");
        generatorConfigFingerprint =
                SaveMetadataValidation.requireGeneratorConfigFingerprint(
                        generatorConfigFingerprint);
        if (modifiedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("Modified time must not precede created time");
        }
        chunkRadius = SaveMetadataValidation.requireSupportedChunkRadius(chunkRadius);
        worldHeight = SaveMetadataValidation.requirePositiveWorldHeight(worldHeight);
        fixedTick = SaveMetadataValidation.requireNonnegativeFixedTick(fixedTick);
        summary = SaveMetadataValidation.requireSummaryWithinV1Bound(summary);
        sections = List.copyOf(require(sections, "sections"));
        validateSections(formatVersion, sections);
    }

    private static void validateSections(
            SaveFormatVersion formatVersion,
            List<SaveSectionDescriptor> sections) {
        Set<SaveSectionId> ids = new HashSet<>();
        for (SaveSectionDescriptor descriptor : sections) {
            require(descriptor, "section descriptor");
            if (!ids.add(descriptor.sectionId())) {
                throw new IllegalArgumentException("Manifest contains duplicate section ID: " + descriptor.sectionId().value());
            }
        }
        Set<SaveSectionId> required = SaveFormatVersion.CURRENT.equals(formatVersion)
                ? Set.of(
                        SaveSectionId.CHUNKS,
                        SaveSectionId.PLAYER,
                        SaveSectionId.INVENTORY,
                        SaveSectionId.WORLD_ITEMS)
                : Set.of(
                        SaveSectionId.STREAMED_CHUNKS,
                        SaveSectionId.PLAYER,
                        SaveSectionId.INVENTORY,
                        SaveSectionId.WORLD_ITEMS);
        if (!ids.containsAll(required)) {
            throw new IllegalArgumentException(
                    "Manifest is missing one or more required save sections");
        }
        for (SaveSectionDescriptor descriptor : sections) {
            if (required.contains(descriptor.sectionId()) && !descriptor.required()) {
                throw new IllegalArgumentException("A required v1 section cannot be optional");
            }
            if (SaveSectionId.isReservedOptionalV1(descriptor.sectionId()) && descriptor.required()) {
                throw new IllegalArgumentException("A reserved optional v1 section cannot be required");
            }
        }
    }

    private static <T> T require(T value, String field) {
        if (value == null) {
            throw new IllegalArgumentException(field + " must not be null");
        }
        return value;
    }
}
