package com.gaia.save.streaming;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gaia.blocks.BlockDefinition;
import com.gaia.blocks.BlockRegistry;
import com.gaia.save.format.SaveGameId;
import com.gaia.save.format.SaveSectionId;
import com.gaia.save.store.JdkSaveFileOperations;
import com.overlord.assets.ResourceLocation;
import com.overlord.renderer.material.MaterialDefinition;
import com.overlord.renderer.material.RenderType;
import com.overlord.renderer.texture.TextureRegion;
import com.overlord.voxel.BlockFace;
import com.overlord.voxel.BlockRenderInfo;
import com.overlord.voxel.ChunkGenerationData;
import com.overlord.voxel.ChunkKey;
import com.overlord.voxel.ChunkSnapshot;
import com.overlord.voxel.DetailChunkSnapshot;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StreamedChunkCanonicalDecoderTest {
    private static final SaveGameId SAVE_ID =
            SaveGameId.parse("123e4567-e89b-12d3-a456-426614174000");
    private static final ChunkKey KEY = new ChunkKey(-9, 12);
    private static final String HASH = "55".repeat(32);
    private static final ResourceLocation AIR = ResourceLocation.parse("gaia:air");
    private static final ResourceLocation DIRT = ResourceLocation.parse("gaia:dirt");
    private static final ResourceLocation STONE = ResourceLocation.parse("gaia:stone");

    @TempDir Path temporaryDirectory;

    private final BlockRegistry registry = registry();
    private final DetailBlocksCodec detailCodec = new DetailBlocksCodec();
    private final StreamedChunkCanonicalDecoder decoder =
            new StreamedChunkCanonicalDecoder(registry);

    @Test
    void absentDetailExtensionLoadsPhase14AndPhase15FullOnlyPayloadsUnchanged() {
        byte[] full = new byte[16 * 2 * 16];
        full[0] = 1;
        full[full.length - 1] = 2;

        StreamedChunkCanonicalDecoder.DecodeResult result = decoder.decode(
                payload(4L, 2, full, List.of()));

        assertEquals(StreamedChunkCanonicalDecoder.DecodeResult.Status.VALID,
                result.status());
        ChunkGenerationData data = result.chunkData().orElseThrow();
        assertArrayEquals(full, data.copyBlocks());
        assertTrue(data.details().isEmpty());
    }

    @Test
    void supportedDetailExtensionRestoresExactRuntimeIdsAtMultipleParents() {
        int[] parents = {0, 16 * 2 * 16 - 1};
        long[] masks = {3L, Long.MIN_VALUE};
        byte[] ids = new byte[2 * 64];
        ids[0] = 1;
        ids[1] = 2;
        ids[127] = 1;
        DetailChunkSnapshot details = DetailChunkSnapshot.of(parents, masks, ids);
        ChunkSnapshot snapshot = ChunkSnapshot.of(
                KEY, 8L, 2, new byte[16 * 2 * 16], details);
        var extension = detailCodec.encode(snapshot, registry).orElseThrow();

        StreamedChunkCanonicalDecoder.DecodeResult result = decoder.decode(
                payload(8L, 2, new byte[16 * 2 * 16], List.of(extension)));

        assertEquals(StreamedChunkCanonicalDecoder.DecodeResult.Status.VALID,
                result.status());
        assertEquals(details, result.chunkData().orElseThrow().details());
    }

    @Test
    void malformedUnknownVersionAndUnknownMaterialFailClosedWithoutChunkData() {
        ChunkSnapshot snapshot = oneDetail((byte) 1);
        var valid = detailCodec.encode(snapshot, registry).orElseThrow();
        var version = new StreamedChunkPayload.ExtensionDescriptor(
                SaveSectionId.DETAIL_BLOCKS, 2, true, valid.copyBytes());
        assertClosed(payload(2L, 1, new byte[16 * 16], List.of(version)),
                StreamedChunkCanonicalDecoder.DecodeResult.Status.UNSUPPORTED_EXTENSION,
                "detail-blocks.unsupported-version");

        byte[] malformed = valid.copyBytes();
        malformed[0] = 'X';
        assertClosed(payload(2L, 1, new byte[16 * 16], List.of(
                        new StreamedChunkPayload.ExtensionDescriptor(
                                SaveSectionId.DETAIL_BLOCKS, 1, true, malformed))),
                StreamedChunkCanonicalDecoder.DecodeResult.Status.CORRUPT,
                "detail-blocks.invalid-magic");

        byte[] unknown = valid.copyBytes();
        byte[] from = "gaia:dirt".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] to = "gaia:void".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        int offset = indexOf(unknown, from);
        System.arraycopy(to, 0, unknown, offset, to.length);
        assertClosed(payload(2L, 1, new byte[16 * 16], List.of(
                        new StreamedChunkPayload.ExtensionDescriptor(
                                SaveSectionId.DETAIL_BLOCKS, 1, true, unknown))),
                StreamedChunkCanonicalDecoder.DecodeResult.Status.CORRUPT,
                "detail-blocks.unknown-material");
    }

    @Test
    void outerFramingNeverSkipsOptionalUnknownVersionDetailGeometry() {
        var valid = detailCodec.encode(oneDetail((byte) 1), registry).orElseThrow();
        var mislabeled = new StreamedChunkPayload.ExtensionDescriptor(
                SaveSectionId.DETAIL_BLOCKS, 2, false, valid.copyBytes());
        byte[] framed = new StreamedChunkCodec().encode(
                payload(2L, 1, new byte[16 * 16], List.of(mislabeled)));

        StreamedChunkPayload retained = new StreamedChunkCodec()
                .decode(framed).payload().orElseThrow();
        StreamedChunkCanonicalDecoder.DecodeResult result = decoder.decode(retained);

        assertEquals(1, retained.extensions().size());
        assertEquals(StreamedChunkCanonicalDecoder.DecodeResult.Status.UNSUPPORTED_EXTENSION,
                result.status());
        assertTrue(result.chunkData().isEmpty());
        assertEquals("detail-blocks.unsupported-version",
                result.diagnostic().orElseThrow().code());
    }

    @Test
    void durableStoreRoundTripReconstructsExactDetailAndLeavesNoGlobalStore()
            throws Exception {
        Path root = java.nio.file.Files.createDirectory(
                temporaryDirectory.resolve("round-trip"));
        StreamedChunkStore store = new StreamedChunkStore(
                root, SAVE_ID, new StreamedChunkCodec(),
                new StreamedChunkIndexCodec(), new JdkSaveFileOperations());
        ChunkSnapshot snapshot = oneDetail((byte) 2);
        StreamedChunkPayload source = payload(
                3L, 1, new byte[16 * 16],
                List.of(detailCodec.encode(snapshot, registry).orElseThrow()));
        assertEquals(StreamedChunkStore.CommitResult.Status.SUCCESS,
                store.commitTransaction(new StreamedPersistenceTransaction(
                        List.of(new StreamedChunkMutation.Upsert(
                                new StreamedChunkStore.ExactChunkCapture(
                                        source, () -> true))),
                        List.of(), () -> true)).status());

        StreamedChunkPayload read = store.read(
                SAVE_ID, KEY, new StreamedChunkStore.ExpectedBase("v16", HASH))
                .payload().orElseThrow();
        ChunkGenerationData restored = decoder.decode(read).chunkData().orElseThrow();

        assertEquals(snapshot.details(), restored.details());
        assertEquals((byte) 0, restored.getBlock(0, 0, 0));
    }

    private void assertClosed(
            StreamedChunkPayload payload,
            StreamedChunkCanonicalDecoder.DecodeResult.Status status,
            String code) {
        StreamedChunkCanonicalDecoder.DecodeResult result = decoder.decode(payload);
        assertEquals(status, result.status());
        assertTrue(result.chunkData().isEmpty());
        assertEquals(code, result.diagnostic().orElseThrow().code());
    }

    private static StreamedChunkPayload payload(
            long revision,
            int height,
            byte[] full,
            List<StreamedChunkPayload.ExtensionDescriptor> extensions) {
        return new StreamedChunkPayload(
                SAVE_ID, KEY, "v16", HASH, revision, 0L,
                true, true, height, full, extensions);
    }

    private static ChunkSnapshot oneDetail(byte id) {
        byte[] ids = new byte[64];
        ids[7] = id;
        return ChunkSnapshot.of(KEY, 2L, 1, new byte[16 * 16],
                DetailChunkSnapshot.of(new int[] {3}, new long[] {1L << 7}, ids));
    }

    private static int indexOf(byte[] haystack, byte[] needle) {
        outer: for (int index = 0; index <= haystack.length - needle.length; index++) {
            for (int offset = 0; offset < needle.length; offset++) {
                if (haystack[index + offset] != needle[offset]) {
                    continue outer;
                }
            }
            return index;
        }
        throw new AssertionError("fixture bytes not found");
    }

    private static BlockRegistry registry() {
        MaterialDefinition material = new MaterialDefinition(
                ResourceLocation.parse("gaia:opaque"),
                ResourceLocation.parse("gaia:blocks"), RenderType.OPAQUE,
                0.5f, AIR);
        TextureRegion region = new TextureRegion(AIR, 0, 0, 1, 1, 1, 1);
        EnumMap<BlockFace, TextureRegion> regions = new EnumMap<>(BlockFace.class);
        for (BlockFace face : BlockFace.values()) {
            regions.put(face, region);
        }
        List<ResourceLocation> names = List.of(AIR, DIRT, STONE);
        java.util.ArrayList<BlockDefinition> definitions = new java.util.ArrayList<>();
        java.util.HashMap<Integer, BlockRenderInfo> infos = new java.util.HashMap<>();
        for (int id = 0; id < names.size(); id++) {
            definitions.add(new BlockDefinition(
                    id, names.get(id), material.id(), Map.of(), 0, 0, 0,
                    false, false, 0, null));
            infos.put(id, new BlockRenderInfo(material, regions, id != 0));
        }
        return BlockRegistry.create(definitions, infos);
    }
}
