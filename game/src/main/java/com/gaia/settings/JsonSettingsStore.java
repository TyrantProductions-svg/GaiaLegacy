package com.gaia.settings;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/** Schema-v1 Gson settings store backed by atomic UTF-8 file replacement. */
public final class JsonSettingsStore implements SettingsStore {
    private static final Gson GSON = new Gson();
    private static final SettingsDiagnostic INVALID_JSON =
            new SettingsDiagnostic("INVALID_JSON", "$");

    private final Path settingsFile;
    private final AtomicFileWriter writer;

    public JsonSettingsStore(Path settingsFile) {
        this(settingsFile, new AtomicFileWriter());
    }

    public JsonSettingsStore(Path settingsFile, AtomicFileWriter writer) {
        this.settingsFile = Objects.requireNonNull(settingsFile, "settingsFile");
        this.writer = Objects.requireNonNull(writer, "writer");
    }

    @Override
    public SettingsLoadResult load() {
        if (Files.notExists(settingsFile)) {
            return new SettingsLoadResult(SettingsDefaults.schemaV1(), List.of());
        }
        try {
            SettingsDocument document = GSON.fromJson(
                    Files.readString(settingsFile, StandardCharsets.UTF_8),
                    SettingsDocument.class);
            if (document == null) {
                return invalidJsonResult();
            }
            return SettingsValidator.validate(document);
        } catch (JsonParseException | IllegalStateException failure) {
            return invalidJsonResult();
        } catch (IOException failure) {
            throw new SettingsPersistenceException(
                    "Unable to load settings from " + settingsFile, failure);
        }
    }

    @Override
    public void save(SettingsSnapshot snapshot) {
        SettingsSnapshot checkedSnapshot =
                Objects.requireNonNull(snapshot, "snapshot");
        requireSchemaV1WithFiniteValues(checkedSnapshot);
        try {
            writer.write(settingsFile, GSON.toJson(documentFor(checkedSnapshot)));
        } catch (IOException failure) {
            SettingsPersistenceException persistenceFailure =
                    new SettingsPersistenceException(
                            "Unable to save settings to " + settingsFile,
                            failure);
            for (Throwable cleanupFailure : failure.getSuppressed()) {
                persistenceFailure.addSuppressed(cleanupFailure);
            }
            throw persistenceFailure;
        }
    }

    private static SettingsLoadResult invalidJsonResult() {
        return new SettingsLoadResult(
                SettingsDefaults.schemaV1(), List.of(INVALID_JSON));
    }

    private static SettingsDocument documentFor(SettingsSnapshot snapshot) {
        return new SettingsDocument(
                snapshot.schemaVersion(),
                snapshot.vsync(),
                snapshot.fovDegrees(),
                snapshot.mouseSensitivity(),
                snapshot.invertY(),
                snapshot.chunkRadius(),
                snapshot.masterVolume(),
                snapshot.musicVolume(),
                snapshot.sfxVolume(),
                snapshot.muteWhenUnfocused(),
                Objects.requireNonNull(
                                snapshot.defaultGameMode(), "defaultGameMode")
                        .name(),
                snapshot.debugHudDefault());
    }

    private static void requireSchemaV1WithFiniteValues(
            SettingsSnapshot snapshot) {
        if (snapshot.schemaVersion() != 1) {
            throw new IllegalArgumentException(
                    "Only schema version 1 settings can be persisted");
        }
        if (!Double.isFinite(snapshot.fovDegrees())
                || !Double.isFinite(snapshot.mouseSensitivity())
                || !Double.isFinite(snapshot.masterVolume())
                || !Double.isFinite(snapshot.musicVolume())
                || !Double.isFinite(snapshot.sfxVolume())) {
            throw new IllegalArgumentException(
                    "Settings snapshots must not contain non-finite values");
        }
    }
}
