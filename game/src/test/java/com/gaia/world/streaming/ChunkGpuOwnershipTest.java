package com.gaia.world.streaming;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.overlord.core.thread.MainThreadGuard;
import com.overlord.renderer.ChunkRenderBackend;
import com.overlord.renderer.ChunkRenderObject;
import com.overlord.voxel.ChunkDirtyTracker;
import com.overlord.voxel.ChunkKey;
import com.overlord.voxel.ChunkMeshBudget;
import com.overlord.voxel.ChunkMeshData;
import com.overlord.voxel.ChunkMeshManager;
import com.overlord.voxel.ChunkRepository;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class ChunkGpuOwnershipTest {
    @Test
    void task8WorkerValuesCarryNoRepositoryMeshOrGpuAuthority() {
        for (Class<?> valueType : new Class<?>[] {
                ChunkStreamingPipeline.DetachedLoadWork.class,
                ChunkStreamingPipeline.DetachedSaveWork.class,
                ChunkWorkResult.class
        }) {
            assertTrue(valueType.isRecord());
            assertFalse(Arrays.stream(valueType.getRecordComponents())
                    .map(RecordComponent::getType)
                    .anyMatch(type -> type == ChunkRepository.class
                            || type == ChunkMeshManager.class
                            || type == ChunkRenderBackend.class
                            || type == ChunkRenderObject.class));
        }
    }

    @Test
    void workerCannotPumpGpuUploadOrDestruction() throws Exception {
        ChunkKey key = new ChunkKey(0, 0);
        ChunkRepository repository = new ChunkRepository(1, new ChunkDirtyTracker());
        repository.generate(key, chunk -> chunk.setBlock(0, 0, 0, (byte) 1));
        AtomicInteger backendCalls = new AtomicInteger();
        ChunkMeshManager manager = new ChunkMeshManager(
                repository,
                input -> new ChunkMeshData(
                        input.center().key(), input.center().revision(), new float[0]),
                Runnable::run,
                new ChunkRenderBackend() {
                    @Override
                    public ChunkRenderObject upload(ChunkMeshData data) {
                        backendCalls.incrementAndGet();
                        throw new AssertionError("worker reached GPU upload");
                    }

                    @Override
                    public void release(ChunkRenderObject object) {
                        backendCalls.incrementAndGet();
                        throw new AssertionError("worker reached GPU destruction");
                    }
                },
                MainThreadGuard.captureCurrentThread(),
                ChunkMeshBudget.productionDefaults());
        manager.scheduleEligible();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            try {
                manager.processMainThreadWork();
            } catch (Throwable thrown) {
                failure.set(thrown);
            }
        }, "task9-gpu-owner-test");

        worker.start();
        worker.join(5_000L);

        assertFalse(worker.isAlive());
        assertTrue(failure.get() instanceof IllegalStateException);
        assertEquals(0, backendCalls.get());
        manager.close();
    }
}
