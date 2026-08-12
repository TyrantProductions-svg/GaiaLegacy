package com.gaia.save.archive;

import com.gaia.save.snapshot.SaveGameSnapshot;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Closed result of validating and decoding one save archive. */
public final class SaveArchiveReadResult {
    public enum Status {
        VALID,
        CORRUPT,
        UNSUPPORTED_VERSION
    }

    private final Status status;
    private final SaveGameSnapshot snapshot;
    private final List<SaveDiagnostic> diagnostics;

    private SaveArchiveReadResult(
            Status status,
            SaveGameSnapshot snapshot,
            List<SaveDiagnostic> diagnostics) {
        this.status = Objects.requireNonNull(status, "status");
        this.snapshot = snapshot;
        this.diagnostics = List.copyOf(Objects.requireNonNull(diagnostics, "diagnostics"));
        if ((status == Status.VALID) != (snapshot != null)) {
            throw new IllegalArgumentException(
                    "Only a VALID archive result may publish a snapshot");
        }
        if (status != Status.VALID && this.diagnostics.isEmpty()) {
            throw new IllegalArgumentException("A closed failure result requires a diagnostic");
        }
    }

    public static SaveArchiveReadResult valid(
            SaveGameSnapshot snapshot, List<SaveDiagnostic> diagnostics) {
        return new SaveArchiveReadResult(Status.VALID,
                Objects.requireNonNull(snapshot, "snapshot"), diagnostics);
    }

    public static SaveArchiveReadResult corrupt(SaveDiagnostic diagnostic) {
        return new SaveArchiveReadResult(Status.CORRUPT, null, List.of(diagnostic));
    }

    public static SaveArchiveReadResult unsupported(SaveDiagnostic diagnostic) {
        return new SaveArchiveReadResult(
                Status.UNSUPPORTED_VERSION, null, List.of(diagnostic));
    }

    public Status status() {
        return status;
    }

    public Optional<SaveGameSnapshot> snapshot() {
        return Optional.ofNullable(snapshot);
    }

    public List<SaveDiagnostic> diagnostics() {
        return diagnostics;
    }
}
