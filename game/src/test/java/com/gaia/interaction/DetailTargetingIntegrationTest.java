package com.gaia.interaction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gaia.blocks.BlockDefinition;
import com.gaia.blocks.BlockRegistry;
import com.gaia.blocks.ItemFormDefinition;
import com.gaia.inventory.BodyInventoryService;
import com.overlord.assets.ResourceLocation;
import com.overlord.core.thread.MainThreadGuard;
import com.overlord.interaction.api.BlockHitResult;
import com.overlord.interaction.api.DetailMutationRequest;
import com.overlord.interaction.api.DetailMutationResult;
import com.overlord.interaction.api.EntityRef;
import com.overlord.interaction.api.InteractionAction;
import com.overlord.inventory.api.BodySlot;
import com.overlord.inventory.api.ItemStack;
import com.overlord.physics.Aabb;
import com.overlord.physics.BlockCollisionShapeResolver;
import com.overlord.physics.BlockRaycast;
import com.overlord.physics.DetailRaycastTarget;
import com.overlord.physics.MassProperties;
import com.overlord.physics.PhysicsBody;
import com.overlord.physics.SimulationOrigin;
import com.overlord.physics.SpatialQueryResult;
import com.overlord.renderer.material.MaterialDefinition;
import com.overlord.renderer.material.RenderType;
import com.overlord.renderer.texture.TextureRegion;
import com.overlord.voxel.BlockFace;
import com.overlord.voxel.BlockRenderInfo;
import com.overlord.voxel.ChunkDetailMutation;
import com.overlord.voxel.ChunkDetailMutationOutcome;
import com.overlord.voxel.ChunkKey;
import com.overlord.voxel.LocalSubVoxelPosition;
import com.overlord.voxel.ParentCellObservation;
import com.overlord.voxel.World;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

class DetailTargetingIntegrationTest {
    private static final ResourceLocation AIR = ResourceLocation.parse("gaia:air");
    private static final ResourceLocation STONE = ResourceLocation.parse("gaia:stone");
    private static final ResourceLocation DIRT = ResourceLocation.parse("gaia:dirt");
    private static final ResourceLocation MISSING = ResourceLocation.parse("gaia:missing");
    private static final MaterialDefinition MATERIAL = new MaterialDefinition(
            ResourceLocation.parse("gaia:opaque"),
            ResourceLocation.parse("gaia:blocks"),
            RenderType.OPAQUE, 0.5f, MISSING);
    private static final TextureRegion REGION =
            new TextureRegion(MISSING, 0, 0, 1, 1, 1, 1);

    @Test
    void actualDetailRaycastMapsExactRegistryIdentityAndProvenance() {
        World world = detailWorld(1, 0, 0,
                new LocalSubVoxelPosition(0, 0, 0), (byte) 2);
        GaiaBlockRaycastService service = service(
                world, new SimulationOrigin(new ChunkKey(0, 0)));

        SpatialQueryResult<BlockHitResult> result = service.query(
                new Vector3f(0.5f, 0.125f, 0.125f),
                new Vector3f(1, 0, 0), 2);

        assertEquals(SpatialQueryResult.Status.AVAILABLE, result.status());
        BlockHitResult hit = result.result().orElseThrow();
        assertEquals(DIRT, hit.block());
        DetailRaycastTarget target = assertInstanceOf(
                DetailRaycastTarget.class, hit.target());
        assertEquals(new LocalSubVoxelPosition(0, 0, 0), target.position());
        assertEquals(world.chunks().revision(new ChunkKey(0, 0)),
                hit.chunkRevision());
    }

