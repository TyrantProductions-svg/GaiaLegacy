package com.gaia.interaction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_LEFT;
import static org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_RIGHT;

import com.gaia.blocks.BlockDefinition;
import com.gaia.blocks.BlockRegistry;
import com.gaia.blocks.DetailSupportDefinition;
import com.gaia.blocks.ItemCapability;
import com.gaia.blocks.ItemFormDefinition;
import com.gaia.blocks.ItemVisualReference;
import com.gaia.blocks.ItemVisualType;
import com.gaia.blocks.StandaloneItemDefinition;
import com.gaia.interaction.feedback.CommittedGameplayFeedback;
import com.gaia.inventory.BodyInventoryService;
import com.gaia.inventory.BodyInventoryReservationPlanner;
import com.overlord.assets.ResourceLocation;
import com.overlord.core.input.InputSnapshot;
import com.overlord.core.thread.MainThreadGuard;
import com.overlord.interaction.api.BlockChangeResult;
import com.overlord.interaction.api.BlockHitResult;
import com.overlord.interaction.api.EntityRef;
import com.overlord.physics.Aabb;
import com.overlord.physics.DetailRaycastTarget;
import com.overlord.physics.FullRaycastTarget;
import com.overlord.physics.MassProperties;
import com.overlord.physics.PhysicsBody;
import com.overlord.physics.SpatialQueryResult;
import com.overlord.renderer.material.MaterialDefinition;
import com.overlord.renderer.material.RenderType;
import com.overlord.renderer.texture.TextureRegion;
import com.overlord.voxel.BlockFace;
import com.overlord.voxel.BlockRenderInfo;
import com.overlord.voxel.ChunkDirtyTracker;
import com.overlord.voxel.ChunkKey;
import com.overlord.voxel.ChunkRepository;
import com.overlord.voxel.DetailCellState;
import com.overlord.voxel.DirtyChunkRevision;
import com.overlord.voxel.FullCellState;
import com.overlord.voxel.LocalSubVoxelPosition;
import com.overlord.voxel.ParentCellState;
import com.overlord.voxel.VoxelScale;
import com.overlord.worlditem.LogicalWorldItemService;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

class BlockInteractionControllerDetailCreativeTest {
    private static final EntityRef OWNER = new EntityRef(42);
    private static final ResourceLocation AIR = ResourceLocation.parse("gaia:air");
    private static final ResourceLocation STONE = ResourceLocation.parse("gaia:stone");
    private static final ResourceLocation DIRT = ResourceLocation.parse("gaia:dirt");
    private static final ResourceLocation CHISEL = ResourceLocation.parse("gaia:chisel");
    private static final ResourceLocation STONE_UNIT =
            ResourceLocation.parse("gaia:stone_detail_unit");
    private static final ResourceLocation DIRT_UNIT =
            ResourceLocation.parse("gaia:dirt_detail_unit");
    private static final ResourceLocation MISSING = ResourceLocation.parse("gaia:missing");

    @Test
    void creativeCoarseRouteRemovesWholeDetailParentOnce() {
        Fixture fixture = fixture(outsideBody());
        LocalSubVoxelPosition local = new LocalSubVoxelPosition(0, 0, 0);
        fixture.setDetail(1, 2, 3, detail(local, (byte) 1));
        fixture.target.set(detailHit(1, 2, 3, local, fixture.revision(1, 3)));
        fixture.creativeSelection.select(STONE);

        fixture.controller.fixedUpdate(primary(), 1.0 / 60.0, 1, 2, true);

        assertEquals(BlockInteractionRoute.DETAIL_COARSE_REMOVE,
                fixture.controller.viewModel().route().route());
        assertEquals(new FullCellState((byte) 0), fixture.state(1, 2, 3));
        assertEquals(1, fixture.feedback.detailRemovals);
        assertEquals(0, fixture.feedback.detailPlacements);
    }

