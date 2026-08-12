package com.gaia.save.path;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class DefaultSaveRootProviderTest {
    @ParameterizedTest(name = "{0}")
    @MethodSource("platforms")
    void resolvesApprovedPlatformSaveRoot(
            String policy, String osName, String userHome, Map<String, String> environment,
            Path expected) {
        assertEquals(expected, new DefaultSaveRootProvider(osName, userHome, environment).saveRoot());
    }

    private static Stream<Arguments> platforms() {
        return Stream.of(
                Arguments.of(
                        "Windows uses APPDATA",
                        "Windows 11",
                        "C:/Users/Test",
                        Map.of("APPDATA", "D:/Injected/AppData"),
                        Path.of("D:/Injected/AppData/GaiaLegacy/saves")),
                Arguments.of(
                        "Windows treats blank APPDATA as absent",
                        "Windows Server 2022",
                        "C:/Users/Test",
                        Map.of("APPDATA", "  "),
                        Path.of("C:/Users/Test/AppData/Roaming/GaiaLegacy/saves")),
                Arguments.of(
                        "Windows treats missing APPDATA as absent",
                        "Windows 11",
                        "C:/Users/Test",
                        Map.of(),
                        Path.of("C:/Users/Test/AppData/Roaming/GaiaLegacy/saves")),
                Arguments.of(
                        "macOS uses Application Support",
                        "Mac OS X",
                        "/Users/test",
                        Map.of(),
                        Path.of("/Users/test/Library/Application Support/GaiaLegacy/saves")),
                Arguments.of(
                        "Darwin uses the macOS policy",
                        "Darwin",
                        "/Users/test",
                        Map.of(),
                        Path.of("/Users/test/Library/Application Support/GaiaLegacy/saves")),
                Arguments.of(
                        "Linux uses XDG_DATA_HOME",
                        "Linux",
                        "/home/test",
                        Map.of("XDG_DATA_HOME", "/xdg/data"),
                        Path.of("/xdg/data/GaiaLegacy/saves")),
                Arguments.of(
                        "Linux treats blank XDG_DATA_HOME as absent",
                        "Linux",
                        "/home/test",
                        Map.of("XDG_DATA_HOME", " "),
                        Path.of("/home/test/.local/share/GaiaLegacy/saves")),
                Arguments.of(
                        "Linux treats missing XDG_DATA_HOME as absent",
                        "Linux",
                        "/home/test",
                        Map.of(),
                        Path.of("/home/test/.local/share/GaiaLegacy/saves")),
                Arguments.of(
                        "unrecognized Unix OS uses the Linux policy",
                        "FreeBSD",
                        "/home/test",
                        Map.of(),
                        Path.of("/home/test/.local/share/GaiaLegacy/saves")));
    }
}
