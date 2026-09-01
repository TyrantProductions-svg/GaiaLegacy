package com.gaia.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gaia.blocks.BlockRegistry;
import com.gaia.interaction.DetailAction;
import com.gaia.interaction.DetailActionPolicy;
import com.gaia.interaction.DetailParentBreakResult;
import com.gaia.interaction.DetailParentBreakTransaction;
import com.gaia.interaction.DetailPrecisionTarget;
import com.gaia.interaction.BlockInteractionController;
import com.gaia.interaction.GameMode;
import com.gaia.interaction.GaiaDetailMutationService;
import com.gaia.interaction.GaiaInteractionContext;
import com.gaia.interaction.SurvivalDetailEditResult;
import com.gaia.interaction.SurvivalDetailEditTransaction;
import com.gaia.inventory.BodyInventoryService;
import com.gaia.save.snapshot.SaveGameSnapshot;
import com.overlord.assets.ResourceLocation;
import com.overlord.interaction.api.BlockFace;
import com.overlord.interaction.api.BlockHitResult;
import com.overlord.interaction.api.DetailMutationResult;
import com.overlord.interaction.api.FullToDetailRequest;
import com.overlord.interaction.api.InteractionAction;
import com.overlord.interaction.api.SculptParentSubVoxelRequest;
import com.overlord.inventory.api.BodySlot;
import com.overlord.inventory.api.ItemStack;
import com.overlord.physics.Aabb;
import com.overlord.physics.BlockCollisionShapeResolver;
import com.overlord.physics.BlockRaycast;
import com.overlord.physics.CollisionWorld;
import com.overlord.physics.DetailRaycastTarget;
import com.overlord.voxel.ChunkKey;
import com.overlord.voxel.ChunkMeshManager;
import com.overlord.voxel.DetailCellState;
import com.overlord.voxel.FullCellState;
import com.overlord.voxel.LocalSubVoxelPosition;
import com.overlord.voxel.VoxelScale;
import com.overlord.voxel.World;
import java.lang.reflect.Field;
import java.util.Optional;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

class DetailToolPersistenceIntegrationTest {
    private static final ChunkKey KEY = new ChunkKey(0, 0);
    private static final LocalSubVoxelPosition LOCAL = new LocalSubVoxelPosition(2, 1, 3);
    private static final ResourceLocation STONE = ResourceLocation.parse("gaia:stone");
    private static final ResourceLocation CHISEL = ResourceLocation.parse("gaia:chisel");
    private static final ResourceLocation STONE_UNIT =
            ResourceLocation.parse("gaia:stone_detail_unit");

    @Test
    void productionSessionSculptRoundTripsThroughFreshRestoreWithoutPreviewState()
            throws Exception {
        var access = GameSessionFactory.productionSessionTestAccess();
        GameSession first = access.factory().restore(
                ChunkStreamingSessionIntegrationTest.productionSnapshot());
        SaveGameSnapshot captured;
        DetailCellState expected;
        try {
            driveToReady(first);
            Object runtime = runtime(first);
            World world = field(runtime, "world", World.class);
            GaiaDetailMutationService mutations = field(
                    runtime, "detailMutations", GaiaDetailMutationService.class);
            long revision = world.chunks().revision(KEY);
            DetailMutationResult result = mutations.sculptParentSubVoxel(
                    new SculptParentSubVoxelRequest(
                            new GaiaInteractionContext(
                                    first.captureSave().snapshot().orElseThrow().player().owner(),
                                    BodySlot.RIGHT_HAND, InteractionAction.USE, 1, 1),
                            1, 2, 1, revision, new FullCellState((byte) 0), LOCAL,
                            Optional.of(STONE)));
            assertEquals(DetailMutationResult.Status.APPLIED, result.status());
            expected = assertInstanceOf(DetailCellState.class, result.newState().orElseThrow());
            captured = first.captureSave().snapshot().orElseThrow();
            assertEquals(expected, captured.chunks().chunks().stream()
                    .filter(chunk -> chunk.key().equals(KEY))
                    .findFirst().orElseThrow().cellState(1, 2, 1));
        } finally {
            first.close();
        }

        GameSession restored = access.factory().restore(captured);
        try {
            driveToReady(restored);
            SaveGameSnapshot recaptured = restored.captureSave().snapshot().orElseThrow();
            assertEquals(expected, recaptured.chunks().chunks().stream()
                    .filter(chunk -> chunk.key().equals(KEY))
                    .findFirst().orElseThrow().cellState(1, 2, 1));
            BlockInteractionController controller = field(
                    runtime(restored), "blockInteraction", BlockInteractionController.class);
            assertTrue(controller.viewModel().detailPreview().isEmpty(),
                    "preview/material-cycle intent is session transient, not save data");
            Object restoredRuntime = runtime(restored);
            World restoredWorld = field(restoredRuntime, "world", World.class);
            var shapes = BlockCollisionShapeResolver.fullCubesForNonAir();
            var rayHit = new BlockRaycast(restoredWorld, shapes).cast(
                    new Vector3f(0.0f, 2.375f, 1.875f),
                    new Vector3f(1.0f, 0.0f, 0.0f),
                    3.0f).orElseThrow();
            assertEquals(1, rayHit.blockX());
            assertInstanceOf(DetailRaycastTarget.class, rayHit.target());
            assertTrue(new CollisionWorld(restoredWorld, shapes).overlapsSolid(
                    new Aabb(1.51f, 2.26f, 1.76f, 1.74f, 2.49f, 1.99f)));
            ChunkMeshManager meshes = field(
                    restoredRuntime, "chunkMeshes", ChunkMeshManager.class);
            assertTrue(meshes.hasInstalledRenderObject(KEY));
        } finally {
            restored.close();
        }
        assertEquals(0, access.liveWorkerCount());
    }

