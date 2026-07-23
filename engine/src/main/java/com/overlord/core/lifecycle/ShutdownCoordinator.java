package com.overlord.core.lifecycle;

import java.util.ArrayDeque;
import java.util.Objects;

public final class ShutdownCoordinator implements AutoCloseable {
    private final ArrayDeque<Registration> registrations = new ArrayDeque<>();
    private boolean closed;

    public synchronized void register(String name, Runnable action) {
        if (closed) {
            throw new IllegalStateException("Shutdown has already started");
        }
        registrations.addLast(
                new Registration(
                        Objects.requireNonNull(name, "name"),
                        Objects.requireNonNull(action, "action")));
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;

        Throwable firstFailure = null;
        while (!registrations.isEmpty()) {
            Registration registration = registrations.removeLast();
            try {
                registration.action().run();
            } catch (RuntimeException | Error failure) {
                if (firstFailure == null) {
                    firstFailure = failure;
                } else {
                    firstFailure.addSuppressed(failure);
                }
            }
        }

        if (firstFailure != null) {
            rethrow(firstFailure);
        }
    }

    private static void rethrow(Throwable failure) {
        if (failure instanceof RuntimeException runtimeException) {
            throw runtimeException;
        }
        throw (Error) failure;
    }

    private record Registration(String name, Runnable action) {}
}
