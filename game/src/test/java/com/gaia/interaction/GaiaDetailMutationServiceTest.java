package com.gaia.interaction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gaia.blocks.BlockDefinition;
import com.gaia.blocks.BlockRegistry;
import com.gaia.blocks.ItemFormDefinition;
import com.overlord.assets.ResourceLocation;
import com.overlord.core.thread.MainThreadGuard;
import com.overlord.interaction.api.DetailMutationRequest;
import com.overlord.interaction.api.DetailMutationResult;
import com.overlord.interaction.api.DetailToFullRequest;
import com.overlord.interaction.api.EntityRef;
import com.overlord.interaction.api.FullToDetailRequest;
import com.overlord.interaction.api.InteractionAction;
import com.overlord.interaction.api.RemoveDetailParentRequest;
import com.overlord.interaction.api.SculptParentSubVoxelRequest;
import com.overlord.inventory.api.BodySlot;
import com.overlord.renderer.material.MaterialDefinition;
import com.overlord.renderer.material.RenderType;
import com.overlord.renderer.texture.TextureRegion;
import com.overlord.voxel.BlockFace;
import com.overlord.voxel.BlockRenderInfo;
import com.overlord.voxel.ChunkDirtyTracker;
import com.overlord.voxel.ChunkKey;
import com.overlord.voxel.ChunkRepository;
import com.overlord.voxel.DetailCellState;
import com.overlord.voxel.FullCellState;
import com.overlord.voxel.LocalSubVoxelPosition;
import java.lang.reflect.Method;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class GaiaDetailMutationServiceTest {
    private static final ResourceLocation AIR =
            ResourceLocation.parse("gaia:air");
    private static final ResourceLocation STONE =
            ResourceLocation.parse("gaia:stone");
    private static final ResourceLocation DIRT =
            ResourceLocation.parse("gaia:dirt");
    private static final ResourceLocation MISSING =
            ResourceLocation.parse("gaia:missing");
    private static final MaterialDefinition MATERIAL =
            new MaterialDefinition(
                    ResourceLocation.parse("gaia:opaque"),
                    ResourceLocation.parse("gaia:blocks"),
                    RenderType.OPAQUE,
                    0.5f,
                    MISSING);
    private static final TextureRegion REGION =
            new TextureRegion(MISSING, 0, 0, 1, 1, 1, 1);

    @Test
    void convertsRegistryIdentityAndPreservesContextAndExactStates() {
        Fixture fixture = fixture((byte) 1);
        GaiaInteractionContext context = context();
        long before = fixture.repository().revision(new ChunkKey(0, 0));

        DetailMutationResult result =
                fixture.service()
                        .convertFullToDetail(
                                new FullToDetailRequest(
                                        context,
                                        4,
                                        7,
                                        6,
                                        before,
                                        STONE));

        assertEquals(DetailMutationResult.Status.APPLIED, result.status());
        assertSame(context, result.context());
        assertSame(context.actor(), result.context().actor());
        assertEquals(91L, result.context().tick());
        assertEquals(new FullCellState((byte) 1), result.oldState().orElseThrow());
        DetailCellState detail =
                assertInstanceOf(
                        DetailCellState.class,
                        result.newState().orElseThrow());
        assertEquals(-1L, detail.occupancyMask());
        for (byte id : detail.copyBlockIds()) {
            assertEquals(1, Byte.toUnsignedInt(id));
        }
        assertEquals(before + 1, result.resultingChunkRevision());
    }

    @Test
    void mapsReplacementIdentityAndFinalClearThroughRepositoryOnly() {
        Fixture fixture = fixture((byte) 0);
        GaiaInteractionContext context = context();
        LocalSubVoxelPosition position =
                new LocalSubVoxelPosition(2, 1, 3);
        long initial = fixture.repository().revision(new ChunkKey(0, 0));

        DetailMutationResult placed =
                fixture.service()
                        .setSubVoxel(
                                new DetailMutationRequest(
                                        context,
                                        4,
                                        7,
                                        6,
                                        initial,
                                        new FullCellState((byte) 0),
                                        position,
                                        Optional.of(DIRT)));
        DetailCellState oneCell =
                assertInstanceOf(
                        DetailCellState.class,
                        placed.newState().orElseThrow());
        assertEquals(2, Byte.toUnsignedInt(oneCell.blockId(position)));

        DetailMutationResult cleared =
                fixture.service()
                        .setSubVoxel(
                                new DetailMutationRequest(
                                        context,
                                        4,
                                        7,
                                        6,
                                        placed.resultingChunkRevision(),
                                        oneCell,
                                        position,
                                        Optional.empty()));

        assertEquals(DetailMutationResult.Status.APPLIED, cleared.status());
        assertEquals(new FullCellState((byte) 0), cleared.newState().orElseThrow());
    }

    @Test
    void unknownMaterialAndStaleExpectedMappingRejectWithoutMutation() {
        Fixture fixture = fixture((byte) 1);
        long before = fixture.repository().revision(new ChunkKey(0, 0));

        DetailMutationResult unknown =
                fixture.service()
                        .convertFullToDetail(
                                new FullToDetailRequest(
                                        context(),
                                        4,
                                        7,
                                        6,
                                        before,
                                        ResourceLocation.parse(
                                                "gaia:not_registered")));
        DetailMutationResult staleMapping =
                fixture.service()
                        .convertFullToDetail(
                                new FullToDetailRequest(
                                        context(),
                                        4,
                                        7,
                                        6,
                                        before,
                                        DIRT));

        assertEquals(DetailMutationResult.Status.UNKNOWN_MATERIAL, unknown.status());
        assertEquals(
                DetailMutationResult.Status.EXPECTED_STATE_CONFLICT,
                staleMapping.status());
        assertEquals(new FullCellState((byte) 1), staleMapping.oldState().orElseThrow());
        assertEquals(before, fixture.repository().revision(new ChunkKey(0, 0)));
        assertTrue(unknown.dirtiedChunks().isEmpty());
        assertTrue(staleMapping.dirtiedChunks().isEmpty());
    }

    @Test
    void explicitCompactionTranslatesRequestedFullIdentity() {
        Fixture fixture = fixture((byte) 1);
        long before = fixture.repository().revision(new ChunkKey(0, 0));
        DetailMutationResult converted =
                fixture.service()
                        .convertFullToDetail(
                                new FullToDetailRequest(
                                        context(), 4, 7, 6, before, STONE));
        DetailCellState detail =
                assertInstanceOf(
                        DetailCellState.class,
                        converted.newState().orElseThrow());

        DetailMutationResult compacted =
                fixture.service()
                        .compactDetailToFull(
                                new DetailToFullRequest(
                                        context(),
                                        4,
                                        7,
                                        6,
                                        converted.resultingChunkRevision(),
                                        detail,
                                        STONE));

        assertEquals(DetailMutationResult.Status.APPLIED, compacted.status());
        assertEquals(new FullCellState((byte) 1), compacted.newState().orElseThrow());
    }

    @Test
    void removesExactDetailParentWithoutMaterialTranslation() {
        Fixture fixture = fixture((byte) 1);
        GaiaInteractionContext context = context();
        long before = fixture.repository().revision(new ChunkKey(0, 0));
        DetailMutationResult converted =
                fixture.service().convertFullToDetail(
                        new FullToDetailRequest(
                                context, 4, 7, 6, before, STONE));
        DetailCellState expected =
                assertInstanceOf(
                        DetailCellState.class,
                        converted.newState().orElseThrow());

        DetailMutationResult removed =
                fixture.service().removeDetailParent(
                        new RemoveDetailParentRequest(
                                context,
                                4,
                                7,
                                6,
                                converted.resultingChunkRevision(),
                                expected));

        assertEquals(DetailMutationResult.Status.APPLIED, removed.status());
        assertSame(context, removed.context());
        assertEquals(expected, removed.oldState().orElseThrow());
        assertEquals(new FullCellState((byte) 0), removed.newState().orElseThrow());
        assertEquals(
                converted.resultingChunkRevision() + 1,
                removed.resultingChunkRevision());
        assertEquals(List.of(new ChunkKey(0, 0)), removed.dirtiedChunks().stream()
                .map(dirty -> dirty.key()).toList());
    }

    @Test
    void sculptsFullAndDetailThroughCanonicalRegistryTranslation() {
        Fixture fixture = fixture((byte) 1);
        GaiaInteractionContext context = context();
        long before = fixture.repository().revision(new ChunkKey(0, 0));
        LocalSubVoxelPosition position = new LocalSubVoxelPosition(2, 1, 3);

        DetailMutationResult sculpted =
                fixture.service().sculptParentSubVoxel(
                        new SculptParentSubVoxelRequest(
                                context,
                                4,
                                7,
                                6,
                                before,
                                new FullCellState((byte) 1),
                                position,
                                Optional.empty()));
        DetailCellState detail =
                assertInstanceOf(
                        DetailCellState.class,
                        sculpted.newState().orElseThrow());
        assertFalse(detail.occupied(position));
        assertSame(context, sculpted.context());

        DetailMutationResult replaced =
                fixture.service().sculptParentSubVoxel(
                        new SculptParentSubVoxelRequest(
                                context,
                                4,
                                7,
                                6,
                                sculpted.resultingChunkRevision(),
                                detail,
                                position,
                                Optional.of(DIRT)));
        DetailCellState replacedDetail =
                assertInstanceOf(
                        DetailCellState.class,
                        replaced.newState().orElseThrow());
        assertEquals(2, Byte.toUnsignedInt(replacedDetail.blockId(position)));
    }

    @Test
    void explicitAirReplacementIsRejectedInsteadOfBecomingRemoval() {
        Fixture fixture = fixture((byte) 1);
        long before = fixture.repository().revision(new ChunkKey(0, 0));

        DetailMutationResult result = fixture.service().sculptParentSubVoxel(
                new SculptParentSubVoxelRequest(
                        context(),
                        4,
                        7,
                        6,
                        before,
                        new FullCellState((byte) 1),
                        new LocalSubVoxelPosition(2, 1, 3),
                        Optional.of(ResourceLocation.parse("gaia:air"))));

        assertEquals(DetailMutationResult.Status.UNKNOWN_MATERIAL, result.status());
        assertEquals(before, fixture.repository().revision(new ChunkKey(0, 0)));
        assertEquals(
                new FullCellState((byte) 1),
                fixture.repository().snapshot(new ChunkKey(0, 0))
                        .orElseThrow().cellState(4, 7, 6));
        assertTrue(result.dirtiedChunks().isEmpty());
    }

    @Test
    void sculptUnknownMaterialRejectsBeforeRepositoryMutation() {
        Fixture fixture = fixture((byte) 0);
        long before = fixture.repository().revision(new ChunkKey(0, 0));

        DetailMutationResult result =
                fixture.service().sculptParentSubVoxel(
                        new SculptParentSubVoxelRequest(
                                context(),
                                4,
                                7,
                                6,
                                before,
                                new FullCellState((byte) 0),
                                new LocalSubVoxelPosition(0, 0, 0),
                                Optional.of(MISSING)));

        assertEquals(DetailMutationResult.Status.UNKNOWN_MATERIAL, result.status());
        assertEquals(before, fixture.repository().revision(new ChunkKey(0, 0)));
        assertTrue(result.dirtiedChunks().isEmpty());
    }

    @Test
    void ownerThreadIsEnforcedBeforeRepositoryMutation() throws Exception {
        Fixture fixture = fixture((byte) 1);
        long before = fixture.repository().revision(new ChunkKey(0, 0));
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            ExecutionException failure =
                    assertThrows(
                            ExecutionException.class,
                            () ->
                                    worker.submit(
                                                    () ->
                                                            fixture.service()
                                                                    .convertFullToDetail(
                                                                            new FullToDetailRequest(
                                                                                    context(),
                                                                                    4,
                                                                                    7,
                                                                                    6,
                                                                                    before,
                                                                                    STONE)))
                                            .get(5, TimeUnit.SECONDS));

            assertInstanceOf(IllegalStateException.class, failure.getCause());
            assertEquals(before, fixture.repository().revision(new ChunkKey(0, 0)));
        } finally {
            worker.shutdownNow();
            assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void serviceApiCannotExposeMutableDetailStorage() {
        assertTrue(
                java.util.Arrays.stream(GaiaDetailMutationService.class.getDeclaredFields())
                        .noneMatch(
                                field ->
                                        field.getType().getSimpleName()
                                                .equals("DetailStorage")));
        for (Method method : GaiaDetailMutationService.class.getMethods()) {
            assertFalse(method.getReturnType().getSimpleName().equals("DetailStorage"));
        }
    }

    private static Fixture fixture(byte fullId) {
        ChunkRepository repository =
                new ChunkRepository(32, new ChunkDirtyTracker());
        repository.generate(
                new ChunkKey(0, 0),
                chunk -> chunk.setBlock(4, 7, 6, fullId));
        return new Fixture(
                repository,
                new GaiaDetailMutationService(
                        MainThreadGuard.captureCurrentThread(),
                        registry(),
                        repository));
    }

    private static GaiaInteractionContext context() {
        return new GaiaInteractionContext(
                new EntityRef(42),
                BodySlot.RIGHT_HAND,
                InteractionAction.USE,
                91L,
                123L);
    }

    private static BlockRegistry registry() {
        return BlockRegistry.create(
                List.of(
                        definition(0, AIR),
                        definition(1, STONE),
                        definition(2, DIRT)),
                Map.of(
                        0, renderInfo(false),
                        1, renderInfo(true),
                        2, renderInfo(true)));
    }

    private static BlockDefinition definition(
            int id, ResourceLocation name) {
        return new BlockDefinition(
                id,
                name,
                MATERIAL.id(),
                textures(),
                1.0f,
                1.0f,
                1.0f,
                false,
                false,
                1.0f,
                id == 0
                        ? null
                        : new ItemFormDefinition(
                                name, 64, false, false));
    }

    private static EnumMap<BlockFace, ResourceLocation> textures() {
        EnumMap<BlockFace, ResourceLocation> textures =
                new EnumMap<>(BlockFace.class);
        for (BlockFace face : BlockFace.values()) {
            textures.put(face, MISSING);
        }
        return textures;
    }

    private static BlockRenderInfo renderInfo(boolean renderable) {
        EnumMap<BlockFace, TextureRegion> faces =
                new EnumMap<>(BlockFace.class);
        for (BlockFace face : BlockFace.values()) {
            faces.put(face, REGION);
        }
        return renderable
                ? new BlockRenderInfo(MATERIAL, faces, true)
                : BlockRenderInfo.nonRenderable(MATERIAL, REGION);
    }

    private record Fixture(
            ChunkRepository repository,
            GaiaDetailMutationService service) {}
}
