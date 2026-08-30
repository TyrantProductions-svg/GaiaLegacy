package com.gaia.save.streaming;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import com.overlord.voxel.ChunkKey;
import com.overlord.voxel.ChunkSnapshot;
import com.overlord.voxel.DetailChunkSnapshot;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StreamedDetailPersistenceTest {
    private static final SaveGameId SAVE_ID =
            SaveGameId.parse("123e4567-e89b-12d3-a456-426614174000");
    private static final ChunkKey KEY = new ChunkKey(5, -7);
    private static final String BASE_HASH = "11".repeat(32);
    private static final ResourceLocation AIR = ResourceLocation.parse("gaia:air");
    private static final ResourceLocation DIRT = ResourceLocation.parse("gaia:dirt");
    private static final ResourceLocation STONE = ResourceLocation.parse("gaia:stone");

    @TempDir Path temporaryDirectory;

    private final BlockRegistry registry = registry();
    private final DetailBlocksCodec detailCodec = new DetailBlocksCodec();

    @Test
    void detailOnlyDifferenceCannotBeClassifiedAsFlatByteNoOp() throws Exception {
        StreamedChunkStore store = store(temporaryDirectory.resolve("detail-change"));
        StreamedChunkPayload durable = payload(1L, 0L, detailExtension(snapshot(1L, 0, (byte) 1)));
        commit(store, durable);
        StreamedChunkPayload changed = payload(2L, 1L, detailExtension(snapshot(2L, 0, (byte) 2)));

        StreamedChunkUnloadResult result = new StreamedWorldItemPageBackend(store)
                .persistUnload(new StreamedChunkUnloadPlan(
                        capture(changed, () -> true), Optional.empty(), List.of(), true));

        assertEquals(StreamedChunkUnloadResult.Status.SUCCESS, result.status());
        assertEquals(2L, result.persistedChunkRevision().orElseThrow());
        StreamedChunkPayload restored = store.read(
                SAVE_ID, KEY, new StreamedChunkStore.ExpectedBase("v16", BASE_HASH))
                .payload().orElseThrow();
        assertArrayEquals(changed.extensions().get(0).copyBytes(),
                restored.extensions().get(0).copyBytes());
    }

    @Test
    void higherByteIdenticalDetailRevisionIsWrittenBeforeAcknowledgement() throws Exception {
        StreamedChunkStore store = store(temporaryDirectory.resolve("detail-higher-revision"));
        StreamedChunkPayload durable = payload(1L, 0L, detailExtension(snapshot(1L, 0, (byte) 1)));
        commit(store, durable);
        StreamedChunkPayload equivalent = payload(2L, 1L,
                detailExtension(snapshot(2L, 0, (byte) 1)));

        StreamedChunkUnloadResult result = new StreamedWorldItemPageBackend(store)
                .persistUnload(new StreamedChunkUnloadPlan(
                        capture(equivalent, () -> true), Optional.empty(), List.of(), true));

        assertEquals(StreamedChunkUnloadResult.Status.SUCCESS, result.status());
        assertEquals(2L, result.persistedChunkRevision().orElseThrow());
        assertEquals(2L, store.readCurrentIndex().entry(KEY).orElseThrow().revision());
    }

    @Test
    void exactRevisionAndDetailContentNoOpNeverAcknowledgesUnwrittenRevision()
            throws Exception {
        StreamedChunkStore store = store(temporaryDirectory.resolve("detail-noop"));
        StreamedChunkPayload durable = payload(1L, 0L,
                detailExtension(snapshot(1L, 0, (byte) 1)));
        commit(store, durable);
        StreamedChunkPayload equivalent = payload(1L, 1L,
                detailExtension(snapshot(1L, 0, (byte) 1)));

        StreamedChunkUnloadResult result = new StreamedWorldItemPageBackend(store)
                .persistUnload(new StreamedChunkUnloadPlan(
                        capture(equivalent, () -> true), Optional.empty(), List.of(), true));

        assertEquals(StreamedChunkUnloadResult.Status.SUCCESS, result.status());
        assertTrue(result.persistedChunkRevision().isEmpty());
        assertEquals(1L, store.readCurrentIndex().entry(KEY).orElseThrow().revision());
    }

    @Test
    void staleDetachedDetailCaptureCannotPublishOrAcknowledgeRevision()
            throws Exception {
        StreamedChunkStore store = store(temporaryDirectory.resolve("detail-stale"));
        StreamedChunkPayload durable = payload(
                1L, 0L, detailExtension(snapshot(1L, 0, (byte) 1)));
        commit(store, durable);
        StreamedChunkPayload changed = payload(
                2L, 1L, detailExtension(snapshot(2L, 0, (byte) 2)));

        StreamedChunkUnloadResult result = new StreamedWorldItemPageBackend(store)
                .persistUnload(new StreamedChunkUnloadPlan(
                        capture(changed, () -> false), Optional.empty(), List.of(), true));

        assertEquals(StreamedChunkUnloadResult.Status.STALE, result.status());
        assertTrue(result.persistedChunkRevision().isEmpty());
        assertEquals(1L, store.readCurrentIndex().entry(KEY).orElseThrow().revision());
    }

    @Test
    void exactSnapshotCaptureCannotMixLaterFullOrDetailMutation() {
        byte[] full = new byte[16 * 16];
        byte[] ids = new byte[64];
        ids[0] = 1;
        DetailChunkSnapshot details = DetailChunkSnapshot.of(
                new int[] {0}, new long[] {1L}, ids);
        ChunkSnapshot captured = ChunkSnapshot.of(KEY, 7L, 1, full, details);

        byte[] capturedFull = ChunkDetailPersistence.canonicalFullVoxels(captured);
        List<StreamedChunkPayload.ExtensionDescriptor> capturedExtensions =
                ChunkDetailPersistence.mergeDetailExtension(captured, List.of(), registry);
        full[0] = 2;
        ids[0] = 2;

        assertEquals(0, capturedFull[0]);
        DetailBlocksCodec.DecodeResult decoded = detailCodec.decode(
                capturedExtensions.get(0), 1, capturedFull, registry);
        assertEquals((byte) 1, decoded.details().orElseThrow()
                .copyBlockIds()[0]);
    }

    @Test
    void detailMergePreservesUnrelatedExtensionAndRemovesOnlyPriorDetail() {
        StreamedChunkPayload.ExtensionDescriptor unrelated =
                new StreamedChunkPayload.ExtensionDescriptor(
                        new SaveSectionId("future-lighting"), 3, false,
                        new byte[] {7, 8, 9});
        StreamedChunkPayload.ExtensionDescriptor oldDetail =
                detailExtension(snapshot(1L, 0, (byte) 1));

        List<StreamedChunkPayload.ExtensionDescriptor> merged =
                ChunkDetailPersistence.mergeDetailExtension(
                        snapshot(2L, 1, (byte) 2),
                        List.of(oldDetail, unrelated), registry);

        assertEquals(List.of(SaveSectionId.DETAIL_BLOCKS, unrelated.sectionId()),
                merged.stream().map(
                        StreamedChunkPayload.ExtensionDescriptor::sectionId).toList());
        assertArrayEquals(new byte[] {7, 8, 9}, merged.get(1).copyBytes());
        assertFalse(java.util.Arrays.equals(
                oldDetail.copyBytes(), merged.get(0).copyBytes()));
    }

    @Test
    void modifiedDetailUnloadPreservesDurableUnrelatedExtension() throws Exception {
        Path root = temporaryDirectory.resolve("detail-unrelated-unload");
        java.nio.file.Files.createDirectories(root);
        StreamedChunkStore store = new StreamedChunkStore(
                root, SAVE_ID,
                new StreamedChunkCodec(StreamedExtensionSupportRegistry.builder()
                        .supportRequired(SaveSectionId.DETAIL_BLOCKS, 1)
                        .supportOptional(SaveSectionId.DISCOVERY_LORE, 1)
                        .build()),
                new StreamedChunkIndexCodec(), new JdkSaveFileOperations());
        StreamedChunkPayload.ExtensionDescriptor unrelated =
                new StreamedChunkPayload.ExtensionDescriptor(
                        SaveSectionId.DISCOVERY_LORE, 1, false,
                        new byte[] {7, 8, 9});
        StreamedChunkPayload durable = new StreamedChunkPayload(
                SAVE_ID, KEY, "v16", BASE_HASH, 1L, 0L,
                true, true, 1, new byte[16 * 16],
                List.of(detailExtension(snapshot(1L, 0, (byte) 1)), unrelated));
        commit(store, durable);
        StreamedChunkPayload changed = payload(
                2L, 1L, detailExtension(snapshot(2L, 0, (byte) 2)));

        StreamedChunkUnloadResult result = new StreamedWorldItemPageBackend(store)
                .persistUnload(new StreamedChunkUnloadPlan(
                        capture(changed, () -> true), Optional.empty(), List.of(), true));

        assertEquals(StreamedChunkUnloadResult.Status.SUCCESS, result.status());
        assertEquals(2L, result.persistedChunkRevision().orElseThrow());
        StreamedChunkPayload restored = store.read(
                SAVE_ID, KEY, new StreamedChunkStore.ExpectedBase("v16", BASE_HASH))
                .payload().orElseThrow();
        assertArrayEquals(new byte[] {7, 8, 9}, restored.extensions().stream()
                .filter(extension -> extension.sectionId().equals(
                        SaveSectionId.DISCOVERY_LORE))
                .findFirst().orElseThrow().copyBytes());
        assertArrayEquals(changed.extensions().get(0).copyBytes(),
                restored.extensions().stream()
                        .filter(extension -> extension.sectionId().equals(
                                SaveSectionId.DETAIL_BLOCKS))
                        .findFirst().orElseThrow().copyBytes());
    }

    @Test
    void modifiedDetailUnloadRejectsConflictingIndependentlyOwnedExtension()
            throws Exception {
        Path root = temporaryDirectory.resolve("detail-unrelated-conflict");
        java.nio.file.Files.createDirectories(root);
        StreamedChunkStore store = new StreamedChunkStore(
                root, SAVE_ID,
                new StreamedChunkCodec(StreamedExtensionSupportRegistry.builder()
                        .supportRequired(SaveSectionId.DETAIL_BLOCKS, 1)
                        .supportOptional(SaveSectionId.DISCOVERY_LORE, 1)
                        .build()),
                new StreamedChunkIndexCodec(), new JdkSaveFileOperations());
        StreamedChunkPayload.ExtensionDescriptor durableUnrelated =
                new StreamedChunkPayload.ExtensionDescriptor(
                        SaveSectionId.DISCOVERY_LORE, 1, false,
                        new byte[] {7, 8, 9});
        StreamedChunkPayload durable = new StreamedChunkPayload(
                SAVE_ID, KEY, "v16", BASE_HASH, 1L, 0L,
                true, true, 1, new byte[16 * 16],
                List.of(detailExtension(snapshot(1L, 0, (byte) 1)),
                        durableUnrelated));
        commit(store, durable);
        StreamedChunkPayload.ExtensionDescriptor conflictingUnrelated =
                new StreamedChunkPayload.ExtensionDescriptor(
                        SaveSectionId.DISCOVERY_LORE, 1, false,
                        new byte[] {9, 8, 7});
        StreamedChunkPayload conflicting = new StreamedChunkPayload(
                SAVE_ID, KEY, "v16", BASE_HASH, 2L, 1L,
                true, true, 1, new byte[16 * 16],
                List.of(detailExtension(snapshot(2L, 0, (byte) 2)),
                        conflictingUnrelated));

        StreamedChunkUnloadResult result = new StreamedWorldItemPageBackend(store)
                .persistUnload(new StreamedChunkUnloadPlan(
                        capture(conflicting, () -> true),
                        Optional.empty(), List.of(), true));

        assertEquals(StreamedChunkUnloadResult.Status.FAILED, result.status());
        assertTrue(result.persistedChunkRevision().isEmpty());
        assertTrue(result.durableProof().isEmpty());
        assertEquals(1L, store.readCurrentIndex().entry(KEY).orElseThrow().revision());
        StreamedChunkPayload unchanged = store.read(
                SAVE_ID, KEY, new StreamedChunkStore.ExpectedBase("v16", BASE_HASH))
                .payload().orElseThrow();
        assertArrayEquals(durableUnrelated.copyBytes(), unchanged.extensions().stream()
                .filter(extension -> extension.sectionId().equals(
                        SaveSectionId.DISCOVERY_LORE))
                .findFirst().orElseThrow().copyBytes());
        assertArrayEquals(durable.extensions().stream()
                        .filter(extension -> extension.sectionId().equals(
                                SaveSectionId.DETAIL_BLOCKS))
                        .findFirst().orElseThrow().copyBytes(),
                unchanged.extensions().stream()
                        .filter(extension -> extension.sectionId().equals(
                                SaveSectionId.DETAIL_BLOCKS))
                        .findFirst().orElseThrow().copyBytes());
    }

    @Test
    void canonicalEqualityIncludesDetailButIgnoresUnrelatedExtensionOwnership() {
        ChunkSnapshot snapshot = snapshot(3L, 0, (byte) 1);
        StreamedChunkPayload equal = payload(3L, 2L, detailExtension(snapshot));
        StreamedChunkPayload different = payload(3L, 2L,
                detailExtension(snapshot(3L, 0, (byte) 2)));

        assertTrue(ChunkDetailPersistence.canonicalStateEquals(snapshot, equal, registry));
        assertFalse(ChunkDetailPersistence.canonicalStateEquals(snapshot, different, registry));
    }

    private StreamedChunkPayload.ExtensionDescriptor detailExtension(ChunkSnapshot snapshot) {
        return detailCodec.encode(snapshot, registry).orElseThrow();
    }

    private static ChunkSnapshot snapshot(long revision, int parent, byte id) {
        byte[] ids = new byte[64];
        ids[0] = id;
        return ChunkSnapshot.of(KEY, revision, 1, new byte[16 * 16],
                DetailChunkSnapshot.of(new int[] {parent}, new long[] {1L}, ids));
    }

    private static StreamedChunkPayload payload(
            long revision,
            long persistedRevision,
            StreamedChunkPayload.ExtensionDescriptor detail) {
        return new StreamedChunkPayload(
                SAVE_ID, KEY, "v16", BASE_HASH, revision, persistedRevision,
                true, true, 1, new byte[16 * 16], List.of(detail));
    }

    private static StreamedChunkStore.ExactChunkCapture capture(
            StreamedChunkPayload payload,
            java.util.function.BooleanSupplier current) {
        return new StreamedChunkStore.ExactChunkCapture(payload, current);
    }

    private static StreamedChunkStore store(Path root) throws Exception {
        java.nio.file.Files.createDirectories(root);
        return new StreamedChunkStore(
                root, SAVE_ID, new StreamedChunkCodec(),
                new StreamedChunkIndexCodec(), new JdkSaveFileOperations());
    }

    private static void commit(StreamedChunkStore store, StreamedChunkPayload payload) {
        assertEquals(StreamedChunkStore.CommitResult.Status.SUCCESS,
                store.commitTransaction(new StreamedPersistenceTransaction(
                        List.of(new StreamedChunkMutation.Upsert(capture(payload, () -> true))),
                        List.of(), () -> true)).status());
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