    @Test
    void creativePrecisionFullSculptAndDetailRemovalMutateExactQuarter() {
        Fixture fullFixture = fixture(outsideBody());
        fullFixture.setFull(1, 2, 3, (byte) 1);
        LocalSubVoxelPosition local = new LocalSubVoxelPosition(3, 2, 1);
        fullFixture.target.set(fullHit(1, 2, 3, local, fullFixture.revision(1, 3)));
        assertTrue(fullFixture.creativeSelection.select(CHISEL));

        fullFixture.controller.fixedUpdate(primary(), 1.0 / 60.0, 1, 2, true);

        DetailCellState sculpted = assertInstanceOf(
                DetailCellState.class, fullFixture.state(1, 2, 3));
        assertEquals(63, Long.bitCount(sculpted.occupancyMask()));
        assertTrue(!sculpted.occupied(local));
        assertEquals(1, fullFixture.feedback.detailRemovals);

        Fixture detailFixture = fixture(outsideBody());
        detailFixture.setDetail(1, 2, 3, detail(local, (byte) 1));
        detailFixture.target.set(detailHit(1, 2, 3, local, detailFixture.revision(1, 3)));
        detailFixture.creativeSelection.select(CHISEL);
        detailFixture.controller.fixedUpdate(primary(), 1.0 / 60.0, 1, 2, true);
        assertEquals(new FullCellState((byte) 0), detailFixture.state(1, 2, 3));
        assertEquals(1, detailFixture.feedback.detailRemovals);
    }

    @Test
    void creativePrecisionPlaceSupportsSameParentAndFullAirAcrossParentBoundary() {
        Fixture same = fixture(outsideBody());
        LocalSubVoxelPosition source = new LocalSubVoxelPosition(0, 0, 0);
        same.setDetail(1, 2, 3, detail(source, (byte) 1));
        same.target.set(detailHit(1, 2, 3, source, same.revision(1, 3)));
        same.creativeSelection.select(CHISEL);

        same.controller.fixedUpdate(secondary(), 1.0 / 60.0, 1, 2, true);

        DetailCellState placed = assertInstanceOf(DetailCellState.class, same.state(1, 2, 3));
        assertTrue(placed.occupied(new LocalSubVoxelPosition(1, 0, 0)));
        assertEquals(1, same.feedback.detailPlacements);

        Fixture across = fixture(outsideBody());
        LocalSubVoxelPosition edge = new LocalSubVoxelPosition(3, 0, 0);
        across.setDetail(15, 2, 15, detail(edge, (byte) 1));
        across.ensureChunk(new ChunkKey(1, 0));
        across.target.set(detailHit(15, 2, 15, edge, across.revision(15, 15)));
        across.creativeSelection.select(CHISEL);
        across.controller.fixedUpdate(secondary(), 1.0 / 60.0, 1, 2, true);
        DetailCellState first = assertInstanceOf(DetailCellState.class, across.state(16, 2, 15));
        assertTrue(first.occupied(new LocalSubVoxelPosition(0, 0, 0)));
    }

    @Test
    void overlapRejectsWhileSurvivalPrecisionRemovalConservesOneUnit() {
        PhysicsBody overlapping = bodyAt(new Vector3f(1.4f, 2.0f, 3.1f));
        Fixture overlap = fixture(overlapping);
        LocalSubVoxelPosition source = new LocalSubVoxelPosition(0, 0, 0);
        overlap.setDetail(1, 2, 3, detail(source, (byte) 1));
        overlap.target.set(detailHit(1, 2, 3, source, overlap.revision(1, 3)));
        overlap.creativeSelection.select(CHISEL);
        long before = overlap.revision(1, 3);
        overlap.controller.fixedUpdate(secondary(), 1.0 / 60.0, 1, 2, true);
        assertEquals(before, overlap.revision(1, 3));
        assertEquals(0, overlap.feedback.detailPlacements);
        assertEquals(ResourceLocation.parse("gaia:interaction/player_intersection"),
                overlap.controller.viewModel().failureReason().orElseThrow().code());

        Fixture survival = fixture(outsideBody());
        survival.setDetail(1, 2, 3, detail(source, (byte) 1));
        survival.target.set(detailHit(1, 2, 3, source, survival.revision(1, 3)));
        survival.modes.setMode(GameMode.SURVIVAL, 0);
        survival.inventory.insert(OWNER, new com.overlord.inventory.api.ItemStack(CHISEL, 1));
        long survivalBefore = survival.revision(1, 3);
        survival.controller.fixedUpdate(primary(), 1.0 / 60.0, 1, 2, true);
        assertEquals(survivalBefore + 1, survival.revision(1, 3));
        assertEquals(1, survival.inventory.totalCount(OWNER, STONE_UNIT));
        assertEquals(1, survival.feedback.detailRemovals);
        assertTrue(survival.controller.viewModel().failureReason().isEmpty());
    }

