package com.gaia.assets;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonPrimitive;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import java.io.IOException;
import java.io.StringReader;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

final class StrictJson {
    private final JsonObject object;
    private final String source;
    private final String fieldPrefix;

    StrictJson(JsonObject object, String source) {
        this(object, source, "");
    }

    StrictJson(
            JsonObject object,
            String source,
            String fieldPrefix) {
        this.object = Objects.requireNonNull(object, "object");
        this.source = Objects.requireNonNull(source, "source");
        this.fieldPrefix =
                Objects.requireNonNull(
                        fieldPrefix, "fieldPrefix");
    }

    static JsonObject parseObject(String json, String source) {
        Objects.requireNonNull(json, "json");
        Objects.requireNonNull(source, "source");
        JsonReader reader =
                new JsonReader(new StringReader(json));
        reader.setLenient(false);
        try {
            JsonElement root =
                    readValue(reader, source, "");
            if (reader.peek() != JsonToken.END_DOCUMENT) {
                throw invalid(
                        source,
                        "$",
                        "exactly one JSON value");
            }
            if (!root.isJsonObject()) {
                throw invalid(
                        source, "$", "an object");
            }
            return root.getAsJsonObject();
        } catch (JsonFieldException failure) {
            throw failure;
        } catch (IOException
                | IllegalStateException
                | NumberFormatException failure) {
            String field = normalizeReaderPath(reader.getPath());
            throw new JsonFieldException(
                    field,
                    source
                            + " has invalid JSON at field '"
                            + field
                            + "': "
                            + Objects.toString(
                                    failure.getMessage(),
                                    failure.getClass()
                                            .getSimpleName()));
        }
    }

    void requireOnly(String... allowed) {
        Set<String> allowedNames = Set.of(allowed);
        for (String actual : object.keySet()) {
            if (!allowedNames.contains(actual)) {
                throw new UnknownFieldException(
                        field(actual),
                        source
                                + " has unknown field '"
                                + field(actual)
                                + "'");
            }
        }
    }

    String requireString(String field) {
        JsonPrimitive value = requirePrimitive(field, "a string");
        if (!value.isString()) {
            throw invalid(field, "a string");
        }
        return value.getAsString();
    }

    int requireInt(String field) {
        JsonPrimitive value = requirePrimitive(field, "an integer");
        if (!value.isNumber()) {
            throw invalid(field, "an integer");
        }
        try {
            BigDecimal decimal = value.getAsBigDecimal();
            if (decimal.stripTrailingZeros().scale() > 0) {
                throw invalid(field, "an integer");
            }
            return decimal.intValueExact();
        } catch (ArithmeticException | NumberFormatException failure) {
            throw invalid(field, "a 32-bit integer");
        }
    }

    float requireFloat(String field) {
        JsonPrimitive value = requirePrimitive(field, "a finite number");
        if (!value.isNumber()) {
            throw invalid(field, "a finite number");
        }
        float result;
        try {
            result = value.getAsFloat();
        } catch (NumberFormatException failure) {
            throw invalid(field, "a finite number");
        }
        if (!Float.isFinite(result)) {
            throw invalid(field, "a finite number");
        }
        return result;
    }

    boolean requireBoolean(String field) {
        JsonPrimitive value = requirePrimitive(field, "a boolean");
        if (!value.isBoolean()) {
            throw invalid(field, "a boolean");
        }
        return value.getAsBoolean();
    }

    JsonObject requireObject(String field) {
        JsonElement value = requireValue(field);
        if (!value.isJsonObject()) {
            throw invalid(field, "an object");
        }
        return value.getAsJsonObject();
    }

    JsonObject optionalObject(String field) {
        if (!object.has(field)) {
            return null;
        }
        JsonElement value = object.get(field);
        if (value == null || value.isJsonNull() || !value.isJsonObject()) {
            throw invalid(field, "an object");
        }
        return value.getAsJsonObject();
    }

    List<String> requireStringList(String field) {
        JsonElement value = requireValue(field);
        if (!value.isJsonArray()) {
            throw invalid(field, "an array");
        }
        JsonArray array = value.getAsJsonArray();
        List<String> result = new ArrayList<>();
        for (int index = 0; index < array.size(); index++) {
            JsonElement element = array.get(index);
            String elementField =
                    field(field) + "[" + index + "]";
            if (element == null
                    || element.isJsonNull()
                    || !element.isJsonPrimitive()
                    || !element.getAsJsonPrimitive().isString()) {
                throw invalid(
                        source,
                        elementField,
                        "a string");
            }
            result.add(element.getAsString());
        }
        return List.copyOf(result);
    }

