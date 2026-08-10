package com.gaia.settings;

import com.gaia.interaction.GameMode;
import java.util.Objects;
import java.util.Optional;

/** Owns validated draft state and transactional settings publication. */
public final class SettingsController {
    private static final SettingsDiagnostic INCOHERENT_RUNTIME_DIAGNOSTIC =
            new SettingsDiagnostic("RUNTIME_SETTINGS_INCOHERENT", "$");

    private final SettingsStore store;
    private final SettingsApplier applier;
    private SettingsSnapshot applied;
    private SettingsSnapshot draft;
    private Optional<SettingsDiagnostic> blockingDiagnostic = Optional.empty();

    public SettingsController(
            SettingsSnapshot applied,
            SettingsStore store,
            SettingsApplier applier) {
        this.applied = validate(Objects.requireNonNull(applied, "applied"));
        this.draft = this.applied;
        this.store = Objects.requireNonNull(store, "store");
        this.applier = Objects.requireNonNull(applier, "applier");
    }

    public SettingsSnapshot applied() {
        return applied;
    }

    public SettingsDraftSnapshot snapshot() {
        return new SettingsDraftSnapshot(
                applied,
                draft,
                !applied.equals(draft),
                blockingDiagnostic);
    }

    public void toggleVsync() {
        updateDraft(new SettingsSnapshot(
                draft.schemaVersion(),
                !draft.vsync(),
                draft.fovDegrees(),
                draft.mouseSensitivity(),
                draft.invertY(),
                draft.chunkRadius(),
                draft.masterVolume(),
                draft.musicVolume(),
                draft.sfxVolume(),
                draft.muteWhenUnfocused(),
                draft.defaultGameMode(),
                draft.debugHudDefault()));
    }

    public void toggleInvertY() {
        updateDraft(new SettingsSnapshot(
                draft.schemaVersion(),
                draft.vsync(),
                draft.fovDegrees(),
                draft.mouseSensitivity(),
                !draft.invertY(),
                draft.chunkRadius(),
                draft.masterVolume(),
                draft.musicVolume(),
                draft.sfxVolume(),
                draft.muteWhenUnfocused(),
                draft.defaultGameMode(),
                draft.debugHudDefault()));
    }

    public void toggleMuteWhenUnfocused() {
        updateDraft(new SettingsSnapshot(
                draft.schemaVersion(),
                draft.vsync(),
                draft.fovDegrees(),
                draft.mouseSensitivity(),
                draft.invertY(),
                draft.chunkRadius(),
                draft.masterVolume(),
                draft.musicVolume(),
                draft.sfxVolume(),
                !draft.muteWhenUnfocused(),
                draft.defaultGameMode(),
                draft.debugHudDefault()));
    }

    public void toggleDefaultGameMode() {
        updateDraft(new SettingsSnapshot(
                draft.schemaVersion(),
                draft.vsync(),
                draft.fovDegrees(),
                draft.mouseSensitivity(),
                draft.invertY(),
                draft.chunkRadius(),
                draft.masterVolume(),
                draft.musicVolume(),
                draft.sfxVolume(),
                draft.muteWhenUnfocused(),
                draft.defaultGameMode() == GameMode.SURVIVAL
                        ? GameMode.CREATIVE
                        : GameMode.SURVIVAL,
                draft.debugHudDefault()));
    }

    public void toggleDebugHudDefault() {
        updateDraft(new SettingsSnapshot(
                draft.schemaVersion(),
                draft.vsync(),
                draft.fovDegrees(),
                draft.mouseSensitivity(),
                draft.invertY(),
                draft.chunkRadius(),
                draft.masterVolume(),
                draft.musicVolume(),
                draft.sfxVolume(),
                draft.muteWhenUnfocused(),
                draft.defaultGameMode(),
                !draft.debugHudDefault()));
    }

    public void adjustFov(double fovDegrees) {
        updateDraft(numericDraft(
                fovDegrees,
                draft.mouseSensitivity(),
                draft.chunkRadius(),
                draft.masterVolume(),
                draft.musicVolume(),
                draft.sfxVolume()));
    }

    public void adjustMouseSensitivity(double mouseSensitivity) {
        updateDraft(numericDraft(
                draft.fovDegrees(),
                mouseSensitivity,
                draft.chunkRadius(),
                draft.masterVolume(),
                draft.musicVolume(),
                draft.sfxVolume()));
    }

