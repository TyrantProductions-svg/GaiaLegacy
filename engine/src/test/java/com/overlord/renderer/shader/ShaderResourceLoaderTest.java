package com.overlord.renderer.shader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.overlord.assets.AssetLoadException;
import com.overlord.assets.AssetManager;
import com.overlord.assets.ResourceLocation;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ShaderResourceLoaderTest {
    private static final ResourceLocation WORLD_VERTEX =
            ResourceLocation.parse("overlord:shaders/world.vert");
    private static final ResourceLocation WORLD_FRAGMENT =
            ResourceLocation.parse("overlord:shaders/world.frag");

    @TempDir Path temp;

    @Test
    void loadsWorldShadersFromClasspathAndJar() throws Exception {
        ShaderSourceSet classpathSources =
                new ShaderResourceLoader(new AssetManager(getClass().getClassLoader()))
                        .load("world", WORLD_VERTEX, WORLD_FRAGMENT);

        assertWorldSources(classpathSources);

        Path jar =
                jar(
                        temp.resolve("world-shaders.jar"),
                        Map.of(
                                WORLD_VERTEX.toClasspathPath(),
                                classpathSources.vertexSource(),
                                WORLD_FRAGMENT.toClasspathPath(),
                                classpathSources.fragmentSource()));
        try (URLClassLoader loader =
                new URLClassLoader(
                        new URL[] {jar.toUri().toURL()},
                        ClassLoader.getPlatformClassLoader())) {
            ShaderSourceSet jarSources =
                    new ShaderResourceLoader(new AssetManager(loader))
                            .load("world", WORLD_VERTEX, WORLD_FRAGMENT);

            assertWorldSources(jarSources);
        }
    }

    @Test
    void reportsExactMissingResourceDiagnostic() {
        ResourceLocation missing = ResourceLocation.parse("overlord:shaders/missing.vert");

        AssetLoadException exception =
                assertThrows(
                        AssetLoadException.class,
                        () ->
                                new ShaderResourceLoader(
                                                new AssetManager(getClass().getClassLoader()))
                                        .load("world", missing, WORLD_FRAGMENT));

        assertEquals("ASSET_NOT_FOUND", exception.report().errors().get(0).code());
        assertEquals(missing, exception.report().errors().get(0).resource());
    }

    @Test
    void rejectsBlankLabelBeforeReadingResources() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new ShaderResourceLoader(new AssetManager(getClass().getClassLoader()))
                                .load(" ", WORLD_VERTEX, WORLD_FRAGMENT));
    }

    @Test
    void rejectsNullConstructorAndRecordFields() {
        assertThrows(NullPointerException.class, () -> new ShaderResourceLoader(null));
        assertThrows(
                NullPointerException.class,
                () -> new ShaderSourceSet(null, WORLD_VERTEX, "vertex", WORLD_FRAGMENT, "fragment"));
        assertThrows(
                NullPointerException.class,
                () -> new ShaderSourceSet("world", null, "vertex", WORLD_FRAGMENT, "fragment"));
        assertThrows(
                NullPointerException.class,
                () -> new ShaderSourceSet("world", WORLD_VERTEX, null, WORLD_FRAGMENT, "fragment"));
        assertThrows(
                NullPointerException.class,
                () -> new ShaderSourceSet("world", WORLD_VERTEX, "vertex", null, "fragment"));
        assertThrows(
                NullPointerException.class,
                () -> new ShaderSourceSet("world", WORLD_VERTEX, "vertex", WORLD_FRAGMENT, null));
    }

    private static void assertWorldSources(ShaderSourceSet sources) {
        assertEquals("world", sources.label());
        assertTrue(sources.vertexSource().startsWith("#version 410 core"));
        assertTrue(sources.fragmentSource().startsWith("#version 410 core"));
    }

    private static Path jar(Path path, Map<String, String> entries) throws IOException {
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(path))) {
            for (Map.Entry<String, String> entry : entries.entrySet()) {
                output.putNextEntry(new JarEntry(entry.getKey()));
                output.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                output.closeEntry();
            }
        }
        return path;
    }
}
