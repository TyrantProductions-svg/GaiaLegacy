package com.overlord.assets;

import java.util.Objects;

public record AssetDiagnostic(
        AssetSeverity severity,
        String code,
        String source,
        ResourceLocation resource,
        String field,
        String message,
        ResourceLocation fallback) {
    public AssetDiagnostic {
        Objects.requireNonNull(severity, "severity");
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(message, "message");
    }
}
