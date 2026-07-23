package com.overlord.core.thread;

import java.util.Objects;

public final class MainThreadGuard {
    private final Thread owner;

    private MainThreadGuard(Thread owner) {
        this.owner = Objects.requireNonNull(owner, "owner");
    }

    public static MainThreadGuard captureCurrentThread() {
        return new MainThreadGuard(Thread.currentThread());
    }

    public void assertMainThread(String operation) {
        Thread current = Thread.currentThread();
        if (current != owner) {
            throw new IllegalStateException(
                    operation
                            + " must run on "
                            + owner.getName()
                            + " but ran on "
                            + current.getName());
        }
    }
}
