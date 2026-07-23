package com.overlord.assets;

import java.util.Objects;
import java.util.regex.Pattern;

public record ResourceLocation(String namespace, String path)
        implements Comparable<ResourceLocation> {
    private static final Pattern NAMESPACE =
            Pattern.compile("[a-z0-9_.-]+");
    private static final Pattern PATH =
            Pattern.compile("[a-z0-9_.-]+(?:/[a-z0-9_.-]+)*");

    public ResourceLocation {
        namespace = Objects.requireNonNull(namespace, "namespace");
        path = Objects.requireNonNull(path, "path");
        if (!NAMESPACE.matcher(namespace).matches()) {
            throw new IllegalArgumentException(
                    "Invalid resource namespace: " + namespace);
        }
        if (!PATH.matcher(path).matches()) {
            throw new IllegalArgumentException(
                    "Invalid resource path: " + path);
        }
        for (String segment : path.split("/")) {
            if (segment.equals(".") || segment.equals("..")) {
                throw new IllegalArgumentException(
                        "Resource path cannot traverse directories: " + path);
            }
        }
    }

    public static ResourceLocation of(String namespace, String path) {
        return new ResourceLocation(namespace, path);
    }

    public static ResourceLocation parse(String text) {
        Objects.requireNonNull(text, "text");
        int separator = text.indexOf(':');
        if (separator <= 0
                || separator == text.length() - 1
                || separator != text.lastIndexOf(':')) {
            throw new IllegalArgumentException(
                    "Resource location must be namespace:path: " + text);
        }
        return of(
                text.substring(0, separator),
                text.substring(separator + 1));
    }

    public String toClasspathPath() {
        return "assets/" + namespace + "/" + path;
    }

    @Override
    public int compareTo(ResourceLocation other) {
        return toString().compareTo(other.toString());
    }

    @Override
    public String toString() {
        return namespace + ":" + path;
    }
}
