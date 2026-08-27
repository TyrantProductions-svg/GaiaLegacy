package com.gaia.world.streaming;

import com.overlord.voxel.ChunkCoordinatePolicy;
import com.overlord.voxel.ChunkKey;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.concurrent.Callable;
import java.util.function.BooleanSupplier;

/** Fixed-capacity lane whose token includes completed-but-undrained results. */
public final class ChunkWorkScheduler implements AutoCloseable {
    public enum Admission { ADMITTED, REJECTED_CAPACITY }
    public enum Cancellation {
        REMOVED_QUEUED,
        MARKED_RUNNING,
        ALREADY_COMPLETED,
        NOT_FOUND
    }
    public record Metrics(int accepted, int active, int queued, int completed) {}
    public record Work(
            long workId,
            ChunkKey key,
            long desiredEpoch,
            int priority,
            ChunkWorkResult.Kind kind,
            long expectedRevision,
            Callable<ChunkWorkResult> operation,
            BooleanSupplier durableCompletion) {
        public Work(
                long workId,
                ChunkKey key,
                long desiredEpoch,
                int priority,
                ChunkWorkResult.Kind kind,
                long expectedRevision,
                Callable<ChunkWorkResult> operation) {
            this(workId, key, desiredEpoch, priority, kind, expectedRevision,
                    operation, () -> false);
        }

        public Work(
                long workId,
                ChunkKey key,
                long desiredEpoch,
                int priority,
                Callable<ChunkWorkResult> operation) {
            this(workId, key, desiredEpoch, priority,
                    ChunkWorkResult.Kind.LOAD_GENERATE, 0L, operation);
        }

        public Work {
            if (workId <= 0L || desiredEpoch <= 0L || expectedRevision < 0L) {
                throw new IllegalArgumentException("work identity is invalid");
            }
            key = ChunkCoordinatePolicy.requireSafe(key);
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(operation, "operation");
            Objects.requireNonNull(durableCompletion, "durableCompletion");
        }
    }

    private final int capacity;
    private final PriorityQueue<State> queued = new PriorityQueue<>(
            Comparator.comparingInt((State state) -> state.queuePriority)
                    .thenComparingLong(state -> state.work.workId()));
    private final ArrayDeque<State> completed = new ArrayDeque<>();
    private final Map<Long, State> accepted = new HashMap<>();
    private final List<Thread> workers;
    private int active;
    private boolean closed;

    public ChunkWorkScheduler(String threadPrefix, int capacity, int activeLimit) {
        String prefix = Objects.requireNonNull(threadPrefix, "threadPrefix");
        if (prefix.isBlank() || capacity <= 0 || activeLimit <= 0
                || activeLimit > capacity) {
            throw new IllegalArgumentException("scheduler bounds are invalid");
        }
        this.capacity = capacity;
        List<Thread> created = new ArrayList<>(activeLimit);
        for (int index = 0; index < activeLimit; index++) {
            Thread worker = new Thread(this::runWorker, prefix + "-" + index);
            worker.setDaemon(true);
            created.add(worker);
            worker.start();
        }
        workers = List.copyOf(created);
    }

    public synchronized Admission submit(Work work) {
        Work checked = Objects.requireNonNull(work, "work");
        if (closed || accepted.size() >= capacity) {
            return Admission.REJECTED_CAPACITY;
        }
        if (accepted.containsKey(checked.workId())) {
            throw new IllegalArgumentException("workId is already accepted");
        }
        State state = new State(checked);
        accepted.put(checked.workId(), state);
        queued.add(state);
        notifyAll();
        return Admission.ADMITTED;
    }

    public synchronized Cancellation cancel(long workId) {
        State state = accepted.get(workId);
        if (state == null) {
            return Cancellation.NOT_FOUND;
        }
        if (state.phase == Phase.QUEUED) {
            state.canceled = true;
            queued.remove(state);
            accepted.remove(workId);
            notifyAll();
            return Cancellation.REMOVED_QUEUED;
        }
        if (state.phase == Phase.COMPLETED) {
            return Cancellation.ALREADY_COMPLETED;
        }
        if (durableCompletionPublished(state.work)) {
            return Cancellation.ALREADY_COMPLETED;
        }
        state.canceled = true;
        return Cancellation.MARKED_RUNNING;
    }