    @Test
    void simultaneousEdgesUsePrimaryRouteAndPerformAtMostOneMutation() {
        Fixture fixture = fixture(outsideBody());
        LocalSubVoxelPosition local = new LocalSubVoxelPosition(0, 0, 0);
        fixture.setDetail(1, 2, 3, detail(local, (byte) 1));
        fixture.target.set(detailHit(1, 2, 3, local, fixture.revision(1, 3)));
        fixture.creativeSelection.select(CHISEL);
        long before = fixture.revision(1, 3);

        fixture.controller.fixedUpdate(
                new InputSnapshot(
                        Set.of(), Set.of(),
                        Set.of(GLFW_MOUSE_BUTTON_LEFT, GLFW_MOUSE_BUTTON_RIGHT),
                        Set.of(GLFW_MOUSE_BUTTON_LEFT, GLFW_MOUSE_BUTTON_RIGHT),
                        List.of()),
                1.0 / 60.0,
                1,
                2,
                true);

        assertEquals(BlockInteractionRoute.DETAIL_PRECISION_REMOVE,
                fixture.controller.viewModel().route().route());
        assertEquals(before + 1, fixture.revision(1, 3));
        assertEquals(1, fixture.feedback.detailRemovals);
        assertEquals(0, fixture.feedback.detailPlacements);
    }

    @Test
    void survivalPrecisionPlacementConsumesExactlyOneMatchingUnit() {
        Fixture fixture = fixture(outsideBody());
        LocalSubVoxelPosition source = new LocalSubVoxelPosition(0, 0, 0);
        fixture.setDetail(1, 2, 3, detail(source, (byte) 1));
        fixture.target.set(detailHit(1, 2, 3, source, fixture.revision(1, 3)));
        fixture.inventory.insert(OWNER, new com.overlord.inventory.api.ItemStack(CHISEL, 1));
        fixture.inventory.insert(OWNER, new com.overlord.inventory.api.ItemStack(STONE_UNIT, 1));
        fixture.modes.setMode(GameMode.SURVIVAL, 0);

        fixture.controller.fixedUpdate(secondary(), 1.0 / 60.0, 1, 2, true);

        DetailCellState state = assertInstanceOf(DetailCellState.class, fixture.state(1, 2, 3));
        assertTrue(state.occupied(new LocalSubVoxelPosition(1, 0, 0)));
        assertEquals(0, fixture.inventory.totalCount(OWNER, STONE_UNIT));
        assertEquals(1, fixture.feedback.detailPlacements);
    }

    @Test
    void survivalCoarseBreakUsesExistingBreakTrackerAndProducesOneFullItem() {
        Fixture fixture = fixture(outsideBody());
        fixture.setDetail(1, 2, 3, DetailCellState.uniform((byte) 1));
        fixture.target.set(detailHit(
                1, 2, 3, new LocalSubVoxelPosition(0, 0, 0), fixture.revision(1, 3)));
        fixture.inventory.insert(OWNER, new com.overlord.inventory.api.ItemStack(STONE, 1));
        fixture.modes.setMode(GameMode.SURVIVAL, 0);

        for (int frame = 0; frame < 60; frame++) {
            fixture.controller.fixedUpdate(
                    frame == 0 ? primary() : heldPrimary(),
                    1.0 / 60.0,
                    frame + 1,
                    frame + 2,
                    true);
        }

        assertEquals(new FullCellState((byte) 0), fixture.state(1, 2, 3));
        assertEquals(1, fixture.worldItems.snapshots().size());
        assertEquals(STONE, fixture.worldItems.snapshots().get(0).stack().itemId());
    }

