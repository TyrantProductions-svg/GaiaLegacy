package com.overlord.assets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AssetManagerTest {
    @TempDir Path temp;

    @Test
    void discoversIndexAndReadsAssetFromJar() throws Exception {
        Path jar =
                jar(
                        temp.resolve("assets.jar"),
                        Map.of(
                                "META-INF/gaialegacy/resource-indexes.list",
                                "# Gaia\nassets/gaia/resource-index.json\n",
                                "assets/gaia/resource-index.json",
                                "{\"namespace\":\"gaia\"}",
                                "assets/gaia/blocks/grass.json",
                                "{\"id\":1}"));
        try (URLClassLoader loader =
                new URLClassLoader(
                        new URL[] {jar.toUri().toURL()},
                        ClassLoader.getPlatformClassLoader())) {
            AssetManager manager = new AssetManager(loader);
            List<AssetSource> indexes = manager.discoverResourceIndexes();

            assertEquals(1, indexes.size());
            assertEquals(
                    "assets/gaia/resource-index.json",
                    indexes.get(0).classpathPath());
            assertEquals(
                    "{\"id\":1}",
                    manager.readUtf8(
                            ResourceLocation.parse("gaia:blocks/grass.json")));
        }
    }

    @Test
    void rejectsMissingAndAmbiguousResources() throws Exception {
        AssetManager empty =
                new AssetManager(ClassLoader.getPlatformClassLoader());
        AssetLoadException missing =
                assertThrows(
                        AssetLoadException.class,
                        () -> empty.open(
                                ResourceLocation.parse("gaia:missing.json")));
        assertEquals("ASSET_NOT_FOUND", missing.report().errors().get(0).code());

        Path first =
                jar(
                        temp.resolve("first.jar"),
                        Map.of("assets/gaia/shared.json", "first"));
        Path second =
                jar(
                        temp.resolve("second.jar"),
                        Map.of("assets/gaia/shared.json", "second"));
        try (URLClassLoader loader =
                new URLClassLoader(
                        new URL[] {
                            first.toUri().toURL(),
                            second.toUri().toURL()
                        },
                        ClassLoader.getPlatformClassLoader())) {
            AssetLoadException ambiguous =
                    assertThrows(
                            AssetLoadException.class,
                            () -> new AssetManager(loader).open(
                                    ResourceLocation.parse("gaia:shared.json")));
            assertEquals(
                    "ASSET_AMBIGUOUS",
                    ambiguous.report().errors().get(0).code());
        }
    }

    @Test
    void discoversIndexFromDirectoryClasspath() throws Exception {
        Path root = Files.createDirectories(temp.resolve("classes"));
        Path list =
                root.resolve(
                        "META-INF/gaialegacy/resource-indexes.list");
        Files.createDirectories(list.getParent());
        Files.writeString(
                list,
                "assets/gaia/resource-index.json\n",
                StandardCharsets.UTF_8);
        Path manifest =
                root.resolve("assets/gaia/resource-index.json");
        Files.createDirectories(manifest.getParent());
        Files.writeString(
                manifest,
                "{\"namespace\":\"gaia\"}",
                StandardCharsets.UTF_8);

        try (URLClassLoader loader =
                new URLClassLoader(
                        new URL[] {root.toUri().toURL()},
                        ClassLoader.getPlatformClassLoader())) {
            List<AssetSource> sources =
                    new AssetManager(loader)
                            .discoverResourceIndexes();
            assertEquals(1, sources.size());
            assertEquals(
                    "assets/gaia/resource-index.json",
                    sources.get(0).classpathPath());
        }
    }

    @Test
    void rejectsIndexEntriesWithoutMatchingManifests() throws Exception {
        Path jar =
                jar(
                        temp.resolve("missing-manifest.jar"),
                        Map.of(
                                "META-INF/gaialegacy/resource-indexes.list",
                                "assets/gaia/missing.json\n"));

        try (URLClassLoader loader =
                new URLClassLoader(
                        new URL[] {jar.toUri().toURL()},
                        ClassLoader.getPlatformClassLoader())) {
            AssetLoadException exception =
                    assertThrows(
                            AssetLoadException.class,
                            () -> new AssetManager(loader)
                                    .discoverResourceIndexes());

            assertEquals(
                    "ASSET_INDEX_NOT_FOUND",
                    exception.report().errors().get(0).code());
        }
    }

    @Test
    void rejectsUnsafeDiscoveryPaths() throws Exception {
        Path jar =
                jar(
                        temp.resolve("unsafe-path.jar"),
                        Map.of(
                                "META-INF/gaialegacy/resource-indexes.list",
                                "assets/gaia/../resource-index.json\n"));

        try (URLClassLoader loader =
                new URLClassLoader(
                        new URL[] {jar.toUri().toURL()},
                        ClassLoader.getPlatformClassLoader())) {
            AssetLoadException exception =
                    assertThrows(
                            AssetLoadException.class,
                            () -> new AssetManager(loader)
                                    .discoverResourceIndexes());

            assertEquals(
                    "ASSET_INDEX_INVALID",
                    exception.report().errors().get(0).code());
        }
    }

    @Test
    void rejectsParentNamespaceBeforeDirectoryClasspathAccess()
            throws Exception {
        Path root = Files.createDirectories(temp.resolve("escape-directory"));
        Files.writeString(
                root.resolve("escaped.txt"),
                "outside namespace root",
                StandardCharsets.UTF_8);

        try (URLClassLoader loader =
                new URLClassLoader(
                        new URL[] {root.toUri().toURL()},
                        ClassLoader.getPlatformClassLoader())) {
            AssetManager manager = new AssetManager(loader);

            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            manager.readUtf8(
                                    ResourceLocation.of(
                                            "..", "escaped.txt")));
        }
    }

    @Test
    void rejectsParentNamespaceBeforeJarClasspathAccess()
            throws Exception {
        Path jar =
                jar(
                        temp.resolve("escape.jar"),
                        Map.of(
                                "assets/../escaped.txt",
                                "outside namespace root"));

        try (URLClassLoader loader =
                new URLClassLoader(
                        new URL[] {jar.toUri().toURL()},
                        ClassLoader.getPlatformClassLoader())) {
            AssetManager manager = new AssetManager(loader);

            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            manager.readUtf8(
                                    ResourceLocation.of(
                                            "..", "escaped.txt")));
        }
    }

    @Test
    void discoversIndexesInDeterministicOrder() throws Exception {
        Path alpha =
                jar(
                        temp.resolve("alpha.jar"),
                        Map.of(
                                "META-INF/gaialegacy/resource-indexes.list",
                                "assets/gaia/a.json\n",
                                "assets/gaia/a.json",
                                "{}"));
        Path beta =
                jar(
                        temp.resolve("beta.jar"),
                        Map.of(
                                "META-INF/gaialegacy/resource-indexes.list",
                                "assets/gaia/b.json\n",
                                "assets/gaia/b.json",
                                "{}"));

        try (URLClassLoader loader =
                new URLClassLoader(
                        new URL[] {beta.toUri().toURL(), alpha.toUri().toURL()},
                        ClassLoader.getPlatformClassLoader())) {
            List<AssetSource> sources =
                    new AssetManager(loader).discoverResourceIndexes();

            assertEquals(2, sources.size());
            assertEquals(
                    List.of("assets/gaia/a.json", "assets/gaia/b.json"),
                    sources.stream().map(AssetSource::classpathPath).toList());
            assertEquals(sources.stream().sorted().toList(), sources);
        }
    }

    private static Path jar(Path path, Map<String, String> entries)
            throws IOException {
        try (JarOutputStream output =
                new JarOutputStream(Files.newOutputStream(path))) {
            for (Map.Entry<String, String> entry : entries.entrySet()) {
                output.putNextEntry(new JarEntry(entry.getKey()));
                output.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                output.closeEntry();
            }
        }
        return path;
    }
}
