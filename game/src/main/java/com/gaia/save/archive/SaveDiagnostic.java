package com.gaia.save.archive;

import java.util.Objects;
import java.util.Optional;

/** Stable bounded archive diagnostic suitable for UI display and log correlation. */
public final class SaveDiagnostic {
    public static final int MAX_MESSAGE_CODE_POINTS = 280;

    private final String code;
    private final String message;
    private final Throwable cause;

    private SaveDiagnostic(String code, String message, Throwable cause) {
        this.code = requireBounded(code, "code", 96);
        this.message = requireBounded(message, "message", MAX_MESSAGE_CODE_POINTS);
        this.cause = cause;
    }

    public static SaveDiagnostic of(String code, String message) {
        return new SaveDiagnostic(code, message, null);
    }

    public static SaveDiagnostic of(String code, String message, Throwable cause) {
        return new SaveDiagnostic(code, message, Objects.requireNonNull(cause, "cause"));
    }

    public String code() {
        return code;
    }

    public String message() {
        return message;
    }

    /** The operational cause is retained for logs/tests but is not part of the UI message. */
    public Optional<Throwable> cause() {
        return Optional.ofNullable(cause);
    }

    private static String requireBounded(
            String value, String field, int maxCodePoints) {
        Objects.requireNonNull(value, field);
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        if (value.codePointCount(0, value.length()) > maxCodePoints) {
            throw new IllegalArgumentException(field + " exceeds its bounded length");
        }
        return value;
    }
}
