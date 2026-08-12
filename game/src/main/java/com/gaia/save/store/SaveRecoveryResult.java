package com.gaia.save.store;

import com.gaia.save.archive.SaveDiagnostic;
import java.util.List;
import java.util.Objects;

/** Closed result of one explicit backup-recovery command. */
public final class SaveRecoveryResult {
    public enum Status {
        SUCCESS,
        NOT_FOUND,
        NOT_RECOVERABLE,
        FAILURE
    }

    private final Status status;
    private final List<SaveDiagnostic> diagnostics;

    private SaveRecoveryResult(Status status, List<SaveDiagnostic> diagnostics) {
        this.status = Objects.requireNonNull(status, "status");
        this.diagnostics = List.copyOf(Objects.requireNonNull(diagnostics, "diagnostics"));
        if ((status == Status.SUCCESS) != this.diagnostics.isEmpty()) {
            throw new IllegalArgumentException(
                    "Only successful recovery may omit diagnostics");
        }
    }

    public static SaveRecoveryResult success() {
        return new SaveRecoveryResult(Status.SUCCESS, List.of());
    }

    public static SaveRecoveryResult failed(Status status, SaveDiagnostic diagnostic) {
        if (status == Status.SUCCESS) {
            throw new IllegalArgumentException("Use success for successful recovery");
        }
        return new SaveRecoveryResult(
                status, List.of(Objects.requireNonNull(diagnostic, "diagnostic")));
    }

    public Status status() {
        return status;
    }

    public List<SaveDiagnostic> diagnostics() {
        return diagnostics;
    }
}
