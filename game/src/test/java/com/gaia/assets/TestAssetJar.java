package com.gaia.assets;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

final class TestAssetJar implements AutoCloseable {
    private final URLClassLoader classLoader;

    private TestAssetJar(URLClassLoader classLoader) {
        this.classLoader = classLoader;
    }

    static TestAssetJar create(
            Path jarPath, Map<String, byte[]> entries)
            throws IOException {
        try (JarOutputStream output =
                new JarOutputStream(
                        Files.newOutputStream(jarPath))) {
            for (Map.Entry<String, byte[]> entry
                    : new TreeMap<>(entries).entrySet()) {
                output.putNextEntry(
                        new JarEntry(entry.getKey()));
                output.write(entry.getValue());
                output.closeEntry();
            }
        }
        URLClassLoader loader =
                new URLClassLoader(
                        new URL[] {jarPath.toUri().toURL()},
                        ClassLoader.getPlatformClassLoader());
        return new TestAssetJar(loader);
    }

    ClassLoader classLoader() {
        return classLoader;
    }

    @Override
    public void close() throws IOException {
        classLoader.close();
    }
}