    private static Fixture fixture(PhysicsBody body) {
        BlockRegistry blocks = blocks();
        ChunkRepository chunks = new ChunkRepository(32, new ChunkDirtyTracker());
        chunks.generate(new ChunkKey(0, 0), ignored -> {});
        BodyInventoryService inventory = new BodyInventoryService(
                OWNER, blocks, MainThreadGuard.captureCurrentThread(), ignored -> {});
        LogicalWorldItemService worldItems = new LogicalWorldItemService(
                MainThreadGuard.captureCurrentThread(), 16, 10);
        AtomicReference<BlockHitResult> target = new AtomicReference<>();
        BlockTargetProvider targeting = () ->
                SpatialQueryResult.available(Optional.ofNullable(target.get()));
        com.overlord.interaction.api.WorldMutationService fullMutations = request ->
                new BlockChangeResult(
                        request,
                        BlockChangeResult.Status.APPLIED,
                        Optional.of(request.expectedBlock()),
                        List.of(new DirtyChunkRevision(new ChunkKey(0, 0), 2)));
        BlockPlacementWorldView placementWorld = new BlockPlacementWorldView() {
            @Override public boolean isLoaded(int x, int y, int z) { return true; }
            @Override public ParentCellState parentStateAt(int x, int y, int z) { return new FullCellState((byte) 0); }
            @Override public ResourceLocation blockAt(int x, int y, int z) { return AIR; }
        };
        GameModeManager modes = new GameModeManager(GameMode.CREATIVE, ignored -> {});
        CreativeSelection selection = new CreativeSelection(blocks, Optional.of(STONE));
        RecordingFeedback feedback = new RecordingFeedback();
        GaiaDetailMutationService mutations = new GaiaDetailMutationService(
                MainThreadGuard.captureCurrentThread(), blocks, chunks);
        GaiaBlockWorldAccess detailWorld = new GaiaBlockWorldAccess(
                new com.overlord.voxel.World(chunks), blocks);
        CreativeDetailEditTransaction precision = new CreativeDetailEditTransaction(
                mutations,
                detailWorld,
                new DetailPlacementCollisionValidator(),
                body,
                OWNER);
        DetailParentBreakTransaction coarse = new DetailParentBreakTransaction(
                mutations,
                OWNER,
                blocks,
                new Phase17DetailActionPolicy(blocks),
                worldItems);
        BlockInteractionController controller = new BlockInteractionController(
                modes,
                targeting,
                chunks,
                blocks,
                inventory,
                OWNER,
                selection,
                new BlockBreakTransaction(fullMutations, inventory, OWNER, worldItems, AIR),
                new BlockPlacementTransaction(
                        fullMutations, inventory, OWNER, blocks, placementWorld, body, AIR),
                1,
                feedback,
                detailWorld,
                precision,
                new SurvivalDetailEditTransaction(
                        mutations,
                        detailWorld,
                        new BodyInventoryReservationPlanner(inventory),
                        inventory,
                        OWNER,
                        new DetailPlacementCollisionValidator(),
                        body),
                coarse,
                new Phase17DetailActionPolicy(blocks));
        return new Fixture(
                controller, modes, selection, chunks, target, feedback, inventory, worldItems);
    }

    private static BlockHitResult detailHit(
            int x, int y, int z, LocalSubVoxelPosition local, long revision) {
        return hit(x, y, z, local, revision,
                new DetailRaycastTarget(VoxelScale.DETAIL_4, local));
    }

