package com.overlord.assets;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Objects;
import java.util.SortedSet;
import java.util.TreeSet;

public final class AssetManager {
    public static final String INDEX_LIST_PATH =
            "META-INF/gaialegacy/resource-indexes.list";

    private final ClassLoader classLoader;

    public AssetManager(ClassLoader classLoader) {
        this.classLoader = Objects.requireNonNull(classLoader, "classLoader");
    }

    public List<AssetSource> discoverResourceIndexes() {
        try {
            SortedSet<String> manifestPaths = new TreeSet<>();
            Enumeration<URL> lists = classLoader.getResources(INDEX_LIST_PATH);
            while (lists.hasMoreElements()) {
                URL listUrl = lists.nextElement();
                try (BufferedReader reader =
                        new BufferedReader(
                                new InputStreamReader(
                                        openStream(listUrl), StandardCharsets.UTF_8))) {
                    reader.lines()
                            .map(String::trim)
                            .filter(line -> !line.isEmpty())
                            .filter(line -> !line.startsWith("#"))
                            .peek(AssetManager::validateManifestPath)
                            .forEach(manifestPaths::add);
                }
            }

            SortedSet<AssetSource> result = new TreeSet<>();
            for (String path : manifestPaths) {
                Enumeration<URL> manifests = classLoader.getResources(path);
                if (!manifests.hasMoreElements()) {
                    throw failure(
                            "ASSET_INDEX_NOT_FOUND",
                            path,
                            null,
                            "index",
                            "Discovery entry has no matching manifest");
                }
                while (manifests.hasMoreElements()) {
                    result.add(new AssetSource(path, manifests.nextElement()));
                }
            }
            return List.copyOf(result);
        } catch (IOException exception) {
            throw failure(
                    "ASSET_IO",
                    INDEX_LIST_PATH,
                    null,
                    "index",
                    exception.getMessage());
        }
    }

    public InputStream open(ResourceLocation location) {
        Objects.requireNonNull(location, "location");
        String path = location.toClasspathPath();
        try {
            List<URL> matches = Collections.list(classLoader.getResources(path));
            if (matches.isEmpty()) {
                throw failure(
                        "ASSET_NOT_FOUND",
                        path,
                        location,
                        null,
                        "Resource is not present on the classpath");
            }
            if (matches.size() != 1) {
                throw failure(
                        "ASSET_AMBIGUOUS",
                        path,
                        location,
                        null,
                        "Resource has " + matches.size() + " classpath owners");
            }
            return openStream(matches.get(0));
        } catch (IOException exception) {
            throw failure(
                    "ASSET_IO", path, location, null, exception.getMessage());
        }
    }

    public String readUtf8(ResourceLocation location) {
        try (InputStream input = open(location)) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw failure(
                    "ASSET_IO",
                    location.toClasspathPath(),
                    location,
                    null,
                    exception.getMessage());
        }
    }

    private static void validateManifestPath(String path) {
        if (!path.startsWith("assets/")) {
            throw invalidManifestPath(path);
        }
        String resourcePath = path.substring("assets/".length());
        int separator = resourcePath.indexOf('/');
        if (separator <= 0 || separator == resourcePath.length() - 1) {
            throw invalidManifestPath(path);
        }
        try {
            ResourceLocation.of(
                    resourcePath.substring(0, separator),
                    resourcePath.substring(separator + 1));
        } catch (IllegalArgumentException exception) {
            throw invalidManifestPath(path);
        }
    }

    private static AssetLoadException invalidManifestPath(String path) {
        return failure(
                "ASSET_INDEX_INVALID",
                path,
                null,
                "index",
                "Discovery entry is not a safe asset manifest path");
    }

    private static InputStream openStream(URL url) throws IOException {
        URLConnection connection = url.openConnection();
        connection.setUseCaches(false);
        return connection.getInputStream();
    }

    private static AssetLoadException failure(
            String code,
            String source,
            ResourceLocation resource,
            String field,
            String message) {
        AssetLoadReport.Builder builder = AssetLoadReport.builder();
        builder.add(
                new AssetDiagnostic(
                        AssetSeverity.ERROR,
                        code,
                        source,
                        resource,
                        field,
                        message,
                        null));
        return new AssetLoadException(builder.build());
    }
}
