package com.overlord.worlditem.api;

import java.util.Objects;

/** Immutable physical/runtime view derived from the logical world-item store. */
public record WorldItemPhysicalSnapshot(
        WorldItemRuntimeSnapshot runtime,
        WorldItemPhysicalState state,
        boolean extractionReserved) {
    public WorldItemPhysicalSnapshot {
        runtime = Objects.requireNonNull(runtime, "runtime");
        state = Objects.requireNonNull(state, "state");
    }

    public WorldItemId id() {
        return runtime.item().id();
    }
}
