package com.gaia.save.format;

import java.util.Objects;
import java.util.UUID;

/** Immutable canonical UUID identity for one save-game directory. */
public record SaveGameId(String value) {
    private static final String CANONICAL_UUID =
            "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";

    public SaveGameId {
        Objects.requireNonNull(value, "value");
        if (!value.matches(CANONICAL_UUID)) {
            throw new IllegalArgumentException("Save game ID must be a canonical lowercase UUID");
        }
    }

    public static SaveGameId parse(String value) {
        SaveGameId result = new SaveGameId(value);
        try {
            UUID.fromString(result.value());
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("Save game ID must be a UUID", invalid);
        }
        return result;
    }
}
