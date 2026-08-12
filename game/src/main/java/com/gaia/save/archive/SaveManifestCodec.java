package com.gaia.save.archive;

import com.gaia.save.format.SaveFormatVersion;
import com.gaia.save.format.SaveGameId;
import com.gaia.save.format.SaveGameManifest;
import com.gaia.save.format.SaveSectionDescriptor;
import com.gaia.save.format.SaveSectionId;
import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import java.io.IOException;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Deterministic bounded JSON codec with duplicate-aware streaming decode. */
public final class SaveManifestCodec {
    private static final Set<String> ROOT_FIELDS = Set.of(
            "formatVersion",
            "gameVersion",
            "saveGameId",
            "displayName",
            "createdAt",
            "modifiedAt",
            "worldSeed",
            "generatorVersion",
            "generatorConfigFingerprint",
            "chunkRadius",
            "worldHeight",
            "fixedTick",
            "summary",
            "sections");
    private static final Set<String> SECTION_FIELDS = Set.of(
            "sectionId", "codecVersion", "required", "uncompressedSize", "sha256");

    public byte[] encode(SaveGameManifest manifest) {
        JsonObject root = new JsonObject();
        root.addProperty("formatVersion", manifest.formatVersion().value());
        root.addProperty("gameVersion", manifest.gameVersion());
        root.addProperty("saveGameId", manifest.saveGameId().value());
        root.addProperty("displayName", manifest.displayName());
        root.addProperty("createdAt", manifest.createdAt().toString());
        root.addProperty("modifiedAt", manifest.modifiedAt().toString());
        root.addProperty("worldSeed", manifest.worldSeed());
        root.addProperty("generatorVersion", manifest.generatorVersion());
        root.addProperty(
                "generatorConfigFingerprint", manifest.generatorConfigFingerprint());
        root.addProperty("chunkRadius", manifest.chunkRadius());
        root.addProperty("worldHeight", manifest.worldHeight());
        root.addProperty("fixedTick", manifest.fixedTick());
        root.add("summary", manifest.summary() == null
                ? JsonNull.INSTANCE
                : new JsonPrimitive(manifest.summary()));
        JsonArray sections = new JsonArray();
        for (SaveSectionDescriptor descriptor : manifest.sections()) {
            JsonObject section = new JsonObject();
            section.addProperty("sectionId", descriptor.sectionId().value());
            section.addProperty("codecVersion", descriptor.codecVersion());
            section.addProperty("required", descriptor.required());
            section.addProperty("uncompressedSize", descriptor.uncompressedSize());
            section.addProperty("sha256", descriptor.sha256());
            sections.add(section);
        }
        root.add("sections", sections);
        byte[] bytes = root.toString().getBytes(StandardCharsets.UTF_8);
        if (bytes.length > SaveArchiveLimits.MAX_MANIFEST_BYTES) {
            throw new IllegalArgumentException("Encoded manifest exceeds the v1 bound");
        }
        return bytes;
    }

    /** Finds v1 for strict decode or one future version in a bounded generic scan. */
    public int formatVersion(byte[] bytes) {
        try (JsonReader reader = reader(bytes)) {
            reader.beginObject();
            Integer formatVersion = null;
            while (reader.hasNext()) {
                String field = reader.nextName();
                if ("formatVersion".equals(field)) {
                    if (formatVersion != null) {
                        throw new IllegalArgumentException(
                                "Manifest has a duplicate formatVersion");
                    }
                    formatVersion = readInt(reader, field);
                    if (formatVersion == SaveFormatVersion.CURRENT.value()) {
                        return formatVersion;
                    }
                } else {
                    reader.skipValue();
                }
            }
            reader.endObject();
            if (reader.peek() != JsonToken.END_DOCUMENT) {
                throw new IllegalArgumentException("Manifest has trailing JSON data");
            }
            return required(formatVersion, "formatVersion");
        } catch (EntryCountLimitException failure) {
            throw failure;
        } catch (IOException failure) {
            throw new IllegalArgumentException("Manifest JSON is malformed", failure);
        }
    }