    @Test
    void productionComposedSurvivalRecoveryRoundTripsCanonicalUnitAndVoxelState()
            throws Exception {
        var access = GameSessionFactory.productionSessionTestAccess();
        GameSession first = access.factory().restore(
                ChunkStreamingSessionIntegrationTest.productionSnapshot());
        SaveGameSnapshot captured;
        try {
            driveToReady(first);
            Object runtime = runtime(first);
            World world = field(runtime, "world", World.class);
            GaiaDetailMutationService mutations = field(
                    runtime, "detailMutations", GaiaDetailMutationService.class);
            BlockInteractionController controller = field(
                    runtime, "blockInteraction", BlockInteractionController.class);
            BodyInventoryService inventory = field(
                    runtime, "inventoryService", BodyInventoryService.class);
            var owner = first.captureSave().snapshot().orElseThrow().player().owner();
            assertTrue(inventory.insert(owner, new ItemStack(CHISEL, 1)).remainder().isEmpty());

            long revision = world.chunks().revision(KEY);
            DetailMutationResult setup = mutations.sculptParentSubVoxel(
                    new SculptParentSubVoxelRequest(
                            new GaiaInteractionContext(
                                    owner, BodySlot.RIGHT_HAND,
                                    InteractionAction.USE, 1, 1),
                            1, 2, 1, revision, new FullCellState((byte) 0), LOCAL,
                            Optional.of(STONE)));
            DetailCellState expected = assertInstanceOf(
                    DetailCellState.class, setup.newState().orElseThrow());
            long observedRevision = setup.resultingChunkRevision();
            DetailPrecisionTarget target = new DetailPrecisionTarget(
                    1, 2, 1, LOCAL, BlockFace.UP, STONE, observedRevision,
                    new DetailRaycastTarget(VoxelScale.DETAIL_4, LOCAL));

            SurvivalDetailEditTransaction transaction = optionalField(
                    controller, "survivalDetailEdits", SurvivalDetailEditTransaction.class);
            DetailActionPolicy policy = optionalField(
                    controller, "detailActionPolicy", DetailActionPolicy.class);
            BlockRegistry blocks = field(controller, "blocks", BlockRegistry.class);
            SurvivalDetailEditResult result = transaction.removeRecoverable(
                    target,
                    policy.decide(
                            GameMode.SURVIVAL,
                            DetailAction.PRECISION_REMOVE,
                            Optional.of(CHISEL),
                            blocks.require(STONE),
                            false),
                    BodySlot.RIGHT_HAND,
                    new GaiaInteractionContext(
                            owner, BodySlot.RIGHT_HAND,
                            InteractionAction.PRIMARY, 2, 2));
            assertEquals(SurvivalDetailEditResult.Status.APPLIED, result.status());
            assertEquals(new FullCellState((byte) 0), world.chunks().snapshot(KEY)
                    .orElseThrow().cellState(1, 2, 1));

            captured = first.captureSave().snapshot().orElseThrow();
            assertEquals(1, savedCount(captured, STONE_UNIT));
            assertEquals(1, savedCount(captured, CHISEL));
            assertEquals(1, Long.bitCount(expected.occupancyMask()));
        } finally {
            first.close();
        }

        GameSession restored = access.factory().restore(captured);
        try {
            driveToReady(restored);
            SaveGameSnapshot recaptured = restored.captureSave().snapshot().orElseThrow();
            assertEquals(new FullCellState((byte) 0), recaptured.chunks().chunks().stream()
                    .filter(chunk -> chunk.key().equals(KEY))
                    .findFirst().orElseThrow().cellState(1, 2, 1));
            assertEquals(1, savedCount(recaptured, STONE_UNIT));
            assertEquals(1, savedCount(recaptured, CHISEL));
        } finally {
            restored.close();
        }
        assertEquals(0, access.liveWorkerCount());
    }