    @Test
    void fixedStateTargetIsInvariantAcrossRebaseAndRenderSamplingRates() {
        ChunkKey targetKey = new ChunkKey(101, -50);
        World world = detailWorld(
                targetKey.worldOriginX(), 1, targetKey.worldOriginZ(),
                new LocalSubVoxelPosition(0, 2, 2), (byte) 1);
        world.generate(new ChunkKey(100, -50), ignored -> {});
        GaiaBlockRaycastService before = service(
                world, new SimulationOrigin(new ChunkKey(100, -50)));
        GaiaBlockRaycastService after = service(
                world, new SimulationOrigin(targetKey));

        BlockHitResult expected = before.query(
                new Vector3f(15.5f, 1.625f, 0.625f),
                new Vector3f(1, 0, 0), 2).result().orElseThrow();
        BlockHitResult rebased = after.query(
                new Vector3f(-0.5f, 1.625f, 0.625f),
                new Vector3f(1, 0, 0), 2).result().orElseThrow();

        assertEquals(expected.blockX(), rebased.blockX());
        assertEquals(expected.blockY(), rebased.blockY());
        assertEquals(expected.blockZ(), rebased.blockZ());
        assertEquals(expected.target(), rebased.target());
        assertEquals(expected.chunkRevision(), rebased.chunkRevision());
        assertEquals(expected.worldPointX(), rebased.worldPointX(), 1.0e-12);
        assertEquals(expected.worldPointZ(), rebased.worldPointZ(), 1.0e-12);
        for (int renderRate : new int[] {10, 60, 144, 240}) {
            BlockHitResult sampled = before.query(
                    new Vector3f(15.5f, 1.625f, 0.625f),
                    new Vector3f(1, 0, 0), 2).result().orElseThrow();
            assertEquals(expected, sampled, "render rate " + renderRate);
        }
    }

    @Test
    void staleDetailTargetRevisionIsRejectedByGate16BMutationService() {
        LocalSubVoxelPosition position = new LocalSubVoxelPosition(0, 0, 0);
        World world = detailWorld(1, 0, 0, position, (byte) 2);
        ParentCellObservation observed = world.observeCell(1, 0, 0)
                .observation().orElseThrow();
        BlockHitResult hit = service(
                world, new SimulationOrigin(new ChunkKey(0, 0)))
                .query(new Vector3f(0.5f, 0.125f, 0.125f),
                        new Vector3f(1, 0, 0), 2)
                .result().orElseThrow();
        placeDetail(world, 1, 0, 0,
                new LocalSubVoxelPosition(1, 0, 0), (byte) 1);
        GaiaDetailMutationService mutations = new GaiaDetailMutationService(
                MainThreadGuard.captureCurrentThread(), registry(), world.chunks());
        DetailRaycastTarget target = assertInstanceOf(
                DetailRaycastTarget.class, hit.target());

        DetailMutationResult stale = mutations.setSubVoxel(
                new DetailMutationRequest(
                        new GaiaInteractionContext(
                                new EntityRef(42), BodySlot.RIGHT_HAND,
                                InteractionAction.USE, 17L, 19L),
                        hit.blockX(), hit.blockY(), hit.blockZ(),
                        hit.chunkRevision(), observed.state(), target.position(),
                        Optional.empty()));

        assertEquals(DetailMutationResult.Status.STALE_CHUNK_REVISION,
                stale.status());
        assertTrue(stale.dirtiedChunks().isEmpty());
    }

