package com.gaia.session.streaming;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.overlord.physics.SimulationOrigin;
import com.overlord.renderer.RenderOrigin;
import com.overlord.voxel.ChunkKey;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class SimulationOriginCoordinatorTest {
    @Test
    void zeroToZeroInitializationCommitsParticipantsOnceAndIsIdempotent() {
        SimulationOrigin zero = new SimulationOrigin(new ChunkKey(0, 0));
        RenderOrigin zeroRender = new RenderOrigin(new ChunkKey(0, 0));
        List<String> preparations = new ArrayList<>();
        List<String> commits = new ArrayList<>();
        SimulationOriginCoordinator.Participant originAwareProbe =
                (oldSimulation, nextSimulation, oldRender, nextRender) -> {
                    assertEquals(zero, oldSimulation);
                    assertEquals(zero, nextSimulation);
                    assertEquals(zeroRender, oldRender);
                    assertEquals(zeroRender, nextRender);
                    preparations.add("origin-aware");
                    return () -> commits.add("origin-aware");
                };
        SimulationOriginCoordinator coordinator = new SimulationOriginCoordinator(
                Thread.currentThread(), zero, zeroRender, List.of(originAwareProbe));

        assertTrue(coordinator.initializeParticipants());
        assertTrue(coordinator.initializeParticipants());
        assertTrue(coordinator.rebase(zero, zeroRender));

        assertEquals(List.of("origin-aware"), preparations);
        assertEquals(List.of("origin-aware"), commits);
        assertEquals(zero, coordinator.simulationOrigin());
        assertEquals(zeroRender, coordinator.renderOrigin());
    }

    @Test
    void initializationPreparationFailureCommitsNothingAndLeavesOriginsUnchanged() {
        SimulationOrigin zero = new SimulationOrigin(new ChunkKey(0, 0));
        RenderOrigin zeroRender = new RenderOrigin(new ChunkKey(0, 0));
        List<String> commits = new ArrayList<>();
        SimulationOriginCoordinator coordinator = new SimulationOriginCoordinator(
                Thread.currentThread(),
                zero,
                zeroRender,
                List.of(
                        (oldSim, nextSim, oldRender, nextRender) ->
                                () -> commits.add("first"),
                        (oldSim, nextSim, oldRender, nextRender) -> {
                            throw new IllegalStateException("cannot initialize");
                        }));

        assertFalse(coordinator.initializeParticipants());

        assertEquals(List.of(), commits);
        assertEquals(zero, coordinator.simulationOrigin());
        assertEquals(zeroRender, coordinator.renderOrigin());
    }

    @Test
    void initializationRejectsReentrantMutationBeforeCommit() {
        SimulationOrigin zero = new SimulationOrigin(new ChunkKey(0, 0));
        RenderOrigin zeroRender = new RenderOrigin(new ChunkKey(0, 0));
        List<String> commits = new ArrayList<>();
        AtomicReference<SimulationOriginCoordinator> reference = new AtomicReference<>();
        SimulationOriginCoordinator.Participant reentrant =
                (oldSim, nextSim, oldRender, nextRender) -> {
                    assertThrows(
                            IllegalStateException.class,
                            () -> reference.get().initializeParticipants());
                    return () -> commits.add("initialized");
                };
        SimulationOriginCoordinator coordinator = new SimulationOriginCoordinator(
                Thread.currentThread(), zero, zeroRender, List.of(reentrant));
        reference.set(coordinator);

        assertTrue(coordinator.initializeParticipants());

        assertEquals(List.of("initialized"), commits);
    }

    @Test
    void anyPreparationFailureLeavesEveryParticipantAndBothOriginsUnchanged() {
        List<String> names =
                List.of("player", "camera", "physics body", "WorldItems", "transients", "chunk renders");
        SimulationOrigin oldSimulation = new SimulationOrigin(new ChunkKey(0, 0));
        RenderOrigin oldRender = new RenderOrigin(new ChunkKey(0, 0));
        SimulationOrigin nextSimulation = new SimulationOrigin(new ChunkKey(8, -4));
        RenderOrigin nextRender = new RenderOrigin(new ChunkKey(8, -4));

        for (String failingName : names) {
            List<String> commits = new ArrayList<>();
            SimulationOriginCoordinator coordinator =
                    new SimulationOriginCoordinator(
                            Thread.currentThread(),
                            oldSimulation,
                            oldRender,
                            probes(names, failingName, commits));

            assertFalse(coordinator.rebase(nextSimulation, nextRender));
            assertEquals(List.of(), commits, failingName);
            assertEquals(oldSimulation, coordinator.simulationOrigin(), failingName);
            assertEquals(oldRender, coordinator.renderOrigin(), failingName);
        }
    }

    @Test
    void reentrantMutationIsRejectedBeforeAnyPreparedCommitRuns() {
        SimulationOrigin oldSimulation = new SimulationOrigin(new ChunkKey(0, 0));
        RenderOrigin oldRender = new RenderOrigin(new ChunkKey(0, 0));
        SimulationOrigin nextSimulation = new SimulationOrigin(new ChunkKey(2, 0));
        RenderOrigin nextRender = new RenderOrigin(new ChunkKey(2, 0));
        List<String> commits = new ArrayList<>();
        AtomicReference<SimulationOriginCoordinator> reference = new AtomicReference<>();
        SimulationOriginCoordinator.Participant reentrant =
                (oldSim, newSim, oldRen, newRen) -> {
                    assertThrows(
                            IllegalStateException.class,
                            () -> reference.get().rebase(nextSimulation, nextRender));
                    return () -> commits.add("player");
                };
        SimulationOriginCoordinator coordinator =
                new SimulationOriginCoordinator(
                        Thread.currentThread(), oldSimulation, oldRender, List.of(reentrant));
        reference.set(coordinator);

        assertTrue(coordinator.rebase(nextSimulation, nextRender));

        assertEquals(List.of("player"), commits);
        assertEquals(nextSimulation, coordinator.simulationOrigin());
        assertEquals(nextRender, coordinator.renderOrigin());
    }

    @Test
    void successfulRebaseCommitsEveryConcreteParticipantBeforePublishingBothOriginsOnce() {
        List<String> commits = new ArrayList<>();
        List<OriginObservation> observedOrigins = new ArrayList<>();
        List<String> names =
                List.of("player", "camera", "physics body", "WorldItems", "transients", "chunk renders");
        SimulationOrigin oldSimulation = new SimulationOrigin(new ChunkKey(-3, 9));
        RenderOrigin oldRender = new RenderOrigin(new ChunkKey(-3, 9));
        SimulationOrigin nextSimulation = new SimulationOrigin(new ChunkKey(4, -7));
        RenderOrigin nextRender = new RenderOrigin(new ChunkKey(4, -7));
        AtomicReference<SimulationOriginCoordinator> reference = new AtomicReference<>();
        SimulationOriginCoordinator coordinator =
                new SimulationOriginCoordinator(
                        Thread.currentThread(),
                        oldSimulation,
                        oldRender,
                        probes(names, null, commits, reference, observedOrigins));
        reference.set(coordinator);

        assertTrue(coordinator.rebase(nextSimulation, nextRender));

        assertEquals(names, commits);
        assertEquals(names, observedOrigins.stream().map(OriginObservation::name).toList());
        for (OriginObservation observation : observedOrigins) {
            assertEquals(oldSimulation, observation.simulationOrigin(), observation.name());
            assertEquals(oldRender, observation.renderOrigin(), observation.name());
        }
        assertEquals(nextSimulation, coordinator.simulationOrigin());
        assertEquals(nextRender, coordinator.renderOrigin());
    }

    @Test
    void wrongOwnerThreadFailsBeforePreparingOrPublishing() throws InterruptedException {
        SimulationOrigin oldSimulation = new SimulationOrigin(new ChunkKey(0, 0));
        RenderOrigin oldRender = new RenderOrigin(new ChunkKey(0, 0));
        SimulationOrigin nextSimulation = new SimulationOrigin(new ChunkKey(1, 1));
        RenderOrigin nextRender = new RenderOrigin(new ChunkKey(1, 1));
        List<String> commits = new ArrayList<>();
        SimulationOriginCoordinator coordinator =
                new SimulationOriginCoordinator(
                        Thread.currentThread(),
                        oldSimulation,
                        oldRender,
                        probes(List.of("player"), null, commits));
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread worker =
                new Thread(
                        () -> {
                            try {
                                coordinator.rebase(nextSimulation, nextRender);
                            } catch (Throwable caught) {
                                failure.set(caught);
                            }
                        });

        worker.start();
        worker.join();

        assertEquals(IllegalStateException.class, failure.get().getClass());
        assertEquals(List.of(), commits);
        assertEquals(oldSimulation, coordinator.simulationOrigin());
        assertEquals(oldRender, coordinator.renderOrigin());
    }

    @Test
    void simulationAndRenderOriginsMustNameTheSameCanonicalChunk() {
        SimulationOrigin zero = new SimulationOrigin(new ChunkKey(0, 0));
        RenderOrigin zeroRender = new RenderOrigin(new ChunkKey(0, 0));

        assertThrows(IllegalArgumentException.class, () ->
                new SimulationOriginCoordinator(
                        Thread.currentThread(), zero,
                        new RenderOrigin(new ChunkKey(1, 0)), List.of()));

        SimulationOriginCoordinator coordinator = new SimulationOriginCoordinator(
                Thread.currentThread(), zero, zeroRender, List.of());
        assertThrows(IllegalArgumentException.class, () -> coordinator.rebase(
                new SimulationOrigin(new ChunkKey(2, 0)),
                new RenderOrigin(new ChunkKey(3, 0))));
        assertEquals(zero, coordinator.simulationOrigin());
        assertEquals(zeroRender, coordinator.renderOrigin());
    }

    private static List<SimulationOriginCoordinator.Participant> probes(
            List<String> names, String failingName, List<String> commits) {
        return probes(names, failingName, commits, null, null);
    }

    private static List<SimulationOriginCoordinator.Participant> probes(
            List<String> names,
            String failingName,
            List<String> commits,
            AtomicReference<SimulationOriginCoordinator> coordinator,
            List<OriginObservation> observedOrigins) {
        List<SimulationOriginCoordinator.Participant> participants = new ArrayList<>();
        for (String name : names) {
            participants.add(
                    (oldSimulation, nextSimulation, oldRender, nextRender) -> {
                        if (name.equals(failingName)) {
                            throw new IllegalArgumentException(name + " cannot prepare");
                        }
                        return () -> {
                            commits.add(name);
                            if (coordinator != null) {
                                SimulationOriginCoordinator value = coordinator.get();
                                observedOrigins.add(
                                        new OriginObservation(
                                                name,
                                                value.simulationOrigin(),
                                                value.renderOrigin()));
                            }
                        };
                    });
        }
        return participants;
    }

    private record OriginObservation(
            String name, SimulationOrigin simulationOrigin, RenderOrigin renderOrigin) {}
}
