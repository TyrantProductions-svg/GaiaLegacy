package com.gaia.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gaia.interaction.GameMode;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class SettingsValidatorTest {
    @ParameterizedTest(name = "FOV {0} is accepted without diagnostics")
    @MethodSource("validFovValues")
    void acceptsInclusiveFovBoundaries(double fovDegrees) {
        SettingsLoadResult result = SettingsValidator.validate(document(fovDegrees, 0.10, 4, 1.0, 0.65, 1.0));

        assertEquals(fovDegrees, result.snapshot().fovDegrees());
        assertTrue(result.diagnostics().isEmpty());
    }

    @ParameterizedTest(name = "sensitivity {0} is accepted without diagnostics")
    @MethodSource("validSensitivityValues")
    void acceptsInclusiveMouseSensitivityBoundaries(double mouseSensitivity) {
        SettingsLoadResult result = SettingsValidator.validate(document(70.0, mouseSensitivity, 4, 1.0, 0.65, 1.0));

        assertEquals(mouseSensitivity, result.snapshot().mouseSensitivity());
        assertTrue(result.diagnostics().isEmpty());
    }

    @ParameterizedTest(name = "chunk radius {0} is accepted without diagnostics")
    @MethodSource("validChunkRadiusValues")
    void acceptsInclusiveChunkRadiusBoundaries(int chunkRadius) {
        SettingsLoadResult result = SettingsValidator.validate(document(70.0, 0.10, chunkRadius, 1.0, 0.65, 1.0));

        assertEquals(chunkRadius, result.snapshot().chunkRadius());
        assertTrue(result.diagnostics().isEmpty());
    }

    @ParameterizedTest(name = "volume {0} is accepted without diagnostics")
    @MethodSource("validVolumeValues")
    void acceptsInclusiveVolumeBoundaries(double volume) {
        SettingsLoadResult result = SettingsValidator.validate(document(70.0, 0.10, 4, volume, volume, volume));

        assertEquals(volume, result.snapshot().masterVolume());
        assertEquals(volume, result.snapshot().musicVolume());
        assertEquals(volume, result.snapshot().sfxVolume());
        assertTrue(result.diagnostics().isEmpty());
    }

    @ParameterizedTest(name = "{0} is clamped to {1}")
    @MethodSource("outOfRangeDocuments")
    void clampsFiniteValuesImmediatelyOutsideApprovedRanges(
            SettingsDocument document, SettingsSnapshot expectedSnapshot, String field) {
        SettingsLoadResult result = SettingsValidator.validate(document);

        assertEquals(expectedSnapshot, result.snapshot());
        assertEquals(List.of("CLAMPED_VALUE:" + field), diagnosticCodesAndFields(result));
    }

    @ParameterizedTest(name = "{0} falls back to the approved default")
    @MethodSource("nonFiniteDocuments")
    void replacesNonFiniteDoubleValuesWithApprovedDefaults(
            String displayName, SettingsDocument document, SettingsSnapshot expectedSnapshot, String field) {
        SettingsLoadResult result = SettingsValidator.validate(document);

        assertEquals(expectedSnapshot, result.snapshot());
        assertEquals(List.of("NON_FINITE_VALUE:" + field), diagnosticCodesAndFields(result));
    }

    @Test
    void missingNullableFieldsFallBackIndividuallyWithStableDiagnostics() {
        SettingsLoadResult result = SettingsValidator.validate(
                new SettingsDocument(null, null, null, null, null, null, null, null, null, null, null, null));

        assertEquals(defaults(), result.snapshot());
        assertEquals(
                List.of(
                        "MISSING_VALUE:schemaVersion",
                        "MISSING_VALUE:vsync",
                        "MISSING_VALUE:fovDegrees",
                        "MISSING_VALUE:mouseSensitivity",
                        "MISSING_VALUE:invertY",
                        "MISSING_VALUE:chunkRadius",
                        "MISSING_VALUE:masterVolume",
                        "MISSING_VALUE:musicVolume",
                        "MISSING_VALUE:sfxVolume",
                        "MISSING_VALUE:muteWhenUnfocused",
                        "MISSING_VALUE:defaultGameMode",
                        "MISSING_VALUE:debugHudDefault"),
                diagnosticCodesAndFields(result));
    }

    @Test
    void unsupportedSchemaFallsBackToCompleteSchemaV1Default() {
        SettingsLoadResult result = SettingsValidator.validate(
                new SettingsDocument(2, false, 100.0, 0.50, true, 8, 0.0, 0.0, 0.0, false, "CREATIVE", true));

        assertEquals(defaults(), result.snapshot());
        assertEquals(List.of("UNSUPPORTED_SCHEMA:schemaVersion"), diagnosticCodesAndFields(result));
    }

    @Test
    void invalidGameModeFallsBackToSurvivalWithStableDiagnostic() {
        SettingsLoadResult result = SettingsValidator.validate(
                new SettingsDocument(1, true, 70.0, 0.10, false, 4, 1.0, 0.65, 1.0, true, "ADVENTURE", false));

        assertEquals(GameMode.SURVIVAL, result.snapshot().defaultGameMode());
        assertEquals(List.of("INVALID_ENUM:defaultGameMode"), diagnosticCodesAndFields(result));
    }

    private static List<Arguments> validFovValues() {
        return List.of(Arguments.of(50.0), Arguments.of(100.0));
    }

    private static List<Arguments> validSensitivityValues() {
        return List.of(Arguments.of(0.02), Arguments.of(0.50));
    }

    private static List<Arguments> validChunkRadiusValues() {
        return List.of(Arguments.of(2), Arguments.of(8));
    }

    private static List<Arguments> validVolumeValues() {
        return List.of(Arguments.of(0.0), Arguments.of(1.0));
    }

    private static List<Arguments> outOfRangeDocuments() {
        return List.of(
                Arguments.of(document(49.99999999999999, 0.10, 4, 1.0, 0.65, 1.0), snapshot(50.0, 0.10, 4, 1.0, 0.65, 1.0), "fovDegrees"),
                Arguments.of(document(100.00000000000001, 0.10, 4, 1.0, 0.65, 1.0), snapshot(100.0, 0.10, 4, 1.0, 0.65, 1.0), "fovDegrees"),
                Arguments.of(document(70.0, 0.019999999999999997, 4, 1.0, 0.65, 1.0), snapshot(70.0, 0.02, 4, 1.0, 0.65, 1.0), "mouseSensitivity"),
                Arguments.of(document(70.0, 0.5000000000000001, 4, 1.0, 0.65, 1.0), snapshot(70.0, 0.50, 4, 1.0, 0.65, 1.0), "mouseSensitivity"),
                Arguments.of(document(70.0, 0.10, 1, 1.0, 0.65, 1.0), snapshot(70.0, 0.10, 2, 1.0, 0.65, 1.0), "chunkRadius"),
                Arguments.of(document(70.0, 0.10, 9, 1.0, 0.65, 1.0), snapshot(70.0, 0.10, 8, 1.0, 0.65, 1.0), "chunkRadius"),
                Arguments.of(document(70.0, 0.10, 4, -0.0000000000000001, 0.65, 1.0), snapshot(70.0, 0.10, 4, 0.0, 0.65, 1.0), "masterVolume"),
                Arguments.of(document(70.0, 0.10, 4, 1.0000000000000002, 0.65, 1.0), snapshot(70.0, 0.10, 4, 1.0, 0.65, 1.0), "masterVolume"),
                Arguments.of(document(70.0, 0.10, 4, 1.0, -0.0000000000000001, 1.0), snapshot(70.0, 0.10, 4, 1.0, 0.0, 1.0), "musicVolume"),
                Arguments.of(document(70.0, 0.10, 4, 1.0, 1.0000000000000002, 1.0), snapshot(70.0, 0.10, 4, 1.0, 1.0, 1.0), "musicVolume"),
                Arguments.of(document(70.0, 0.10, 4, 1.0, 0.65, -0.0000000000000001), snapshot(70.0, 0.10, 4, 1.0, 0.65, 0.0), "sfxVolume"),
                Arguments.of(document(70.0, 0.10, 4, 1.0, 0.65, 1.0000000000000002), snapshot(70.0, 0.10, 4, 1.0, 0.65, 1.0), "sfxVolume"));
    }

    private static List<Arguments> nonFiniteDocuments() {
        return List.of(
                Arguments.of("FOV NaN", document(Double.NaN, 0.10, 4, 1.0, 0.65, 1.0), snapshot(70.0, 0.10, 4, 1.0, 0.65, 1.0), "fovDegrees"),
                Arguments.of("sensitivity positive infinity", document(70.0, Double.POSITIVE_INFINITY, 4, 1.0, 0.65, 1.0), snapshot(70.0, 0.10, 4, 1.0, 0.65, 1.0), "mouseSensitivity"),
                Arguments.of("master volume negative infinity", document(70.0, 0.10, 4, Double.NEGATIVE_INFINITY, 0.65, 1.0), snapshot(70.0, 0.10, 4, 1.0, 0.65, 1.0), "masterVolume"),
                Arguments.of("music volume NaN", document(70.0, 0.10, 4, 1.0, Double.NaN, 1.0), snapshot(70.0, 0.10, 4, 1.0, 0.65, 1.0), "musicVolume"),
                Arguments.of("SFX volume positive infinity", document(70.0, 0.10, 4, 1.0, 0.65, Double.POSITIVE_INFINITY), snapshot(70.0, 0.10, 4, 1.0, 0.65, 1.0), "sfxVolume"));
    }

    private static SettingsDocument document(
            double fovDegrees,
            double mouseSensitivity,
            int chunkRadius,
            double masterVolume,
            double musicVolume,
            double sfxVolume) {
        return new SettingsDocument(
                1,
                true,
                fovDegrees,
                mouseSensitivity,
                false,
                chunkRadius,
                masterVolume,
                musicVolume,
                sfxVolume,
                true,
                "SURVIVAL",
                false);
    }

    private static SettingsSnapshot snapshot(
            double fovDegrees,
            double mouseSensitivity,
            int chunkRadius,
            double masterVolume,
            double musicVolume,
            double sfxVolume) {
        return new SettingsSnapshot(
                1,
                true,
                fovDegrees,
                mouseSensitivity,
                false,
                chunkRadius,
                masterVolume,
                musicVolume,
                sfxVolume,
                true,
                GameMode.SURVIVAL,
                false);
    }

    private static SettingsSnapshot defaults() {
        return snapshot(70.0, 0.10, 4, 1.0, 0.65, 1.0);
    }

    private static List<String> diagnosticCodesAndFields(SettingsLoadResult result) {
        return result.diagnostics().stream()
                .map(diagnostic -> diagnostic.code() + ":" + diagnostic.field())
                .toList();
    }
}
