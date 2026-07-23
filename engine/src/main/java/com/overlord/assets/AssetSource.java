package com.overlord.assets;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLConnection;
import java.util.Objects;

public record AssetSource(String classpathPath, URL url)
        implements Comparable<AssetSource> {
    public AssetSource {
        Objects.requireNonNull(classpathPath, "classpathPath");
        Objects.requireNonNull(url, "url");
    }

    public InputStream open() throws IOException {
        URLConnection connection = url.openConnection();
        connection.setUseCaches(false);
        return connection.getInputStream();
    }

    @Override
    public int compareTo(AssetSource other) {
        int pathOrder = classpathPath.compareTo(other.classpathPath);
        return pathOrder != 0
                ? pathOrder
                : url.toExternalForm().compareTo(other.url.toExternalForm());
    }
}