    public SaveGameManifest decode(byte[] bytes) {
        try (JsonReader reader = reader(bytes)) {
            ManifestFields fields = readManifest(reader);
            if (reader.peek() != JsonToken.END_DOCUMENT) {
                throw new IllegalArgumentException("Manifest has trailing JSON data");
            }
            return fields.toManifest();
        } catch (EntryCountLimitException failure) {
            throw failure;
        } catch (IOException | RuntimeException failure) {
            throw new IllegalArgumentException("Manifest JSON is malformed", failure);
        }
    }

    private static ManifestFields readManifest(JsonReader reader) throws IOException {
        reader.beginObject();
        Set<String> seen = new HashSet<>();
        Integer formatVersion = null;
        String gameVersion = null;
        String saveGameId = null;
        String displayName = null;
        String createdAt = null;
        String modifiedAt = null;
        Long worldSeed = null;
        String generatorVersion = null;
        String generatorConfigFingerprint = null;
        Integer chunkRadius = null;
        Integer worldHeight = null;
        Long fixedTick = null;
        String summary = null;
        boolean summarySeen = false;
        List<SaveSectionDescriptor> sections = null;
        while (reader.hasNext()) {
            String field = uniqueField(reader, seen, ROOT_FIELDS, "manifest");
            switch (field) {
                case "formatVersion" -> formatVersion = readInt(reader, field);
                case "gameVersion" -> gameVersion = readString(reader, field);
                case "saveGameId" -> saveGameId = readString(reader, field);
                case "displayName" -> displayName = readString(reader, field);
                case "createdAt" -> createdAt = readString(reader, field);
                case "modifiedAt" -> modifiedAt = readString(reader, field);
                case "worldSeed" -> worldSeed = readLong(reader, field);
                case "generatorVersion" -> generatorVersion = readString(reader, field);
                case "generatorConfigFingerprint" ->
                        generatorConfigFingerprint = readString(reader, field);
                case "chunkRadius" -> chunkRadius = readInt(reader, field);
                case "worldHeight" -> worldHeight = readInt(reader, field);
                case "fixedTick" -> fixedTick = readLong(reader, field);
                case "summary" -> {
                    summarySeen = true;
                    summary = readNullableString(reader, field);
                }
                case "sections" -> sections = readSections(reader);
                default -> throw new IllegalArgumentException("Unknown manifest field");
            }
        }
        reader.endObject();
        if (!seen.equals(ROOT_FIELDS) || !summarySeen) {
            throw new IllegalArgumentException("Manifest has missing fields");
        }
        return new ManifestFields(
                required(formatVersion, "formatVersion"),
                required(gameVersion, "gameVersion"),
                required(saveGameId, "saveGameId"),
                required(displayName, "displayName"),
                required(createdAt, "createdAt"),
                required(modifiedAt, "modifiedAt"),
                required(worldSeed, "worldSeed"),
                required(generatorVersion, "generatorVersion"),
                required(generatorConfigFingerprint, "generatorConfigFingerprint"),
                required(chunkRadius, "chunkRadius"),
                required(worldHeight, "worldHeight"),
                required(fixedTick, "fixedTick"),
                summary,
                required(sections, "sections"));
    }

    private static List<SaveSectionDescriptor> readSections(JsonReader reader)
            throws IOException {
        reader.beginArray();
        List<SaveSectionDescriptor> sections = new ArrayList<>();
        int count = 0;
        while (reader.hasNext()) {
            count++;
            if (count > SaveArchiveLimits.MAX_ENTRY_COUNT - 1) {
                throw new EntryCountLimitException();
            }
            sections.add(readSection(reader));
        }
        reader.endArray();
        return List.copyOf(sections);
    }

