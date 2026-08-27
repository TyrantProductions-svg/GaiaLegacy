package com.gaia.world.streaming;

/** Detached bounded observation of an accepted load/generate request. */
public enum RequestedLoadPhase {
    QUEUED,
    ACTIVE,
    COMPLETED
}
