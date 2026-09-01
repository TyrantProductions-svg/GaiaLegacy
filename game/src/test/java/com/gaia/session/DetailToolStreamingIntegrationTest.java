package com.gaia.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gaia.assets.GaiaResourceLoader;
import com.gaia.interaction.GaiaDetailMutationService;
import com.gaia.interaction.GaiaInteractionContext;
import com.overlord.assets.AssetManager;
import com.overlord.assets.ResourceLocation;
import com.overlord.core.thread.MainThreadGuard;
import com.overlord.interaction.api.DetailMutationResult;
import com.overlord.interaction.api.EntityRef;
import com.overlord.interaction.api.InteractionAction;
import com.overlord.interaction.api.SculptParentSubVoxelRequest;
import com.overlord.inventory.api.BodySlot;
import com.overlord.voxel.ChunkDirtyTracker;
import com.overlord.voxel.ChunkKey;
import com.overlord.voxel.ChunkRepository;
import com.overlord.voxel.ChunkRepositorySnapshot;
import com.overlord.voxel.ChunkUnloadResult;
import com.overlord.voxel.DetailCellState;
import com.overlord.voxel.FullCellState;
import com.overlord.voxel.LocalSubVoxelPosition;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class DetailToolStreamingIntegrationTest {
    private static final ChunkKey KEY = new ChunkKey(-1, -1);
    private static final LocalSubVoxelPosition LOCAL = new LocalSubVoxelPosition(3, 2, 1);

    @Test
    void playerModifiedDetailCaptureSurvivesRealRepositoryUnloadAndReturn() {
        ChunkRepository repository = new ChunkRepository(8, new ChunkDirtyTracker());
        repository.generate(KEY, ignored -> {});
        GaiaDetailMutationService mutations = new GaiaDetailMutationService(
                MainThreadGuard.captureCurrentThread(),
                new GaiaResourceLoader(new AssetManager(getClass().getClassLoader()))
                        .load().blockRegistry(),
                repository);
        int worldX = KEY.worldOriginX() + 15;
        int worldZ = KEY.worldOriginZ() + 15;
        DetailMutationResult result = mutations.sculptParentSubVoxel(
                new SculptParentSubVoxelRequest(
                        new GaiaInteractionContext(
                                new EntityRef(1), BodySlot.RIGHT_HAND,
                                InteractionAction.USE, 1, 1),
                        worldX, 2, worldZ, repository.revision(KEY),
                        new FullCellState((byte) 0), LOCAL,
                        Optional.of(ResourceLocation.parse("gaia:dirt"))));
        DetailCellState expected = assertInstanceOf(
                DetailCellState.class, result.newState().orElseThrow());

        var prepared = repository.prepareStreamingUnload(KEY);
        var capture = prepared.capture().orElseThrow();
        assertTrue(prepared.voxelModified());
        assertEquals(ChunkUnloadResult.Status.VALID,
                repository.acknowledgeStreamingPersistence(
                        prepared.ticket().orElseThrow(), capture.revision()).status());
        assertEquals(ChunkUnloadResult.Status.COMMITTED,
                repository.commitStreamingUnload(prepared.ticket().orElseThrow()).status());
        assertTrue(repository.snapshot(KEY).isEmpty());

        ChunkRepository returned = new ChunkRepository(8, new ChunkDirtyTracker());
        assertEquals(com.overlord.voxel.ChunkRepositoryRestoreResult.Status.RESTORED,
                returned.restoreCanonical(new ChunkRepositorySnapshot(
                        8, capture.revision(), List.of(capture))).status());
        assertEquals(expected, returned.snapshot(KEY).orElseThrow()
                .cellState(15, 2, 15));
    }
}
