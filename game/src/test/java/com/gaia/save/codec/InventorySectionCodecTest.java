package com.gaia.save.codec;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.gaia.save.format.SaveSectionId;
import com.gaia.save.snapshot.InventorySaveSnapshot;
import com.overlord.assets.ResourceLocation;
import com.overlord.interaction.api.EntityRef;
import com.overlord.inventory.api.BodySlot;
import com.overlord.inventory.api.ItemStack;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class InventorySectionCodecTest {
    private static final int MAX_INVENTORY_PAYLOAD_BYTES = 64 * 1024;
    private static final EntityRef OWNER = new EntityRef(7);
    private static final ItemStack DIRT = stack("gaia:dirt", 3);
    private static final ItemStack STONE = stack("gaia:stone", 2);
    private static final ItemStack LEAVES = stack("gaia:leaves", 1);
    private static final String CANONICAL_JSON =
            "{\"owner\":7,"
                    + "\"revision\":9,"
                    + "\"activeSlot\":\"RIGHT_HAND\","
                    + "\"twoHandedHandsOccupied\":false,"
                    + "\"slots\":["
                    + "{\"slot\":\"LEFT_HAND\",\"stack\":{\"itemId\":\"gaia:dirt\",\"count\":3}},"
                    + "{\"slot\":\"RIGHT_HAND\",\"stack\":{\"itemId\":\"gaia:stone\",\"count\":2}},"
                    + "{\"slot\":\"MOUTH\",\"stack\":{\"itemId\":\"gaia:leaves\",\"count\":1}}]}";

    private final InventorySectionCodec codec = new InventorySectionCodec();

    @Test
    void exposesRequiredV1InventorySectionAndExactCanonicalSlotOrderUtf8() {
        Map<BodySlot, ItemStack> shuffled = new LinkedHashMap<>();
        shuffled.put(BodySlot.MOUTH, LEAVES);
        shuffled.put(BodySlot.RIGHT_HAND, STONE);
        shuffled.put(BodySlot.LEFT_HAND, DIRT);
        InventorySaveSnapshot snapshot =
                snapshot(shuffled, BodySlot.RIGHT_HAND, false, 9);

        byte[] encoded = codec.encode(snapshot);

        assertEquals(SaveSectionId.INVENTORY, codec.sectionId());
        assertEquals(1, codec.codecVersion());
        assertEquals(true, codec.required());
        assertArrayEquals(CANONICAL_JSON.getBytes(StandardCharsets.UTF_8), encoded);
        assertEquals(snapshot, codec.decode(encoded));
    }

    @Test
    void roundTripPreservesEveryEmptySlotActiveSlotRevisionAndTwoHandedShape() {
        InventorySaveSnapshot empty =
                snapshot(Map.of(), BodySlot.MOUTH, false, 0);
        String emptyJson =
                "{\"owner\":7,\"revision\":0,\"activeSlot\":\"MOUTH\","
                        + "\"twoHandedHandsOccupied\":false,\"slots\":["
                        + "{\"slot\":\"LEFT_HAND\",\"stack\":null},"
                        + "{\"slot\":\"RIGHT_HAND\",\"stack\":null},"
                        + "{\"slot\":\"MOUTH\",\"stack\":null}]}";
        InventorySaveSnapshot twoHanded = snapshot(
                Map.of(BodySlot.LEFT_HAND, stack("gaia:heavy", 1)),
                BodySlot.LEFT_HAND,
                true,
                Long.MAX_VALUE);
        String twoHandedJson =
                "{\"owner\":7,\"revision\":9223372036854775807,"
                        + "\"activeSlot\":\"LEFT_HAND\","
                        + "\"twoHandedHandsOccupied\":true,\"slots\":["
                        + "{\"slot\":\"LEFT_HAND\",\"stack\":{\"itemId\":\"gaia:heavy\",\"count\":1}},"
                        + "{\"slot\":\"RIGHT_HAND\",\"stack\":null},"
                        + "{\"slot\":\"MOUTH\",\"stack\":null}]}";

        assertArrayEquals(emptyJson.getBytes(StandardCharsets.UTF_8), codec.encode(empty));
        assertEquals(empty, codec.decode(codec.encode(empty)));
        assertArrayEquals(twoHandedJson.getBytes(StandardCharsets.UTF_8), codec.encode(twoHanded));
        assertEquals(twoHanded, codec.decode(codec.encode(twoHanded)));
    }

    @Test
    void equivalentMapsEncodeIdenticallyAndArraysRemainCallerOwned() {
        Map<BodySlot, ItemStack> firstMap = new HashMap<>();
        firstMap.put(BodySlot.LEFT_HAND, DIRT);
        firstMap.put(BodySlot.RIGHT_HAND, STONE);
        firstMap.put(BodySlot.MOUTH, LEAVES);
        Map<BodySlot, ItemStack> secondMap = new LinkedHashMap<>();
        secondMap.put(BodySlot.MOUTH, LEAVES);
        secondMap.put(BodySlot.LEFT_HAND, DIRT);
        secondMap.put(BodySlot.RIGHT_HAND, STONE);

        byte[] first = codec.encode(
                snapshot(firstMap, BodySlot.RIGHT_HAND, false, 9));
        byte[] second = codec.encode(
                snapshot(secondMap, BodySlot.RIGHT_HAND, false, 9));

        assertNotSame(first, second);
        assertArrayEquals(first, second);
        InventorySaveSnapshot decoded = codec.decode(second);
        Arrays.fill(second, (byte) 0);
        assertEquals(
                snapshot(firstMap, BodySlot.RIGHT_HAND, false, 9),
                decoded);
    }

    @Test
    void encodeRejectsNullAndImpossibleTwoHandedDirectSlotState() {
        assertCodecFailure("inventory.invalid-snapshot", () -> codec.encode(null));
        assertCodecFailure(
                "inventory.invalid-snapshot",
                () -> codec.encode(snapshot(Map.of(), BodySlot.LEFT_HAND, true, 0)));
        assertCodecFailure(
                "inventory.invalid-snapshot",
                () -> codec.encode(snapshot(
                        Map.of(
                                BodySlot.LEFT_HAND, stack("gaia:heavy", 1),
                                BodySlot.RIGHT_HAND, stack("gaia:heavy", 1)),
                        BodySlot.LEFT_HAND,
                        true,
                        0)));
    }

    @Test
    void decodeRejectsMissingDuplicateUnknownSlotsFieldsAndEnums() {
        String missingMouth = CANONICAL_JSON.replace(
                ",{\"slot\":\"MOUTH\",\"stack\":{\"itemId\":\"gaia:leaves\",\"count\":1}}",
                "");
        String duplicateLeft = CANONICAL_JSON.replace(
                "{\"slot\":\"MOUTH\",\"stack\":{\"itemId\":\"gaia:leaves\",\"count\":1}}",
                "{\"slot\":\"LEFT_HAND\",\"stack\":{\"itemId\":\"gaia:leaves\",\"count\":1}}");
        List<String> invalidPayloads = List.of(
                CANONICAL_JSON.replace("\"owner\":7,", ""),
                CANONICAL_JSON.replace(
                        "{\"owner\":7,", "{\"owner\":7,\"owner\":8,"),
                CANONICAL_JSON.substring(0, CANONICAL_JSON.length() - 1)
                        + ",\"reservationId\":4}",
                CANONICAL_JSON.replace("\"activeSlot\":\"RIGHT_HAND\"", "\"activeSlot\":\"TAIL\""),
                CANONICAL_JSON.replace("\"slot\":\"MOUTH\"", "\"slot\":\"TAIL\""),
                missingMouth,
                duplicateLeft,
                CANONICAL_JSON.replace(
                        "\"slot\":\"LEFT_HAND\"",
                        "\"slot\":\"LEFT_HAND\",\"slot\":\"RIGHT_HAND\""),
                CANONICAL_JSON.replace(
                        "\"itemId\":\"gaia:dirt\"",
                        "\"itemId\":\"gaia:dirt\",\"itemId\":\"gaia:stone\""),
                CANONICAL_JSON.replace(
                        "\"count\":3", "\"count\":3,\"runtimeHandle\":1"),
                "[]",
                "null");

        for (String payload : invalidPayloads) {
            assertInvalidPayload(payload);
        }
    }

    @Test
    void decodeRejectsInvalidOwnersRevisionsItemShapesCountsAndTwoHandedState() {
        String emptySlots =
                "[{\"slot\":\"LEFT_HAND\",\"stack\":null},"
                        + "{\"slot\":\"RIGHT_HAND\",\"stack\":null},"
                        + "{\"slot\":\"MOUTH\",\"stack\":null}]";
        String invalidTwoHandedEmpty =
                "{\"owner\":7,\"revision\":0,\"activeSlot\":\"LEFT_HAND\","
                        + "\"twoHandedHandsOccupied\":true,\"slots\":" + emptySlots + "}";
        String invalidTwoHandedRight = CANONICAL_JSON.replace(
                "\"twoHandedHandsOccupied\":false",
                "\"twoHandedHandsOccupied\":true");
        List<String> invalidPayloads = List.of(
                CANONICAL_JSON.replace("\"owner\":7", "\"owner\":-1"),
                CANONICAL_JSON.replace("\"owner\":7", "\"owner\":1.5"),
                CANONICAL_JSON.replace("\"revision\":9", "\"revision\":-1"),
                CANONICAL_JSON.replace("\"count\":3", "\"count\":0"),
                CANONICAL_JSON.replace("\"count\":3", "\"count\":2147483648"),
                CANONICAL_JSON.replace("\"itemId\":\"gaia:dirt\"", "\"itemId\":\"dirt\""),
                CANONICAL_JSON.replace("\"itemId\":\"gaia:dirt\"", "\"itemId\":\"gaia:../dirt\""),
                CANONICAL_JSON.replace("\"itemId\":\"gaia:dirt\"", "\"itemId\":{}"),
                CANONICAL_JSON.replace(
                        "\"itemId\":\"gaia:dirt\"",
                        "\"itemId\":\"gaia:" + "a".repeat(257) + "\""),
                CANONICAL_JSON.replace(
                        "{\"itemId\":\"gaia:dirt\",\"count\":3}",
                        "[]"),
                invalidTwoHandedEmpty,
                invalidTwoHandedRight);

        for (String payload : invalidPayloads) {
            assertInvalidPayload(payload);
        }
    }

    @Test
    void decodeRejectsMalformedUtf8TrailingValuesOversizedInputAndDepth() {
        assertCodecFailure(
                "inventory.invalid-payload",
                () -> codec.decode("{\"owner\":".getBytes(StandardCharsets.UTF_8)));
        assertCodecFailure(
                "inventory.invalid-payload",
                () -> codec.decode((CANONICAL_JSON + "{}").getBytes(StandardCharsets.UTF_8)));
        assertCodecFailure(
                "inventory.invalid-payload",
                () -> codec.decode(new byte[] {'{', '"', (byte) 0xc3, (byte) 0x28, '"', ':'}));
        assertCodecFailure(
                "inventory.invalid-payload",
                () -> codec.decode(new byte[MAX_INVENTORY_PAYLOAD_BYTES + 1]));
        assertCodecFailure(
                "inventory.invalid-payload",
                () -> codec.decode(("[".repeat(65) + "0" + "]".repeat(65))
                        .getBytes(StandardCharsets.UTF_8)));
        assertCodecFailure("inventory.invalid-payload", () -> codec.decode(null));
    }

    private static InventorySaveSnapshot snapshot(
            Map<BodySlot, ItemStack> stacks,
            BodySlot activeSlot,
            boolean twoHanded,
            long revision) {
        return new InventorySaveSnapshot(
                OWNER, stacks, activeSlot, twoHanded, revision);
    }

    private static ItemStack stack(String itemId, int count) {
        return new ItemStack(ResourceLocation.parse(itemId), count);
    }

    private void assertInvalidPayload(String payload) {
        assertCodecFailure(
                "inventory.invalid-payload",
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
