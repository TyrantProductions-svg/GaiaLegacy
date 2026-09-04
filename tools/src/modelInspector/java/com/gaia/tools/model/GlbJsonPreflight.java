package com.gaia.tools.model;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.core.exc.StreamConstraintsException;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

import static com.gaia.tools.model.PreflightException.Code.*;

/** Streaming security policy only; no accessor, graph, material or image expansion. */
final class GlbJsonPreflight {
    private static final JsonFactory JSON = JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .streamReadConstraints(StreamReadConstraints.builder()
                    .maxNestingDepth(32).maxStringLength(4096)
                    .maxNameLength(4096).maxNumberLength(64).build())
            .build();

    private GlbJsonPreflight() { }

    static void check(byte[] data, int offset, int length) throws PreflightException {
        final String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(data, offset, length)).toString();
        } catch (CharacterCodingException invalid) {
            throw new PreflightException(JSON_INVALID);
        }
        if (text.startsWith("\uFEFF")) { throw new PreflightException(JSON_INVALID); }
        try (JsonParser parser = JSON.createParser(text)) {
            Scan scan = new Scan(parser);
            if (scan.next() != JsonToken.START_OBJECT) { throw new PreflightException(JSON_INVALID); }
            scan.value(JsonToken.START_OBJECT, true, false);
            if (scan.next() != null) { throw new PreflightException(JSON_INVALID); }
            if (!scan.versionSeen) { throw new PreflightException(ASSET_VERSION); }
        } catch (PreflightException rejected) {
            throw rejected;
        } catch (StreamConstraintsException bounded) {
            throw new PreflightException(JSON_LIMIT);
        } catch (IOException invalid) {
            // Parser exception text may include the source. Never retain it.
            throw new PreflightException(JSON_INVALID);
        }
    }

    private static final class Scan {
        private final JsonParser parser;
        private int tokens;
        private boolean versionSeen;

        Scan(JsonParser parser) { this.parser = parser; }

        JsonToken next() throws IOException {
            JsonToken token = parser.nextToken();
            if (token != null && ++tokens > 100_000) { throw new PreflightException(JSON_LIMIT); }
            if ((token == JsonToken.FIELD_NAME || token == JsonToken.VALUE_STRING)
                    && parser.getTextLength() > 4096) { throw new PreflightException(JSON_LIMIT); }
            return token;
        }

        void value(JsonToken token, boolean root, boolean asset) throws IOException {
            if (token == JsonToken.START_OBJECT) {
                JsonToken field;
                while ((field = next()) != JsonToken.END_OBJECT) {
                    if (field != JsonToken.FIELD_NAME) { throw new PreflightException(JSON_INVALID); }
                    String name = parser.currentName();
                    if (name.equals("uri")) { throw new PreflightException(URI_FORBIDDEN); }
                    JsonToken content = next();
                    if (name.equals("extensions")) {
                        empty(content, JsonToken.START_OBJECT, JsonToken.END_OBJECT);
                    } else if (name.equals("extensionsUsed") || name.equals("extensionsRequired")) {
                        empty(content, JsonToken.START_ARRAY, JsonToken.END_ARRAY);
                    } else if (root && name.equals("asset")) {
                        if (content != JsonToken.START_OBJECT) { throw new PreflightException(ASSET_VERSION); }
                        value(content, false, true);
                    } else if (asset && (name.equals("version") || name.equals("minVersion"))) {
                        if (content != JsonToken.VALUE_STRING || !parser.getText().equals("2.0")) {
                            throw new PreflightException(ASSET_VERSION);
                        }
                        if (name.equals("version")) { versionSeen = true; }
                    } else {
                        value(content, false, false);
                    }
                }
            } else if (token == JsonToken.START_ARRAY) {
                JsonToken entry;
                while ((entry = next()) != JsonToken.END_ARRAY) { value(entry, false, false); }
            } else if (token == null || !token.isScalarValue()) {
                throw new PreflightException(JSON_INVALID);
            }
        }

        void empty(JsonToken actual, JsonToken start, JsonToken end) throws IOException {
            if (actual != start || next() != end) { throw new PreflightException(EXTENSION_FORBIDDEN); }
        }
    }
}
