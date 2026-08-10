package com.gaia.settings;

import java.util.List;
import java.util.Objects;

public record SettingsLoadResult(
        SettingsSnapshot snapshot, List<SettingsDiagnostic> diagnostics) {
    public SettingsLoadResult {
        snapshot = Objects.requireNonNull(snapshot, "snapshot");
        diagnostics = List.copyOf(diagnostics);
    }
}
