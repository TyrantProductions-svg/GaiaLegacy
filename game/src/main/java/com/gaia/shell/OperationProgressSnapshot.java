package com.gaia.shell;

import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalLong;

/** One bounded immutable publication of a product load/save operation. */
public record OperationProgressSnapshot(
        Kind kind,
        int phaseOrdinal,
        String phase,
        String status,
        OptionalLong completedUnits,
        OptionalLong totalUnits,
        OptionalLong overallCompletedUnits,
        OptionalLong overallTotalUnits,
        TerminalState terminalState,
        boolean cancelable,
        Optional<String> detail,
        long operationId,
        long sequence) {
    private static final int MAX_TEXT_LENGTH = 240;

    public OperationProgressSnapshot {
        kind = Objects.requireNonNull(kind, "kind");
        if (phaseOrdinal < 0) {
            throw new IllegalArgumentException("phaseOrdinal must be non-negative");
        }
        phase = requireText(phase, "phase");
        status = requireText(status, "status");
        completedUnits = Objects.requireNonNull(completedUnits, "completedUnits");
        totalUnits = Objects.requireNonNull(totalUnits, "totalUnits");
        overallCompletedUnits = Objects.requireNonNull(
                overallCompletedUnits, "overallCompletedUnits");
        overallTotalUnits = Objects.requireNonNull(
                overallTotalUnits, "overallTotalUnits");
        validateUnitPair(completedUnits, totalUnits, "phase");
        validateUnitPair(overallCompletedUnits, overallTotalUnits, "overall");
        terminalState = Objects.requireNonNull(terminalState, "terminalState");
        detail = Objects.requireNonNull(detail, "detail")
                .map(value -> requireText(value, "detail"));
        if (terminalState != TerminalState.RUNNING && cancelable) {
            throw new IllegalArgumentException(
                    "a terminal operation cannot remain cancelable");
        }
        if ((operationId == 0L) != (sequence == 0L)
                || operationId < 0L || sequence < 0L) {
            throw new IllegalArgumentException(
                    "publication identity and sequence must be both unassigned or positive");
        }
    }

    public OperationProgressSnapshot(
            Kind kind,
            String phase,
            String status,
            OptionalLong completedUnits,
            OptionalLong totalUnits,
            TerminalState terminalState,
            boolean cancelable,
            Optional<String> detail) {
        this(kind, 0, phase, status, completedUnits, totalUnits,
                OptionalLong.empty(), OptionalLong.empty(), terminalState,
                cancelable, detail, 0L, 0L);
    }

    public static OperationProgressSnapshot indeterminate(
            Kind kind, String phase, String status, boolean cancelable) {
        return indeterminate(kind, 0, phase, status, cancelable);
    }

    public static OperationProgressSnapshot indeterminate(
            Kind kind,
            int phaseOrdinal,
            String phase,
            String status,
            boolean cancelable) {
        return new OperationProgressSnapshot(
                kind, phaseOrdinal, phase, status,
                OptionalLong.empty(), OptionalLong.empty(),
                OptionalLong.empty(), OptionalLong.empty(),
                TerminalState.RUNNING, cancelable, Optional.empty(), 0L, 0L);
    }

    public static OperationProgressSnapshot exactOverall(
            Kind kind,
            int phaseOrdinal,
            String phase,
            String status,
            OptionalLong completedUnits,
            OptionalLong totalUnits,
            long overallCompleted,
            long overallTotal,
            boolean cancelable) {
        return new OperationProgressSnapshot(
                kind, phaseOrdinal, phase, status, completedUnits, totalUnits,
                OptionalLong.of(overallCompleted), OptionalLong.of(overallTotal),
                TerminalState.RUNNING, cancelable, Optional.empty(), 0L, 0L);
    }

    public OptionalDouble fraction() {
        if (overallTotalUnits.isEmpty()) {
            return OptionalDouble.empty();
        }
        return OptionalDouble.of(
                (double) overallCompletedUnits.orElseThrow()
                        / (double) overallTotalUnits.orElseThrow());
    }

    public Optional<String> exactUnitsText() {
        if (totalUnits.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(
                completedUnits.orElseThrow() + " / " + totalUnits.orElseThrow());
    }

    OperationProgressSnapshot published(
            long publishedOperationId, long publishedSequence) {
        return new OperationProgressSnapshot(
                kind, phaseOrdinal, phase, status, completedUnits, totalUnits,
                overallCompletedUnits, overallTotalUnits, terminalState,
                cancelable, detail, publishedOperationId, publishedSequence);
    }

    OperationProgressSnapshot withUpdate(
            OperationProgressUpdate update, long publishedSequence) {
        return new OperationProgressSnapshot(
                kind, update.phaseOrdinal(), update.phase(), update.status(),
                update.completedUnits(), update.totalUnits(),
                overallCompletedUnits, overallTotalUnits, TerminalState.RUNNING,
                update.cancelable(), update.detail(), operationId, publishedSequence);
    }

    OperationProgressSnapshot terminal(
            TerminalState terminal,
            Optional<String> terminalDetail,
            long publishedSequence) {
        return new OperationProgressSnapshot(
                kind, phaseOrdinal, phase, status, completedUnits, totalUnits,
                overallCompletedUnits, overallTotalUnits, terminal, false,
                terminalDetail, operationId, publishedSequence);
    }

    private static void validateUnitPair(
            OptionalLong completed, OptionalLong total, String label) {
        if (completed.isPresent() != total.isPresent()) {
            throw new IllegalArgumentException(
                    label + " completed and total units must be present together");
        }
        if (total.isPresent()) {
            long completedValue = completed.orElseThrow();
            long totalValue = total.orElseThrow();
            if (totalValue <= 0L
                    || completedValue < 0L
                    || completedValue > totalValue) {
                throw new IllegalArgumentException(
                        label + " progress units must remain within their total");
            }
        }
    }

    private static String requireText(String value, String label) {
        String checked = Objects.requireNonNull(value, label).strip();
        if (checked.isEmpty() || checked.length() > MAX_TEXT_LENGTH) {
            throw new IllegalArgumentException(label + " must be short non-blank text");
        }
        return checked;
    }

    public enum Kind {
        STARTUP,
        LOAD_WORLD,
        CREATE_WORLD,
        SAVE_WORLD,
        SAVE_AND_QUIT
    }

    public enum TerminalState {
        RUNNING,
        SUCCESS,
        FAILED,
        CANCELED
    }
}
