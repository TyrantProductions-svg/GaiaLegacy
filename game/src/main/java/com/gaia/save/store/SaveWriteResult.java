package com.gaia.save.store;

import com.gaia.save.archive.SaveDiagnostic;
import com.gaia.save.format.SaveGameManifest;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Closed result of one save-store transaction. */
public final class SaveWriteResult {
    public enum Status {
        SUCCESS,
        FAILED,
        BLOCKING_FAILURE
    }

    private final Status status;
    private final SaveGameManifest committedManifest;
    private final List<SaveDiagnostic> diagnostics;

    private SaveWriteResult(
            Status status,
            SaveGameManifest committedManifest,
            List<SaveDiagnostic> diagnostics) {
        this.status = Objects.requireNonNull(status, "status");
        this.committedManifest = committedManifest;
        this.diagnostics = List.copyOf(
                Objects.requireNonNull(diagnostics, "diagnostics"));
        if ((status == Status.SUCCESS) != (committedManifest != null)) {
            throw new IllegalArgumentException(
                    "Only SUCCESS may publish a committed manifest");
        }
        if (status == Status.SUCCESS && !this.diagnostics.isEmpty()) {
            throw new IllegalArgumentException("SUCCESS cannot carry failure diagnostics");
        }
        if (status != Status.SUCCESS && this.diagnostics.isEmpty()) {
            throw new IllegalArgumentException("A failed save requires a diagnostic");
        }
    }

    public static SaveWriteResult success(SaveGameManifest committedManifest) {
        return new SaveWriteResult(
                Status.SUCCESS,
                Objects.requireNonNull(committedManifest, "committedManifest"),
                List.of());
    }

    public static SaveWriteResult failed(SaveDiagnostic diagnostic) {
        return new SaveWriteResult(
                Status.FAILED, null, List.of(Objects.requireNonNull(diagnostic, "diagnostic")));
    }

    public static SaveWriteResult blockingFailure(SaveDiagnostic diagnostic) {
        return new SaveWriteResult(
                Status.BLOCKING_FAILURE,
                null,
                List.of(Objects.requireNonNull(diagnostic, "diagnostic")));
    }

    public Status status() {
        return status;
    }

    public Optional<SaveGameManifest> committedManifest() {
        return Optional.ofNullable(committedManifest);
    }

    public List<SaveDiagnostic> diagnostics() {
        return diagnostics;
    }
}
