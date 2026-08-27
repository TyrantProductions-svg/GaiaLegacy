package com.gaia.audio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class InstalledAudioRuntimeAuditTest {
    private static final String OPENAL_VERSION = "3.3.3";
    private static final String OPENAL_API =
            "lwjgl-openal-" + OPENAL_VERSION + ".jar";
    private static final String OPENAL_NATIVE =
            "lwjgl-openal-" + OPENAL_VERSION + "-" + currentNativeClassifier() + ".jar";

    @Test
    void resolvedCurrentPlatformOpenAlInventoryPasses(@TempDir Path temporary)
            throws Exception {
        Path install = createInstall(temporary, true, true);

        AuditResult result = runAudit(install, temporary.resolve("project-cache"));

        assertEquals(0, result.exitCode(), result.output());
        assertTrue(result.output().contains("AUDIO_INSTALL_AUDIT_OK"), result.output());
        assertTrue(result.output().contains("api=" + OPENAL_API), result.output());
        assertTrue(result.output().contains("native=" + OPENAL_NATIVE), result.output());
    }

    @Test
    void missingResolvedOpenAlApiFailsClosed(@TempDir Path temporary) throws Exception {
        Path install = createInstall(temporary, false, true);

        AuditResult result = runAudit(install, temporary.resolve("project-cache"));

        assertNotEquals(0, result.exitCode(), result.output());
        assertTrue(
                result.output().contains("AUDIO_INSTALL_OPENAL_API_SET_MISMATCH"),
                result.output());
    }

    @Test
    void missingResolvedOpenAlNativeFailsClosed(@TempDir Path temporary) throws Exception {
        Path install = createInstall(temporary, true, false);

        AuditResult result = runAudit(install, temporary.resolve("project-cache"));

        assertNotEquals(0, result.exitCode(), result.output());
        assertTrue(
                result.output().contains("AUDIO_INSTALL_OPENAL_NATIVE_SET_MISMATCH"),
                result.output());
    }

    @Test
    void foreignOpenAlNativeBesideCurrentNativeFailsClosed(@TempDir Path temporary)
            throws Exception {
        Path install = createInstall(temporary, true, true);
        Files.createFile(install.resolve("lib")
                .resolve("lwjgl-openal-" + OPENAL_VERSION + "-natives-foreign.jar"));

        AuditResult result = runAudit(install, temporary.resolve("project-cache"));

        assertNotEquals(0, result.exitCode(), result.output());
        assertTrue(
                result.output().contains("AUDIO_INSTALL_OPENAL_NATIVE_SET_MISMATCH"),
                result.output());
    }

    @Test
    void looseMp3AnywhereUnderInstallDistFailsClosed(@TempDir Path temporary)
            throws Exception {
        Path install = createInstall(temporary, true, true);
        Path nestedSource = install.resolve("share").resolve("source-assets").resolve("audio");
        Files.createDirectories(nestedSource);
        Files.writeString(
                nestedSource.resolve("Gaia.mp3"),
                "not runtime audio",
                StandardCharsets.UTF_8);

        AuditResult result = runAudit(install, temporary.resolve("project-cache"));

        assertNotEquals(0, result.exitCode(), result.output());
        assertTrue(result.output().contains("AUDIO_INSTALL_MP3_FORBIDDEN"), result.output());
    }

    private static Path createInstall(Path temporary, boolean api, boolean nativeJar)
            throws IOException {
        Path install = temporary.resolve("install");
        Path library = Files.createDirectories(install.resolve("lib"));
        if (api) {
            Files.createFile(library.resolve(OPENAL_API));
        }
        if (nativeJar) {
            Files.createFile(library.resolve(OPENAL_NATIVE));
        }
        return install;
    }

    private static AuditResult runAudit(Path install, Path projectCache)
            throws IOException, InterruptedException {
        Path repository = Path.of("..").toAbsolutePath().normalize();
        boolean windows = System.getProperty("os.name")
                .toLowerCase(Locale.ROOT)
                .contains("win");
        List<String> command = new ArrayList<>();
        if (windows) {
            command.add("cmd.exe");
            command.add("/d");
            command.add("/c");
            command.add("gradlew.bat");
        } else {
            command.add(repository.resolve("gradlew").toString());
        }
        command.add(":game:verifyInstalledAudioRuntime");
        command.add("-PgaiaAudioAuditRoot=" + install.toAbsolutePath().normalize());
        command.add("--project-cache-dir");
        command.add(projectCache.toAbsolutePath().normalize().toString());
        command.add("--console=plain");
        command.add("--no-daemon");

        Process process = new ProcessBuilder(command)
                .directory(repository.toFile())
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        return new AuditResult(process.waitFor(), output);
    }

    private static String currentNativeClassifier() {
        String osName = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        String osArch = System.getProperty("os.arch").toLowerCase(Locale.ROOT);
        boolean arm64 = osArch.contains("aarch64") || osArch.contains("arm64");
        if (osName.contains("win")) {
            return arm64 ? "natives-windows-arm64" : "natives-windows";
        }
        if (osName.contains("mac")) {
            return arm64 ? "natives-macos-arm64" : "natives-macos";
        }
        if (osName.contains("linux")) {
            return arm64 ? "natives-linux-arm64" : "natives-linux";
        }
        throw new IllegalStateException(
                "Unsupported test platform: " + osName + " " + osArch);
    }

    private record AuditResult(int exitCode, String output) {}
}
