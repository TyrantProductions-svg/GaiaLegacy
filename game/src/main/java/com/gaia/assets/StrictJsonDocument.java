package com.gaia.assets;

import com.google.gson.JsonObject;

/** Public boundary for the resource system's duplicate-aware strict JSON parser. */
public final class StrictJsonDocument {
    private StrictJsonDocument() {}

    public static JsonObject parseObject(String json, String source) {
        return StrictJson.parseObject(json, source);
    }
}