    public synchronized List<ChunkWorkResult> drainCompleted(int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive");
        }
        List<ChunkWorkResult> drained = new ArrayList<>(Math.min(limit, completed.size()));
        while (drained.size() < limit && !completed.isEmpty()) {
            State state = completed.removeFirst();
            accepted.remove(state.work.workId(), state);
            drained.add(state.result);
        }
        notifyAll();
        return List.copyOf(drained);
    }

    public synchronized Metrics metrics() {
        return new Metrics(accepted.size(), active, queued.size(), completed.size());
    }

    /** Rebuilds queued priority in place without replacing work identity or tokens. */
    public synchronized void reprioritizeQueued(List<ChunkKey> orderedKeys) {
        Objects.requireNonNull(orderedKeys, "orderedKeys");
        Map<ChunkKey, Integer> ranks = new HashMap<>();
        for (int index = 0; index < orderedKeys.size(); index++) {
            ChunkKey key = ChunkCoordinatePolicy.requireSafe(orderedKeys.get(index));
            if (ranks.put(key, index) != null) {
                throw new IllegalArgumentException("orderedKeys repeats a Chunk key");
            }
        }
        Map<State, Integer> validated = new HashMap<>();
        for (State state : queued) {
            Integer rank = ranks.get(state.work.key());
            if (rank == null) {
                throw new IllegalArgumentException(
                        "orderedKeys omits currently queued work");
            }
            validated.put(state, rank);
        }
        for (Map.Entry<State, Integer> entry : validated.entrySet()) {
            entry.getKey().queuePriority = entry.getValue();
        }
        List<State> rebuild = new ArrayList<>(queued);
        queued.clear();
        queued.addAll(rebuild);
        notifyAll();
    }

    public synchronized Map<Long, RequestedLoadPhase> phasesByWorkId() {
        Map<Long, RequestedLoadPhase> phases = new HashMap<>();
        for (State state : accepted.values()) {
            RequestedLoadPhase phase = switch (state.phase) {
                case QUEUED -> RequestedLoadPhase.QUEUED;
                case RUNNING -> RequestedLoadPhase.ACTIVE;
                case COMPLETED -> RequestedLoadPhase.COMPLETED;
            };
            phases.put(state.work.workId(), phase);
        }
        return Map.copyOf(phases);
    }

    public synchronized void awaitQuiescent(Duration timeout)
            throws InterruptedException {
        long remaining = Objects.requireNonNull(timeout, "timeout").toNanos();
        long deadline = System.nanoTime() + remaining;
        while ((!queued.isEmpty() || active != 0) && remaining > 0L) {
            long millis = Math.max(1L, Math.min(remaining / 1_000_000L, 100L));
            wait(millis);
            remaining = deadline - System.nanoTime();
        }
        if (!queued.isEmpty() || active != 0) {
            throw new IllegalStateException("scheduler did not become quiescent");
        }
    }

    public boolean isTerminated() {
        for (Thread worker : workers) {
            if (worker.isAlive()) {
                return false;
            }
        }
        return closed;
    }

    public int liveWorkerCount() {
        return (int) workers.stream().filter(Thread::isAlive).count();
    }

    @Override
    public void close() {
        synchronized (this) {
            if (closed) {
                if (liveWorkerCount() != 0) {
                    throw new IllegalStateException(
                            "Chunk streaming worker did not terminate");
                }
                accepted.clear();
                active = 0;
                notifyAll();
                return;
            }
            closed = true;
            for (State state : accepted.values()) {
                state.canceled = true;
            }
            queued.clear();
            completed.clear();
            notifyAll();
        }
        for (Thread worker : workers) {
            worker.interrupt();
        }
        for (Thread worker : workers) {
            try {
                worker.join(5_000L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        int liveWorkers = liveWorkerCount();
        synchronized (this) {
            if (liveWorkers == 0) {
                accepted.clear();
                active = 0;
            }
            notifyAll();
        }
        if (liveWorkers != 0) {
            throw new IllegalStateException(
                    "Chunk streaming worker did not terminate");
        }
    }

    private void runWorker() {
        while (true) {
            State state;
            synchronized (this) {
                while (!closed && queued.isEmpty()) {
                    try {
                        wait();
                    } catch (InterruptedException interrupted) {
                        if (closed) {
                            return;
                        }
                    }
                }
                if (closed) {
                    return;
                }
                state = queued.remove();
                state.phase = Phase.RUNNING;
                active++;
            }
            ChunkWorkResult result;
            try {
                result = Objects.requireNonNull(
                        state.work.operation().call(), "work result");
                if (result.workId() != state.work.workId()
                        || !result.key().equals(state.work.key())
                        || result.desiredEpoch() != state.work.desiredEpoch()) {
                    throw new IllegalStateException("worker result identity mismatch");
                }
            } catch (Throwable failure) {
                result = ChunkWorkResult.workerFailure(
                        state.work.workId(), state.work.key(),
                        state.work.desiredEpoch(), state.work.kind(),
                        state.work.expectedRevision(), failure);
            }
            synchronized (this) {
                active--;
                if (closed) {
                    accepted.remove(state.work.workId(), state);
                    notifyAll();
                    continue;
                }
                boolean preserveDurableSave = result.kind() == ChunkWorkResult.Kind.SAVE
                        && result.status() == ChunkWorkResult.Status.SUCCESS
                        && durableCompletionPublished(state.work);
                state.result = state.canceled && !preserveDurableSave
                        ? result.canceled()
                        : result;
                state.phase = Phase.COMPLETED;
                completed.addLast(state);
                notifyAll();
            }
        }
    }

    private static boolean durableCompletionPublished(Work work) {
        return work.kind() == ChunkWorkResult.Kind.SAVE
                && work.durableCompletion().getAsBoolean();
    }

    private enum Phase { QUEUED, RUNNING, COMPLETED }

    private static final class State {
        private final Work work;
        private int queuePriority;
        private Phase phase = Phase.QUEUED;
        private boolean canceled;
        private ChunkWorkResult result;

        private State(Work work) {
            this.work = work;
            this.queuePriority = work.priority();
        }
    }
}
