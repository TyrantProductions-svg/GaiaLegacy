package com.overlord.inventory.api;

import com.overlord.event.Event;
import java.util.Objects;

/**
 * Reports a failed post-commit inventory notification. The state change is
 * already durable in the owning inventory and must not be retried blindly.
 */
public final class InventoryEventDispatchException extends RuntimeException {
    private final Event notification;
    private final boolean stateChangeApplied;

    public InventoryEventDispatchException(
            String message,
            Throwable cause,
            Event notification,
            boolean stateChangeApplied) {
        super(message, cause);
        this.notification = Objects.requireNonNull(notification, "notification");
        this.stateChangeApplied = stateChangeApplied;
    }

    public Event notification() {
        return notification;
    }

    public boolean stateChangeApplied() {
        return stateChangeApplied;
    }
}