    private static SaveSectionDescriptor readSection(JsonReader reader)
            throws IOException {
        reader.beginObject();
        Set<String> seen = new HashSet<>();
        String sectionId = null;
        Integer codecVersion = null;
        Boolean required = null;
        Long size = null;
        String sha256 = null;
        while (reader.hasNext()) {
            String field = uniqueField(reader, seen, SECTION_FIELDS, "section descriptor");
            switch (field) {
                case "sectionId" -> sectionId = readString(reader, field);
                case "codecVersion" -> codecVersion = readInt(reader, field);
                case "required" -> required = readBoolean(reader, field);
                case "uncompressedSize" -> size = readLong(reader, field);
                case "sha256" -> sha256 = readString(reader, field);
                default -> throw new IllegalArgumentException("Unknown section field");
            }
        }
        reader.endObject();
        if (!seen.equals(SECTION_FIELDS)) {
            throw new IllegalArgumentException("Section descriptor has missing fields");
        }
        return new SaveSectionDescriptor(
                new SaveSectionId(required(sectionId, "sectionId")),
                required(codecVersion, "codecVersion"),
                required(required, "required"),
                required(size, "uncompressedSize"),
                required(sha256, "sha256"));
    }

    private static String uniqueField(
            JsonReader reader,
            Set<String> seen,
            Set<String> allowed,
            String description) throws IOException {
        String field = reader.nextName();
        if (!allowed.contains(field)) {
            throw new IllegalArgumentException(description + " has an unknown field");
        }
        if (!seen.add(field)) {
            throw new IllegalArgumentException(description + " has a duplicate field");
        }
        return field;
    }

    private static JsonReader reader(byte[] bytes) {
        if (bytes == null || bytes.length == 0
                || bytes.length > SaveArchiveLimits.MAX_MANIFEST_BYTES) {
            throw new IllegalArgumentException("Manifest byte length is invalid");
        }
        try {
            String json = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
            JsonReader reader = new JsonReader(new StringReader(json));
            reader.setLenient(false);
            return reader;
        } catch (CharacterCodingException failure) {
            throw new IllegalArgumentException("Manifest is not valid UTF-8", failure);
        }
    }

    private static String readString(JsonReader reader, String field)
            throws IOException {
        if (reader.peek() != JsonToken.STRING) {
            throw new IllegalArgumentException(field + " must be a string");
        }
        return reader.nextString();
    }

    private static String readNullableString(JsonReader reader, String field)
            throws IOException {
        if (reader.peek() == JsonToken.NULL) {
            reader.nextNull();
            return null;
        }
        return readString(reader, field);
    }

    private static int readInt(JsonReader reader, String field) throws IOException {
        return Math.toIntExact(readLong(reader, field));
    }

    private static long readLong(JsonReader reader, String field) throws IOException {
        if (reader.peek() != JsonToken.NUMBER) {
            throw new IllegalArgumentException(field + " must be an integer");
        }
        String text = reader.nextString();
        if (!text.matches("-?(0|[1-9][0-9]*)")) {
            throw new IllegalArgumentException(field + " must be an integer");
        }
        return Long.parseLong(text);
    }

    private static boolean readBoolean(JsonReader reader, String field)
            throws IOException {
        if (reader.peek() != JsonToken.BOOLEAN) {
            throw new IllegalArgumentException(field + " must be a boolean");
        }
        return reader.nextBoolean();
    }

    private static <T> T required(T value, String field) {
        if (value == null) {
            throw new IllegalArgumentException("Missing manifest field " + field);
        }
        return value;
    }

    static final class EntryCountLimitException extends IllegalArgumentException {
        private EntryCountLimitException() {
            super("Manifest section count exceeds the archive bound");
        }
    }

    private record ManifestFields(
            int formatVersion,
            String gameVersion,
            String saveGameId,
            String displayName,
            String createdAt,
            String modifiedAt,
            long worldSeed,
            String generatorVersion,
            String generatorConfigFingerprint,
            int chunkRadius,
            int worldHeight,
            long fixedTick,
            String summary,
            List<SaveSectionDescriptor> sections) {
        private SaveGameManifest toManifest() {
            return new SaveGameManifest(
                    new SaveFormatVersion(formatVersion),
                    gameVersion,
                    SaveGameId.parse(saveGameId),
                    displayName,
                    Instant.parse(createdAt),
                    Instant.parse(modifiedAt),
                    worldSeed,
                    generatorVersion,
                    generatorConfigFingerprint,
                    chunkRadius,
                    worldHeight,
                    fixedTick,
                    summary,
                    sections);
        }
    }
}
