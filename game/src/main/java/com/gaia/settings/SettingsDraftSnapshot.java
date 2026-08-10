package com.gaia.settings;

import java.util.Objects;
import java.util.Optional;

/** Immutable settings state exposed to product-shell input and presentation. */
public record SettingsDraftSnapshot(
        SettingsSnapshot applied,
        SettingsSnapshot draft,
        boolean dirty,
        Optional<SettingsDiagnostic> blockingDiagnostic) {
    public SettingsDraftSnapshot {
        applied = Objects.requireNonNull(applied, "applied");
        draft = Objects.requireNonNull(draft, "draft");
        blockingDiagnostic = Objects.requireNonNull(
                blockingDiagnostic, "blockingDiagnostic");
        if (dirty != !applied.equals(draft)) {
            throw new IllegalArgumentException(
                    "dirty must match the applied and draft snapshots");
        }
    }
}
