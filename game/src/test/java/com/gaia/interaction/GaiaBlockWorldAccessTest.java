package com.gaia.interaction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gaia.blocks.BlockDefinition;
import com.gaia.blocks.BlockRegistry;
import com.gaia.blocks.ItemFormDefinition;
import com.overlord.assets.ResourceLocation;
import com.overlord.core.thread.MainThreadGuard;
import com.overlord.interaction.DefaultWorldMutationService;
import com.overlord.interaction.SynchronousBlockChangeEventPublisher;
import com.overlord.interaction.api.BlockChangeDecision;
import com.overlord.interaction.api.BlockChangeRequest;
import com.overlord.interaction.api.BlockChangeResult;
import com.overlord.interaction.api.EntityRef;
import com.overlord.interaction.api.InteractionAction;
import com.overlord.inventory.api.BodySlot;
import com.overlord.renderer.material.MaterialDefinition;
import com.overlord.renderer.material.RenderType;
import com.overlord.renderer.texture.TextureRegion;
import com.overlord.voxel.BlockFace;
import com.overlord.voxel.BlockRenderInfo;
import com.overlord.voxel.ChunkKey;
import com.overlord.voxel.ChunkRepository;
import com.overlord.voxel.World;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class GaiaBlockWorldAccessTest {
    private static final ResourceLocation AIR = ResourceLocation.parse("gaia:air");
    private static final ResourceLocation STONE = ResourceLocation.parse("gaia:stone");
    private static final ResourceLocation MISSING = ResourceLocation.parse("gaia:missing");

    @Test
    void repositoryMutationOwnsBoundaryDirtyRevisionsAndEventsObserveExactOrder() {
        ChunkRepository chunks = new ChunkRepository();
        ChunkKey center = new ChunkKey(0, 0);
        ChunkKey east = center.east();
        chunks.generate(center, chunk -> chunk.setBlock(15, 5, 4, (byte) 1));
        chunks.generate(east, ignored -> {});
        World world = new World(chunks);
        GaiaBlockWorldAccess access = new GaiaBlockWorldAccess(world, blocks());
        List<String> events = new ArrayList<>();
        DefaultWorldMutationService mutations = new DefaultWorldMutationService(
                MainThreadGuard.captureCurrentThread(),
                access,
                new SynchronousBlockChangeEventPublisher(
                        event -> { events.add("before"); return BlockChangeDecision.ALLOW; },
                        event -> events.add("changed"),
                        event -> events.add("dirty")));
        BlockChangeRequest request = new BlockChangeRequest(
                new GaiaInteractionContext(
                        new EntityRef(1), BodySlot.LEFT_HAND,
                        InteractionAction.PRIMARY, 1, 1),
                15, 5, 4, STONE, AIR);

        BlockChangeResult result = mutations.changeBlock(request);

        assertEquals(BlockChangeResult.Status.APPLIED, result.status());
        assertEquals(List.of("before", "changed", "dirty"), events);
        assertEquals(List.of(center, east),
                result.dirtiedChunks().stream().map(dirty -> dirty.key()).toList());
        assertEquals(AIR, access.blockAt(15, 5, 4));
        assertTrue(result.dirtiedChunks().stream().allMatch(dirty -> dirty.revision() > 0));
    }

    @Test
    void beforeCancellationHasNoWriteDirtyOrPostEventSideEffects() {
        ChunkRepository chunks = new ChunkRepository();
        ChunkKey key = new ChunkKey(0, 0);
        chunks.generate(key, chunk -> chunk.setBlock(4, 5, 4, (byte) 1));
        long revision = chunks.revision(key);
        GaiaBlockWorldAccess access = new GaiaBlockWorldAccess(
                new World(chunks), blocks());
        List<String> events = new ArrayList<>();
        DefaultWorldMutationService mutations = new DefaultWorldMutationService(
                MainThreadGuard.captureCurrentThread(),
                access,
                new SynchronousBlockChangeEventPublisher(
                        event -> { events.add("before"); return BlockChangeDecision.CANCEL; },
                        event -> events.add("changed"),
                        event -> events.add("dirty")));

        BlockChangeResult result = mutations.changeBlock(new BlockChangeRequest(
                new GaiaInteractionContext(
                        new EntityRef(1), BodySlot.LEFT_HAND,
                        InteractionAction.PRIMARY, 1, 1),
                4, 5, 4, STONE, AIR));

        assertEquals(BlockChangeResult.Status.CANCELLED, result.status());
        assertEquals(List.of("before"), events);
        assertEquals(STONE, access.blockAt(4, 5, 4));
        assertEquals(revision, chunks.revision(key));
        assertTrue(result.dirtiedChunks().isEmpty());
    }

    private static BlockRegistry blocks() {
        MaterialDefinition material = new MaterialDefinition(
                ResourceLocation.parse("gaia:opaque"),
                ResourceLocation.parse("gaia:blocks"),
                RenderType.OPAQUE, 0.5f, MISSING);
        TextureRegion region = new TextureRegion(MISSING, 0, 0, 1, 1, 1, 1);
        BlockDefinition air = definition(0, AIR, material.id());
        BlockDefinition stone = definition(1, STONE, material.id());
        return BlockRegistry.create(
                List.of(air, stone),
                Map.of(
                        0, BlockRenderInfo.nonRenderable(material, region),
                        1, renderInfo(material, region)));
    }

    private static BlockDefinition definition(
            int id, ResourceLocation name, ResourceLocation material) {
        EnumMap<BlockFace, ResourceLocation> textures = new EnumMap<>(BlockFace.class);
        for (BlockFace face : BlockFace.values()) {
            textures.put(face, MISSING);
        }
        return new BlockDefinition(
                id, name, material, textures, 1, 1, 1,
                false, false, 1,
                id == 0 ? null : new ItemFormDefinition(name, 64, false, false));
    }

    private static BlockRenderInfo renderInfo(
            MaterialDefinition material, TextureRegion region) {
        EnumMap<BlockFace, TextureRegion> faces = new EnumMap<>(BlockFace.class);
        for (BlockFace face : BlockFace.values()) {
            faces.put(face, region);
        }
        return new BlockRenderInfo(material, faces, true);
    }
}
