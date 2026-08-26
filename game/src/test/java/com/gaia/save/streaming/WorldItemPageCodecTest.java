package com.gaia.save.streaming;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gaia.save.format.SaveGameId;
import com.overlord.assets.ResourceLocation;
import com.overlord.interaction.api.EntityRef;
import com.overlord.inventory.api.ItemStack;
import com.overlord.voxel.ChunkCoordinatePolicy;
import com.overlord.voxel.ChunkKey;
import com.overlord.worlditem.api.SaveIdentity;
import com.overlord.worlditem.api.WorldItemId;
import com.overlord.worlditem.api.WorldItemPageSnapshot;
import com.overlord.worlditem.api.WorldItemPhysicalState;
import com.overlord.worlditem.api.WorldItemRestoreEntry;
import com.overlord.worlditem.api.WorldItemRuntimeSnapshot;
import com.overlord.worlditem.api.WorldItemSnapshot;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class WorldItemPageCodecTest {
    private static final SaveGameId SAVE_ID =
            SaveGameId.parse("123e4567-e89b-12d3-a456-426614174000");
    private static final ChunkKey KEY = new ChunkKey(
            -2, ChunkCoordinatePolicy.MAX_SAFE_CHUNK_COORDINATE);
    private static final SaveIdentity ENGINE_SAVE =
            new SaveIdentity(UUID.fromString(SAVE_ID.value()));

    private final WorldItemPageCodec codec = new WorldItemPageCodec(SAVE_ID);

    @Test
    void exactExpiryIsPartOfTheCanonicalGlwpEntryAndSurvivesDetachedRoundTrip() {
        WorldItemPageCodec ttlCodec = new WorldItemPageCodec();
        WorldItemRestoreEntry source = entry(7L, WorldItemPhysicalState.SLEEPING);
        WorldItemPageSnapshot page = new WorldItemPageSnapshot(KEY, 5L, List.of(source));

        byte[] encoded = ttlCodec.encode(ENGINE_SAVE, page);
        WorldItemPageSnapshot decoded = ttlCodec.decode(ENGINE_SAVE, KEY, encoded);

        assertEquals(18_021L,
                decoded.entries().get(0).runtime().expiresAtWorldTick());
        assertEquals(page, decoded);
        encoded[encoded.length - 1] ^= 1;
        assertThrows(IllegalArgumentException.class,
                () -> ttlCodec.decode(ENGINE_SAVE, KEY, encoded));
    }

    @Test
    void runtimeRejectsExpiryBeforePickupAndForgedWireFailsClosed() {
        WorldItemSnapshot item = entry(9L, WorldItemPhysicalState.ACTIVE).runtime().item();
        assertThrows(IllegalArgumentException.class, () -> new WorldItemRuntimeSnapshot(
                item, Optional.empty(), 100L, 100L, 99L));
        assertThrows(IllegalArgumentException.class, () -> new WorldItemRuntimeSnapshot(
                item, Optional.empty(), 100L, 120L, 119L));

        byte[] forged = codec.encode(new WorldItemPageSnapshot(
                KEY, 1L, List.of(entry(9L, WorldItemPhysicalState.ACTIVE))));
        int bodyOffset = 48;
        ByteBuffer buffer = ByteBuffer.wrap(forged).order(ByteOrder.BIG_ENDIAN);
        int resourceLength = buffer.getInt(bodyOffset + Long.BYTES);
        int spawnOffset = bodyOffset
                + Long.BYTES
                + Integer.BYTES + resourceLength
                + Integer.BYTES
                + (6 * Double.BYTES)
                + Long.BYTES
                + 1 + Integer.BYTES;
        long pickupTick = buffer.getLong(spawnOffset + Long.BYTES);
        buffer.putLong(spawnOffset + (2 * Long.BYTES), pickupTick - 1L);
        refreshChecksum(forged);
        assertClosed(forged, "world-item-page.invalid-payload");

        WorldItemRuntimeSnapshot saturated = new WorldItemRuntimeSnapshot(
                item, Optional.empty(), Long.MAX_VALUE - 1L,
                Long.MAX_VALUE, Long.MAX_VALUE);
        assertEquals(Long.MAX_VALUE, saturated.expiresAtWorldTick());
    }

    @Test
    void glwpV1LiteralPrefixCarriesCheckedIdentityNegativeBoundaryKeyAndRevision() {
        WorldItemPageSnapshot page = new WorldItemPageSnapshot(
                KEY, 5L, List.of(entry(7L, WorldItemPhysicalState.SLEEPING)));

        byte[] encoded = codec.encode(page);

        assertEquals(
                "474c5750" // GLWP
                        + "00000001"
                        + "123e4567e89b12d3a456426614174000"
                        + "fffffffe"
                        + "07ffffff"
                        + "0000000000000005"
                        + "00000001",
                HexFormat.of().formatHex(Arrays.copyOf(encoded, 44)));
        assertEquals("GLWP", new String(encoded, 0, 4, java.nio.charset.StandardCharsets.US_ASCII));

        WorldItemPageCodec.DecodeResult decoded = codec.decode(encoded);
        assertEquals(WorldItemPageCodec.DecodeResult.Status.VALID, decoded.status());
        assertEquals(page, decoded.page().orElseThrow());
        assertTrue(decoded.diagnostics().isEmpty());
    }

    @Test
    void allPhysicalStatesAndFullRuntimeFieldsRoundTripInStableIdOrder() {
        List<WorldItemRestoreEntry> reversed = new ArrayList<>();
        reversed.add(entry(Long.MAX_VALUE, WorldItemPhysicalState.FROZEN_UNLOADED));
        reversed.add(entry(100L, WorldItemPhysicalState.SLEEPING));
        reversed.add(entry(7L, WorldItemPhysicalState.GROUNDED));
        reversed.add(entry(0L, WorldItemPhysicalState.ACTIVE));
        WorldItemPageSnapshot page = new WorldItemPageSnapshot(KEY, 19L, reversed);

        byte[] encoded = codec.encode(page);
        WorldItemPageSnapshot decoded = codec.decode(encoded).page().orElseThrow();

        assertEquals(List.of(0L, 7L, 100L, Long.MAX_VALUE), decoded.entries().stream()
                .map(value -> value.runtime().item().id().value())
                .toList());
        assertEquals(List.of(
                        WorldItemPhysicalState.ACTIVE,
                        WorldItemPhysicalState.GROUNDED,
                        WorldItemPhysicalState.SLEEPING,
                        WorldItemPhysicalState.FROZEN_UNLOADED),
                decoded.entries().stream().map(WorldItemRestoreEntry::physicalState).toList());
        assertEquals(Optional.of(new EntityRef(41)),
                decoded.entries().get(0).runtime().source());
        assertEquals(ResourceLocation.of("gaia", "test/item-0"),
                decoded.entries().get(0).runtime().item().stack().itemId());
        assertEquals(3, decoded.entries().get(0).runtime().item().stack().count());
        assertEquals(-0.25d, decoded.entries().get(0).runtime().item().positionX());
        assertEquals(16.5d, decoded.entries().get(0).runtime().item().positionZ());
        assertEquals(0.125d, decoded.entries().get(0).runtime().item().velocityX());
        assertEquals(13L, decoded.entries().get(0).runtime().item().revision());
        assertEquals(21L, decoded.entries().get(0).runtime().spawnTick());
        assertEquals(23L, decoded.entries().get(0).runtime().pickupAvailableTick());
        assertEquals(18_021L, decoded.entries().get(0).runtime().expiresAtWorldTick());
    }

    @Test
    void encodingIsDeterministicAndAllReturnedBytesAndListsAreDetached() {
        List<WorldItemRestoreEntry> source = new ArrayList<>(List.of(
                entry(100L, WorldItemPhysicalState.GROUNDED),
                entry(7L, WorldItemPhysicalState.ACTIVE)));
        WorldItemPageSnapshot page = new WorldItemPageSnapshot(KEY, 3L, source);

        byte[] first = codec.encode(page);
        byte[] second = codec.encode(page);
        assertArrayEquals(first, second);
        assertNotSame(first, second);

        source.clear();
        first[0] = 'X';
        byte[] third = codec.encode(page);
        assertEquals('G', third[0]);
        WorldItemPageSnapshot decoded = codec.decode(third).page().orElseThrow();
        assertThrows(UnsupportedOperationException.class,
                () -> decoded.entries().add(entry(9L, WorldItemPhysicalState.ACTIVE)));
    }

    @Test
    void duplicateIdsAreRejectedAndCanonicalDecodeRejectsReorderedEntries() {
        WorldItemRestoreEntry duplicate = entry(7L, WorldItemPhysicalState.ACTIVE);
        assertThrows(IllegalArgumentException.class, () -> codec.encode(
                new WorldItemPageSnapshot(KEY, 1L, List.of(duplicate, duplicate))));

        byte[] encoded = codec.encode(new WorldItemPageSnapshot(
                KEY,
                1L,
                List.of(entry(7L, WorldItemPhysicalState.ACTIVE),
                        entry(8L, WorldItemPhysicalState.SLEEPING))));
        byte[] noncanonical = swapCanonicalEntryBodies(encoded);
        assertClosed(noncanonical, "world-item-page.noncanonical-order");
    }

    @Test
    void wrongSaveAndUnsafeKeyFailClosedWithoutPublishingAPartialPage() {
        byte[] encoded = codec.encode(new WorldItemPageSnapshot(
                KEY, 1L, List.of(entry(7L, WorldItemPhysicalState.ACTIVE))));

        WorldItemPageCodec foreign = new WorldItemPageCodec(
                SaveGameId.parse("00000000-0000-0000-0000-000000000001"));
        assertClosed(foreign.decode(encoded), "world-item-page.wrong-save");

        byte[] unsafeKey = encoded.clone();
        ByteBuffer.wrap(unsafeKey).order(ByteOrder.BIG_ENDIAN)
                .putInt(24, ChunkCoordinatePolicy.MAX_SAFE_CHUNK_COORDINATE + 1);
        assertClosed(unsafeKey, "world-item-page.invalid-key");
    }

    @Test
    void corruptionTruncationTrailingBytesAndOversizeAlwaysFailClosed() {
        byte[] valid = codec.encode(new WorldItemPageSnapshot(
                KEY, 1L, List.of(entry(7L, WorldItemPhysicalState.FROZEN_UNLOADED))));
        byte[] corrupt = valid.clone();
        corrupt[corrupt.length - 1] ^= 1;
        assertClosed(corrupt, "world-item-page.checksum-mismatch");
        assertClosed(concat(valid, new byte[] {1}), "world-item-page.trailing-bytes");
        assertClosed(
                new byte[Math.toIntExact(WorldItemPageCodec.MAX_FILE_BYTES + 1L)],
                "world-item-page.file-size-limit");

        for (int length = 0; length < valid.length; length++) {
            WorldItemPageCodec.DecodeResult result = codec.decode(Arrays.copyOf(valid, length));
            assertEquals(WorldItemPageCodec.DecodeResult.Status.CORRUPT,
                    result.status(), "length=" + length);
            assertTrue(result.page().isEmpty(), "length=" + length);
            assertFalse(result.diagnostics().isEmpty(), "length=" + length);
        }
    }

    @Test
    void nonPositiveRevisionAndOverCapacityAreRejectedBeforeEncoding() {
        assertThrows(IllegalArgumentException.class, () -> codec.encode(
                new WorldItemPageSnapshot(KEY, 0L, List.of())));
        List<WorldItemRestoreEntry> oversized = new ArrayList<>();
        for (int index = 0; index <= WorldItemPageCodec.MAX_ENTRIES; index++) {
            oversized.add(entry(index, WorldItemPhysicalState.ACTIVE));
        }
        assertThrows(IllegalArgumentException.class, () -> codec.encode(
                new WorldItemPageSnapshot(KEY, 1L, oversized)));
    }

    private static WorldItemRestoreEntry entry(long id, WorldItemPhysicalState state) {
        WorldItemSnapshot item = new WorldItemSnapshot(
                new WorldItemId(id),
                new ItemStack(ResourceLocation.of("gaia", "test/item-" + id), 3),
                id - 0.25d,
                4.75d,
                id + 16.5d,
                0.125d,
                -0.5d,
                0.0d,
                13L);
        return new WorldItemRestoreEntry(
                new WorldItemRuntimeSnapshot(
                        item,
                        Optional.of(new EntityRef(41)),
                        21L,
                        23L,
                        18_021L),
                state);
    }

    private static byte[] swapCanonicalEntryBodies(byte[] encoded) {
        // The canonical GLWP v1 fixture stores each entry as a checked
        // length-prefixed body after its fixed 44-byte page header.
        ByteBuffer input = ByteBuffer.wrap(encoded).order(ByteOrder.BIG_ENDIAN);
        int firstLength = input.getInt(44);
        int firstOffset = 48;
        int secondLengthOffset = firstOffset + firstLength;
        int secondLength = input.getInt(secondLengthOffset);
        int secondOffset = secondLengthOffset + Integer.BYTES;
        byte[] result = encoded.clone();
        assertEquals(firstLength, secondLength);
        System.arraycopy(encoded, secondOffset, result, firstOffset, secondLength);
        System.arraycopy(encoded, firstOffset, result, secondOffset, firstLength);
        // Preserve a valid checksum so the failure proves order closure rather
        // than incidental integrity failure.
        refreshChecksum(result);
        return result;
    }

    private static void refreshChecksum(byte[] encoded) {
        byte[] entryBytes = Arrays.copyOfRange(
                encoded, 44, encoded.length - WorldItemPageCodec.SHA256_BYTES);
        System.arraycopy(
                StreamedChunkCodec.sha256(entryBytes),
                0,
                encoded,
                encoded.length - WorldItemPageCodec.SHA256_BYTES,
                WorldItemPageCodec.SHA256_BYTES);
    }

    private void assertClosed(byte[] bytes, String code) {
        assertClosed(codec.decode(bytes), code);
    }

    private static void assertClosed(WorldItemPageCodec.DecodeResult result, String code) {
        assertEquals(WorldItemPageCodec.DecodeResult.Status.CORRUPT, result.status());
        assertTrue(result.page().isEmpty());
        assertEquals(code, result.diagnostics().get(0).code());
    }

    private static byte[] concat(byte[] first, byte[] second) {
        byte[] result = Arrays.copyOf(first, first.length + second.length);
        System.arraycopy(second, 0, result, first.length, second.length);
        return result;
    }
}
