package com.gaia.world.streaming;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.overlord.voxel.ChunkKey;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.ServiceLoader;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Gate 15F structural assertions over raw observations from the production session graph. */
class ChunkStreamingSoakTest {
    @Test
    void productionSoakTraversesTheRequiredCoordinatesAndPersistsTheirRealState() {
        Gate15FSoakObservation soak = probe().runStructuralSoak();

        assertTrue(soak.visitedChunkKeys().size() - 1 >= 500,
                "the production scenario must execute at least 500 Chunk transitions");
        assertEquals(EnumSet.allOf(TravelDirection.class),
                directions(soak.visitedChunkKeys()));
        assertTrue(soak.visitedChunkKeys().stream()
                        .anyMatch(key -> key.x() < 0 && key.z() < 0),
                "travel must enter negative Chunk keys");
        assertTrue(hasReversal(soak.visitedChunkKeys()),
                "the real travel sequence must reverse direction");
        assertTrue(hasRapidTravel(soak.visitedChunkKeys()),
                "the real travel sequence must include a multi-Chunk jump");
        assertTrue(soak.visitedChunkKeys().stream().skip(1)
                        .anyMatch(key -> key.equals(soak.visitedChunkKeys().get(0))),
                "the scenario must return to an earlier coordinate");

        assertTrue(rebaseCount(soak.originSequence()) >= 2L,
                "the real session must rebase its simulation origin at least twice");
        assertFalse(soak.untouchedChunks().isEmpty());
        soak.untouchedChunks().forEach(untouched -> {
            assertTrue(untouched.absentFromDurableIndexBeforeReload(),
                    "untouched Chunk must have no durable payload authority");
            assertEquals(untouched.hashBeforeUnload(), untouched.hashAfterProductionReload(),
                    "untouched Chunk " + untouched.key()
                            + " must regenerate through the production pipeline");
        });
        assertFalse(soak.modifiedChunks().isEmpty());
        soak.modifiedChunks().forEach(modified -> {
            assertEquals(modified.beforeUnloadSnapshot().key(),
                    modified.reloadedSnapshot().key());
            assertTrue(modified.reloadedSnapshot().revision()
                            > modified.beforeUnloadSnapshot().revision(),
                    "reloaded Chunk revision must advance the repository publication epoch");
            assertTrue(Arrays.equals(modified.modifiedBytesBeforeUnload(),
                            modified.reloadedBytes()),
                    "modified Chunk bytes must reload exactly");
        });

        assertFalse(soak.worldItemLifecycles().isEmpty());
        soak.worldItemLifecycles().forEach(lifecycle -> {
            assertEquals(lifecycle.beforeHibernate().id(), lifecycle.afterActivate().id(),
                    "hibernation and activation must preserve the WorldItem ID");
            assertEquals(lifecycle.beforeHibernate().stack(), lifecycle.afterActivate().stack());
            assertTrue(lifecycle.afterExpiry().isEmpty(),
                    "due WorldItems must be absent from active interaction");
            assertTrue(lifecycle.afterCleanupFailure().isEmpty(),
                    "cleanup failure must not resurrect an expired WorldItem");
            assertTrue(lifecycle.afterExpiredPageRevisit().isEmpty(),
                    "revisiting an expired page must converge to no live WorldItem");
            assertEquals(1, lifecycle.pagesReadForExpiredRevisit(),
                    "expired-page cleanup must revisit the concrete page, not globally scan");
        });
        assertEquals(soak.restart().saveIdentityBeforeQuit(),
                soak.restart().saveIdentityAfterRestart());
        assertEquals(soak.restart().worldTickBeforeQuit(),
                soak.restart().worldTickAfterRestart(),
                "Save & Quit restart must restore the authoritative world tick");
        Gate15FModifiedChunkObservation modified = soak.modifiedChunks().get(0);
        assertTrue(Arrays.equals(modified.modifiedBytesBeforeUnload(),
                        soak.restart().modifiedChunkAfterRestart().copyBlocks()),
                "fresh restart must load the exact persisted modified Chunk bytes");
        assertTrue(Arrays.equals(modified.modifiedBytesBeforeUnload(),
                        soak.restart().modifiedChunkAfterSecondReload().copyBlocks()),
                "the restarted modified Chunk must survive a second unload/reload");
        assertTrue(soak.restart().modifiedChunkAfterSecondReload().revision()
                        > soak.restart().modifiedChunkAfterRestart().revision(),
                "the second production reload must publish a newer resident revision");
        assertEquals(soak.restart().worldItemBeforeQuit().id(),
                soak.restart().worldItemAfterRestart().id(),
                "fresh restart must preserve the WorldItem stable ID");
        assertEquals(soak.restart().worldItemBeforeQuit().stack(),
                soak.restart().worldItemAfterRestart().stack(),
                "fresh restart must preserve the WorldItem stack");
        assertEquals(soak.restart().expiresAtWorldTickBeforeQuit(),
                soak.restart().expiresAtWorldTickAfterRestart(),
                "fresh restart must preserve the absolute expiry tick");
    }

