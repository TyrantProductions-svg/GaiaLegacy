package com.gaia.session;

import com.gaia.save.archive.SaveDiagnostic;
import com.gaia.save.format.SaveGameManifest;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Closed outcome of one owner-thread session save attempt. */
public final class GameSessionSaveResult {
    public enum Status {
        SUCCESS,
        CAPTURE_REJECTED,
        WRITE_FAILED,
        BLOCKING_FAILURE
    }

    private final Status status;
    private final SaveGameManifest committedManifest;
    private final List<SaveDiagnostic> diagnostics;

    private GameSessionSaveResult(
            Status status,
            SaveGameManifest committedManifest,
            List<SaveDiagnostic> diagnostics) {
        this.status = Objects.requireNonNull(status, "status");
        this.committedManifest = committedManifest;
        this.diagnostics = List.copyOf(Objects.requireNonNull(diagnostics, "diagnostics"));
        if ((status == Status.SUCCESS) != (committedManifest != null)) {
            throw new IllegalArgumentException("only SUCCESS publishes a manifest");
        }
        if (status == Status.SUCCESS && !this.diagnostics.isEmpty()) {
            throw new IllegalArgumentException("SUCCESS cannot carry diagnostics");
        }
        if (status != Status.SUCCESS && this.diagnostics.isEmpty()) {
            throw new IllegalArgumentException("save failure requires diagnostics");
        }
    }

    public static GameSessionSaveResult success(SaveGameManifest manifest) {
        return new GameSessionSaveResult(
                Status.SUCCESS, Objects.requireNonNull(manifest, "manifest"), List.of());
    }

    public static GameSessionSaveResult failed(
            Status status, List<SaveDiagnostic> diagnostics) {
        if (status == Status.SUCCESS) {
            throw new IllegalArgumentException("failure status required");
        }
        return new GameSessionSaveResult(status, null, diagnostics);
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
