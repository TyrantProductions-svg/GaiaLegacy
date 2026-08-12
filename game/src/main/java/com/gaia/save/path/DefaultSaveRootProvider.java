package com.gaia.save.path;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** Resolves the approved per-platform save-data root without touching the filesystem. */
public final class DefaultSaveRootProvider implements SaveRootProvider {
    private final String osName;
    private final String userHome;
    private final Map<String, String> environment;

    public DefaultSaveRootProvider() {
        this(System.getProperty("os.name"), System.getProperty("user.home"), System.getenv());
    }

    public DefaultSaveRootProvider(String osName, String userHome, Map<String, String> environment) {
        this.osName = Objects.requireNonNull(osName, "osName");
        this.userHome = Objects.requireNonNull(userHome, "userHome");
        this.environment = Map.copyOf(Objects.requireNonNull(environment, "environment"));
    }

    @Override
    public Path saveRoot() {
        String normalizedOs = osName.toLowerCase(Locale.ROOT);
        if (normalizedOs.contains("mac") || normalizedOs.contains("darwin")) {
            return Path.of(userHome, "Library", "Application Support", "GaiaLegacy", "saves");
        }
        if (normalizedOs.contains("win")) {
            return Path.of(
                    environmentValueOrDefault(
                            "APPDATA", Path.of(userHome, "AppData", "Roaming").toString()),
                    "GaiaLegacy",
                    "saves");
        }
        return Path.of(
                environmentValueOrDefault(
                        "XDG_DATA_HOME", Path.of(userHome, ".local", "share").toString()),
                "GaiaLegacy",
                "saves");
    }

    private String environmentValueOrDefault(String name, String fallback) {
        String value = environment.get(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
