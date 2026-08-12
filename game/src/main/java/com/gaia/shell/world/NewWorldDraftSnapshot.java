package com.gaia.shell.world;

import java.util.Objects;
import java.util.Optional;

/** Immutable presentation snapshot for the New World form. */
public record NewWorldDraftSnapshot(
        String name,
        String seedText,
        Field focusedField,
        Optional<Diagnostic> diagnostic) {
    public NewWorldDraftSnapshot {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(seedText, "seedText");
        Objects.requireNonNull(focusedField, "focusedField");
        diagnostic = Objects.requireNonNull(diagnostic, "diagnostic");
    }

    public enum Field {
        NAME,
        SEED
    }

    public enum Diagnostic {
        INVALID_NAME,
        DUPLICATE_NAME,
        INVALID_SEED
    }
}
