package com.gaia.save.codec;

import com.gaia.interaction.GameMode;
import com.gaia.save.format.SaveSectionCodec;
import com.gaia.save.format.SaveSectionId;
import com.gaia.save.snapshot.PlayerSaveSnapshot;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;
import com.overlord.interaction.api.EntityRef;
import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Set;

/** Deterministic JSON codec for authoritative player save state. */
public final class PlayerSectionCodec
        implements SaveSectionCodec<PlayerSaveSnapshot> {
    private static final int CODEC_VERSION = 1;
    private static final int MAX_PAYLOAD_BYTES = 16 * 1024;
    private static final Set<String> FIELDS = Set.of(
            "owner",
            "feetPositionX",
            "feetPositionY",
            "feetPositionZ",
            "velocityX",
            "velocityY",
            "velocityZ",
            "yaw",
            "pitch",
            "gameMode",
            "noclip");

    @Override
    public SaveSectionId sectionId() {
        return SaveSectionId.PLAYER;
    }

    @Override
    public int codecVersion() {
        return CODEC_VERSION;
    }

    @Override
    public boolean required() {
        return true;
    }

    @Override
    public byte[] encode(PlayerSaveSnapshot snapshot) {
        try {
            PlayerSaveSnapshot value = Objects.requireNonNull(snapshot, "snapshot");
            return JsonCodecSupport.write(writer -> {
                writer.beginObject();
                writer.name("owner").value(value.owner().id());
                writer.name("feetPositionX").value(value.feetPositionX());
                writer.name("feetPositionY").value(value.feetPositionY());
                writer.name("feetPositionZ").value(value.feetPositionZ());
                writer.name("velocityX").value(value.velocityX());
                writer.name("velocityY").value(value.velocityY());
                writer.name("velocityZ").value(value.velocityZ());
                writer.name("yaw").value(value.yaw());
                writer.name("pitch").value(value.pitch());
                writer.name("gameMode").value(value.gameMode().name());
                writer.name("noclip").value(value.noclip());
                writer.endObject();
            });
        } catch (RuntimeException failure) {
            throw new SaveCodecException(
                    "player.invalid-snapshot",
                    "Invalid player snapshot",
                    failure);
        }
    }

    @Override
    public PlayerSaveSnapshot decode(byte[] bytes) {
        try {
            JsonReader reader = JsonCodecSupport.reader(
                    Objects.requireNonNull(bytes, "bytes"), MAX_PAYLOAD_BYTES);
            PlayerDocument document = readDocument(reader);
            JsonCodecSupport.requireEndDocument(reader);
            return document.toDomain();
        } catch (IOException | RuntimeException failure) {
            throw new SaveCodecException(
                    "player.invalid-payload",
                    "Invalid player payload",
                    failure);
        }
    }

    private static PlayerDocument readDocument(JsonReader reader)
            throws IOException {
        JsonCodecSupport.requireToken(reader, JsonToken.BEGIN_OBJECT, "player object");
        reader.beginObject();
        Set<String> seen = JsonCodecSupport.newFieldSet();
        Integer owner = null;
        Double feetPositionX = null;
        Double feetPositionY = null;
        Double feetPositionZ = null;
        Double velocityX = null;
        Double velocityY = null;
        Double velocityZ = null;
        Double yaw = null;
        Double pitch = null;
        String gameMode = null;
        Boolean noclip = null;
        while (reader.hasNext()) {
            String field = JsonCodecSupport.nextUniqueField(reader, seen);
            switch (field) {
                case "owner" -> owner = JsonCodecSupport.readInt(reader, field);
                case "feetPositionX" ->
                        feetPositionX = JsonCodecSupport.readFiniteDouble(reader, field);
                case "feetPositionY" ->
                        feetPositionY = JsonCodecSupport.readFiniteDouble(reader, field);
                case "feetPositionZ" ->
                        feetPositionZ = JsonCodecSupport.readFiniteDouble(reader, field);
                case "velocityX" ->
                        velocityX = JsonCodecSupport.readFiniteDouble(reader, field);
                case "velocityY" ->
                        velocityY = JsonCodecSupport.readFiniteDouble(reader, field);
                case "velocityZ" ->
                        velocityZ = JsonCodecSupport.readFiniteDouble(reader, field);
                case "yaw" -> yaw = JsonCodecSupport.readFiniteDouble(reader, field);
                case "pitch" -> pitch = JsonCodecSupport.readFiniteDouble(reader, field);
                case "gameMode" -> gameMode = JsonCodecSupport.readString(reader, field, 32);
                case "noclip" -> noclip = JsonCodecSupport.readBoolean(reader, field);
                default -> throw new IllegalArgumentException(
                        "Unknown player field: " + field);
            }
        }
        reader.endObject();
        JsonCodecSupport.requireFields(seen, FIELDS, "player");
        return new PlayerDocument(
                owner,
                feetPositionX,
                feetPositionY,
                feetPositionZ,
                velocityX,
                velocityY,
                velocityZ,
                yaw,
                pitch,
                gameMode,
                noclip);
    }

    private record PlayerDocument(
            int owner,
            double feetPositionX,
            double feetPositionY,
            double feetPositionZ,
            double velocityX,
            double velocityY,
            double velocityZ,
            double yaw,
            double pitch,
            String gameMode,
            boolean noclip) {
        private PlayerSaveSnapshot toDomain() {
            return new PlayerSaveSnapshot(
                    new EntityRef(owner),
                    feetPositionX,
                    feetPositionY,
                    feetPositionZ,
                    velocityX,
                    velocityY,
                    velocityZ,
                    yaw,
                    pitch,
                    GameMode.valueOf(gameMode),
                    noclip);
        }
    }
}

