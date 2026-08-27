package com.gaia.shell;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/** One-slot bounded worker lane and sole publisher for product operation progress. */
public final class ProductOperationRunner implements AutoCloseable {
    private static final Duration CLOSE_TIMEOUT = Duration.ofSeconds(5);

    private final Thread ownerThread;
    private final ExecutorService executor;
    private final AtomicLong generations = new AtomicLong();
    private final Object publicationLock = new Object();
    private final AtomicReference<Active> active = new AtomicReference<>();
    private final AtomicReference<Completion> completion = new AtomicReference<>();
    private final AtomicReference<OperationProgressSnapshot> progress =
            new AtomicReference<>();
    private long publicationSequence;
    private boolean closed;

    private ProductOperationRunner(Thread ownerThread, ExecutorService executor) {
        this.ownerThread = Objects.requireNonNull(ownerThread, "ownerThread");
        this.executor = Objects.requireNonNull(executor, "executor");
    }

    public static ProductOperationRunner createForOwner(
            Thread ownerThread, String threadName) {
        String checkedName = Objects.requireNonNull(threadName, "threadName").strip();
        if (checkedName.isEmpty()) {
            throw new IllegalArgumentException("threadName must not be blank");
        }
        ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread worker = new Thread(runnable, checkedName);
            worker.setDaemon(true);
            return worker;
        });
        return new ProductOperationRunner(ownerThread, executor);
    }

    public long start(
            OperationProgressSnapshot initialProgress,
            WorkerTask task) {
        WorkerTask checkedTask = Objects.requireNonNull(task, "task");
        long generation = startOwner(initialProgress);
        if (!submitWorker(generation, checkedTask)) {
            throw new IllegalStateException("new operation rejected its worker");
        }
        return generation;
    }

    public long startOwner(OperationProgressSnapshot initialProgress) {
        assertOwner();
        if (closed) {
            throw new IllegalStateException("operation runner is closed");
        }
        OperationProgressSnapshot checked = Objects.requireNonNull(
                initialProgress, "initialProgress");
        if (checked.terminalState()
                        != OperationProgressSnapshot.TerminalState.RUNNING
                || checked.operationId() != 0L
                || checked.sequence() != 0L) {
            throw new IllegalArgumentException(
                    "initial progress must be running and unpublished");
        }
        if (active.get() != null || completion.get() != null) {
            throw new IllegalStateException("the bounded operation lane is occupied");
        }
        long generation = generations.incrementAndGet();
        if (generation <= 0L) {
            throw new IllegalStateException("operation generation exhausted");
        }
        Active next = new Active(generation, checked.kind());
        if (!active.compareAndSet(null, next)) {
            throw new IllegalStateException("the bounded operation lane is occupied");
        }
        synchronized (publicationLock) {
            progress.set(checked.published(generation, nextSequenceLocked()));
        }
        return generation;
    }

    public boolean submitWorker(long generation, WorkerTask task) {
        assertOwner();
        Active expected = current(generation);
        WorkerTask checked = Objects.requireNonNull(task, "task");
        if (expected == null
                || completion.get() != null
                || !expected.workerSubmitted.compareAndSet(false, true)) {
            return false;
        }
        expected.future = executor.submit(() -> execute(expected, checked));
        return true;
    }

    public boolean completeOwnerWork(long generation, Object value) {
        assertOwner();
        Active expected = current(generation);
        return expected != null && completion.compareAndSet(
                null,
                Completion.success(
                        generation, Objects.requireNonNull(value, "value")));
    }

    public Optional<OperationProgressSnapshot> progress() {
        assertOwner();
        return Optional.ofNullable(progress.get());
    }

    public OptionalLong activeGeneration() {
        assertOwner();
        Active current = active.get();
        return current == null
                ? OptionalLong.empty()
                : OptionalLong.of(current.generation);
    }

    public int acceptedCount() {
        assertOwner();
        return active.get() == null ? 0 : 1;
    }

    public Optional<Completion> peekCompletion() {
        assertOwner();
        return Optional.ofNullable(completion.get());
    }

    public boolean ownerUpdate(
            long generation, OperationProgressUpdate nextProgress) {
        assertOwner();
        Active expected = current(generation);
        if (expected == null) {
            return false;
        }
        return publishUpdate(
                expected, Objects.requireNonNull(nextProgress, "nextProgress"));
    }

    public boolean finishSuccess(long generation) {
        assertOwner();
        Active expected = current(generation);
        Completion observed = completion.get();
        if (expected == null
                || observed == null
                || observed.generation() != generation
                || observed.value().isEmpty()) {
            return false;
        }
        return publishTerminal(
                expected,
                OperationProgressSnapshot.TerminalState.SUCCESS,
                Optional.empty());
    }

    public boolean finishFailure(long generation, Throwable failure) {
        assertOwner();
        Active expected = current(generation);
        if (expected == null) {
            return false;
        }
        completion.compareAndSet(
                null,
                Completion.failed(
                        generation,
                        Objects.requireNonNull(failure, "failure")));
        return publishTerminal(
                expected,
                OperationProgressSnapshot.TerminalState.FAILED,
                Optional.of(shortFailure(failure)));
    }

    /** Releases the sole capacity token only after an owner-published terminal state. */
    public boolean releaseTerminal(long generation) {
        assertOwner();
        synchronized (publicationLock) {
            Active current = active.get();
            OperationProgressSnapshot observed = progress.get();
            Completion completed = completion.get();
            if (current == null
                    || current.generation != generation
                    || observed == null
                    || observed.operationId() != generation
                    || observed.terminalState()
                            == OperationProgressSnapshot.TerminalState.RUNNING
                    || completed == null
                    || completed.generation() != generation) {
                return false;
            }
            completion.set(null);
            progress.set(null);
            active.set(null);
            return true;
        }
    }

    /** Compatibility owner drain; never clears running, unpublished work. */
    public Optional<Completion> drainCompletion() {
        assertOwner();
        Completion observed = completion.get();
        if (observed == null || !releaseTerminal(observed.generation())) {
            return Optional.empty();
        }
        return Optional.of(observed);
    }

    public boolean cancel(long generation) {
        assertOwner();
        Active expected = current(generation);
        OperationProgressSnapshot observed = progress.get();
        if (expected == null
                || expected.canceled.get()
                || observed == null
                || !observed.cancelable()) {
            return false;
        }
        expected.canceled.set(true);
        completion.compareAndSet(null, Completion.canceled(generation));
        boolean published = publishTerminal(
                expected,
                OperationProgressSnapshot.TerminalState.CANCELED,
                Optional.empty());
        Future<?> future = expected.future;
        if (future != null) {
            future.cancel(true);
        }
        return published;
    }

    @Override
    public void close() {
        assertOwner();
        if (closed) {
            return;
        }
        closed = true;
        Active current = active.get();
        if (current != null) {
            OperationProgressSnapshot observed = progress.get();
            if (observed != null && observed.terminalState()
                    == OperationProgressSnapshot.TerminalState.RUNNING) {
                current.canceled.set(true);
                completion.compareAndSet(
                        null, Completion.canceled(current.generation));
                publishTerminal(
                        current,
                        OperationProgressSnapshot.TerminalState.CANCELED,
                        Optional.empty());
            }
            Future<?> future = current.future;
            if (future != null) {
                future.cancel(true);
            }
        }
        executor.shutdownNow();
        try {
            if (!executor.awaitTermination(
                    CLOSE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                throw new IllegalStateException(
                        "product operation worker did not terminate");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                    "interrupted while closing product operation worker",
                    interrupted);
        }
    }

    private void execute(Active expected, WorkerTask task) {
        try {
            Object value = Objects.requireNonNull(
                    task.run(new Context(expected)), "operation worker result");
            if (!expected.canceled.get() && active.get() == expected) {
                completion.compareAndSet(
                        null, Completion.success(expected.generation, value));
            }
        } catch (Throwable failure) {
            if (!expected.canceled.get() && active.get() == expected) {
                completion.compareAndSet(
                        null, Completion.failed(expected.generation, failure));
            }
        }
    }

    private Active current(long generation) {
        Active observed = active.get();
        return observed != null && observed.generation == generation
                ? observed
                : null;
    }

    private boolean publishUpdate(
            Active expected, OperationProgressUpdate nextProgress) {
        synchronized (publicationLock) {
            if (expected.canceled.get() || active.get() != expected) {
                return false;
            }
            OperationProgressSnapshot observed = progress.get();
            if (observed == null
                    || observed.kind() != expected.kind
                    || observed.terminalState()
                            != OperationProgressSnapshot.TerminalState.RUNNING) {
                return false;
            }
            validateMonotonic(observed, nextProgress);
            progress.set(observed.withUpdate(
                    nextProgress, nextSequenceLocked()));
            return true;
        }
    }

    private static void validateMonotonic(
            OperationProgressSnapshot observed,
            OperationProgressUpdate next) {
        if (next.phaseOrdinal() < observed.phaseOrdinal()) {
            throw new IllegalArgumentException("operation phase cannot regress");
        }
        if (next.phaseOrdinal() != observed.phaseOrdinal()) {
            return;
        }
        boolean oldExact = observed.totalUnits().isPresent();
        boolean nextExact = next.totalUnits().isPresent();
        if (oldExact && !nextExact) {
            throw new IllegalArgumentException(
                    "same-phase exact progress cannot become indeterminate");
        }
        if (!oldExact || !nextExact) {
            return;
        }
        if (observed.totalUnits().orElseThrow()
                != next.totalUnits().orElseThrow()) {
            throw new IllegalArgumentException(
                    "same-phase exact progress total cannot change");
        }
        if (next.completedUnits().orElseThrow()
                < observed.completedUnits().orElseThrow()) {
            throw new IllegalArgumentException(
                    "same-phase exact progress cannot decrease");
        }
    }

    private boolean publishTerminal(
            Active expected,
            OperationProgressSnapshot.TerminalState terminal,
            Optional<String> detail) {
        synchronized (publicationLock) {
            if (active.get() != expected) {
                return false;
            }
            OperationProgressSnapshot observed = progress.get();
            if (observed == null
                    || observed.terminalState()
                            != OperationProgressSnapshot.TerminalState.RUNNING) {
                return false;
            }
            progress.set(observed.terminal(
                    terminal, detail, nextSequenceLocked()));
            return true;
        }
    }

    private long nextSequenceLocked() {
        publicationSequence = Math.addExact(publicationSequence, 1L);
        if (publicationSequence <= 0L) {
            throw new IllegalStateException(
                    "operation publication sequence exhausted");
        }
        return publicationSequence;
    }

    private void assertOwner() {
        if (Thread.currentThread() != ownerThread) {
            throw new IllegalStateException(
                    "product operations must be coordinated by their owner thread");
        }
    }

    private static String shortFailure(Throwable failure) {
        String message = failure.getMessage();
        String text = message == null || message.isBlank()
                ? failure.getClass().getSimpleName()
                : message.strip();
        return text.length() <= 240 ? text : text.substring(0, 240);
    }

    @FunctionalInterface
    public interface WorkerTask {
        Object run(WorkerContext context) throws Exception;
    }

    public interface WorkerContext {
        boolean canceled();

        void update(OperationProgressUpdate nextProgress);
    }

    private final class Context implements WorkerContext {
        private final Active expected;

        private Context(Active expected) {
            this.expected = expected;
        }

        @Override
        public boolean canceled() {
            return expected.canceled.get() || active.get() != expected;
        }

        @Override
        public void update(OperationProgressUpdate nextProgress) {
            if (!canceled()) {
                publishUpdate(expected, Objects.requireNonNull(
                        nextProgress, "nextProgress"));
            }
        }
    }

    public record Completion(
            long generation,
            Optional<Object> value,
            Optional<Throwable> failure,
            boolean canceled) {
        public Completion {
            if (generation <= 0L) {
                throw new IllegalArgumentException("generation must be positive");
            }
            value = Objects.requireNonNull(value, "value");
            failure = Objects.requireNonNull(failure, "failure");
            int outcomes = (value.isPresent() ? 1 : 0)
                    + (failure.isPresent() ? 1 : 0)
                    + (canceled ? 1 : 0);
            if (outcomes != 1) {
                throw new IllegalArgumentException(
                        "completion must contain exactly one outcome");
            }
        }

        private static Completion success(long generation, Object value) {
            return new Completion(
                    generation, Optional.of(value), Optional.empty(), false);
        }

        private static Completion failed(long generation, Throwable failure) {
            return new Completion(
                    generation, Optional.empty(), Optional.of(failure), false);
        }

        private static Completion canceled(long generation) {
            return new Completion(
                    generation, Optional.empty(), Optional.empty(), true);
        }
    }

    private static final class Active {
        private final long generation;
        private final OperationProgressSnapshot.Kind kind;
        private final AtomicBoolean canceled = new AtomicBoolean();
        private final AtomicBoolean workerSubmitted = new AtomicBoolean();
        private volatile Future<?> future;

        private Active(long generation, OperationProgressSnapshot.Kind kind) {
            this.generation = generation;
            this.kind = kind;
        }
    }
}
