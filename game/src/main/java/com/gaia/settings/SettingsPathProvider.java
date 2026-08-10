package com.gaia.settings;

import java.nio.file.Path;

/** Supplies the user settings file location without exposing platform policy to callers. */
@FunctionalInterface
public interface SettingsPathProvider {
    Path settingsFile();
}
