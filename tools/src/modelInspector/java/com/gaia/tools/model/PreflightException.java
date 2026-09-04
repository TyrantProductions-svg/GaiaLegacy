package com.gaia.tools.model;

import java.io.IOException;

/** A bounded diagnostic; never includes untrusted source text or URI values. */
public final class PreflightException extends IOException {
    public enum Code {
        FILE_SIZE, CONTAINER, JSON_SIZE, JSON_INVALID, JSON_LIMIT,
        URI_FORBIDDEN, EXTENSION_FORBIDDEN, ASSET_VERSION, DECODE_REJECTED
    }

    private final Code code;

    PreflightException(Code code) {
        super("GLB preflight rejected: " + code);
        this.code = code;
    }

    public Code code() { return code; }
}
