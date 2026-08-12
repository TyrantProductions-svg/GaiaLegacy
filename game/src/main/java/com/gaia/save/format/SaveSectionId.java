package com.gaia.save.format;

import java.util.Objects;
import java.util.Set;

/** Stable wire identifier for an independently versioned save section. */
public record SaveSectionId(String value) {
    public static final SaveSectionId CHUNKS = new SaveSectionId("chunks");
    public static final SaveSectionId PLAYER = new SaveSectionId("player");
    public static final SaveSectionId INVENTORY = new SaveSectionId("inventory");
    public static final SaveSectionId WORLD_ITEMS = new SaveSectionId("world-items");
    public static final SaveSectionId DISCOVERY_LORE = new SaveSectionId("discovery-lore");
    public static final SaveSectionId DETAIL_BLOCKS = new SaveSectionId("detail-blocks");
    private static final Set<SaveSectionId> REQUIRED_V1 = Set.of(
            CHUNKS, PLAYER, INVENTORY, WORLD_ITEMS);
    private static final Set<SaveSectionId> RESERVED_OPTIONAL_V1 = Set.of(
            DISCOVERY_LORE, DETAIL_BLOCKS);

    public SaveSectionId {
        Objects.requireNonNull(value, "value");
        if (!value.matches("[a-z][a-z0-9-]*")) {
            throw new IllegalArgumentException("Save section ID must be a lowercase wire identifier");
        }
    }

    public static boolean isRequiredV1(SaveSectionId sectionId) {
        return REQUIRED_V1.contains(Objects.requireNonNull(sectionId, "sectionId"));
    }

    public static boolean isReservedOptionalV1(SaveSectionId sectionId) {
        return RESERVED_OPTIONAL_V1.contains(Objects.requireNonNull(sectionId, "sectionId"));
    }
}
