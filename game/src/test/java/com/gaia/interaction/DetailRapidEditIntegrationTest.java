package com.gaia.interaction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.gaia.world.streaming.ChunkStreamingPolicy;
import com.overlord.voxel.ChunkMeshBudget;
import com.overlord.voxel.ChunkMeshBuilder;
import com.overlord.voxel.ChunkMeshManager;
import java.lang.reflect.Field;
import java.util.Collection;
import java.util.Queue;
import org.junit.jupiter.api.Test;

class DetailRapidEditIntegrationTest {
    @Test
    void interactionOwnerHasNoGameplayEditQueueOrAutomaticRetryLane() {
        for (Field field : BlockInteractionController.class.getDeclaredFields()) {
            assertFalse(Queue.class.isAssignableFrom(field.getType()),
                    "DETAIL edits must not gain a retry queue: " + field.getName());
            assertFalse(Collection.class.isAssignableFrom(field.getType())
                            && field.getName().toLowerCase().contains("edit"),
                    "DETAIL edits must not retain history: " + field.getName());
        }
    }

    @Test
    void phase16MeshResourceBudgetsRemainFrozenUnderRapidEditing() {
        ChunkMeshBudget budget = ChunkMeshBudget.productionDefaults();
        assertEquals(8_388_608L, ChunkMeshBuilder.MAX_HYBRID_MESH_BYTES);
        assertEquals(134_217_728L, ChunkMeshManager.MAX_CPU_MESH_MEMORY_BYTES);
        assertEquals(32, budget.maxAccepted());
        assertEquals(2, budget.maxActive());
        assertEquals(2, budget.maxUploadsPerFrame());
        assertEquals(4, budget.maxDestructionsPerFrame());
        assertEquals(2, ChunkStreamingPolicy.productionDefaults().publicationBudget());
    }
}
