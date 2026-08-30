package com.gaia.debug;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gaia.blocks.BlockDefinition;
import com.gaia.blocks.BlockRegistry;
import com.gaia.blocks.ItemFormDefinition;
import com.gaia.interaction.GaiaDetailMutationService;
import com.gaia.interaction.GaiaInteractionContext;
import com.overlord.assets.ResourceLocation;
import com.overlord.core.thread.MainThreadGuard;
import com.overlord.interaction.api.DetailMutationResult;
import com.overlord.interaction.api.EntityRef;
import com.overlord.interaction.api.InteractionAction;
import com.overlord.inventory.api.BodySlot;
import com.overlord.physics.DetailCollisionBoxMerger;
import com.overlord.renderer.material.MaterialDefinition;
import com.overlord.renderer.material.RenderType;
import com.overlord.renderer.texture.TextureRegion;
import com.overlord.voxel.BlockFace;
import com.overlord.voxel.BlockRenderInfo;
import com.overlord.voxel.ChunkDirtyTracker;
import com.overlord.voxel.ChunkKey;
import com.overlord.voxel.ChunkRepository;
import com.overlord.voxel.DetailCellState;
import com.overlord.voxel.LocalSubVoxelPosition;
import com.overlord.voxel.World;
import java.lang.reflect.Field;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DetailDebugToolsTest {
    private static final ResourceLocation AIR = ResourceLocation.parse("gaia:air");
    private static final ResourceLocation STONE = ResourceLocation.parse("gaia:stone");
    private static final ResourceLocation DIRT = ResourceLocation.parse("gaia:dirt");
    private static final ResourceLocation MISSING = ResourceLocation.parse("gaia:missing");
    private static final MaterialDefinition MATERIAL = new MaterialDefinition(
            ResourceLocation.parse("gaia:opaque"),
            ResourceLocation.parse("gaia:blocks"),
            RenderType.OPAQUE,
            0.5f,
            MISSING);
    private static final TextureRegion REGION = new TextureRegion(MISSING, 0, 0, 1, 1, 1, 1);

    @Test
    void convertFillAndClearDelegateThroughCanonicalMutationService() {
        Fixture fixture = fixture((byte) 1);
        LocalSubVoxelPosition selected = new LocalSubVoxelPosition(2, 1, 3);
        DetailDebugTools.Selection full = fixture.tools().inspect(4, 7, 6, selected);

        DetailMutationResult converted = fixture.tools().convert(full, context());
        DetailDebugTools.Selection detail = fixture.tools().inspect(4, 7, 6, selected);
        DetailMutationResult filled = fixture.tools().fill(detail, context(), DIRT);
        DetailDebugTools.Selection changed = fixture.tools().inspect(4, 7, 6, selected);
        DetailMutationResult cleared = fixture.tools().clear(changed, context());

        assertEquals(DetailMutationResult.Status.APPLIED, converted.status());
        assertEquals(DetailMutationResult.Status.APPLIED, filled.status());
        assertEquals(DetailMutationResult.Status.APPLIED, cleared.status());
        assertTrue(fixture.tools().inspect(4, 7, 6, selected).selectedMaterial().isEmpty());
        assertTrue(fixture.repository().voxelModified(new ChunkKey(0, 0)));
    }

    @Test
    void staleDebugSelectionIsRejectedByRepositoryAuthority() {
        Fixture fixture = fixture((byte) 1);
        LocalSubVoxelPosition selected = new LocalSubVoxelPosition(0, 0, 0);
        DetailDebugTools.Selection stale = fixture.tools().inspect(4, 7, 6, selected);
        assertEquals(
                DetailMutationResult.Status.APPLIED,
                fixture.tools().convert(stale, context()).status());

        DetailMutationResult rejected = fixture.tools().convert(stale, context());

        assertEquals(DetailMutationResult.Status.STALE_CHUNK_REVISION, rejected.status());
        assertEquals(stale.chunkRevision() + 1, fixture.repository().revision(new ChunkKey(0, 0)));
    }

    @Test
    void fixtureApplicationProducesExactCanonicalStateAndBoundedDiagnostics() {
        Fixture fixture = fixture((byte) 0);

        DetailDebugTools.FixtureApplication applied = fixture.tools().applyFixture(
                4, 7, 6,
                DetailFixturePattern.ASYMMETRIC,
                STONE,
                DIRT,
                context());
        DetailDebugTools.Selection selection = fixture.tools().inspect(
                4, 7, 6, new LocalSubVoxelPosition(0, 0, 0));

        DetailCellState state = assertInstanceOf(DetailCellState.class, selection.state());
        assertEquals(0x0480010040000029L, state.occupancyMask());
        assertTrue(applied.mutations().size() <= 65);
        assertTrue(applied.mutations().stream().allMatch(
                result -> result.status() == DetailMutationResult.Status.APPLIED));
        assertEquals(7, selection.occupiedSubVoxels());
        assertTrue(selection.collisionBoxCount() >= 1);
        assertTrue(selection.collisionBoxCount() <= 7);
        String printed = fixture.tools().format(selection);
        assertTrue(printed.contains("parent=[4,7,6]"));
        assertTrue(printed.contains("chunk=ChunkKey[x=0, z=0]"));
        assertTrue(printed.contains("revision=" + selection.chunkRevision()));
        assertTrue(printed.contains("occupancy=0x0480010040000029"));
        assertTrue(printed.contains("selected=[0,0,0]"));
        assertTrue(printed.contains("collisionBoxes=" + selection.collisionBoxCount()));
    }

    @Test
    void inspectionIncludesBoundedReadOnlyMeshStatus() {
        Fixture fixture = fixture(
                (byte) 1,
                (key, revision) -> new DetailDebugTools.MeshStatus(
                        "FAILED",
                        true,
                        "MESH_OUTPUT_LIMIT_EXCEEDED requiredBytes=8388720 limitBytes=8388608"));

        DetailDebugTools.Selection selection = fixture.tools().inspect(
                4, 7, 6, new LocalSubVoxelPosition(0, 0, 0));
        String printed = fixture.tools().format(selection);

        assertEquals("FAILED", selection.meshStatus().phase());
        assertTrue(selection.meshStatus().lastKnownGoodInstalled());
        assertTrue(printed.contains("meshPhase=FAILED"));
        assertTrue(printed.contains("lastKnownGood=true"));
        assertTrue(printed.contains("MESH_OUTPUT_LIMIT_EXCEEDED"));
    }

    @Test
    void toolingOwnsNoRepositoryChunkOrMutableDetailStorage() {
        for (Class<?> toolingType : List.of(
                DetailDebugTools.class, DetailDebugInputController.class)) {
            for (Field field : toolingType.getDeclaredFields()) {
                String type = field.getType().getSimpleName();
                assertFalse(type.equals("ChunkRepository"), field.toString());
                assertFalse(type.equals("Chunk"), field.toString());
                assertFalse(type.equals("DetailStorage"), field.toString());
            }
        }
    }

    private static Fixture fixture(byte fullId) {
        return fixture(fullId, (key, revision) -> DetailDebugTools.MeshStatus.none());
    }

    private static Fixture fixture(
            byte fullId, DetailDebugTools.MeshDiagnosticSource meshDiagnostics) {
        ChunkRepository repository = new ChunkRepository(32, new ChunkDirtyTracker());
        repository.generate(new ChunkKey(0, 0), chunk -> chunk.setBlock(4, 7, 6, fullId));
        BlockRegistry blocks = registry();
        GaiaDetailMutationService mutations = new GaiaDetailMutationService(
                MainThreadGuard.captureCurrentThread(), blocks, repository);
        return new Fixture(
                repository,
                new DetailDebugTools(
                        new World(repository),
                        blocks,
                        mutations,
                        com.overlord.physics.BlockCollisionShapeResolver.fullCubesForNonAir(),
                        new DetailCollisionBoxMerger(),
                        meshDiagnostics));
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
                List.of(definition(0, AIR), definition(1, STONE), definition(2, DIRT)),
                Map.of(0, renderInfo(false), 1, renderInfo(true), 2, renderInfo(true)));
    }

    private static BlockDefinition definition(int id, ResourceLocation name) {
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
                id == 0 ? null : new ItemFormDefinition(name, 64, false, false));
    }

    private static EnumMap<BlockFace, ResourceLocation> textures() {
        EnumMap<BlockFace, ResourceLocation> textures = new EnumMap<>(BlockFace.class);
        for (BlockFace face : BlockFace.values()) {
            textures.put(face, MISSING);
        }
        return textures;
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

    private record Fixture(ChunkRepository repository, DetailDebugTools tools) {}
}
