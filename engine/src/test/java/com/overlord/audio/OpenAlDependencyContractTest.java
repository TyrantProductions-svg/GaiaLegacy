package com.overlord.audio;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class OpenAlDependencyContractTest {
    @Test
    void engineDeclaresOpenAlApiAndMatchingPlatformNatives() throws IOException {
        String script = Files.readString(Path.of("build.gradle"));

        assertTrue(
                script.contains("api \"org.lwjgl:lwjgl-openal\""),
                "engine must expose the LWJGL OpenAL module");
        assertTrue(
                script.contains("runtimeOnly \"org.lwjgl:lwjgl-openal::$lwjglNatives\""),
                "engine must package the matching current-platform OpenAL natives");
    }
}
