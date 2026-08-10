package com.gaia.settings;

/** Loads and persists immutable applied settings snapshots. */
public interface SettingsStore {
    SettingsLoadResult load();

    void save(SettingsSnapshot snapshot);
}
