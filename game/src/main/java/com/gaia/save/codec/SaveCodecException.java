package com.gaia.save.codec;

import java.util.Objects;

/** Bounded, stable failure reported by a save-section codec. */
public final class SaveCodecException extends RuntimeException {
    private final String code;

    public SaveCodecException(String code, String message, Throwable cause) {
        super(
                Objects.requireNonNull(message, "message"),
                Objects.requireNonNull(cause, "cause"));
        this.code = requireCode(code);
    }

    public String code() {
        return code;
    }

    private static String requireCode(String code) {
        Objects.requireNonNull(code, "code");
        if (code.isBlank()) {
            throw new IllegalArgumentException("code must not be blank");
        }
        return code;
    }
}
