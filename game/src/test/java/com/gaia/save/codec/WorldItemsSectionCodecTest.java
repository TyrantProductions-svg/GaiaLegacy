package com.gaia.save.codec;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.gaia.save.format.SaveSectionId;
import com.gaia.save.snapshot.WorldItemsSaveSnapshot;
import com.overlord.assets.ResourceLocation;
import com.overlord.config.GameConfig;
import com.overlord.interaction.api.EntityRef;
import com.overlord.inventory.api.ItemStack;
import com.overlord.worlditem.api.WorldItemId;
import com.overlord.worlditem.api.WorldItemPhysicalState;
import com.overlord.worlditem.api.WorldItemRestoreEntry;
import com.overlord.worlditem.api.WorldItemRuntimeSnapshot;
import com.overlord.worlditem.api.WorldItemSnapshot;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class WorldItemsSectionCodecTest {
    private static final int MAX_WORLD_ITEMS_PAYLOAD_BYTES = 1024 * 1024;
    private static final EntityRef SOURCE = new EntityRef(5);
    private static final String CANONICAL_JSON =
            "{\"fixedTick\":100,\"nextItemId\":10,\"itemIdsExhausted\":false,\"entries\":["
                    + "{\"id\":2,\"stack\":{\"itemId\":\"gaia:dirt\",\"count\":3},"
                    + "\"positionX\":1.25,\"positionY\":-2.5,\"positionZ\":3.75,"
                    + "\"velocityX\":-0.5,\"velocityY\":0.25,\"velocityZ\":1.5,"
                    + "\"revision\":4,\"source\":5,\"spawnTick\":90,"
                    + "\"pickupAvailableTick\":110,\"physicalState\":\"GROUNDED\"},"
                    + "{\"id\":9,\"stack\":{\"itemId\":\"gaia:stone\",\"count\":1},"
                    + "\"positionX\":9.0,\"positionY\":8.0,\"positionZ\":7.0,"
                    + "\"velocityX\":0.0,\"velocityY\":-1.0,\"velocityZ\":2.0,"
                    + "\"revision\":8,\"source\":null,\"spawnTick\":100,"
                    + "\"pickupAvailableTick\":100,\"physicalState\":\"FROZEN_UNLOADED\"}]}";

    private final WorldItemsSectionCodec codec = new WorldItemsSectionCodec();

    @Test
    void exposesRequiredV1WorldItemsSectionAndExactStableIdOrderedUtf8() {
        WorldItemRestoreEntry low = lowEntry();
        WorldItemRestoreEntry high = highEntry();
        WorldItemsSaveSnapshot shuffled = snapshot(List.of(high, low), 10, false);
        WorldItemsSaveSnapshot sorted = snapshot(List.of(low, high), 10, false);

        byte[] shuffledBytes = codec.encode(shuffled);
        byte[] sortedBytes = codec.encode(sorted);

        assertEquals(SaveSectionId.WORLD_ITEMS, codec.sectionId());
        assertEquals(1, codec.codecVersion());
        assertEquals(true, codec.required());
        assertArrayEquals(CANONICAL_JSON.getBytes(StandardCharsets.UTF_8), shuffledBytes);
        assertArrayEquals(sortedBytes, shuffledBytes);
        assertEquals(sorted, codec.decode(shuffledBytes));
    }

    @Test
    void roundTripPreservesSourceTicksStatesAndAllocatorExhaustion() {
        WorldItemsSaveSnapshot snapshot = fixture();

        WorldItemsSaveSnapshot decoded = codec.decode(codec.encode(snapshot));

        assertEquals(snapshot, decoded);
        assertEquals(Optional.of(SOURCE), decoded.entries().get(0).runtime().source());
        assertEquals(110, decoded.entries().get(0).runtime().pickupAvailableTick(),
                "future pickup availability remains measured in fixed ticks");
        assertEquals(WorldItemPhysicalState.GROUNDED,
                decoded.entries().get(0).physicalState());
        assertEquals(WorldItemPhysicalState.FROZEN_UNLOADED,
                decoded.entries().get(1).physicalState());

        WorldItemsSaveSnapshot exhausted =
                new WorldItemsSaveSnapshot(0, List.of(), Long.MAX_VALUE, true);
        String exhaustedJson =
                "{\"fixedTick\":0,\"nextItemId\":9223372036854775807,"
                        + "\"itemIdsExhausted\":true,\"entries\":[]}";
        assertArrayEquals(
                exhaustedJson.getBytes(StandardCharsets.UTF_8),
                codec.encode(exhausted));
        assertEquals(exhausted, codec.decode(codec.encode(exhausted)));
    }

    @Test
    void encodedAndDecodedValuesDoNotAliasCallerArrays() {
        WorldItemsSaveSnapshot snapshot = fixture();

        byte[] first = codec.encode(snapshot);
        byte[] second = codec.encode(snapshot);
        assertNotSame(first, second);
        assertArrayEquals(first, second);

        WorldItemsSaveSnapshot decoded = codec.decode(second);
        Arrays.fill(second, (byte) 0);
        assertEquals(snapshot, decoded);
        assertFalse(decoded.entries().isEmpty());
    }

    @Test
    void encodeRejectsNullWithStableSectionDiagnostic() {
        assertCodecFailure("world-items.invalid-snapshot", () -> codec.encode(null));
    }

    @Test
    void decodeRejectsMissingDuplicateUnknownFieldsEnumsAndItemShapes() {
        List<String> invalidPayloads = List.of(
                CANONICAL_JSON.replace("\"fixedTick\":100,", ""),
                CANONICAL_JSON.replace(
                        "{\"fixedTick\":100,",
                        "{\"fixedTick\":100,\"fixedTick\":101,"),
                CANONICAL_JSON.substring(0, CANONICAL_JSON.length() - 1)
                        + ",\"physicsBody\":7}",
                CANONICAL_JSON.replace(
                        "\"id\":2", "\"id\":2,\"id\":3"),
                CANONICAL_JSON.replace(
                        "\"itemId\":\"gaia:dirt\"",
                        "\"itemId\":\"gaia:dirt\",\"itemId\":\"gaia:stone\""),
                CANONICAL_JSON.replace(
                        "\"count\":3", "\"count\":3,\"reservationId\":1"),
                CANONICAL_JSON.replace(
                        "\"physicalState\":\"GROUNDED\"",
                        "\"physicalState\":\"FLYING\""),
                CANONICAL_JSON.replace(
                        "\"itemId\":\"gaia:dirt\"", "\"itemId\":\"dirt\""),
                CANONICAL_JSON.replace(
                        "\"itemId\":\"gaia:dirt\"", "\"itemId\":{}"),
                CANONICAL_JSON.replace(
                        "\"itemId\":\"gaia:dirt\"",
                        "\"itemId\":\"gaia:" + "a".repeat(257) + "\""),
                CANONICAL_JSON.replace(
                        "{\"itemId\":\"gaia:dirt\",\"count\":3}", "[]"),
                "[]",
                "null");

        for (String payload : invalidPayloads) {
            assertInvalidPayload(payload);
        }
    }

    @Test
    void decodeRejectsDuplicateIdsNonfiniteMotionAndInvalidTicksAllocatorOrRevision() {
        List<String> invalidPayloads = List.of(
                CANONICAL_JSON.replace("\"id\":9", "\"id\":2"),
                CANONICAL_JSON.replace("\"id\":2", "\"id\":-1"),
                CANONICAL_JSON.replace("\"positionX\":1.25", "\"positionX\":1e309"),
                CANONICAL_JSON.replace("\"velocityZ\":1.5", "\"velocityZ\":-1e309"),
                CANONICAL_JSON.replace("\"revision\":4", "\"revision\":-1"),
                CANONICAL_JSON.replace("\"source\":5", "\"source\":-1"),
                CANONICAL_JSON.replace("\"fixedTick\":100", "\"fixedTick\":-1"),
                CANONICAL_JSON.replace("\"nextItemId\":10", "\"nextItemId\":-1"),
                CANONICAL_JSON.replace("\"nextItemId\":10", "\"nextItemId\":9"),
                CANONICAL_JSON.replace(
                        "\"itemIdsExhausted\":false", "\"itemIdsExhausted\":true"),
                CANONICAL_JSON.replace("\"spawnTick\":100", "\"spawnTick\":101"),
                CANONICAL_JSON.replace(
                        "\"pickupAvailableTick\":110", "\"pickupAvailableTick\":89"),
                CANONICAL_JSON.replace("\"count\":3", "\"count\":0"));

        for (String payload : invalidPayloads) {
            assertInvalidPayload(payload);
        }
    }

    @Test
    void decodeRejectsCollectionBeyondRuntimeCapacityBeforeDomainPublication() {
        StringBuilder json = new StringBuilder(
                "{\"fixedTick\":0,\"nextItemId\":1025,"
                        + "\"itemIdsExhausted\":false,\"entries\":[");
        for (int id = 0; id <= GameConfig.Interaction.MAX_LOGICAL_WORLD_ITEMS; id++) {
            if (id > 0) {
                json.append(',');
            }
            json.append("{\"id\":").append(id)
                    .append(",\"stack\":{\"itemId\":\"gaia:dirt\",\"count\":1},")
                    .append("\"positionX\":0.0,\"positionY\":0.0,\"positionZ\":0.0,")
                    .append("\"velocityX\":0.0,\"velocityY\":0.0,\"velocityZ\":0.0,")
                    .append("\"revision\":0,\"source\":null,\"spawnTick\":0,")
                    .append("\"pickupAvailableTick\":0,\"physicalState\":\"ACTIVE\"}");
        }
        json.append("]}");

        assertInvalidPayload(json.toString());
    }

    @Test
    void decodeRejectsMalformedUtf8TrailingValuesOversizedInputAndDepth() {
        assertCodecFailure(
                "world-items.invalid-payload",
                () -> codec.decode("{\"fixedTick\":".getBytes(StandardCharsets.UTF_8)));
        assertCodecFailure(
                "world-items.invalid-payload",
                () -> codec.decode((CANONICAL_JSON + "{}").getBytes(StandardCharsets.UTF_8)));
        assertCodecFailure(
                "world-items.invalid-payload",
                () -> codec.decode(new byte[] {'{', '"', (byte) 0xc3, (byte) 0x28, '"', ':'}));
        assertCodecFailure(
                "world-items.invalid-payload",
                () -> codec.decode(new byte[MAX_WORLD_ITEMS_PAYLOAD_BYTES + 1]));
        assertCodecFailure(
                "world-items.invalid-payload",
                () -> codec.decode(("[".repeat(65) + "0" + "]".repeat(65))
                        .getBytes(StandardCharsets.UTF_8)));
        assertCodecFailure("world-items.invalid-payload", () -> codec.decode(null));
    }

    @Test
    void canonicalJsonContainsNoRuntimeProjectionOrJavaIdentity() {
        String json = new String(codec.encode(fixture()), StandardCharsets.UTF_8);

        for (String forbidden : List.of(
                "com.gaia",
                "com.overlord",
                "PhysicsBody",
                "reservation",
                "runtimeHandle",
                "C:\\\\",
                "/Users/")) {
            assertFalse(json.contains(forbidden), () -> "forbidden JSON content: " + forbidden);
        }
    }

    private static WorldItemsSaveSnapshot fixture() {
        return snapshot(List.of(highEntry(), lowEntry()), 10, false);
    }

    private static WorldItemsSaveSnapshot snapshot(
            List<WorldItemRestoreEntry> entries,
            long nextItemId,
            boolean exhausted) {
        return new WorldItemsSaveSnapshot(100, entries, nextItemId, exhausted);
    }

    private static WorldItemRestoreEntry lowEntry() {
        return entry(
                2,
                "gaia:dirt",
                3,
                1.25,
                -2.5,
                3.75,
                -0.5,
                0.25,
                1.5,
                4,
                Optional.of(SOURCE),
                90,
                110,
                WorldItemPhysicalState.GROUNDED);
    }

    private static WorldItemRestoreEntry highEntry() {
        return entry(
                9,
                "gaia:stone",
                1,
                9.0,
                8.0,
                7.0,
                0.0,
                -1.0,
                2.0,
                8,
                Optional.empty(),
                100,
                100,
                WorldItemPhysicalState.FROZEN_UNLOADED);
    }

    private static WorldItemRestoreEntry entry(
            long id,
            String itemId,
            int count,
            double positionX,
            double positionY,
            double positionZ,
            double velocityX,
            double velocityY,
            double velocityZ,
            long revision,
            Optional<EntityRef> source,
            long spawnTick,
            long pickupAvailableTick,
            WorldItemPhysicalState physicalState) {
        WorldItemSnapshot item = new WorldItemSnapshot(
                new WorldItemId(id),
                new ItemStack(ResourceLocation.parse(itemId), count),
                positionX,
                positionY,
                positionZ,
                velocityX,
                velocityY,
                velocityZ,
                revision);
        return new WorldItemRestoreEntry(
                new WorldItemRuntimeSnapshot(
                        item, source, spawnTick, pickupAvailableTick),
                physicalState);
    }

    private void assertInvalidPayload(String payload) {
        assertCodecFailure(
                "world-items.invalid-payload",
                () -> codec.decode(payload.getBytes(StandardCharsets.UTF_8)));
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
