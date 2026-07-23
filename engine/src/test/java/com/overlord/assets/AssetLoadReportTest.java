package com.overlord.assets;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class AssetLoadReportTest {
    @Test
    void addsAndCombinesDiagnosticsBySeverity() {
        AssetLoadReport.Builder original = AssetLoadReport.builder();
        original.add(diagnostic(AssetSeverity.WARNING, "WARN_ONE", "first.json"));

        AssetLoadReport.Builder additional = AssetLoadReport.builder();
        additional.add(diagnostic(AssetSeverity.ERROR, "ERROR_ONE", "second.json"));

        original.addAll(additional.build());
        AssetLoadReport report = original.build();

        assertEquals(
                List.of("WARN_ONE", "ERROR_ONE"),
                report.diagnostics().stream().map(AssetDiagnostic::code).toList());
        assertEquals(
                List.of("WARN_ONE"),
                report.warnings().stream().map(AssetDiagnostic::code).toList());
        assertEquals(
                List.of("ERROR_ONE"),
                report.errors().stream().map(AssetDiagnostic::code).toList());
    }

    @Test
    void doesNotThrowForWarningOnlyReport() {
        AssetLoadReport.Builder builder = AssetLoadReport.builder();
        builder.add(diagnostic(AssetSeverity.WARNING, "WARN_ONLY", "warning.json"));

        assertDoesNotThrow(builder::throwIfErrors);
    }

    @Test
    void throwsAllErrorsWithCodesAndSourcesInMessage() {
        AssetLoadReport.Builder builder = AssetLoadReport.builder();
        builder.add(diagnostic(AssetSeverity.ERROR, "ERROR_ONE", "first.json"));
        builder.add(diagnostic(AssetSeverity.ERROR, "ERROR_TWO", "second.json"));

        AssetLoadException exception =
                assertThrows(AssetLoadException.class, builder::throwIfErrors);

        assertEquals(2, exception.report().errors().size());
        assertTrue(exception.getMessage().contains("ERROR_ONE at first.json"));
        assertTrue(exception.getMessage().contains("ERROR_TWO at second.json"));
    }

    private static AssetDiagnostic diagnostic(
            AssetSeverity severity, String code, String source) {
        return new AssetDiagnostic(
                severity, code, source, null, null, "Test diagnostic", null);
    }
}