    @Test
    void productionComposedUniformCoarseOutputRoundTripsAsOneFullWorldItem()
            throws Exception {
        var access = GameSessionFactory.productionSessionTestAccess();
        GameSession first = access.factory().restore(
                ChunkStreamingSessionIntegrationTest.productionSnapshot());
        SaveGameSnapshot captured;
        try {
            driveToReady(first);
            Object runtime = runtime(first);
            World world = field(runtime, "world", World.class);
            GaiaDetailMutationService mutations = field(
                    runtime, "detailMutations", GaiaDetailMutationService.class);
            BlockInteractionController controller = field(
                    runtime, "blockInteraction", BlockInteractionController.class);
            SaveGameSnapshot before = first.captureSave().snapshot().orElseThrow();
            var owner = before.player().owner();

            assertTrue(world.chunks().setBlock(1, 2, 1, (byte) 3));
            DetailMutationResult setup = mutations.convertFullToDetail(
                    new FullToDetailRequest(
                            new GaiaInteractionContext(
                                    owner, BodySlot.RIGHT_HAND,
                                    InteractionAction.USE, 3, 3),
                            1, 2, 1, world.chunks().revision(KEY), STONE));
            DetailCellState uniform = assertInstanceOf(
                    DetailCellState.class, setup.newState().orElseThrow());
            assertEquals(64, Long.bitCount(uniform.occupancyMask()));

            DetailParentBreakTransaction transaction = optionalField(
                    controller, "detailParentBreaks", DetailParentBreakTransaction.class);
            BlockHitResult target = detailHit(setup.resultingChunkRevision());
            DetailParentBreakResult result = transaction.executeSurvival(
                    target, uniform, Optional.empty(), BodySlot.RIGHT_HAND,
                    before.fixedTick(), 4);
            assertEquals(DetailParentBreakResult.Status.APPLIED, result.status());
            assertEquals(1, result.worldItemCommitted());

            captured = first.captureSave().snapshot().orElseThrow();
            assertEquals(1, captured.worldItems().entries().size());
            assertEquals(STONE, captured.worldItems().entries().get(0)
                    .runtime().item().stack().itemId());
        } finally {
            first.close();
        }

        GameSession restored = access.factory().restore(captured);
        try {
            driveToReady(restored);
            SaveGameSnapshot recaptured = restored.captureSave().snapshot().orElseThrow();
            assertEquals(new FullCellState((byte) 0), recaptured.chunks().chunks().stream()
                    .filter(chunk -> chunk.key().equals(KEY))
                    .findFirst().orElseThrow().cellState(1, 2, 1));
            assertEquals(1, recaptured.worldItems().entries().size());
            assertEquals(STONE, recaptured.worldItems().entries().get(0)
                    .runtime().item().stack().itemId());
        } finally {
            restored.close();
        }
        assertEquals(0, access.liveWorkerCount());
    }

    private static void driveToReady(GameSession session) {
        for (int poll = 0;
                poll < 100_000 && session.state() == GameSessionState.LOADING;
                poll++) {
            session.pollLoad();
            if (session.state() == GameSessionState.LOADING) {
                Thread.yield();
            }
        }
        assertEquals(GameSessionState.READY, session.state());
    }

    private static Object runtime(GameSession session) throws Exception {
        return rawField(session, "runtime");
    }

    private static <T> T field(Object target, String name, Class<T> type) throws Exception {
        return type.cast(rawField(target, name));
    }

    private static Object rawField(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static <T> T optionalField(
            Object target, String name, Class<T> type) throws Exception {
        Optional<?> value = (Optional<?>) rawField(target, name);
        return type.cast(value.orElseThrow());
    }

    private static int savedCount(SaveGameSnapshot snapshot, ResourceLocation itemId) {
        return snapshot.inventory().stacks().values().stream()
                .filter(stack -> stack.itemId().equals(itemId))
                .mapToInt(ItemStack::count)
                .sum();
    }

    private static BlockHitResult detailHit(long revision) {
        return new BlockHitResult(
                1, 2, 1, 1, 3, 1, STONE,
                0, 1, 0,
                1.5f, 3.0f, 1.5f, 2.0f,
                1.5, 3.0, 1.5,
                revision,
                new DetailRaycastTarget(VoxelScale.DETAIL_4, LOCAL));
    }
}
