package com.gaia.shell.save;

import com.gaia.save.archive.SaveDiagnostic;
import com.gaia.save.format.SaveFormatVersion;
import com.gaia.save.format.SaveGameId;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Immutable read-only catalog row for one local world identity. */
public record SaveSummary(
        SaveGameId id,
        String name,
        Optional<Instant> createdTime,
        Instant modifiedTime,
        Optional<Long> worldSeed,
        Optional<SaveFormatVersion> formatVersion,
        Health health,
        List<SaveDiagnostic> diagnostics) {
    public SaveSummary {
        id = Objects.requireNonNull(id, "id");
        name = Objects.requireNonNull(name, "name");
        createdTime = Objects.requireNonNull(createdTime, "createdTime");
        modifiedTime = Objects.requireNonNull(modifiedTime, "modifiedTime");
        worldSeed = Objects.requireNonNull(worldSeed, "worldSeed");
        formatVersion = Objects.requireNonNull(formatVersion, "formatVersion");
        health = Objects.requireNonNull(health, "health");
        diagnostics = List.copyOf(Objects.requireNonNull(diagnostics, "diagnostics"));
    }

    public boolean loadEnabled() {
        return health == Health.VALID;
    }

    public boolean recoveryEnabled() {
        return health == Health.RECOVERABLE_BACKUP;
    }

    public boolean deleteEnabled() {
        return true;
    }

    public enum Health {
        VALID,
        RECOVERABLE_BACKUP,
        CORRUPT,
        UNSUPPORTED_VERSION
    }
}
