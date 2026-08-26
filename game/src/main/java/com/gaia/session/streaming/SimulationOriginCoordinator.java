package com.gaia.session.streaming;

import com.overlord.physics.SimulationOrigin;
import com.overlord.renderer.RenderOrigin;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Owner-thread prepare-all/publish-last transaction for origin changes. */
public final class SimulationOriginCoordinator {
    private final Thread ownerThread;
    private final List<Participant> participants;
    private SimulationOrigin simulationOrigin;
    private RenderOrigin renderOrigin;
    private boolean transactionActive;
    private boolean participantsInitialized;

    public SimulationOriginCoordinator(
            Thread ownerThread,
            SimulationOrigin simulationOrigin,
            RenderOrigin renderOrigin,
            List<Participant> participants) {
        this.ownerThread = Objects.requireNonNull(ownerThread, "ownerThread");
        this.simulationOrigin = Objects.requireNonNull(simulationOrigin, "simulationOrigin");
        this.renderOrigin = Objects.requireNonNull(renderOrigin, "renderOrigin");
        requireMatchingOrigins(this.simulationOrigin, this.renderOrigin);
        this.participants = List.copyOf(Objects.requireNonNull(participants, "participants"));
    }

    public SimulationOrigin simulationOrigin() {
        return simulationOrigin;
    }

    public RenderOrigin renderOrigin() {
        return renderOrigin;
    }

    /**
     * Atomically installs the current origins into every participant exactly once.
     * Construction intentionally does not prepare or mutate participants.
     */
    public boolean initializeParticipants() {
        return initializeParticipants(simulationOrigin, renderOrigin);
    }

    /** Installs a chosen initial origin through the same all-participant transaction. */
    public boolean initializeParticipants(
            SimulationOrigin initialSimulation,
            RenderOrigin initialRender) {
        assertOwner();
        Objects.requireNonNull(initialSimulation, "initialSimulation");
        Objects.requireNonNull(initialRender, "initialRender");
        requireMatchingOrigins(initialSimulation, initialRender);
        if (transactionActive) {
            throw new IllegalStateException("origin transaction is already active");
        }
        if (participantsInitialized) {
            return simulationOrigin.equals(initialSimulation)
                    && renderOrigin.equals(initialRender);
        }
        return prepareCommitAndPublish(initialSimulation, initialRender);
    }

    public boolean rebase(SimulationOrigin nextSimulation, RenderOrigin nextRender) {
        assertOwner();
        Objects.requireNonNull(nextSimulation, "nextSimulation");
        Objects.requireNonNull(nextRender, "nextRender");
        requireMatchingOrigins(nextSimulation, nextRender);
        if (transactionActive) {
            throw new IllegalStateException("origin transaction is already active");
        }
        if (simulationOrigin.equals(nextSimulation) && renderOrigin.equals(nextRender)) {
            return participantsInitialized || initializeParticipants();
        }
        return prepareCommitAndPublish(nextSimulation, nextRender);
    }

    private boolean prepareCommitAndPublish(
            SimulationOrigin nextSimulation, RenderOrigin nextRender) {
        transactionActive = true;
        try {
            List<Prepared> prepared = new ArrayList<>(participants.size());
            try {
                for (Participant participant : participants) {
                    prepared.add(Objects.requireNonNull(
                            participant.prepare(
                                    simulationOrigin, nextSimulation, renderOrigin, nextRender),
                            "prepared origin participant"));
                }
            } catch (RuntimeException preparationFailure) {
                return false;
            }
            for (Prepared action : prepared) {
                action.commit();
            }
            simulationOrigin = nextSimulation;
            renderOrigin = nextRender;
            participantsInitialized = true;
            return true;
        } finally {
            transactionActive = false;
        }
    }

    private void assertOwner() {
        if (Thread.currentThread() != ownerThread) {
            throw new IllegalStateException("origin transaction must run on its owner thread");
        }
    }

    private static void requireMatchingOrigins(
            SimulationOrigin simulation, RenderOrigin render) {
        if (!simulation.chunkKey().equals(render.chunkKey())) {
            throw new IllegalArgumentException(
                    "simulation and render origins must name the same Chunk");
        }
    }

    @FunctionalInterface
    public interface Participant {
        Prepared prepare(
                SimulationOrigin oldSimulation,
                SimulationOrigin nextSimulation,
                RenderOrigin oldRender,
                RenderOrigin nextRender);
    }

    @FunctionalInterface
    public interface Prepared {
        void commit();
    }
}
