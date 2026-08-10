package com.gaia.settings;

import java.util.Objects;

public record SettingsDiagnostic(String code, String field) {
    public SettingsDiagnostic {
        code = Objects.requireNonNull(code, "code");
        field = Objects.requireNonNull(field, "field");
    }
}