    @Test
    void legacyPlacementRejectsDetailDestinationBehindGapWithoutRawByteRead() {
        World world = detailWorld(
                1, 0, 0,
                new LocalSubVoxelPosition(0, 3, 3), (byte) 2);
        assertTrue(world.setBlock(2, 0, 0, (byte) 1));
        BlockRegistry blocks = registry();
        BlockHitResult fullBehindGap = service(
                world, new SimulationOrigin(new ChunkKey(0, 0)))
                .query(
                        new Vector3f(0.5f, 0.125f, 0.125f),
                        new Vector3f(1, 0, 0), 3)
                .result().orElseThrow();
        assertEquals(2, fullBehindGap.blockX());
        assertEquals(1, fullBehindGap.adjacentX());
        DetailPlacementCandidate gapCandidate =
                DetailTargeting.placementCandidate(
                        fullBehindGap,
                        STONE,
                        new GaiaBlockWorldAccess(world, blocks));
        assertEquals(2, gapCandidate.source().parentX());
        assertEquals(1, gapCandidate.parentX());
        assertEquals(
                DetailPlacementCandidate.Status.VALID_DETAIL_EMPTY,
                gapCandidate.status());

        EntityRef owner = new EntityRef(84);
        BodyInventoryService inventory = new BodyInventoryService(
                owner, blocks, MainThreadGuard.captureCurrentThread(), event -> {});
        PhysicsBody player = new PhysicsBody(
                new Aabb(-0.3f, 0, -0.3f, 0.3f, 1.8f, 0.3f),
                MassProperties.dynamic(1));
        player.teleport(new Vector3f(-4, 0, 0));
        AtomicInteger mutations = new AtomicInteger();
        BlockPlacementTransaction transaction = new BlockPlacementTransaction(
                request -> {
                    mutations.incrementAndGet();
                    throw new AssertionError("DETAIL destination must reject before mutation");
                },
                inventory,
                owner,
                blocks,
                new GaiaBlockWorldAccess(world, blocks),
                player,
                AIR);

        BlockPlacementResult result = transaction.execute(
                fullBehindGap,
                Optional.of(new ItemStack(STONE, 1)),
                GameMode.CREATIVE,
                BodySlot.RIGHT_HAND,
                21L,
                34L);

        assertEquals(BlockPlacementResult.Status.NOT_REPLACEABLE, result.status());
        assertEquals(0, mutations.get());
        assertInstanceOf(
                com.overlord.voxel.DetailCellState.class,
                world.observeCell(1, 0, 0).observation().orElseThrow().state());
    }

    private static GaiaBlockRaycastService service(
            World world, SimulationOrigin origin) {
        return new GaiaBlockRaycastService(
                new BlockRaycast(
                        world, BlockCollisionShapeResolver.fullCubesForNonAir()),
                registry(), () -> origin);
    }

    private static World detailWorld(
            int x, int y, int z,
            LocalSubVoxelPosition position, byte blockId) {
        World world = new World();
        world.generate(ChunkKey.fromWorld(x, z), ignored -> {});
        placeDetail(world, x, y, z, position, blockId);
        return world;
    }

    private static void placeDetail(
            World world, int x, int y, int z,
            LocalSubVoxelPosition position, byte blockId) {
        ParentCellObservation observation = world.observeCell(x, y, z)
                .observation().orElseThrow();
        ChunkDetailMutationOutcome outcome = world.chunks().mutateDetail(
                new ChunkDetailMutation.SetSubVoxel(
                        x, y, z, observation.chunkRevision(), observation.state(),
                        position, blockId));
        assertEquals(ChunkDetailMutationOutcome.Status.APPLIED, outcome.status());
    }

    private static BlockRegistry registry() {
        return BlockRegistry.create(
                List.of(definition(0, AIR), definition(1, STONE), definition(2, DIRT)),
                Map.of(0, renderInfo(false), 1, renderInfo(true), 2, renderInfo(true)));
    }

    private static BlockDefinition definition(int id, ResourceLocation name) {
        EnumMap<BlockFace, ResourceLocation> textures = new EnumMap<>(BlockFace.class);
        for (BlockFace face : BlockFace.values()) {
            textures.put(face, MISSING);
        }
        return new BlockDefinition(
                id, name, MATERIAL.id(), textures,
                1, 1, 1, false, false, 1,
                id == 0 ? null : new ItemFormDefinition(name, 64, false, false));
    }

    private static BlockRenderInfo renderInfo(boolean renderable) {
        EnumMap<BlockFace, TextureRegion> faces = new EnumMap<>(BlockFace.class);
        for (BlockFace face : BlockFace.values()) {
            faces.put(face, REGION);
        }
        return renderable
                ? new BlockRenderInfo(MATERIAL, faces, true)
                : BlockRenderInfo.nonRenderable(MATERIAL, REGION);
    }
}
