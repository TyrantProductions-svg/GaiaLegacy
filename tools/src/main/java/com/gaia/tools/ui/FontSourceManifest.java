package com.gaia.tools.ui;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Closed build-time roster for third-party typography source bytes. */
public final class FontSourceManifest {
    public static final String RESOURCE = "ui-source/font-sources.json";

    private static final List<String> EXPECTED_IDS = List.of(
            "pixelify-semibold-600",
            "pixelify-bold-700",
            "inter-regular-400",
            "inter-medium-500",
            "inter-semibold-600",
            "plex-regular-400",
            "plex-medium-500",
            "plex-semibold-600");

    private final List<Entry> entries;

    private FontSourceManifest(List<Entry> entries) {
        this.entries = List.copyOf(entries);
    }

    public static FontSourceManifest load(ClassLoader loader) {
        Objects.requireNonNull(loader, "loader");
        JsonObject root;
        try (InputStream input = required(loader, RESOURCE);
                InputStreamReader reader = new InputStreamReader(input, StandardCharsets.UTF_8)) {
            root = JsonParser.parseReader(reader).getAsJsonObject();
        } catch (IOException | RuntimeException failure) {
            throw new IllegalArgumentException("unable to load typography source manifest", failure);
        }
        if (requireInt(root, "schemaVersion") != 1) {
            throw new IllegalArgumentException("font source schemaVersion must equal 1");
        }
        JsonArray array = requireArray(root, "entries");
        List<Entry> entries = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        Set<String> sourcePaths = new HashSet<>();
        for (JsonElement element : array) {
            if (!element.isJsonObject()) {
                throw new IllegalArgumentException("font source entry must be an object");
            }
            JsonObject object = element.getAsJsonObject();
            Entry entry = new Entry(
                    requireString(object, "id"),
                    requireString(object, "family"),
                    requireInt(object, "weight"),
                    requireResourcePath(object, "sourcePath"),
                    requireSourceUrl(object, "sourceUrl"),
                    requireHash(object, "sourceSha256", false),
                    requireString(object, "upstream"),
                    requireString(object, "upstreamCommitOrTag"),
                    requireHash(object, "gitBlobSha", true),
                    requireHash(object, "sourceArchiveSha256", true),
                    requireResourcePath(object, "licensePath"),
                    requireHash(object, "licenseSha256", false));
            if (!ids.add(entry.id()) || !sourcePaths.add(entry.sourcePath())) {
                throw new IllegalArgumentException("font source ids and paths must be unique");
            }
            verifyHash(loader, entry.sourcePath(), entry.sourceSha256());
            verifyHash(loader, entry.licensePath(), entry.licenseSha256());
            entries.add(entry);
        }
        if (!entries.stream().map(Entry::id).toList().equals(EXPECTED_IDS)) {
            throw new IllegalArgumentException("font source roster/order is not the approved set");
        }
        return new FontSourceManifest(entries);
    }

    public List<Entry> entries() {
        return entries;
    }

    public Entry entry(String id) {
        Objects.requireNonNull(id, "id");
        return entries.stream()
                .filter(entry -> entry.id().equals(id))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unknown font source id " + id));
    }

    private static void verifyHash(ClassLoader loader, String path, String expected) {
        try (InputStream input = required(loader, path)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[16_384];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                digest.update(buffer, 0, read);
            }
            String actual = HexFormat.of().formatHex(digest.digest());
            if (!actual.equals(expected)) {
                throw new IllegalArgumentException(
                        "font source hash mismatch for " + path + ": " + actual);
            }
        } catch (IOException | NoSuchAlgorithmException failure) {
            throw new IllegalArgumentException("unable to verify font source " + path, failure);
        }
    }

    private static InputStream required(ClassLoader loader, String path) {
        InputStream input = loader.getResourceAsStream(path);
        if (input == null) {
            throw new IllegalArgumentException("missing typography source resource " + path);
        }
        return input;
    }

    private static JsonArray requireArray(JsonObject object, String field) {
        JsonElement element = object.get(field);
        if (element == null || !element.isJsonArray()) {
            throw new IllegalArgumentException(field + " must be an array");
        }
        return element.getAsJsonArray();
    }

    private static int requireInt(JsonObject object, String field) {
        JsonElement element = object.get(field);
        if (element == null || !element.isJsonPrimitive()
                || !element.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException(field + " must be an integer");
        }
        int value = element.getAsInt();
        if (element.getAsDouble() != value) {
            throw new IllegalArgumentException(field + " must be an integer");
        }
        return value;
    }

    private static String requireString(JsonObject object, String field) {
        JsonElement element = object.get(field);
        if (element == null || !element.isJsonPrimitive()
                || !element.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException(field + " must be a string");
        }
        String value = element.getAsString();
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }

    private static String requireHash(JsonObject object, String field, boolean allowEmpty) {
        JsonElement element = object.get(field);
        if (element == null || !element.isJsonPrimitive()
                || !element.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException(field + " must be a string");
        }
        String value = element.getAsString();
        if (allowEmpty && value.isEmpty()) {
            return value;
        }
        int length = field.equals("gitBlobSha") ? 40 : 64;
        if (value.length() != length || !value.matches("[0-9a-f]+")) {
            throw new IllegalArgumentException(field + " has an invalid hash");
        }
        return value;
    }

    private static String requireResourcePath(JsonObject object, String field) {
        String path = requireString(object, field);
        if (path.startsWith("/") || path.contains("\\") || path.contains("..")
                || !path.startsWith("ui-source/fonts/")) {
            throw new IllegalArgumentException(field + " must stay in ui-source/fonts");
        }
        return path;
    }

    private static String requireSourceUrl(JsonObject object, String field) {
        String url = requireString(object, field);
        if (!url.startsWith("https://") || url.contains("\\") || url.contains(" ")) {
            throw new IllegalArgumentException(field + " must be an exact HTTPS source URL");
        }
        return url;
    }

    public record Entry(
            String id,
            String family,
            int weight,
            String sourcePath,
            String sourceUrl,
            String sourceSha256,
            String upstream,
            String upstreamCommitOrTag,
            String gitBlobSha,
            String sourceArchiveSha256,
            String licensePath,
            String licenseSha256) {
        public Entry {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(family, "family");
            Objects.requireNonNull(sourcePath, "sourcePath");
            Objects.requireNonNull(sourceUrl, "sourceUrl");
            Objects.requireNonNull(sourceSha256, "sourceSha256");
            Objects.requireNonNull(upstream, "upstream");
            Objects.requireNonNull(upstreamCommitOrTag, "upstreamCommitOrTag");
            Objects.requireNonNull(gitBlobSha, "gitBlobSha");
            Objects.requireNonNull(sourceArchiveSha256, "sourceArchiveSha256");
            Objects.requireNonNull(licensePath, "licensePath");
            Objects.requireNonNull(licenseSha256, "licenseSha256");
        }
    }
}
