package com.gaia.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gaia.interaction.GameMode;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class SettingsSnapshotTest {
    @Test
    void schemaV1DefaultsMatchApprovedProductDefaults() {
        SettingsSnapshot defaults = SettingsDefaults.schemaV1();

        assertEquals(1, defaults.schemaVersion());
        assertTrue(defaults.vsync());
        assertEquals(70.0, defaults.fovDegrees());
        assertEquals(0.10, defaults.mouseSensitivity());
        assertFalse(defaults.invertY());
        assertEquals(4, defaults.chunkRadius());
        assertEquals(1.0, defaults.masterVolume());
        assertEquals(0.65, defaults.musicVolume());
        assertEquals(1.0, defaults.sfxVolume());
        assertTrue(defaults.muteWhenUnfocused());
        assertEquals(GameMode.SURVIVAL, defaults.defaultGameMode());
        assertFalse(defaults.debugHudDefault());
    }

    @Test
    void preservesExplicitFalseZeroAndCreativeValuesWithoutDiagnostics() {
        SettingsLoadResult result = SettingsValidator.validate(
                new SettingsDocument(
                        1,
                        false,
                        70.0,
                        0.10,
                        false,
                        4,
                        0.0,
                        0.0,
                        0.0,
                        false,
                        "CREATIVE",
                        false));

        assertEquals(
                new SettingsSnapshot(
                        1,
                        false,
                        70.0,
                        0.10,
                        false,
                        4,
                        0.0,
                        0.0,
                        0.0,
                        false,
                        GameMode.CREATIVE,
                        false),
                result.snapshot());
        assertTrue(result.diagnostics().isEmpty());
    }

    @Test
    void loadResultCopiesSourceDiagnostics() {
        SettingsDiagnostic diagnostic =
                new SettingsDiagnostic("MISSING_VALUE", "vsync");
        List<SettingsDiagnostic> sourceDiagnostics =
                new ArrayList<>(List.of(diagnostic));
        SettingsLoadResult result = new SettingsLoadResult(
                SettingsDefaults.schemaV1(), sourceDiagnostics);

        sourceDiagnostics.clear();

        assertEquals(List.of(diagnostic), result.diagnostics());
    }

    @Test
    void loadResultDiagnosticsAccessorIsUnmodifiable() {
        SettingsLoadResult result = new SettingsLoadResult(
                SettingsDefaults.schemaV1(), List.of());

        assertThrows(
                UnsupportedOperationException.class,
                () ->
                        result.diagnostics().add(
                                new SettingsDiagnostic(
                                        "MISSING_VALUE", "vsync")));
    }
}
