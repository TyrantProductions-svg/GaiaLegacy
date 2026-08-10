package com.gaia.settings;

import com.gaia.interaction.GameMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class SettingsValidator {
    private static final int SCHEMA_V1 = 1;
    private static final double MIN_FOV_DEGREES = 50.0;
    private static final double MAX_FOV_DEGREES = 100.0;
    private static final double MIN_MOUSE_SENSITIVITY = 0.02;
    private static final double MAX_MOUSE_SENSITIVITY = 0.50;
    private static final int MIN_CHUNK_RADIUS = 2;
    private static final int MAX_CHUNK_RADIUS = 8;
    private static final double MIN_VOLUME = 0.0;
    private static final double MAX_VOLUME = 1.0;
    private static final String MISSING_VALUE = "MISSING_VALUE";
    private static final String UNSUPPORTED_SCHEMA = "UNSUPPORTED_SCHEMA";
    private static final String NON_FINITE_VALUE = "NON_FINITE_VALUE";
    private static final String CLAMPED_VALUE = "CLAMPED_VALUE";
    private static final String INVALID_ENUM = "INVALID_ENUM";

    private SettingsValidator() {}

    public static SettingsLoadResult validate(SettingsDocument document) {
        Objects.requireNonNull(document, "document");
        SettingsSnapshot defaults = SettingsDefaults.schemaV1();
        List<SettingsDiagnostic> diagnostics = new ArrayList<>();

        int schemaVersion = integerOrDefault(
                document.schemaVersion(),
                defaults.schemaVersion(),
                "schemaVersion",
                diagnostics);
        if (schemaVersion != SCHEMA_V1) {
            return new SettingsLoadResult(
                    defaults,
                    List.of(new SettingsDiagnostic(
                            UNSUPPORTED_SCHEMA, "schemaVersion")));
        }

        return new SettingsLoadResult(
                new SettingsSnapshot(
                        schemaVersion,
                        booleanOrDefault(
                                document.vsync(),
                                defaults.vsync(),
                                "vsync",
                                diagnostics),
                        doubleInRange(
                                document.fovDegrees(),
                                defaults.fovDegrees(),
                                MIN_FOV_DEGREES,
                                MAX_FOV_DEGREES,
                                "fovDegrees",
                                diagnostics),
                        doubleInRange(
                                document.mouseSensitivity(),
                                defaults.mouseSensitivity(),
                                MIN_MOUSE_SENSITIVITY,
                                MAX_MOUSE_SENSITIVITY,
                                "mouseSensitivity",
                                diagnostics),
                        booleanOrDefault(
                                document.invertY(),
                                defaults.invertY(),
                                "invertY",
                                diagnostics),
                        integerInRange(
                                document.chunkRadius(),
                                defaults.chunkRadius(),
                                MIN_CHUNK_RADIUS,
                                MAX_CHUNK_RADIUS,
                                "chunkRadius",
                                diagnostics),
                        doubleInRange(
                                document.masterVolume(),
                                defaults.masterVolume(),
                                MIN_VOLUME,
                                MAX_VOLUME,
                                "masterVolume",
                                diagnostics),
                        doubleInRange(
                                document.musicVolume(),
                                defaults.musicVolume(),
                                MIN_VOLUME,
                                MAX_VOLUME,
                                "musicVolume",
                                diagnostics),
                        doubleInRange(
                                document.sfxVolume(),
                                defaults.sfxVolume(),
                                MIN_VOLUME,
                                MAX_VOLUME,
                                "sfxVolume",
                                diagnostics),
                        booleanOrDefault(
                                document.muteWhenUnfocused(),
                                defaults.muteWhenUnfocused(),
                                "muteWhenUnfocused",
                                diagnostics),
                        gameModeOrDefault(
                                document.defaultGameMode(),
                                defaults.defaultGameMode(),
                                diagnostics),
                        booleanOrDefault(
                                document.debugHudDefault(),
                                defaults.debugHudDefault(),
                                "debugHudDefault",
                                diagnostics)),
                diagnostics);
    }

    private static boolean booleanOrDefault(
            Boolean value,
            boolean defaultValue,
            String field,
            List<SettingsDiagnostic> diagnostics) {
        if (value == null) {
            diagnostics.add(new SettingsDiagnostic(MISSING_VALUE, field));
            return defaultValue;
        }
        return value;
    }

    private static int integerOrDefault(
            Integer value,
            int defaultValue,
            String field,
            List<SettingsDiagnostic> diagnostics) {
        if (value == null) {
            diagnostics.add(new SettingsDiagnostic(MISSING_VALUE, field));
            return defaultValue;
        }
        return value;
    }

    private static int integerInRange(
            Integer value,
            int defaultValue,
            int minimum,
            int maximum,
            String field,
            List<SettingsDiagnostic> diagnostics) {
        int validated = integerOrDefault(
                value, defaultValue, field, diagnostics);
        if (value == null) {
            return validated;
        }
        int clamped = Math.max(minimum, Math.min(maximum, validated));
        if (clamped != validated) {
            diagnostics.add(new SettingsDiagnostic(CLAMPED_VALUE, field));
        }
        return clamped;
    }

    private static double doubleInRange(
            Double value,
            double defaultValue,
            double minimum,
            double maximum,
            String field,
            List<SettingsDiagnostic> diagnostics) {
        if (value == null) {
            diagnostics.add(new SettingsDiagnostic(MISSING_VALUE, field));
            return defaultValue;
        }
        if (!Double.isFinite(value)) {
            diagnostics.add(new SettingsDiagnostic(NON_FINITE_VALUE, field));
            return defaultValue;
        }
        double clamped = Math.max(minimum, Math.min(maximum, value));
        if (Double.compare(clamped, value) != 0) {
            diagnostics.add(new SettingsDiagnostic(CLAMPED_VALUE, field));
        }
        return clamped;
    }

    private static GameMode gameModeOrDefault(
            String value,
            GameMode defaultValue,
            List<SettingsDiagnostic> diagnostics) {
        if (value == null) {
            diagnostics.add(new SettingsDiagnostic(
                    MISSING_VALUE, "defaultGameMode"));
            return defaultValue;
        }
        try {
            return GameMode.valueOf(value);
        } catch (IllegalArgumentException exception) {
            diagnostics.add(new SettingsDiagnostic(
                    INVALID_ENUM, "defaultGameMode"));
            return defaultValue;
        }
    }
}