/** Shared strict streaming mechanics for the three v1 canonical JSON codecs. */
final class JsonCodecSupport {
    static final int MAX_RESOURCE_LOCATION_CODE_POINTS = 256;

    private JsonCodecSupport() {
    }

    static JsonReader reader(byte[] bytes, int maxPayloadBytes)
            throws CharacterCodingException {
        if (bytes.length > maxPayloadBytes) {
            throw new IllegalArgumentException("JSON payload exceeds supported size");
        }
        String json = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString();
        JsonReader reader = new JsonReader(new StringReader(json));
        reader.setLenient(false);
        return reader;
    }

    static byte[] write(JsonWriteOperation operation) {
        Objects.requireNonNull(operation, "operation");
        StringWriter characters = new StringWriter();
        try (JsonWriter writer = new JsonWriter(characters)) {
            writer.setLenient(false);
            writer.setSerializeNulls(true);
            operation.write(writer);
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot encode in-memory JSON", failure);
        }
        return characters.toString().getBytes(StandardCharsets.UTF_8);
    }

    static Set<String> newFieldSet() {
        return new java.util.HashSet<>();
    }

    static String nextUniqueField(JsonReader reader, Set<String> seen)
            throws IOException {
        String field = reader.nextName();
        if (!seen.add(field)) {
            throw new IllegalArgumentException("Duplicate JSON field: " + field);
        }
        return field;
    }

    static void requireFields(
            Set<String> actual, Set<String> expected, String documentName) {
        if (!actual.equals(expected)) {
            throw new IllegalArgumentException(
                    documentName + " fields are incomplete");
        }
    }

    static void requireToken(
            JsonReader reader, JsonToken token, String description)
            throws IOException {
        if (reader.peek() != token) {
            throw new IllegalArgumentException(
                    "Expected " + description);
        }
    }

    static int readInt(JsonReader reader, String field) throws IOException {
        long value = readLong(reader, field);
        if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(field + " is outside the int range");
        }
        return (int) value;
    }

    static long readLong(JsonReader reader, String field) throws IOException {
        requireToken(reader, JsonToken.NUMBER, field + " integer");
        String encoded = reader.nextString();
        if (!encoded.matches("-?(0|[1-9][0-9]*)")) {
            throw new IllegalArgumentException(field + " must be an integer");
        }
        try {
            return Long.parseLong(encoded);
        } catch (NumberFormatException failure) {
            throw new IllegalArgumentException(field + " is outside the long range", failure);
        }
    }

    static double readFiniteDouble(JsonReader reader, String field)
            throws IOException {
        requireToken(reader, JsonToken.NUMBER, field + " number");
        double value = reader.nextDouble();
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(field + " must be finite");
        }
        return value;
    }

    static boolean readBoolean(JsonReader reader, String field)
            throws IOException {
        requireToken(reader, JsonToken.BOOLEAN, field + " boolean");
        return reader.nextBoolean();
    }

    static String readString(
            JsonReader reader, String field, int maxCodePoints)
            throws IOException {
        requireToken(reader, JsonToken.STRING, field + " string");
        String value = reader.nextString();
        if (value.codePointCount(0, value.length()) > maxCodePoints) {
            throw new IllegalArgumentException(field + " exceeds supported length");
        }
        return value;
    }

    static void requireEndDocument(JsonReader reader) throws IOException {
        if (reader.peek() != JsonToken.END_DOCUMENT) {
            throw new IllegalArgumentException("JSON payload contains trailing values");
        }
    }

    @FunctionalInterface
    interface JsonWriteOperation {
        void write(JsonWriter writer) throws IOException;
    }
}
