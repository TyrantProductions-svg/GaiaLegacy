package com.gaia.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gaia.interaction.GameMode;
import com.gaia.save.format.SaveGameId;
import com.gaia.session.streaming.SimulationOriginCoordinator;
import com.gaia.world.streaming.ChunkStreamingController;
import com.gaia.world.streaming.ChunkStreamingDecision;
import com.gaia.world.streaming.ChunkStreamingObservation;
import com.gaia.world.streaming.ChunkStreamingPipeline;
import com.gaia.world.streaming.ChunkStreamingPolicy;
import com.overlord.physics.PlayerController;
import com.overlord.voxel.ChunkKey;
import com.overlord.voxel.GlobalPosition;
import com.overlord.voxel.World;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

class NewWorldInitialMeshSchedulingTest {
    private static final int RADIUS_EIGHT_CHUNK_COUNT = 17 * 17;

    @Test
    void radiusEightNewWorldUsesInitialStreamingDecisionBeforeMeshing()
            throws Exception {
        assertNewWorldUsesInitialStreamingDecision(8, RADIUS_EIGHT_CHUNK_COUNT);
    }

    @Test
    void smallerNewWorldUsesTheSameInitialStreamingAuthority()
            throws Exception {
        assertNewWorldUsesInitialStreamingDecision(2, 5 * 5);
    }

    private static void assertNewWorldUsesInitialStreamingDecision(
            int chunkRadius, int expectedGeneratedChunks) throws Exception {
        var access = GameSessionFactory.productionSessionTestAccess();
        GameSession session = access.factory().create(
                new NewWorldRequest(
                        SaveGameId.parse(UUID.randomUUID().toString()),
                        "Streaming Startup Radius " + chunkRadius,
                        12345L),
                new GameSessionConfig(
                        12345L,
                        chunkRadius,
                        GameMode.SURVIVAL,
                        false));
        try {
            driveToReady(session);

            Object runtime = field(session, "runtime", Object.class);
            World world = field(runtime, "world", World.class);
            PlayerController player = field(
                    runtime, "playerController", PlayerController.class);
            SimulationOriginCoordinator origin = field(
                    runtime,
                    "originCoordinator",
                    SimulationOriginCoordinator.class);
            ChunkStreamingPipeline pipeline = field(
                    runtime, "streamingPipeline", ChunkStreamingPipeline.class);
            @SuppressWarnings("unchecked")
            Set<ChunkKey> readiness = Set.copyOf(
                    (Set<ChunkKey>) field(runtime, "meshReadiness", Set.class));
            @SuppressWarnings("unchecked")
            List<ChunkKey> scheduledOrder = List.copyOf(
                    (List<ChunkKey>) field(
                            runtime, "currentMeshPriorityOrder", List.class));

            Vector3f localFeet = player.body().position(new Vector3f());
            GlobalPosition globalFeet = origin.simulationOrigin().toGlobal(localFeet);
            ChunkStreamingDecision expected = new ChunkStreamingController(
                    ChunkStreamingPolicy.productionDefaults()).update(
                            globalFeet,
                            new ChunkStreamingObservation(
                                    Set.copyOf(world.chunks().keys()),
                                    pipeline.requestedLoadPhases()));
            List<ChunkKey> expectedMeshOrder = expected.desiredPriorityOrder().stream()
                    .filter(expected.desiredSets().render()::contains)
                    .toList();

            assertEquals(GameSessionState.READY, session.state());
            assertEquals(expectedGeneratedChunks, world.chunks().keys().size());
            assertEquals(expected.desiredSets().simulation(), readiness);
            assertEquals(expectedMeshOrder, scheduledOrder);
            assertTrue(world.chunks().keys().stream()
                    .map(world.chunks()::snapshot)
                    .map(java.util.Optional::orElseThrow)
                    .allMatch(snapshot -> snapshot.details().isEmpty()),
                    "FULL-only startup must not allocate canonical DETAIL state");
            assertEquals(1, access.generationInvocationCount());
            assertEquals(1, access.readyPublicationCount());
        } finally {
            session.close();
        }
    }

    private static void driveToReady(GameSession session) {
        long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(3L);
        while (session.state() == GameSessionState.LOADING
                && System.nanoTime() < deadline) {
            session.pollLoad();
            if (session.state() == GameSessionState.LOADING) {
                java.util.concurrent.locks.LockSupport.parkNanos(
                        TimeUnit.MILLISECONDS.toNanos(1L));
            }
        }
        assertEquals(GameSessionState.READY, session.state());
    }

    private static <T> T field(Object target, String name, Class<T> type)
            throws ReflectiveOperationException {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return type.cast(field.get(target));
    }
}