    private static BlockHitResult fullHit(
            int x, int y, int z, LocalSubVoxelPosition local, long revision) {
        double worldY = y + (local.y() + 0.5) * 0.25;
        double worldZ = z + (local.z() + 0.5) * 0.25;
        return new BlockHitResult(
                x, y, z, x + 1, y, z, STONE,
                1, 0, 0,
                x + 1, (float) worldY, (float) worldZ, 2,
                x + 1.0, worldY, worldZ,
                revision,
                FullRaycastTarget.INSTANCE);
    }

    private static BlockHitResult hit(
            int x,
            int y,
            int z,
            LocalSubVoxelPosition local,
            long revision,
            com.overlord.physics.RaycastCellTarget target) {
        double worldY = y + (local.y() + 0.5) * 0.25;
        double worldZ = z + (local.z() + 0.5) * 0.25;
        return new BlockHitResult(
                x, y, z, x + 1, y, z, STONE,
                1, 0, 0,
                x + 1, (float) worldY, (float) worldZ, 2,
                x + 1.0, worldY, worldZ,
                revision,
                target);
    }

    private static InputSnapshot primary() {
        return new InputSnapshot(
                Set.of(), Set.of(), Set.of(GLFW_MOUSE_BUTTON_LEFT),
                Set.of(GLFW_MOUSE_BUTTON_LEFT), List.of());
    }

    private static InputSnapshot secondary() {
        return new InputSnapshot(
                Set.of(), Set.of(), Set.of(GLFW_MOUSE_BUTTON_RIGHT),
                Set.of(GLFW_MOUSE_BUTTON_RIGHT), List.of());
    }

    private static InputSnapshot heldPrimary() {
        return new InputSnapshot(
                Set.of(), Set.of(), Set.of(GLFW_MOUSE_BUTTON_LEFT), Set.of(), List.of());
    }

    private static PhysicsBody outsideBody() {
        return bodyAt(new Vector3f(100, 100, 100));
    }

    private static PhysicsBody bodyAt(Vector3f position) {
        PhysicsBody body = new PhysicsBody(
                new Aabb(-0.3f, 0, -0.3f, 0.3f, 1.8f, 0.3f),
                MassProperties.dynamic(1));
        body.teleport(position);
        return body;
    }

    private static DetailCellState detail(LocalSubVoxelPosition local, byte id) {
        byte[] ids = new byte[DetailCellState.CELL_COUNT];
        ids[local.index()] = id;
        return new DetailCellState(1L << local.index(), ids);
    }

    private static BlockRegistry blocks() {
        MaterialDefinition material = new MaterialDefinition(
                ResourceLocation.parse("gaia:opaque"),
                ResourceLocation.parse("gaia:blocks"),
                RenderType.OPAQUE, 0.5f, MISSING);
        TextureRegion region = new TextureRegion(MISSING, 0, 0, 1, 1, 1, 1);
        return BlockRegistry.create(
                List.of(definition(0, AIR, material.id()),
                        definition(1, STONE, material.id()),
                        definition(2, DIRT, material.id())),
                List.of(
                        standalone(CHISEL, 1, Set.of(ItemCapability.DETAIL_PRECISION)),
                        standalone(STONE_UNIT, 64, Set.of()),
                        standalone(DIRT_UNIT, 64, Set.of())),
                Map.of(
                        0, BlockRenderInfo.nonRenderable(material, region),
                        1, renderInfo(material, region),
                        2, renderInfo(material, region)));
    }

    private static BlockDefinition definition(
            int id, ResourceLocation name, ResourceLocation material) {
        EnumMap<BlockFace, ResourceLocation> textures = new EnumMap<>(BlockFace.class);
        for (BlockFace face : BlockFace.values()) textures.put(face, MISSING);
        return new BlockDefinition(
                id, name, material, textures, 1, 1, 1,
                false, false, 1,
                id == 0 ? null : new ItemFormDefinition(name, 64, false, false),
                id == 1 ? new DetailSupportDefinition(STONE_UNIT)
                        : id == 2 ? new DetailSupportDefinition(DIRT_UNIT) : null);
    }

