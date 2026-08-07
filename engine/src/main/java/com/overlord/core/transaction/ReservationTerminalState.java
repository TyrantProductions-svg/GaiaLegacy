package com.overlord.core.transaction;

/** Read-only lifecycle state shared by reservation audit contracts. */
public enum ReservationTerminalState {
    PENDING,
    COMMITTED,
    ROLLED_BACK
}
