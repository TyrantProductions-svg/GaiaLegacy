package com.gaia.assets;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonPrimitive;
import java.math.BigDecimal;
import java.util.Objects;
import java.util.Set;

final class StrictJson {
    private final JsonObject object;
    private final String source;

    StrictJson(JsonObject object, String source) {
        this.object = Objects.requireNonNull(object, "object");
        this.source = Objects.requireNonNull(source, "source");
    }

    void requireOnly(String... allowed) {
        Set<String> allowedNames = Set.of(allowed);
        for (String actual : object.keySet()) {
            if (!allowedNames.contains(actual)) {
                throw new UnknownFieldException(
                        source + " has unknown field '" + actual + "'");
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

    private JsonParseException invalid(
            String field, String expected) {
        return new JsonParseException(
                source
                        + " field '"
                        + field
                        + "' must be "
                        + expected);
    }

    static final class UnknownFieldException
            extends JsonParseException {
        UnknownFieldException(String message) {
            super(message);
        }
    }
}