    @Test
    void productionSoakMaintainsBoundedRawEpochMetricsAndCanonicalSpatialResults() {
        Gate15FSoakObservation soak = probe().runStructuralSoak();
        assertFalse(soak.epochs().isEmpty(), "every real epoch must be observed");
        soak.epochs().forEach(ChunkStreamingSoakTest::assertEpochBounds);
        assertTrue(soak.epochs().stream().anyMatch(epoch ->
                        epoch.lifecycleState() == Gate15FLifecycleState.SETTLED
                                && epoch.metrics().residentChunks() <= 121),
                "at least one post-drain settled epoch must return to the preload footprint");

        assertFalse(soak.pipelineCounters().isEmpty());
        assertTrue(soak.pipelineCounters().stream().anyMatch(sample -> sample.canceled() > 0L),
                "rapid travel must produce an observed canceled pipeline result");
        assertTrue(soak.pipelineCounters().stream().allMatch(sample -> sample.stale() >= 0L),
                "stale-work diagnostics remain a non-negative current counter");
        assertMonotonic(soak.pipelineCounters());

        assertFalse(soak.retainedState().isEmpty());
        soak.retainedState().forEach(retained -> {
            assertTrue(retained.residentChunks() <= 225,
                    "resident authority may use the 15 by 15 unload hysteresis footprint");
            assertTrue(retained.retainedStreamingWork() <= 40,
                    "retained worker authority must stay within 32 load plus 8 save slots");
            assertTrue(retained.liveMetadataCount() <= 1_024);
            assertTrue(retained.decodedPages() <= 32);
            assertTrue(retained.physicalDescriptorCount() <= 1_024);
        });
        assertTrue(soak.retainedState().get(soak.retainedState().size() - 1)
                        .traversedChunkDistance()
                        > soak.retainedState().get(0).traversedChunkDistance(),
                "retained-state samples must span increasing travel distance");
        assertTrue(soak.retainedState().stream().anyMatch(retained ->
                        retained.lifecycleState() == Gate15FLifecycleState.SETTLED
                                && retained.residentChunks() <= 121),
                "a post-drain retained-state sample must return to the preload footprint");

        assertFalse(soak.localTransforms().isEmpty());
        soak.localTransforms().forEach(local -> {
            assertFiniteAndSmall(local.renderX());
            assertFiniteAndSmall(local.renderY());
            assertFiniteAndSmall(local.renderZ());
            assertFiniteAndSmall(local.physicsX());
            assertFiniteAndSmall(local.physicsY());
            assertFiniteAndSmall(local.physicsZ());
        });
        assertFalse(soak.canonicalBlockQueries().isEmpty());
        soak.canonicalBlockQueries().forEach(query -> {
            assertEquals(query.requested(), query.raycastHit());
            assertEquals(query.requested(), query.collisionHit());
        });
    }

    static Gate15FStreamingProbe probe() {
        return ProbeHolder.INSTANCE;
    }

    private static Gate15FStreamingProbe loadProbe() {
        List<Gate15FStreamingProbe> probes = ServiceLoader.load(Gate15FStreamingProbe.class)
                .stream().map(ServiceLoader.Provider::get).toList();
        if (probes.isEmpty()) {
            return fail("Missing typed Gate 15F test probe implementation for the real production session graph");
        }
        if (probes.size() != 1) {
            return fail("Gate 15F must have exactly one typed production-session probe");
        }
        return probes.get(0);
    }

    private static final class ProbeHolder {
        private static final Gate15FStreamingProbe INSTANCE = loadProbe();

        private ProbeHolder() {}
    }