    <T> T requireSemantic(
            String field, Supplier<T> parser) {
        Objects.requireNonNull(parser, "parser");
        try {
            return parser.get();
        } catch (IllegalArgumentException failure) {
            String qualifiedField = field(field);
            throw new JsonFieldException(
                    qualifiedField,
                    source
                            + " field '"
                            + qualifiedField
                            + "' is invalid: "
                            + Objects.toString(
                                    failure.getMessage(),
                                    failure.getClass()
                                            .getSimpleName()));
        }
    }

    void requireSemantic(
            String field,
            boolean condition,
            String expected) {
        if (!condition) {
            throw invalid(field, expected);
        }
    }

    String fieldPath(String child) {
        return field(child);
    }

    private JsonPrimitive requirePrimitive(
            String field, String expected) {
        JsonElement value = requireValue(field);
        if (!value.isJsonPrimitive()) {
            throw invalid(field, expected);
        }
        return value.getAsJsonPrimitive();
    }

    private JsonElement requireValue(String field) {
        JsonElement value = object.get(field);
        if (value == null || value.isJsonNull()) {
            throw invalid(field, "a non-null value");
        }
        return value;
    }

    private JsonFieldException invalid(
            String field, String expected) {
        return invalid(source, field(field), expected);
    }

    private String field(String child) {
        return appendField(fieldPrefix, child);
    }

    private static JsonFieldException invalid(
            String source,
            String field,
            String expected) {
        return new JsonFieldException(
                field,
                source
                        + " field '"
                        + field
                        + "' must be "
                        + expected);
    }

    private static JsonElement readValue(
            JsonReader reader,
            String source,
            String field)
            throws IOException {
        return switch (reader.peek()) {
            case BEGIN_OBJECT ->
                    readObject(reader, source, field);
            case BEGIN_ARRAY ->
                    readArray(reader, source, field);
            case STRING ->
                    new JsonPrimitive(reader.nextString());
            case NUMBER ->
                    new JsonPrimitive(
                            new BigDecimal(
                                    reader.nextString()));
            case BOOLEAN ->
                    new JsonPrimitive(reader.nextBoolean());
            case NULL -> {
                reader.nextNull();
                yield JsonNull.INSTANCE;
            }
            default ->
                    throw invalid(
                            source,
                            displayField(field),
                            "a JSON value");
        };
    }

    private static JsonObject readObject(
            JsonReader reader,
            String source,
            String field)
            throws IOException {
        reader.beginObject();
        JsonObject result = new JsonObject();
        Set<String> names = new HashSet<>();
        while (reader.hasNext()) {
            String name = reader.nextName();
            String childField =
                    appendField(field, name);
            if (!names.add(name)) {
                throw new DuplicateFieldException(
                        displayField(childField),
                        displayField(field),
                        source
                                + " has duplicate field '"
                                + displayField(childField)
                                + "'");
            }
            result.add(
                    name,
                    readValue(
                            reader,
                            source,
                            childField));
        }
        reader.endObject();
        return result;
    }

    private static JsonArray readArray(
            JsonReader reader,
            String source,
            String field)
            throws IOException {
        reader.beginArray();
        JsonArray result = new JsonArray();
        int index = 0;
        while (reader.hasNext()) {
            result.add(
                    readValue(
                            reader,
                            source,
                            field
                                    + "["
                                    + index
                                    + "]"));
            index++;
        }
        reader.endArray();
        return result;
    }

    private static String appendField(
            String parent, String child) {
        return parent.isEmpty()
                ? child
                : parent + "." + child;
    }

    private static String displayField(String field) {
        return field.isEmpty() ? "$" : field;
    }

    private static String normalizeReaderPath(String path) {
        if (path == null || path.isEmpty() || path.equals("$")) {
            return "$";
        }
        String normalized =
                path.startsWith("$.")
                        ? path.substring(2)
                        : path;
        return normalized.isEmpty() ? "$" : normalized;
    }

    static class JsonFieldException
            extends JsonParseException {
        private final String field;

        JsonFieldException(String field, String message) {
            super(message);
            this.field =
                    Objects.requireNonNull(field, "field");
        }

        String field() {
            return field;
        }
    }

    static final class UnknownFieldException
            extends JsonFieldException {
        UnknownFieldException(
                String field, String message) {
            super(field, message);
        }
    }

    static final class DuplicateFieldException
            extends JsonFieldException {
        private final String objectField;

        DuplicateFieldException(
                String field,
                String objectField,
                String message) {
            super(field, message);
            this.objectField =
                    Objects.requireNonNull(
                            objectField, "objectField");
        }

        String objectField() {
            return objectField;
        }
    }
}
