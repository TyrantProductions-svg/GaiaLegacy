package com.gaia.save.streaming;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gaia.blocks.BlockDefinition;
import com.gaia.blocks.BlockRegistry;
import com.gaia.save.format.SaveSectionId;
import com.overlord.assets.ResourceLocation;
import com.overlord.renderer.material.MaterialDefinition;
import com.overlord.renderer.material.RenderType;
import com.overlord.renderer.texture.TextureRegion;
import com.overlord.voxel.BlockFace;
import com.overlord.voxel.BlockRenderInfo;
import com.overlord.voxel.ChunkKey;
import com.overlord.voxel.ChunkSnapshot;
import com.overlord.voxel.DetailCellState;
import com.overlord.voxel.DetailChunkSnapshot;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DetailBlocksCodecTest {
    private static final int HEIGHT = 4;
    private static final int VOXEL_COUNT = 16 * HEIGHT * 16;
    private static final ResourceLocation AIR = ResourceLocation.parse("gaia:air");
    private static final ResourceLocation DIRT = ResourceLocation.parse("gaia:dirt");
    private static final ResourceLocation STONE = ResourceLocation.parse("gaia:stone");

    private final BlockRegistry registry = registry(AIR, DIRT, STONE);
    private final DetailBlocksCodec codec = new DetailBlocksCodec();

    @Test
    void exactV1LiteralUsesCanonicalPaletteParentAndSubvoxelOrder() {
        byte[] ids = new byte[64];
        ids[0] = 2;
        ids[63] = 1;
        DetailChunkSnapshot details = DetailChunkSnapshot.of(
                new int[] {0x0123},
                new long[] {0x8000_0000_0000_0001L},
                ids);
        ChunkSnapshot snapshot = ChunkSnapshot.of(
                new ChunkKey(-2, 3), 7L, HEIGHT, new byte[VOXEL_COUNT], details);

        StreamedChunkPayload.ExtensionDescriptor extension =
                codec.encode(snapshot, registry).orElseThrow();

        String expected = "474c4431" + "04" + "00" + "0002" + "00000001"
                + "0009" + HexFormat.of().formatHex("gaia:dirt".getBytes())
                + "000a" + HexFormat.of().formatHex("gaia:stone".getBytes())
                + "0123" + "8000000000000001"
                + "02" + "00".repeat(62) + "01";
        assertEquals(expected, HexFormat.of().formatHex(extension.copyBytes()));
        assertEquals(SaveSectionId.DETAIL_BLOCKS, extension.sectionId());
        assertEquals(1, extension.codecVersion());
        assertTrue(extension.required());

        DetailBlocksCodec.DecodeResult decoded = codec.decode(
                extension, HEIGHT, new byte[VOXEL_COUNT], registry);
        assertEquals(DetailBlocksCodec.DecodeResult.Status.VALID, decoded.status());
        assertEquals(details, decoded.details().orElseThrow());
    }

    @Test
    void fullOnlySnapshotEmitsNoDetailExtension() {
        assertTrue(codec.encode(
                ChunkSnapshot.empty(new ChunkKey(0, 0), 1L, HEIGHT), registry)
                .isEmpty());
    }

    @Test
    void encodingIsDeterministicAcrossRuntimeIdAndInsertionOrder() {
        DetailChunkSnapshot first = details(
                new int[] {17, 200}, new byte[] {2, 1});
        DetailChunkSnapshot second = details(
                new int[] {17, 200}, new byte[] {2, 1});
        ChunkSnapshot a = snapshot(first);
        ChunkSnapshot b = snapshot(second);

        assertArrayEquals(
                codec.encode(a, registry).orElseThrow().copyBytes(),
                codec.encode(b, registry).orElseThrow().copyBytes());
    }

    @Test
    void decodeFailsClosedForWrongDescriptorVersionRequiredFlagAndHeader() {
        StreamedChunkPayload.ExtensionDescriptor valid = encoded(single(0, (byte) 1));
        assertFailure(new StreamedChunkPayload.ExtensionDescriptor(
                SaveSectionId.DETAIL_BLOCKS, 2, true, valid.copyBytes()),
                DetailBlocksCodec.DecodeResult.Status.UNSUPPORTED_VERSION,
                "detail-blocks.unsupported-version");
        assertFailure(new StreamedChunkPayload.ExtensionDescriptor(
                SaveSectionId.DETAIL_BLOCKS, 1, false, valid.copyBytes()),
                DetailBlocksCodec.DecodeResult.Status.CORRUPT,
                "detail-blocks.invalid-required-flag");

        byte[] badMagic = valid.copyBytes();
        badMagic[0] = 'X';
        assertFailure(descriptor(badMagic), DetailBlocksCodec.DecodeResult.Status.CORRUPT,
                "detail-blocks.invalid-magic");
        byte[] badScale = valid.copyBytes();
        badScale[4] = 8;
        assertFailure(descriptor(badScale), DetailBlocksCodec.DecodeResult.Status.CORRUPT,
                "detail-blocks.unsupported-scale");
        byte[] badFlags = valid.copyBytes();
        badFlags[5] = 1;
        assertFailure(descriptor(badFlags), DetailBlocksCodec.DecodeResult.Status.CORRUPT,
                "detail-blocks.invalid-flags");
    }

    @Test
    void paletteMustBeBoundedCanonicalUniqueKnownAndNonAir() {
        StreamedChunkPayload.ExtensionDescriptor valid = encoded(single(0, (byte) 1));
        byte[] unknown = valid.copyBytes();
        replaceAscii(unknown, "gaia:dirt", "gaia:void");
        assertFailure(descriptor(unknown), DetailBlocksCodec.DecodeResult.Status.CORRUPT,
                "detail-blocks.unknown-material");

        byte[] zeroPalette = valid.copyBytes();
        putUnsignedShort(zeroPalette, 6, 0);
        assertFailure(descriptor(zeroPalette), DetailBlocksCodec.DecodeResult.Status.CORRUPT,
                "detail-blocks.palette-bound");

        assertThrows(IllegalArgumentException.class,
                () -> codec.encode(snapshot(single(0, (byte) 99)), registry));
    }

    @Test
    void parentAndOccupancyValidationRejectsNoncanonicalOrUnsafeData() {
        StreamedChunkPayload.ExtensionDescriptor valid = encoded(single(0, (byte) 1));
        int entryOffset = 12 + 2 + "gaia:dirt".length();

        byte[] empty = valid.copyBytes();
        ByteBuffer.wrap(empty).order(ByteOrder.BIG_ENDIAN).putLong(entryOffset + 2, 0L);
        assertFailure(descriptor(empty), DetailBlocksCodec.DecodeResult.Status.CORRUPT,
                "detail-blocks.empty-detail");

        byte[] invalidMaterial = valid.copyBytes();
        invalidMaterial[entryOffset + 10] = 0;
        assertFailure(descriptor(invalidMaterial), DetailBlocksCodec.DecodeResult.Status.CORRUPT,
                "detail-blocks.invalid-occupancy-material");

        byte[] outOfRange = valid.copyBytes();
        putUnsignedShort(outOfRange, entryOffset, VOXEL_COUNT);
        assertFailure(descriptor(outOfRange), DetailBlocksCodec.DecodeResult.Status.CORRUPT,
                "detail-blocks.parent-out-of-range");

        byte[] backing = new byte[VOXEL_COUNT];
        backing[0] = 1;
        assertFailure(valid, HEIGHT, backing, DetailBlocksCodec.DecodeResult.Status.CORRUPT,
                "detail-blocks.full-backing-conflict");

        byte[] trailing = java.util.Arrays.copyOf(valid.copyBytes(), valid.copyBytes().length + 1);
        assertFailure(descriptor(trailing), DetailBlocksCodec.DecodeResult.Status.CORRUPT,
                "detail-blocks.trailing-bytes");
    }

    @Test
    void actualMaximumFixtureStaysWithinAcceptedExactBound() {
        ResourceLocation[] names = new ResourceLocation[256];
        names[0] = AIR;
        for (int id = 1; id <= 255; id++) {
            names[id] = ResourceLocation.parse(
                    "gaia:" + String.format("%03d", id) + "a".repeat(120));
        }
        BlockRegistry maximumRegistry = registry(names);
        int[] parents = new int[1024];
        long[] masks = new long[1024];
        byte[] ids = new byte[1024 * 64];
        for (int index = 0; index < 1024; index++) {
            parents[index] = index;
            masks[index] = -1L;
            for (int sub = 0; sub < 64; sub++) {
                ids[index * 64 + sub] =
                        (byte) (1 + ((index * 64 + sub) % 255));
            }
        }
        byte[] encoded = codec.encode(
                snapshot(DetailChunkSnapshot.of(parents, masks, ids)), maximumRegistry)
                .orElseThrow().copyBytes();

        assertTrue(encoded.length <= DetailBlocksCodec.MAX_V1_ENCODED_BYTES);
        assertTrue(encoded.length <= StreamedChunkCodec.MAX_EXTENSION_BYTES);
        assertEquals(108_938, encoded.length);
    }

    @Test
    void count1025IsRejectedBeforeAnyPartialDecodeValueCanEscape() {
        StreamedChunkPayload.ExtensionDescriptor valid = encoded(single(0, (byte) 1));
        byte[] oversizedCount = valid.copyBytes();
        ByteBuffer.wrap(oversizedCount).order(ByteOrder.BIG_ENDIAN).putInt(8, 1025);

        DetailBlocksCodec.DecodeResult result = codec.decode(
                descriptor(oversizedCount), HEIGHT, new byte[VOXEL_COUNT], registry);

        assertEquals(DetailBlocksCodec.DecodeResult.Status.CORRUPT, result.status());
        assertTrue(result.details().isEmpty());
        assertEquals("detail-blocks.parent-count-bound", result.diagnostic().code());
    }

    private void assertFailure(
            StreamedChunkPayload.ExtensionDescriptor extension,
            DetailBlocksCodec.DecodeResult.Status status,
            String code) {
        assertFailure(extension, HEIGHT, new byte[VOXEL_COUNT], status, code);
    }

    private void assertFailure(
            StreamedChunkPayload.ExtensionDescriptor extension,
            int height,
            byte[] full,
            DetailBlocksCodec.DecodeResult.Status status,
            String code) {
        DetailBlocksCodec.DecodeResult result = codec.decode(extension, height, full, registry);
        assertEquals(status, result.status());
        assertTrue(result.details().isEmpty());
        assertEquals(code, result.diagnostic().code());
    }

    private StreamedChunkPayload.ExtensionDescriptor encoded(DetailChunkSnapshot details) {
        return codec.encode(snapshot(details), registry).orElseThrow();
    }

    private static StreamedChunkPayload.ExtensionDescriptor descriptor(byte[] bytes) {
        return new StreamedChunkPayload.ExtensionDescriptor(
                SaveSectionId.DETAIL_BLOCKS, 1, true, bytes);
    }

    private static ChunkSnapshot snapshot(DetailChunkSnapshot details) {
        return ChunkSnapshot.of(
                new ChunkKey(1, -1), 4L, HEIGHT, new byte[VOXEL_COUNT], details);
    }

    private static DetailChunkSnapshot single(int parent, byte id) {
        byte[] ids = new byte[64];
        ids[0] = id;
        return DetailChunkSnapshot.of(new int[] {parent}, new long[] {1L}, ids);
    }

    private static DetailChunkSnapshot details(int[] parents, byte[] materialIds) {
        long[] masks = new long[parents.length];
        byte[] ids = new byte[parents.length * DetailCellState.CELL_COUNT];
        for (int entry = 0; entry < parents.length; entry++) {
            masks[entry] = 1L;
            ids[entry * 64] = materialIds[entry];
        }
        return DetailChunkSnapshot.of(parents, masks, ids);
    }

    private static BlockRegistry registry(ResourceLocation... names) {
        MaterialDefinition material = new MaterialDefinition(
                ResourceLocation.parse("gaia:opaque"),
                ResourceLocation.parse("gaia:blocks"),
                RenderType.OPAQUE, 0.5f, AIR);
        TextureRegion region = new TextureRegion(AIR, 0, 0, 1, 1, 1, 1);
        EnumMap<BlockFace, TextureRegion> regions = new EnumMap<>(BlockFace.class);
        for (BlockFace face : BlockFace.values()) {
            regions.put(face, region);
        }
        java.util.ArrayList<BlockDefinition> definitions = new java.util.ArrayList<>();
        java.util.HashMap<Integer, BlockRenderInfo> infos = new java.util.HashMap<>();
        for (int index = 0; index < names.length; index++) {
            definitions.add(new BlockDefinition(
                    index, names[index], material.id(), Map.of(), 0, 0, 0,
                    false, false, 0, null));
            infos.put(index, new BlockRenderInfo(material, regions, index != 0));
        }
        return BlockRegistry.create(definitions, infos);
    }

    private static void putUnsignedShort(byte[] bytes, int offset, int value) {
        ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN).putShort(offset, (short) value);
    }

    private static void replaceAscii(byte[] bytes, String from, String to) {
        assertEquals(from.length(), to.length());
        byte[] needle = from.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        for (int index = 0; index <= bytes.length - needle.length; index++) {
            if (java.util.Arrays.equals(
                    java.util.Arrays.copyOfRange(bytes, index, index + needle.length), needle)) {
                System.arraycopy(to.getBytes(java.nio.charset.StandardCharsets.UTF_8), 0,
                        bytes, index, needle.length);
                return;
            }
        }
        throw new AssertionError("fixture text not found");
    }
}