    private static void assertEpochBounds(Gate15FEpochObservation epoch) {
        ChunkStreamingMetrics metrics = epoch.metrics();
        assertTrue(metrics.residentChunks() <= 225,
                "resident authority may use the unload hysteresis footprint");
        assertWork(metrics.loadGenerationWork(), 32, 4);
        assertWork(metrics.meshWork(), 32, 2);
        assertWork(metrics.saveWork(), 8, 1);
        assertTrue(metrics.publicationsThisFrame() <= 2L,
                () -> "publication budget exceeded at transition="
                        + epoch.transition() + " lifecycle=" + epoch.lifecycleState()
                        + " publicationsThisFrame=" + metrics.publicationsThisFrame()
                        + " uploadsThisFrame=" + metrics.uploadsThisFrame()
                        + " load=" + metrics.loadGenerationWork()
                        + " mesh=" + metrics.meshWork()
                        + " save=" + metrics.saveWork());
        assertTrue(metrics.uploadsThisFrame() <= 2L);
        assertTrue(metrics.destructionsThisFrame() <= 4L);
        var paging = metrics.worldItems();
        long currentLive = Math.addExact(
                Math.addExact(paging.activeDtoCount(), paging.decodedDormantDtoCount()),
                Math.addExact(paging.evictedUnexpiredCount(), paging.pendingCount()));
        assertTrue(currentLive <= 1_024L);
        assertEquals(epoch.survivorIds().size(), paging.liveMetadataCount());
        assertEquals(epoch.survivorIds().size(), epoch.expiryIndexedIds().size());
        assertEquals(paging.liveMetadataCount(), paging.expiryIndexCount());
        assertEquals(paging.physicalDescriptorCount(), epoch.physicalDependencyCount());
        assertTrue(paging.cleanupIntentCount() <= 64);
        assertTrue(paging.cleanupIntentBytes() <= 64L * 1_024L);
        assertTrue(paging.tombstoneCount() <= 64);
    }

    private static void assertWork(
            ChunkStreamingMetrics.WorkMetrics work, int acceptedLimit, int activeLimit) {
        assertEquals(work.accepted(), work.queued() + work.active() + work.completed());
        assertTrue(work.accepted() <= acceptedLimit);
        assertTrue(work.active() <= activeLimit);
    }

    private static Set<TravelDirection> directions(List<ChunkKey> keys) {
        EnumSet<TravelDirection> result = EnumSet.noneOf(TravelDirection.class);
        for (int index = 1; index < keys.size(); index++) {
            ChunkKey before = keys.get(index - 1);
            ChunkKey after = keys.get(index);
            if (after.x() > before.x()) result.add(TravelDirection.EAST);
            if (after.x() < before.x()) result.add(TravelDirection.WEST);
            if (after.z() > before.z()) result.add(TravelDirection.SOUTH);
            if (after.z() < before.z()) result.add(TravelDirection.NORTH);
        }
        return result;
    }

    private static boolean hasReversal(List<ChunkKey> keys) {
        for (int index = 2; index < keys.size(); index++) {
            long priorX = (long) keys.get(index - 1).x() - keys.get(index - 2).x();
            long nextX = (long) keys.get(index).x() - keys.get(index - 1).x();
            long priorZ = (long) keys.get(index - 1).z() - keys.get(index - 2).z();
            long nextZ = (long) keys.get(index).z() - keys.get(index - 1).z();
            if (priorX * nextX < 0L || priorZ * nextZ < 0L) return true;
        }
        return false;
    }

    private static boolean hasRapidTravel(List<ChunkKey> keys) {
        return java.util.stream.IntStream.range(1, keys.size()).anyMatch(index ->
                Math.max(Math.abs((long) keys.get(index).x() - keys.get(index - 1).x()),
                        Math.abs((long) keys.get(index).z() - keys.get(index - 1).z())) > 1L);
    }

    private static long rebaseCount(List<com.overlord.physics.SimulationOrigin> origins) {
        return java.util.stream.IntStream.range(1, origins.size())
                .filter(index -> !origins.get(index).equals(origins.get(index - 1))).count();
    }

    private static void assertMonotonic(List<Gate15FPipelineCounterSample> samples) {
        for (int index = 1; index < samples.size(); index++) {
            assertTrue(samples.get(index).canceled() >= samples.get(index - 1).canceled());
            assertTrue(samples.get(index).stale() >= samples.get(index - 1).stale());
        }
    }

    private static void assertFiniteAndSmall(float value) {
        assertTrue(Float.isFinite(value));
        assertTrue(Math.abs(value) <= 8_192.0f);
    }
}
