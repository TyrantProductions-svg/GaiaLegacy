package com.gaia.save.store;

import com.gaia.save.archive.SaveDiagnostic;
import java.util.List;
import java.util.Objects;

/** Closed result of one root-confined local-save delete command. */
public final class SaveDeleteResult {
    public enum Status {
        SUCCESS,
        DELETED_WITH_CLEANUP_WARNING,
        NOT_FOUND,
        UNSAFE_TARGET,
        FAILURE
    }

    private final Status status;
    private final List<SaveDiagnostic> diagnostics;

    private SaveDeleteResult(Status status, List<SaveDiagnostic> diagnostics) {
        this.status = Objects.requireNonNull(status, "status");
        this.diagnostics = List.copyOf(Objects.requireNonNull(diagnostics, "diagnostics"));
        if (status == Status.SUCCESS && !this.diagnostics.isEmpty()) {
            throw new IllegalArgumentException("Successful delete cannot carry diagnostics");
        }
        if (status != Status.SUCCESS && this.diagnostics.isEmpty()) {
            throw new IllegalArgumentException("A non-success delete requires a diagnostic");
        }
    }

    public static SaveDeleteResult success() {
        return new SaveDeleteResult(Status.SUCCESS, List.of());
    }

    public static SaveDeleteResult failed(Status status, SaveDiagnostic diagnostic) {
        if (status == Status.SUCCESS) {
            throw new IllegalArgumentException("Use success for successful delete");
        }
        return new SaveDeleteResult(
                status, List.of(Objects.requireNonNull(diagnostic, "diagnostic")));
    }

    public Status status() {
        return status;
    }

    public List<SaveDiagnostic> diagnostics() {
        return diagnostics;
    }
}
