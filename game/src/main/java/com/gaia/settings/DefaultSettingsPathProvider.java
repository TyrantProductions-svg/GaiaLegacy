package com.gaia.settings;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** Resolves the approved per-platform settings location. */
public final class DefaultSettingsPathProvider implements SettingsPathProvider {
    private final String osName;
    private final String userHome;
    private final Map<String, String> environment;

    public DefaultSettingsPathProvider() {
        this(System.getProperty("os.name"), System.getProperty("user.home"), System.getenv());
    }

    public DefaultSettingsPathProvider(
            String osName, String userHome, Map<String, String> environment) {
        this.osName = Objects.requireNonNull(osName, "osName");
        this.userHome = Objects.requireNonNull(userHome, "userHome");
        this.environment = Map.copyOf(Objects.requireNonNull(environment, "environment"));
    }

    @Override
    public Path settingsFile() {
        String normalizedOs = osName.toLowerCase(Locale.ROOT);
        if (normalizedOs.contains("mac") || normalizedOs.contains("darwin")) {
            return Path.of(
                    userHome,
                    "Library",
                    "Application Support",
                    "GaiaLegacy",
                    "settings.json");
        }
        if (normalizedOs.contains("win")) {
            return Path.of(
                    environmentValueOrDefault(
                                    "APPDATA",
                                    Path.of(userHome, "AppData", "Roaming").toString())
                            .toString(),
                    "GaiaLegacy",
                    "settings.json");
        }
        return Path.of(
                environmentValueOrDefault(
                                "XDG_CONFIG_HOME",
                                Path.of(userHome, ".config").toString())
                        .toString(),
                "GaiaLegacy",
                "settings.json");
    }

    private String environmentValueOrDefault(String name, String fallback) {
        String value = environment.get(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
