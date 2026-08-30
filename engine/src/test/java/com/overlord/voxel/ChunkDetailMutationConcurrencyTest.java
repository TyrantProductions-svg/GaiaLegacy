package com.overlord.voxel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class ChunkDetailMutationConcurrencyTest {
    @Test
    void concurrentObservationNeverSeesConversionIntermediate() throws Exception {
        ChunkRepository repository =
                new ChunkRepository(32, new ChunkDirtyTracker());
        ChunkKey key = new ChunkKey(0, 0);
        repository.generate(
                key, chunk -> chunk.setBlock(4, 7, 6, (byte) 7));
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<?> mutations =
                    executor.submit(
                            () -> {
                                await(start);
                                for (int iteration = 0; iteration < 100; iteration++) {
                                    long fullRevision = repository.revision(key);
                                    ChunkDetailMutationOutcome converted =
                                            repository.mutateDetail(
                                                    new ChunkDetailMutation.ConvertFullToDetail(
                                                            4,
                                                            7,
                                                            6,
                                                            fullRevision,
                                                            (byte) 7));
                                    assertEquals(
                                            ChunkDetailMutationOutcome.Status.APPLIED,
                                            converted.status());
                                    ChunkDetailMutationOutcome compacted =
                                            repository.mutateDetail(
                                                    new ChunkDetailMutation.CompactDetailToFull(
                                                            4,
                                                            7,
                                                            6,
                                                            converted.resultingChunkRevision(),
                                                            DetailCellState.uniform((byte) 7),
                                                            (byte) 7));
                                    assertEquals(
                                            ChunkDetailMutationOutcome.Status.APPLIED,
                                            compacted.status());
                                }
                            });
            Future<?> observations =
                    executor.submit(
                            () -> {
                                await(start);
                                for (int sample = 0; sample < 10_000; sample++) {
                                    ParentCellState state =
                                            repository.observeCell(4, 7, 6)
                                                    .observation()
                                                    .orElseThrow()
                                                    .state();
                                    if (state instanceof FullCellState full) {
                                        assertEquals(7, Byte.toUnsignedInt(full.blockId()));
                                    } else {
                                        DetailCellState detail =
                                                assertInstanceOf(
                                                        DetailCellState.class,
                                                        state);
                                        assertEquals(-1L, detail.occupancyMask());
                                        for (byte id : detail.copyBlockIds()) {
                                            assertEquals(7, Byte.toUnsignedInt(id));
                                        }
                                    }
                                }
                            });

            start.countDown();
            mutations.get(10, TimeUnit.SECONDS);
            observations.get(10, TimeUnit.SECONDS);
            assertTrue(executor.shutdownNow().isEmpty());
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("start timed out");
            }
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new AssertionError(failure);
        }
    }
}
