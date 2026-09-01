package com.gaia.interaction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gaia.inventory.WorldItemSpawnIndeterminateException;
import com.gaia.testing.FaultInjectingWorldItemService;
import com.gaia.testing.FaultInjectingWorldItemService.CommitFailureKind;
import com.overlord.assets.ResourceLocation;
import com.gaia.blocks.BlockDefinition;
import com.gaia.blocks.BlockRegistry;
import com.gaia.blocks.DetailSupportDefinition;
import com.gaia.blocks.ItemFormDefinition;
import com.gaia.blocks.StandaloneItemDefinition;
import com.gaia.blocks.ItemVisualReference;
import com.gaia.blocks.ItemVisualType;
import com.overlord.renderer.material.MaterialDefinition;
import com.overlord.renderer.material.RenderType;
import com.overlord.renderer.texture.TextureRegion;
import com.overlord.core.thread.MainThreadGuard;
import com.overlord.interaction.api.BlockHitResult;
import com.overlord.interaction.api.DetailMutationRequest;
import com.overlord.interaction.api.DetailMutationEventDispatchException;
import com.overlord.interaction.api.DetailMutationResult;
import com.overlord.interaction.api.DetailMutationService;
import com.overlord.interaction.api.DetailToFullRequest;
import com.overlord.interaction.api.EntityRef;
import com.overlord.interaction.api.FullToDetailRequest;
import com.overlord.interaction.api.RemoveDetailParentRequest;
import com.overlord.interaction.api.SculptParentSubVoxelRequest;
import com.overlord.inventory.api.BodySlot;
import com.overlord.physics.DetailRaycastTarget;
import com.overlord.physics.FullRaycastTarget;
import com.overlord.voxel.ChunkKey;
import com.overlord.voxel.BlockFace;
import com.overlord.voxel.BlockRenderInfo;
import com.overlord.voxel.DetailCellState;
import com.overlord.voxel.DirtyChunkRevision;
import com.overlord.voxel.LocalSubVoxelPosition;
import com.overlord.voxel.VoxelScale;
import com.overlord.worlditem.LogicalWorldItemService;
import com.overlord.worlditem.api.WorldItemSnapshot;
import com.overlord.worlditem.api.WorldItemSpawnRequest;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class DetailParentBreakTransactionTest {
    private static final ResourceLocation STONE = ResourceLocation.parse("gaia:stone");
    private static final ResourceLocation DIRT = ResourceLocation.parse("gaia:dirt");
    private static final ResourceLocation STONE_UNIT =
            ResourceLocation.parse("gaia:stone_detail_unit");
    private static final ResourceLocation DIRT_UNIT =
            ResourceLocation.parse("gaia:dirt_detail_unit");

    @Test
    void partialMixedAndUniformCreativeBreakEachSubmitOneExactParentRemoval() {
        for (DetailCellState expected : List.of(
                detail(new int[] {0}, new byte[] {1}),
                detail(new int[] {0, 17, 63}, new byte[] {1, 2, 1}),
                DetailCellState.uniform((byte) 1))) {
            RecordingMutations mutations = new RecordingMutations();
            DetailParentBreakTransaction transaction =
                    new DetailParentBreakTransaction(mutations, new EntityRef(42));
            BlockHitResult hit = hit(4, 7, 6, 11L);

            DetailParentBreakResult result = transaction.executeCreative(
                    hit,
                    expected,
                    BodySlot.RIGHT_HAND,
                    12L,
                    13L);

            assertEquals(DetailParentBreakResult.Status.APPLIED, result.status());
            assertEquals(1, mutations.removeCalls);
            RemoveDetailParentRequest request = mutations.remove.orElseThrow();
            assertEquals(expected, request.expectedState());
            assertEquals(11L, request.expectedChunkRevision());
            assertEquals(4, request.x());
            assertEquals(7, request.y());
            assertEquals(6, request.z());
            assertEquals(0, result.producedItems());
            assertTrue(result.feedbackEligible());
        }
    }

    @Test
    void staleMutationProducesNoOutputAndNoCommittedFeedbackEligibility() {
        RecordingMutations mutations = new RecordingMutations();
        mutations.nextStatus = DetailMutationResult.Status.STALE_CHUNK_REVISION;
        DetailParentBreakTransaction transaction =
                new DetailParentBreakTransaction(mutations, new EntityRef(42));

        DetailParentBreakResult result = transaction.executeCreative(
                hit(4, 7, 6, 11L),
                detail(new int[] {0}, new byte[] {1}),
                BodySlot.LEFT_HAND,
                12L,
                13L);

        assertEquals(DetailParentBreakResult.Status.MUTATION_REJECTED, result.status());
        assertEquals(1, mutations.removeCalls);
        assertEquals(0, result.producedItems());
        assertFalse(result.feedbackEligible());
        assertEquals(
                DetailMutationResult.Status.STALE_CHUNK_REVISION,
                result.mutation().orElseThrow().status());
    }

    @Test
    void uniformSurvivalBreakProducesExactlyOneFullBlockWorldItem() {
        RecordingMutations mutations = new RecordingMutations();
        LogicalWorldItemService worldItems = worldItems(4);
        BlockRegistry registry = registry();
        DetailParentBreakTransaction transaction = new DetailParentBreakTransaction(
                mutations,
                new EntityRef(42),
                registry,
                new Phase17DetailActionPolicy(registry),
                worldItems);

        DetailParentBreakResult result = transaction.executeSurvival(
                hit(4, 7, 6, 11L),
                DetailCellState.uniform((byte) 1),
                Optional.empty(),
                BodySlot.RIGHT_HAND,
                12L,
                13L);

        assertEquals(DetailParentBreakResult.Status.APPLIED, result.status());
        assertEquals(1, result.producedItems());
        assertEquals(1, result.worldItemCommitted());
        assertEquals(List.of(STONE), worldItems.snapshots().stream()
                .map(WorldItemSnapshot::stack)
                .map(com.overlord.inventory.api.ItemStack::itemId)
                .toList());
        assertEquals(1, mutations.removeCalls);
    }

    @Test
    void appliedMutationNotificationFailureStillCommitsExactlyOneFullBlockOutput() {
        RecordingMutations mutations = new RecordingMutations();
        mutations.appliedNotificationFailure = true;
        LogicalWorldItemService worldItems = worldItems(4);
        BlockRegistry registry = registry();
        DetailParentBreakTransaction transaction = new DetailParentBreakTransaction(
                mutations, new EntityRef(42), registry,
                new Phase17DetailActionPolicy(registry), worldItems);

        DetailParentBreakResult result = transaction.executeSurvival(
                hit(4, 7, 6, 11L), DetailCellState.uniform((byte) 1),
                Optional.empty(), BodySlot.RIGHT_HAND, 12L, 13L);

        assertEquals(
                DetailParentBreakResult.Status.APPLIED_WITH_NOTIFICATION_FAILURE,
                result.status());
        assertEquals(1, mutations.removeCalls);
        assertEquals(1, result.producedItems());
        assertEquals(1, result.worldItemCommitted());
        assertEquals(1, worldItems.snapshots().size());
        assertTrue(result.notificationFailure().isPresent());
    }

    @Test
    void partialAndMixedSurvivalBreakAreDestructiveWithNoMicroOutput() {
        for (DetailCellState state : List.of(
                detail(new int[] {0, 1}, new byte[] {1, 1}),
                detail(new int[] {0, 1}, new byte[] {1, 2}))) {
            RecordingMutations mutations = new RecordingMutations();
            LogicalWorldItemService worldItems = worldItems(4);
            BlockRegistry registry = registry();
            DetailParentBreakResult result = new DetailParentBreakTransaction(
                    mutations, new EntityRef(42), registry,
                    new Phase17DetailActionPolicy(registry), worldItems)
                    .executeSurvival(
                            hit(4, 7, 6, 11L), state, Optional.empty(),
                            BodySlot.RIGHT_HAND, 12L, 13L);

            assertEquals(DetailParentBreakResult.Status.APPLIED, result.status());
            assertEquals(0, result.producedItems());
            assertEquals(0, result.worldItemCommitted());
            assertTrue(worldItems.snapshots().isEmpty());
            assertEquals(1, mutations.removeCalls);
        }
    }

    @Test
    void spawnReservationFailureRejectsBeforeCanonicalMutation() {
        RecordingMutations mutations = new RecordingMutations();
        BlockRegistry registry = registry();
        LogicalWorldItemService fullWorldItems = worldItems(1);
        fullWorldItems.spawn(new WorldItemSpawnRequest(
                new com.overlord.inventory.api.ItemStack(DIRT, 1),
                0, 0, 0, 0, 0, 0, Optional.empty(), 0));
        DetailParentBreakResult result = new DetailParentBreakTransaction(
                mutations, new EntityRef(42), registry,
                new Phase17DetailActionPolicy(registry), fullWorldItems)
                .executeSurvival(
                        hit(4, 7, 6, 11L), DetailCellState.uniform((byte) 1),
                        Optional.empty(), BodySlot.RIGHT_HAND, 12L, 13L);

        assertEquals(DetailParentBreakResult.Status.RESERVATION_REJECTED, result.status());
        assertEquals(0, result.producedItems());
        assertEquals(0, result.worldItemCommitted());
        assertEquals(0, mutations.removeCalls);
        assertEquals(1, fullWorldItems.snapshots().size());
    }

    @Test
    void staleUniformMutationRollsBackReservedWorldItem() {
        RecordingMutations mutations = new RecordingMutations();
        mutations.nextStatus = DetailMutationResult.Status.STALE_CHUNK_REVISION;
        LogicalWorldItemService worldItems = worldItems(1);
        BlockRegistry registry = registry();

        DetailParentBreakResult result = new DetailParentBreakTransaction(
                mutations, new EntityRef(42), registry,
                new Phase17DetailActionPolicy(registry), worldItems)
                .executeSurvival(
                        hit(4, 7, 6, 11L), DetailCellState.uniform((byte) 1),
                        Optional.empty(), BodySlot.RIGHT_HAND, 12L, 13L);

        assertEquals(DetailParentBreakResult.Status.MUTATION_REJECTED, result.status());
        assertTrue(worldItems.snapshots().isEmpty());
        assertEquals(0, result.producedItems());
        assertEquals(0, result.worldItemCommitted());
        assertEquals(1, mutations.removeCalls);
    }

    @Test
    void unsupportedOccupiedMaterialFailsClosedBeforeBreakProgressOrMutation() {
        RecordingMutations mutations = new RecordingMutations();
        BlockRegistry registry = registry();

        DetailParentBreakResult result = new DetailParentBreakTransaction(
                mutations, new EntityRef(42), registry,
                new Phase17DetailActionPolicy(registry), worldItems(1))
                .executeSurvival(
                        hit(4, 7, 6, 11L),
                        detail(new int[] {0}, new byte[] {3}),
                        Optional.empty(), BodySlot.RIGHT_HAND, 12L, 13L);

        assertEquals(DetailParentBreakResult.Status.ACTION_REJECTED, result.status());
        assertEquals(0, result.producedItems());
        assertEquals(0, result.worldItemCommitted());
        assertEquals(0, mutations.removeCalls);
    }

    @Test
    void invalidTargetReportsNoProducedOrCommittedOutput() {
        RecordingMutations mutations = new RecordingMutations();
        BlockRegistry registry = registry();
        BlockHitResult invalid = new BlockHitResult(
                4, 7, 6,
                5, 7, 6,
                STONE,
                1, 0, 0,
                5.0f, 7.1f, 6.1f,
                2.0f,
                5.0, 7.1, 6.1,
                11L,
                FullRaycastTarget.INSTANCE);

        DetailParentBreakResult result = new DetailParentBreakTransaction(
                mutations, new EntityRef(42), registry,
                new Phase17DetailActionPolicy(registry), worldItems(1))
                .executeSurvival(
                        invalid, DetailCellState.uniform((byte) 1), Optional.empty(),
                        BodySlot.RIGHT_HAND, 12L, 13L);

        assertEquals(DetailParentBreakResult.Status.INVALID_TARGET, result.status());
        assertEquals(0, result.producedItems());
        assertEquals(0, result.worldItemCommitted());
        assertEquals(0, mutations.removeCalls);
    }

    @Test
    void unresolvedSurvivalSpawnBlocksCreativeMutationUntilCloseReconcilesIt() {
        RecordingMutations mutations = new RecordingMutations();
        FaultInjectingWorldItemService worldItems = new FaultInjectingWorldItemService();
        worldItems.failFirstCommit(
                CommitFailureKind.UNTYPED_BEFORE_APPLY,
                new IllegalStateException("commit unavailable"));
        worldItems.failAuditWith(new IllegalStateException("audit unavailable"));
        DetailParentBreakTransaction transaction = new DetailParentBreakTransaction(
                mutations, new EntityRef(42), registry(),
                new Phase17DetailActionPolicy(registry()), worldItems);

        WorldItemSpawnIndeterminateException barrier = assertThrows(
                WorldItemSpawnIndeterminateException.class,
                () -> transaction.executeSurvival(
                        hit(4, 7, 6, 11L), DetailCellState.uniform((byte) 1),
                        Optional.empty(), BodySlot.RIGHT_HAND, 12L, 13L));

        WorldItemSpawnIndeterminateException repeated = assertThrows(
                WorldItemSpawnIndeterminateException.class,
                () -> transaction.executeCreative(
                        hit(4, 7, 6, 12L), DetailCellState.uniform((byte) 1),
                        BodySlot.RIGHT_HAND, 13L, 14L));
        assertSame(barrier, repeated);
        assertEquals(1, mutations.removeCalls);

        worldItems.clearAuditFailure();
        transaction.close();
        assertEquals(1, worldItems.snapshots().size());
    }

    @Test
    void unresolvedFatalSpawnFailureRethrowsTheOriginalErrorAndRetainsBarrier() {
        RecordingMutations mutations = new RecordingMutations();
        FaultInjectingWorldItemService worldItems = new FaultInjectingWorldItemService();
        AssertionError fatal = new AssertionError("spawn fatal");
        worldItems.failFirstCommit(CommitFailureKind.UNTYPED_BEFORE_APPLY, fatal);
        worldItems.failAuditWith(new IllegalStateException("audit unavailable"));
        DetailParentBreakTransaction transaction = new DetailParentBreakTransaction(
                mutations, new EntityRef(42), registry(),
                new Phase17DetailActionPolicy(registry()), worldItems);

        AssertionError escaped = assertThrows(
                AssertionError.class,
                () -> transaction.executeSurvival(
                        hit(4, 7, 6, 11L), DetailCellState.uniform((byte) 1),
                        Optional.empty(), BodySlot.RIGHT_HAND, 12L, 13L));

        assertSame(fatal, escaped);
        assertTrue(java.util.Arrays.stream(escaped.getSuppressed())
                .anyMatch(WorldItemSpawnIndeterminateException.class::isInstance));
        assertEquals(1, mutations.removeCalls);

        worldItems.clearAuditFailure();
        assertSame(fatal, assertThrows(AssertionError.class, transaction::close));
    }

    private static BlockHitResult hit(int x, int y, int z, long revision) {
        LocalSubVoxelPosition local = new LocalSubVoxelPosition(0, 0, 0);
        return new BlockHitResult(
                x, y, z,
                x + 1, y, z,
                STONE,
                1, 0, 0,
                x + 1.0f, y + 0.1f, z + 0.1f,
                2.0f,
                x + 1.0, y + 0.1, z + 0.1,
                revision,
                new DetailRaycastTarget(VoxelScale.DETAIL_4, local));
    }

    private static DetailCellState detail(int[] occupied, byte[] materials) {
        byte[] ids = new byte[DetailCellState.CELL_COUNT];
        long mask = 0;
        for (int index = 0; index < occupied.length; index++) {
            mask |= 1L << occupied[index];
            ids[occupied[index]] = materials[index];
        }
        return new DetailCellState(mask, ids);
    }

    private static LogicalWorldItemService worldItems(int capacity) {
        return new LogicalWorldItemService(
                MainThreadGuard.captureCurrentThread(), capacity, 20);
    }

    private static BlockRegistry registry() {
        BlockDefinition air = block(0, "gaia:air", null);
        BlockDefinition stone = block(1, "gaia:stone", STONE_UNIT);
        BlockDefinition dirt = block(2, "gaia:dirt", DIRT_UNIT);
        BlockDefinition unsupported = block(3, "gaia:grass", null);
        return BlockRegistry.create(
                List.of(air, stone, dirt, unsupported),
                List.of(standalone(STONE_UNIT), standalone(DIRT_UNIT)),
                Map.of(
                        0, renderInfo(false),
                        1, renderInfo(true),
                        2, renderInfo(true),
                        3, renderInfo(true)));
    }

    private static BlockDefinition block(int id, String name, ResourceLocation unit) {
        ResourceLocation blockId = ResourceLocation.parse(name);
        return new BlockDefinition(
                id,
                blockId,
                ResourceLocation.parse("gaia:opaque"),
                textures(),
                id == 0 ? 0.0f : id == 1 ? 1.5f : 0.5f,
                1.0f,
                1.0f,
                false,
                false,
                1.0f,
                id == 0 ? null : new ItemFormDefinition(blockId, 64, false, false),
                unit == null ? null : new DetailSupportDefinition(unit));
    }

    private static StandaloneItemDefinition standalone(ResourceLocation id) {
        return new StandaloneItemDefinition(
                new ItemFormDefinition(id, 64, false, false),
                Set.of(),
                new ItemVisualReference(
                        ItemVisualType.ATLAS_REGION,
                        ResourceLocation.parse("gaia:blocks"),
                        ResourceLocation.parse("gaia:stone")));
    }

    private static EnumMap<BlockFace, ResourceLocation> textures() {
        EnumMap<BlockFace, ResourceLocation> result = new EnumMap<>(BlockFace.class);
        for (BlockFace face : BlockFace.values()) {
            result.put(face, ResourceLocation.parse("gaia:stone"));
        }
        return result;
    }

    private static BlockRenderInfo renderInfo(boolean renderable) {
        MaterialDefinition material = new MaterialDefinition(
                ResourceLocation.parse("gaia:opaque"),
                ResourceLocation.parse("gaia:blocks"),
                RenderType.OPAQUE,
                0.5f,
                ResourceLocation.parse("gaia:stone"));
        TextureRegion region = new TextureRegion(
                ResourceLocation.parse("gaia:stone"), 0, 0, 1, 1, 1, 1);
        EnumMap<BlockFace, TextureRegion> faces = new EnumMap<>(BlockFace.class);
        for (BlockFace face : BlockFace.values()) {
            faces.put(face, region);
        }
        return renderable
                ? new BlockRenderInfo(material, faces, true)
                : BlockRenderInfo.nonRenderable(material, region);
    }

    private static final class RecordingMutations implements DetailMutationService {
        private Optional<RemoveDetailParentRequest> remove = Optional.empty();
        private int removeCalls;
        private DetailMutationResult.Status nextStatus = DetailMutationResult.Status.APPLIED;
        private boolean appliedNotificationFailure;

        @Override
        public DetailMutationResult removeDetailParent(RemoveDetailParentRequest request) {
            remove = Optional.of(request);
            removeCalls++;
            boolean applied = nextStatus == DetailMutationResult.Status.APPLIED;
            DetailMutationResult result = new DetailMutationResult(
                    request.context(),
                    nextStatus,
                    Optional.of(request.expectedState()),
                    applied
                            ? Optional.of(new com.overlord.voxel.FullCellState((byte) 0))
                            : Optional.empty(),
                    request.expectedChunkRevision(),
                    applied ? request.expectedChunkRevision() + 1 : request.expectedChunkRevision(),
                    applied
                            ? List.of(new DirtyChunkRevision(
                                    ChunkKey.fromWorld(request.x(), request.z()),
                                    request.expectedChunkRevision() + 1))
                            : List.of());
            if (appliedNotificationFailure) {
                throw new DetailMutationEventDispatchException(
                        "detail notification failed", new RuntimeException("listener"), result);
            }
            return result;
        }

        @Override public DetailMutationResult convertFullToDetail(FullToDetailRequest request) { throw new AssertionError(); }
        @Override public DetailMutationResult setSubVoxel(DetailMutationRequest request) { throw new AssertionError(); }
        @Override public DetailMutationResult sculptParentSubVoxel(SculptParentSubVoxelRequest request) { throw new AssertionError(); }
        @Override public DetailMutationResult compactDetailToFull(DetailToFullRequest request) { throw new AssertionError(); }
    }
}