    public void adjustChunkRadius(int chunkRadius) {
        updateDraft(numericDraft(
                draft.fovDegrees(),
                draft.mouseSensitivity(),
                chunkRadius,
                draft.masterVolume(),
                draft.musicVolume(),
                draft.sfxVolume()));
    }

    public void adjustMasterVolume(double masterVolume) {
        updateDraft(numericDraft(
                draft.fovDegrees(),
                draft.mouseSensitivity(),
                draft.chunkRadius(),
                masterVolume,
                draft.musicVolume(),
                draft.sfxVolume()));
    }

    public void adjustMusicVolume(double musicVolume) {
        updateDraft(numericDraft(
                draft.fovDegrees(),
                draft.mouseSensitivity(),
                draft.chunkRadius(),
                draft.masterVolume(),
                musicVolume,
                draft.sfxVolume()));
    }

    public void adjustSfxVolume(double sfxVolume) {
        updateDraft(numericDraft(
                draft.fovDegrees(),
                draft.mouseSensitivity(),
                draft.chunkRadius(),
                draft.masterVolume(),
                draft.musicVolume(),
                sfxVolume));
    }

    public void apply() {
        requireCoherentRuntime();
        SettingsSnapshot previous = applied;
        SettingsSnapshot next = validate(draft);
        draft = next;
        if (previous.equals(next)) {
            return;
        }

        try {
            applier.apply(previous, next);
        } catch (RuntimeException | Error applicationFailure) {
            if (applicationFailure.getSuppressed().length > 0) {
                blockingDiagnostic = Optional.of(INCOHERENT_RUNTIME_DIAGNOSTIC);
            }
            throw applicationFailure;
        }
        try {
            store.save(next);
        } catch (SettingsPersistenceException persistenceFailure) {
            rollbackHotSettings(next, previous, persistenceFailure);
            throw persistenceFailure;
        }
        applied = next;
        draft = next;
    }

    public void discard() {
        requireCoherentRuntime();
        draft = applied;
    }

    public BackDecision requestBack() {
        return applied.equals(draft)
                ? BackDecision.RETURN
                : BackDecision.CONFIRM_DIRTY;
    }

    private void updateDraft(SettingsSnapshot candidate) {
        requireCoherentRuntime();
        draft = validate(candidate);
    }

    private SettingsSnapshot numericDraft(
            double fovDegrees,
            double mouseSensitivity,
            int chunkRadius,
            double masterVolume,
            double musicVolume,
            double sfxVolume) {
        return new SettingsSnapshot(
                draft.schemaVersion(),
                draft.vsync(),
                fovDegrees,
                mouseSensitivity,
                draft.invertY(),
                chunkRadius,
                masterVolume,
                musicVolume,
                sfxVolume,
                draft.muteWhenUnfocused(),
                draft.defaultGameMode(),
                draft.debugHudDefault());
    }

    private void rollbackHotSettings(
            SettingsSnapshot next,
            SettingsSnapshot previous,
            SettingsPersistenceException persistenceFailure) {
        try {
            applier.apply(next, previous);
        } catch (RuntimeException | Error rollbackFailure) {
            if (rollbackFailure != persistenceFailure) {
                persistenceFailure.addSuppressed(rollbackFailure);
            }
            blockingDiagnostic = Optional.of(INCOHERENT_RUNTIME_DIAGNOSTIC);
        }
    }

    private void requireCoherentRuntime() {
        if (blockingDiagnostic.isPresent()) {
            throw new IllegalStateException(
                    "Settings changes are blocked because runtime rollback failed");
        }
    }

    private static SettingsSnapshot validate(SettingsSnapshot candidate) {
        return SettingsValidator.validate(new SettingsDocument(
                        candidate.schemaVersion(),
                        candidate.vsync(),
                        candidate.fovDegrees(),
                        candidate.mouseSensitivity(),
                        candidate.invertY(),
                        candidate.chunkRadius(),
                        candidate.masterVolume(),
                        candidate.musicVolume(),
                        candidate.sfxVolume(),
                        candidate.muteWhenUnfocused(),
                        Objects.requireNonNull(
                                        candidate.defaultGameMode(),
                                        "defaultGameMode")
                                .name(),
                        candidate.debugHudDefault()))
                .snapshot();
    }

    /** Controller-owned outcome for the Settings screen Back command. */
    public enum BackDecision {
        RETURN,
        CONFIRM_DIRTY
    }
}
