package com.gaia.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gaia.interaction.GameMode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class JsonSettingsStoreTest {
    @TempDir Path nonFiniteRoot;

    @Test
    void missingFileLoadsApprovedDefaultsWithoutDiagnostics(@TempDir Path root) {
        SettingsLoadResult result = store(root.resolve("settings.json")).load();

        assertEquals(SettingsDefaults.schemaV1(), result.snapshot());
        assertTrue(result.diagnostics().isEmpty());
    }

    @Test
    void savedSnapshotRoundTripsThroughSchemaV1Json(@TempDir Path root) {
        Path target = root.resolve("settings.json");
        SettingsSnapshot expected = changedSnapshot();

        store(target).save(expected);

        SettingsLoadResult result = store(target).load();
        assertEquals(expected, result.snapshot());
        assertTrue(result.diagnostics().isEmpty());
    }

    @Test
    void ignoresUnknownJsonFieldsForForwardCompatibility(@TempDir Path root)
            throws IOException {
        Path target = root.resolve("settings.json");
        Files.writeString(
                target,
                validJson(changedSnapshot())
                        .replace("}", ",\"futureSetting\":\"ignored\"}"),
                StandardCharsets.UTF_8);

        SettingsLoadResult result = store(target).load();

        assertEquals(changedSnapshot(), result.snapshot());
        assertTrue(result.diagnostics().isEmpty());
    }

    @Test
    void corruptJsonReturnsDefaultsWithOneInvalidJsonDiagnostic(@TempDir Path root)
            throws IOException {
        Path target = root.resolve("settings.json");
        Files.writeString(target, "{\"schemaVersion\":1,", StandardCharsets.UTF_8);

        SettingsLoadResult result = store(target).load();

        assertEquals(SettingsDefaults.schemaV1(), result.snapshot());
        assertEquals(
                List.of(new SettingsDiagnostic("INVALID_JSON", "$")),
                result.diagnostics());
    }

    @Test
    void missingJsonFieldFallsBackIndividuallyWhileOtherFieldsRemainLoaded(
            @TempDir Path root) throws IOException {
        Path target = root.resolve("settings.json");
        Files.writeString(
                target,
                """
                {"schemaVersion":1,"vsync":false,"mouseSensitivity":0.25,
                "invertY":true,"chunkRadius":7,"masterVolume":0.4,
                "musicVolume":0.3,"sfxVolume":0.2,"muteWhenUnfocused":false,
                "defaultGameMode":"CREATIVE","debugHudDefault":true}
                """,
                StandardCharsets.UTF_8);

        SettingsLoadResult result = store(target).load();

        assertEquals(
                new SettingsSnapshot(
                        1,
                        false,
                        70.0,
                        0.25,
                        true,
                        7,
                        0.4,
                        0.3,
                        0.2,
                        false,
                        GameMode.CREATIVE,
                        true),
                result.snapshot());
        assertEquals(
                List.of(new SettingsDiagnostic("MISSING_VALUE", "fovDegrees")),
                result.diagnostics());
    }

    @Test
    void writesUtf8StableSchemaV1Json(@TempDir Path root) throws IOException {
        Path target = root.resolve("settings.json");
        SettingsSnapshot snapshot = changedSnapshot();

        store(target).save(snapshot);

        assertEquals(validJson(snapshot), Files.readString(target, StandardCharsets.UTF_8));
        assertEquals(
                validJson(snapshot),
                new String(Files.readAllBytes(target), StandardCharsets.UTF_8));
    }

    @Test
    void failedReplaceLeavesPreviousValidFileAndDoesNotReportSaveSuccess(
            @TempDir Path root) throws IOException {
        Path target = root.resolve("settings.json");
        SettingsSnapshot defaults = SettingsDefaults.schemaV1();
        Files.writeString(target, validJson(defaults), StandardCharsets.UTF_8);
        AtomicFileWriter writer = new AtomicFileWriter(
                (source, destination, options) -> {
                    throw new IOException("injected replace failure");
                });

        assertThrows(
                SettingsPersistenceException.class,
                () -> store(target, writer).save(changedSnapshot()));
        assertEquals(defaults, store(target).load().snapshot());
    }

    @ParameterizedTest(name = "{0} rejects {2}")
    @MethodSource("nonFinitePersistedDoubleCases")
    void rejectsEveryNonFinitePersistedDoubleBeforeMutatingTheTarget(
            String field, double invalidValue, String displayValue) throws IOException {
        Path target = nonFiniteRoot.resolve(field + "-" + displayValue + ".json");
        String previousJson = validJson(SettingsDefaults.schemaV1());
        Files.writeString(target, previousJson, StandardCharsets.UTF_8);

        assertThrows(
                IllegalArgumentException.class,
                () -> store(target).save(snapshotWith(field, invalidValue)));

        assertEquals(previousJson, Files.readString(target, StandardCharsets.UTF_8));
    }

    @Test
    void loadIoFailureBecomesSettingsPersistenceExceptionWithIOExceptionCause(
            @TempDir Path root) throws IOException {
        Path target = root.resolve("settings.json");
        Files.createDirectory(target);

        SettingsPersistenceException failure = assertThrows(
                SettingsPersistenceException.class, () -> store(target).load());

        assertInstanceOf(IOException.class, failure.getCause());
        assertTrue(failure.getMessage().contains(target.toString()));
    }

    private static JsonSettingsStore store(Path target) {
        return new JsonSettingsStore(target);
    }

    private static JsonSettingsStore store(
            Path target, AtomicFileWriter writer) {
        return new JsonSettingsStore(target, writer);
    }

    private static SettingsSnapshot changedSnapshot() {
        return new SettingsSnapshot(
                1,
                false,
                85.0,
                0.25,
                true,
                7,
                0.4,
                0.3,
                0.2,
                false,
                GameMode.CREATIVE,
                true);
    }

    private static List<Arguments> nonFinitePersistedDoubleCases() {
        return List.of(
                Arguments.of("fovDegrees", Double.NaN, "nan"),
                Arguments.of("fovDegrees", Double.POSITIVE_INFINITY, "positive-infinity"),
                Arguments.of("fovDegrees", Double.NEGATIVE_INFINITY, "negative-infinity"),
                Arguments.of("mouseSensitivity", Double.NaN, "nan"),
                Arguments.of(
                        "mouseSensitivity", Double.POSITIVE_INFINITY, "positive-infinity"),
                Arguments.of(
                        "mouseSensitivity", Double.NEGATIVE_INFINITY, "negative-infinity"),
                Arguments.of("masterVolume", Double.NaN, "nan"),
                Arguments.of("masterVolume", Double.POSITIVE_INFINITY, "positive-infinity"),
                Arguments.of("masterVolume", Double.NEGATIVE_INFINITY, "negative-infinity"),
                Arguments.of("musicVolume", Double.NaN, "nan"),
                Arguments.of("musicVolume", Double.POSITIVE_INFINITY, "positive-infinity"),
                Arguments.of("musicVolume", Double.NEGATIVE_INFINITY, "negative-infinity"),
                Arguments.of("sfxVolume", Double.NaN, "nan"),
                Arguments.of("sfxVolume", Double.POSITIVE_INFINITY, "positive-infinity"),
                Arguments.of("sfxVolume", Double.NEGATIVE_INFINITY, "negative-infinity"));
    }

    private static SettingsSnapshot snapshotWith(String field, double invalidValue) {
        SettingsSnapshot baseline = changedSnapshot();
        return new SettingsSnapshot(
                baseline.schemaVersion(),
                baseline.vsync(),
                field.equals("fovDegrees") ? invalidValue : baseline.fovDegrees(),
                field.equals("mouseSensitivity")
                        ? invalidValue
                        : baseline.mouseSensitivity(),
                baseline.invertY(),
                baseline.chunkRadius(),
                field.equals("masterVolume") ? invalidValue : baseline.masterVolume(),
                field.equals("musicVolume") ? invalidValue : baseline.musicVolume(),
                field.equals("sfxVolume") ? invalidValue : baseline.sfxVolume(),
                baseline.muteWhenUnfocused(),
                baseline.defaultGameMode(),
                baseline.debugHudDefault());
    }

    private static String validJson(SettingsSnapshot snapshot) {
        return "{\"schemaVersion\":"
                + snapshot.schemaVersion()
                + ",\"vsync\":"
                + snapshot.vsync()
                + ",\"fovDegrees\":"
                + snapshot.fovDegrees()
                + ",\"mouseSensitivity\":"
                + snapshot.mouseSensitivity()
                + ",\"invertY\":"
                + snapshot.invertY()
                + ",\"chunkRadius\":"
                + snapshot.chunkRadius()
                + ",\"masterVolume\":"
                + snapshot.masterVolume()
                + ",\"musicVolume\":"
                + snapshot.musicVolume()
                + ",\"sfxVolume\":"
                + snapshot.sfxVolume()
                + ",\"muteWhenUnfocused\":"
                + snapshot.muteWhenUnfocused()
                + ",\"defaultGameMode\":\""
                + snapshot.defaultGameMode().name()
                + "\",\"debugHudDefault\":"
                + snapshot.debugHudDefault()
                + "}";
    }
}
