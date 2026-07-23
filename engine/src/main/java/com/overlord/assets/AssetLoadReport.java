package com.overlord.assets;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class AssetLoadReport {
    private final List<AssetDiagnostic> diagnostics;

    private AssetLoadReport(List<AssetDiagnostic> diagnostics) {
        this.diagnostics = List.copyOf(diagnostics);
    }

    public List<AssetDiagnostic> diagnostics() {
        return diagnostics;
    }

    public List<AssetDiagnostic> warnings() {
        return diagnostics.stream()
                .filter(d -> d.severity() == AssetSeverity.WARNING)
                .toList();
    }

    public List<AssetDiagnostic> errors() {
        return diagnostics.stream()
                .filter(d -> d.severity() == AssetSeverity.ERROR)
                .toList();
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private final List<AssetDiagnostic> diagnostics = new ArrayList<>();

        public void add(AssetDiagnostic diagnostic) {
            diagnostics.add(Objects.requireNonNull(diagnostic, "diagnostic"));
        }

        public void addAll(AssetLoadReport report) {
            diagnostics.addAll(
                    Objects.requireNonNull(report, "report").diagnostics());
        }

        public AssetLoadReport build() {
            return new AssetLoadReport(diagnostics);
        }

        public void throwIfErrors() {
            AssetLoadReport report = build();
            if (!report.errors().isEmpty()) {
                throw new AssetLoadException(report);
            }
        }
    }
}
