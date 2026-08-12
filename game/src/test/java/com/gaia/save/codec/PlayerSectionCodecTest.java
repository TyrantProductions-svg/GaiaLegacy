package com.gaia.save.codec;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.gaia.interaction.GameMode;
import com.gaia.save.format.SaveSectionId;
import com.gaia.save.snapshot.PlayerSaveSnapshot;
import com.overlord.interaction.api.EntityRef;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class PlayerSectionCodecTest {
    private static final int MAX_PLAYER_PAYLOAD_BYTES = 16 * 1024;
    private static final EntityRef OWNER = new EntityRef(7);
    private static final String CANONICAL_JSON =
            "{\"owner\":7,"
                    + "\"feetPositionX\":1.25,"
                    + "\"feetPositionY\":-2.5,"
                    + "\"feetPositionZ\":3.75,"
                    + "\"velocityX\":-4.5,"
                    + "\"velocityY\":5.25,"
                    + "\"velocityZ\":-6.75,"
                    + "\"yaw\":1080.25,"
                    + "\"pitch\":-89.0,"
                    + "\"gameMode\":\"CREATIVE\","
                    + "\"noclip\":true}";

    private final PlayerSectionCodec codec = new PlayerSectionCodec();

    @Test
    void exposesRequiredV1PlayerSectionAndExactCanonicalUtf8() {
        PlayerSaveSnapshot snapshot = snapshot(1080.25, -89.0);

        byte[] encoded = codec.encode(snapshot);

        assertEquals(SaveSectionId.PLAYER, codec.sectionId());
        assertEquals(1, codec.codecVersion());
        assertEquals(true, codec.required());
        assertArrayEquals(CANONICAL_JSON.getBytes(StandardCharsets.UTF_8), encoded);
        assertEquals(snapshot, codec.decode(encoded));
    }

    @Test
    void preservesArbitraryFiniteYawExactPitchBoundsModeAndNoclip() {
        for (PlayerSaveSnapshot snapshot : List.of(
                snapshot(-Double.MAX_VALUE, -89.0),
                snapshot(0.0, 89.0),
                snapshot(Double.MAX_VALUE, 0.0))) {
            assertEquals(snapshot, codec.decode(codec.encode(snapshot)));
        }
    }

    @Test
    void encodedAndDecodedValuesDoNotAliasCallerArrays() {
        PlayerSaveSnapshot snapshot = snapshot(1080.25, -89.0);

        byte[] first = codec.encode(snapshot);
        byte[] second = codec.encode(snapshot);
        assertNotSame(first, second);
        first[0] = '[';
        assertArrayEquals(CANONICAL_JSON.getBytes(StandardCharsets.UTF_8), second);

        PlayerSaveSnapshot decoded = codec.decode(second);
        Arrays.fill(second, (byte) 0);
        assertEquals(snapshot, decoded);
    }

    @Test
    void encodeRejectsNullWithStableSectionDiagnostic() {
        assertCodecFailure("player.invalid-snapshot", () -> codec.encode(null));
    }

    @Test
    void decodeRejectsMissingNullDuplicateUnknownAndWronglyTypedFields() {
        List<String> invalidPayloads = List.of(
                CANONICAL_JSON.replace("\"owner\":7,", ""),
                CANONICAL_JSON.replace("\"owner\":7", "\"owner\":null"),
                CANONICAL_JSON.replace(
                        "{\"owner\":7,", "{\"owner\":7,\"owner\":8,"),
                CANONICAL_JSON.substring(0, CANONICAL_JSON.length() - 1)
                        + ",\"runtimeHandle\":1}",
                CANONICAL_JSON.replace("\"owner\":7", "\"owner\":1.5"),
                CANONICAL_JSON.replace("\"feetPositionX\":1.25", "\"feetPositionX\":\"1.25\""),
                CANONICAL_JSON.replace("\"noclip\":true", "\"noclip\":1"),
                "[]",
                "null");

        for (String payload : invalidPayloads) {
            assertInvalidPayload(payload);
        }
    }

    @Test
    void decodeRejectsUnknownEnumsNonfiniteNumbersAndPitchOutsideCameraBounds() {
        List<String> invalidPayloads = List.of(
                CANONICAL_JSON.replace("\"gameMode\":\"CREATIVE\"", "\"gameMode\":\"SPECTATOR\""),
                CANONICAL_JSON.replace("\"feetPositionX\":1.25", "\"feetPositionX\":1e309"),
                CANONICAL_JSON.replace("\"velocityY\":5.25", "\"velocityY\":-1e309"),
                CANONICAL_JSON.replace("\"yaw\":1080.25", "\"yaw\":1e309"),
                CANONICAL_JSON.replace("\"pitch\":-89.0", "\"pitch\":-89.0001"),
                CANONICAL_JSON.replace("\"pitch\":-89.0", "\"pitch\":89.0001"),
                CANONICAL_JSON.replace("\"owner\":7", "\"owner\":-1"));

        for (String payload : invalidPayloads) {
            assertInvalidPayload(payload);
        }
    }

    @Test
    void decodeRejectsMalformedUtf8TrailingValuesAndOversizedOrDeepInput() {
        assertCodecFailure(
                "player.invalid-payload",
                () -> codec.decode("{\"owner\":".getBytes(StandardCharsets.UTF_8)));
        assertCodecFailure(
                "player.invalid-payload",
                () -> codec.decode((CANONICAL_JSON + "{}").getBytes(StandardCharsets.UTF_8)));
        assertCodecFailure(
                "player.invalid-payload",
                () -> codec.decode(malformedUtf8()));
        assertCodecFailure(
                "player.invalid-payload",
                () -> codec.decode(new byte[MAX_PLAYER_PAYLOAD_BYTES + 1]));
        assertCodecFailure(
                "player.invalid-payload",
                () -> codec.decode(("[".repeat(65) + "0" + "]".repeat(65))
                        .getBytes(StandardCharsets.UTF_8)));
        assertCodecFailure("player.invalid-payload", () -> codec.decode(null));
    }

    private static PlayerSaveSnapshot snapshot(double yaw, double pitch) {
        return new PlayerSaveSnapshot(
                OWNER,
                1.25,
                -2.5,
                3.75,
                -4.5,
                5.25,
                -6.75,
                yaw,
                pitch,
                GameMode.CREATIVE,
                true);
    }

    private void assertInvalidPayload(String payload) {
        assertCodecFailure(
                "player.invalid-payload",
                () -> codec.decode(payload.getBytes(StandardCharsets.UTF_8)));
    }

    private static byte[] malformedUtf8() {
        byte[] prefix = "{\"owner\":\"".getBytes(StandardCharsets.UTF_8);
        byte[] suffix = "\"}".getBytes(StandardCharsets.UTF_8);
        byte[] result = Arrays.copyOf(prefix, prefix.length + 2 + suffix.length);
        result[prefix.length] = (byte) 0xc3;
        result[prefix.length + 1] = (byte) 0x28;
        System.arraycopy(suffix, 0, result, prefix.length + 2, suffix.length);
        return result;
    }

    private static void assertCodecFailure(
            String expectedCode, ThrowingOperation operation) {
        SaveCodecException failure =
                assertThrows(SaveCodecException.class, operation::run);
        assertEquals(expectedCode, failure.code());
        assertNotNull(failure.getCause());
    }

    @FunctionalInterface
    private interface ThrowingOperation {
        void run();
    }
}
