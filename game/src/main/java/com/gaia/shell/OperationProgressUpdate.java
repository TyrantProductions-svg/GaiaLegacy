package com.gaia.shell;

import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

/** Identity-free phase facts reported to the sole progress publisher. */
public record OperationProgressUpdate(
        int phaseOrdinal,
        String phase,
        String status,
        OptionalLong completedUnits,
        OptionalLong totalUnits,
        boolean cancelable,
        Optional<String> detail) {
    private static final int MAX_TEXT_LENGTH = 240;

    public OperationProgressUpdate {
        if (phaseOrdinal < 0) {
            throw new IllegalArgumentException("phaseOrdinal must be non-negative");
        }
        phase = requireText(phase, "phase");
        status = requireText(status, "status");
        completedUnits = Objects.requireNonNull(completedUnits, "completedUnits");
        totalUnits = Objects.requireNonNull(totalUnits, "totalUnits");
        detail = Objects.requireNonNull(detail, "detail")
                .map(value -> requireText(value, "detail"));
        if (completedUnits.isPresent() != totalUnits.isPresent()) {
            throw new IllegalArgumentException(
                    "completed and total units must be present together");
        }
        if (totalUnits.isPresent()) {
            long completed = completedUnits.orElseThrow();
            long total = totalUnits.orElseThrow();
            if (total <= 0L || completed < 0L || completed > total) {
                throw new IllegalArgumentException(
                        "exact progress units must remain within their total");
            }
        }
    }

    public static OperationProgressUpdate indeterminate(
            int phaseOrdinal,
            String phase,
            String status,
            boolean cancelable) {
        return new OperationProgressUpdate(
                phaseOrdinal, phase, status,
                OptionalLong.empty(), OptionalLong.empty(), cancelable,
                Optional.empty());
    }

    private static String requireText(String value, String label) {
        String checked = Objects.requireNonNull(value, label).strip();
        if (checked.isEmpty() || checked.length() > MAX_TEXT_LENGTH) {
            throw new IllegalArgumentException(label + " must be short non-blank text");
        }
        return checked;
    }
}