    private static StandaloneItemDefinition standalone(
            ResourceLocation id, int maxStack, Set<ItemCapability> capabilities) {
        return new StandaloneItemDefinition(
                new ItemFormDefinition(id, maxStack, false, false),
                capabilities,
                new ItemVisualReference(
                        ItemVisualType.ATLAS_REGION,
                        ResourceLocation.parse("gaia:blocks"),
                        id.equals(CHISEL) ? ResourceLocation.parse("gaia:chisel") : STONE));
    }

    private static BlockRenderInfo renderInfo(
            MaterialDefinition material, TextureRegion region) {
        EnumMap<BlockFace, TextureRegion> faces = new EnumMap<>(BlockFace.class);
        for (BlockFace face : BlockFace.values()) faces.put(face, region);
        return new BlockRenderInfo(material, faces, true);
    }

    private static final class RecordingFeedback implements CommittedGameplayFeedback {
        private int detailRemovals;
        private int detailPlacements;

        @Override
        public void onDetailRemovalCommitted(
                DetailPrecisionTarget target,
                ResourceLocation material,
                long eventIdentity) {
            detailRemovals++;
        }

        @Override
        public void onDetailPlacementCommitted(
                DetailPlacementCandidate candidate,
                long eventIdentity) {
            detailPlacements++;
        }
    }

    private record Fixture(
            BlockInteractionController controller,
            GameModeManager modes,
            CreativeSelection creativeSelection,
            ChunkRepository chunks,
            AtomicReference<BlockHitResult> target,
            RecordingFeedback feedback,
            BodyInventoryService inventory,
            LogicalWorldItemService worldItems) {
        void ensureChunk(ChunkKey key) {
            if (!chunks.contains(key)) chunks.generate(key, ignored -> {});
        }

        void setFull(int x, int y, int z, byte id) {
            ensureChunk(ChunkKey.fromWorld(x, z));
            chunks.setBlock(x, y, z, id);
        }

        void setDetail(int x, int y, int z, DetailCellState detail) {
            ensureChunk(ChunkKey.fromWorld(x, z));
            ParentCellState old = state(x, y, z);
            long revision = revision(x, z);
            if (old instanceof FullCellState full && full.blockId() != 0) {
                throw new IllegalStateException("fixture requires AIR parent");
            }
            byte first = detail.blockIdAtIndex(Long.numberOfTrailingZeros(detail.occupancyMask()));
            LocalSubVoxelPosition firstPosition = LocalSubVoxelPosition.fromIndex(
                    Long.numberOfTrailingZeros(detail.occupancyMask()));
            com.overlord.voxel.ChunkDetailMutationOutcome placed = chunks.mutateDetail(
                    new com.overlord.voxel.ChunkDetailMutation.SculptParentSubVoxel(
                            x, y, z, revision, old, firstPosition, first));
            ParentCellState current = placed.newState().orElseThrow();
            for (int index = 0; index < DetailCellState.CELL_COUNT; index++) {
                byte id = detail.blockIdAtIndex(index);
                if (id != 0 && index != firstPosition.index()) {
                    com.overlord.voxel.ChunkDetailMutationOutcome next = chunks.mutateDetail(
                            new com.overlord.voxel.ChunkDetailMutation.SculptParentSubVoxel(
                                    x, y, z, chunks.revision(ChunkKey.fromWorld(x, z)), current,
                                    LocalSubVoxelPosition.fromIndex(index), id));
                    current = next.newState().orElseThrow();
                }
            }
        }

        ParentCellState state(int x, int y, int z) {
            return chunks.observeCell(x, y, z).observation().orElseThrow().state();
        }

        long revision(int x, int z) {
            return chunks.revision(ChunkKey.fromWorld(x, z));
        }
    }
}
