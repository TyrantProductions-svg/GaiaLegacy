package com.gaia.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import com.overlord.assets.AssetManager;
import com.overlord.assets.ResourceLocation;
import com.overlord.renderer.shader.ShaderResourceLoader;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class UiPackagedResourceContractTest {
    @Test
    void distributedFontDerivativesIncludeCompleteUnmodifiedUpstreamLicenses() throws Exception {
        Map<String, String> licenses = Map.of(
                "assets/gaia/ui/licenses/pixelify-OFL.txt",
                "b66ba46f511a851ab09998b5a5a9fdbb102545a3864cb993095e1745996873a7",
                "assets/gaia/ui/licenses/inter-LICENSE.txt",
                "262481e844521b326f5ecd053e59b98c8b2da78c8ee1bdbb6e8174305e54935a");
        for (var license : licenses.entrySet()) {
            try (InputStream input = getClass().getClassLoader()
                    .getResourceAsStream(license.getKey())) {
                assertNotNull(input, "distributed font license missing: " + license.getKey());
                assertEquals(license.getValue(), java.util.HexFormat.of().formatHex(
                        java.security.MessageDigest.getInstance("SHA-256")
                                .digest(input.readAllBytes())), license.getKey());
            }
        }
    }

    private static final List<String> GAME_UI_RESOURCES = List.of(
            "assets/gaia/ui/ui-assets.json",
            "assets/gaia/ui/brand/gaia-emblem.png",
            "assets/gaia/ui/brand/brand-manifest.json",
            "assets/gaia/ui/ui_font.png",
            "assets/gaia/ui/ui_font.json",
            "assets/gaia/ui/ui_font_body.png",
            "assets/gaia/ui/ui_font_display.png",
            "assets/gaia/ui/ui_typography.json",
            "assets/gaia/ui/hero/gaia-hero-dawn.png",
            "assets/gaia/ui/hero/gaia-hero-highlands.png",
            "assets/gaia/ui/hero/gaia-hero-twilight.png",
            "assets/gaia/ui/hero/hero-manifest.json",
            "assets/gaia/ui/ui_icons.png",
            "assets/gaia/ui/ui_icons.json");
    private static final List<String> ENGINE_UI_RESOURCES = List.of(
            "assets/overlord/shaders/ui/ui.vert",
            "assets/overlord/shaders/ui/ui.frag");

    @Test
    void isolatedJarLoadsTheCompleteUiContractWithoutFileUrls(@TempDir Path temporary)
            throws Exception {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        for (String path : allUiResources()) {
            try (InputStream input = getClass().getClassLoader().getResourceAsStream(path)) {
                assertNotNull(input, "production classpath is missing " + path);
                entries.put(path, input.readAllBytes());
            }
        }
        Path jar = writeJar(temporary.resolve("packaged-ui.jar"), entries);

        try (URLClassLoader loader = new URLClassLoader(
                new URL[] {jar.toUri().toURL()}, ClassLoader.getPlatformClassLoader())) {
            for (String path : allUiResources()) {
                URL resource = loader.getResource(path);
                assertNotNull(resource, path);
                assertEquals("jar", resource.getProtocol(), path);
            }

            AssetManager assets = new AssetManager(loader);
            GaiaUiAssets ui = new GaiaUiAssetLoader(assets).load();
            assertEquals(256, ui.renderAssets().font().width());
            assertEquals(512, ui.renderAssets()
                    .texture(com.overlord.renderer.ui.UiTextureId.FONT_DISPLAY).height());
            assertEquals(128, ui.renderAssets().icons().width());
            var shaders = new ShaderResourceLoader(assets).load(
                    "ui",
                    ResourceLocation.parse("overlord:shaders/ui/ui.vert"),
                    ResourceLocation.parse("overlord:shaders/ui/ui.frag"));
            assertTrue(shaders.vertexSource().startsWith("#version 410 core"));
            assertTrue(shaders.fragmentSource().startsWith("#version 410 core"));
        }
    }

    @Test
    void buildVerificationDeclaresEveryUiArtifactAndCompareOnlyGeneration()
            throws IOException {
        String engine = Files.readString(Path.of("../engine/build.gradle"));
        String game = Files.readString(Path.of("build.gradle"));
        String tools = Files.readString(Path.of("../tools/build.gradle"));

        for (String path : ENGINE_UI_RESOURCES) {
            assertTrue(engine.contains("'" + path + "'"),
                    "engine package verification is missing " + path);
            assertTrue(game.contains("'" + path + "'"),
                    "installDist verification is missing " + path);
        }
        for (String path : GAME_UI_RESOURCES) {
            assertTrue(game.contains("'" + path + "'"),
                    "game package/install verification is missing " + path);
        }
        assertTrue(game.contains("include 'game-*.jar'"),
                "installDist verification must inspect the installed game JAR");
        assertFalse(engine.contains("feedback/crosshair"));
        assertFalse(game.contains("feedback/crosshair"));

        assertTrue(tools.contains("tasks.register('verifyGeneratedUiAssets')"));
        assertTrue(tools.contains("build/generated-ui-verification"));
        assertTrue(tools.contains("Files.mismatch"));
        assertTrue(tools.contains("dependsOn tasks.named('verifyGeneratedUiAssets')"));
        assertFalse(engine.contains("project(':tools')"));
        assertFalse(game.contains("project(':tools')"));
    }

    @Test
    void productionResourceIndexDeclaresTheCompleteUiBundle() {
        String json = new AssetManager(getClass().getClassLoader()).readUtf8(
                ResourceLocation.parse("gaia:resource-index.json"));
        List<String> indexed = JsonParser.parseString(json)
                .getAsJsonObject()
                .getAsJsonArray("ui")
                .asList()
                .stream()
                .map(element -> element.getAsString())
                .toList();

        assertEquals(List.of(
                "ui/ui-assets.json",
                "ui/brand/gaia-emblem.png",
                "ui/brand/brand-manifest.json",
                "ui/ui_font.png",
                "ui/ui_font.json",
                "ui/ui_font_body.png",
                "ui/ui_font_display.png",
                "ui/ui_typography.json",
                "ui/hero/gaia-hero-dawn.png",
                "ui/hero/gaia-hero-highlands.png",
                "ui/hero/gaia-hero-twilight.png",
                "ui/hero/hero-manifest.json",
                "ui/ui_icons.png",
                "ui/ui_icons.json"), indexed);
    }

    private static List<String> allUiResources() {
        return java.util.stream.Stream.concat(
                        GAME_UI_RESOURCES.stream(), ENGINE_UI_RESOURCES.stream())
                .toList();
    }

    private static Path writeJar(Path jar, Map<String, byte[]> entries) throws IOException {
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar))) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                output.putNextEntry(new JarEntry(entry.getKey()));
                output.write(entry.getValue());
                output.closeEntry();
            }
        }
        return jar;
    }
}
