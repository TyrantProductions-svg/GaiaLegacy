package com.overlord.worlditem.api;

import java.util.Objects;

/** Canonical logical/runtime and physical state for one restored world item. */
public record WorldItemRestoreEntry(
        WorldItemRuntimeSnapshot runtime,
        WorldItemPhysicalState physicalState) {
    public WorldItemRestoreEntry {
        runtime = Objects.requireNonNull(runtime, "runtime");
        physicalState = Objects.requireNonNull(physicalState, "physicalState");
    }
}
