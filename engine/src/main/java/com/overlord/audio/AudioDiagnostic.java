package com.overlord.audio;

import java.util.Objects;
import java.util.regex.Pattern;

public record AudioDiagnostic(String code, String message) {
    public static final int MAX_MESSAGE_LENGTH = 256;
    private static final Pattern CODE = Pattern.compile("[A-Z][A-Z0-9_]{0,63}");

    public AudioDiagnostic {
        code = Objects.requireNonNull(code, "code");
        message = Objects.requireNonNull(message, "message");
        if (!CODE.matcher(code).matches()) {
            throw new IllegalArgumentException("invalid audio diagnostic code: " + code);
        }
        if (message.isBlank()) {
            throw new IllegalArgumentException("audio diagnostic message must not be blank");
        }
        if (message.length() > MAX_MESSAGE_LENGTH) {
            throw new IllegalArgumentException(
                    "audio diagnostic message exceeds " + MAX_MESSAGE_LENGTH + " characters");
        }
    }

    static AudioDiagnostic backendInitializationFailure(Throwable failure) {
        Objects.requireNonNull(failure, "failure");
        String failureType = failure.getClass().getSimpleName();
        if (failureType.isBlank()) {
            failureType = "Throwable";
        }
        String detail = failure.getMessage();
        String message =
                failureType
                        + (detail == null || detail.isBlank() ? "" : ": " + detail);
        if (message.length() > MAX_MESSAGE_LENGTH) {
            message = message.substring(0, MAX_MESSAGE_LENGTH);
        }
        return new AudioDiagnostic("AUDIO_BACKEND_INIT_FAILED", message);
    }
}
