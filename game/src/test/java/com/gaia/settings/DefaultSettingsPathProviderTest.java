package com.gaia.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DefaultSettingsPathProviderTest {
    @Test
    void resolvesApprovedPlatformLocations() {
        assertEquals(
                Path.of(
                        "C:/Users/Test/AppData/Roaming/GaiaLegacy/settings.json"),
                provider(
                                "Windows 11",
                                "C:/Users/Test",
                                Map.of(
                                        "APPDATA",
                                        "C:/Users/Test/AppData/Roaming"))
                        .settingsFile());
        assertEquals(
                Path.of(
                        "/Users/test/Library/Application Support/GaiaLegacy/settings.json"),
                provider("Mac OS X", "/Users/test", Map.of()).settingsFile());
        assertEquals(
                Path.of("/xdg/GaiaLegacy/settings.json"),
                provider(
                                "Linux",
                                "/home/test",
                                Map.of("XDG_CONFIG_HOME", "/xdg"))
                        .settingsFile());
    }

    @Test
    void darwinResolvesMacApplicationSupportAndCannotEnterTheWindowsBranch() {
        assertEquals(
                Path.of(
                        "/Users/test/Library/Application Support/GaiaLegacy/settings.json"),
                provider("Darwin", "/Users/test", Map.of()).settingsFile());
    }

    @Test
    void fallsBackToRoamingAppDataBelowInjectedWindowsHomeWhenAppDataIsAbsent() {
        assertEquals(
                Path.of(
                        "C:/Users/Test/AppData/Roaming/GaiaLegacy/settings.json"),
                provider("Windows 11", "C:/Users/Test", Map.of()).settingsFile());
    }

    @Test
    void fallsBackToDotConfigBelowInjectedLinuxHomeWhenXdgConfigHomeIsAbsent() {
        assertEquals(
                Path.of("/home/test/.config/GaiaLegacy/settings.json"),
                provider("Linux", "/home/test", Map.of()).settingsFile());
    }

    private static DefaultSettingsPathProvider provider(
            String osName, String userHome, Map<String, String> environment) {
        return new DefaultSettingsPathProvider(osName, userHome, environment);
    }
}
