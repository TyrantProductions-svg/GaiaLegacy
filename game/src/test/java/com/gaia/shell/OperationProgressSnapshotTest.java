package com.gaia.shell;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;

final class OperationProgressSnapshotTest {
    @Test
    void exactPhaseUnitsRemainTextWithoutClaimingAnOverallFraction() {
        OperationProgressSnapshot zero = new OperationProgressSnapshot(
                OperationProgressSnapshot.Kind.LOAD_WORLD,
                "RESTORING CHUNKS",
                "Preparing simulation neighborhood",
                OptionalLong.of(0),
                OptionalLong.of(25),
                OperationProgressSnapshot.TerminalState.RUNNING,
                true,
                Optional.empty());
        OperationProgressSnapshot complete = new OperationProgressSnapshot(
                OperationProgressSnapshot.Kind.LOAD_WORLD,
                "RESTORING CHUNKS",
                "Preparing simulation neighborhood",
                OptionalLong.of(25),
                OptionalLong.of(25),
                OperationProgressSnapshot.TerminalState.SUCCESS,
                false,
                Optional.empty());

        assertTrue(zero.fraction().isEmpty(),
                "phase-local 0/25 is not an exact overall operation total");
        assertTrue(complete.fraction().isEmpty(),
                "phase-local 25/25 cannot fabricate overall 100 percent");
        assertEquals("0 / 25", zero.exactUnitsText().orElseThrow());
        assertEquals("25 / 25", complete.exactUnitsText().orElseThrow());
    }

    @Test
    void unknownTotalIsIndeterminateAndCannotInventCompletedUnits() {
        OperationProgressSnapshot unknown = OperationProgressSnapshot.indeterminate(
                OperationProgressSnapshot.Kind.SAVE_WORLD,
                "VALIDATING CANDIDATE",
                "Checking the complete save root",
                false);

        assertTrue(unknown.fraction().isEmpty());
        assertTrue(unknown.exactUnitsText().isEmpty());
        assertFalse(unknown.cancelable());
        assertEquals(0, unknown.phaseOrdinal());
        assertEquals(0L, unknown.operationId());
        assertEquals(0L, unknown.sequence());
    }

    @Test
    void exactOverallProgressIsSeparateFromExactPhaseUnits() {
        OperationProgressSnapshot exact = OperationProgressSnapshot.exactOverall(
                OperationProgressSnapshot.Kind.LOAD_WORLD,
                2,
                "RESTORING SESSION",
                "Publishing validated runtime state",
                OptionalLong.of(4),
                OptionalLong.of(10),
                7,
                20,
                true);

        assertEquals(0.35d, exact.fraction().orElseThrow(), 1.0e-12d);
        assertEquals("4 / 10", exact.exactUnitsText().orElseThrow());
        assertEquals(2, exact.phaseOrdinal());
    }

    @Test
    void invalidOrFabricatedProgressIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new OperationProgressSnapshot(
                OperationProgressSnapshot.Kind.LOAD_WORLD,
                "RESTORING",
                "Invalid overflow",
                OptionalLong.of(26),
                OptionalLong.of(25),
                OperationProgressSnapshot.TerminalState.RUNNING,
                true,
                Optional.empty()));
        assertThrows(IllegalArgumentException.class, () -> new OperationProgressSnapshot(
                OperationProgressSnapshot.Kind.LOAD_WORLD,
                "RESTORING",
                "Missing denominator",
                OptionalLong.of(1),
                OptionalLong.empty(),
                OperationProgressSnapshot.TerminalState.RUNNING,
                true,
                Optional.empty()));
        assertThrows(IllegalArgumentException.class, () -> new OperationProgressSnapshot(
                OperationProgressSnapshot.Kind.LOAD_WORLD,
                "RESTORING",
                "Terminal operation cannot be canceled",
                OptionalLong.empty(),
                OptionalLong.empty(),
                OperationProgressSnapshot.TerminalState.FAILED,
                true,
                Optional.of("failure")));
    }
}
