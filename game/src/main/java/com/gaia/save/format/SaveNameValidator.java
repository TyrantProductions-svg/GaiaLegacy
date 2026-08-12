package com.gaia.save.format;

import java.text.Normalizer;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** Validates display names without allowing them to become filesystem paths. */
public final class SaveNameValidator {
    private static final int MAX_CODE_POINTS = 40;
    /** Stable Phase 14 v1 comparison key: NFC, then JDK ROOT uppercase and lowercase. */
    public static final String V1_COMPARISON_KEY_ALGORITHM =
            "NFC then Locale.ROOT uppercase then Locale.ROOT lowercase";

    private SaveNameValidator() {}

    public static ValidationResult validate(String candidate) {
        return validate(candidate, List.of());
    }

    public static ValidationResult validate(String candidate, Collection<String> existingNames) {
        Objects.requireNonNull(existingNames, "existingNames");
        if (candidate == null) {
            return invalid(Diagnostic.MISSING_NAME);
        }
        String displayName = candidate.strip();
        int codePoints = displayName.codePointCount(0, displayName.length());
        if (codePoints == 0) {
            return invalid(Diagnostic.EMPTY_NAME);
        }
        if (codePoints > MAX_CODE_POINTS) {
            return invalid(Diagnostic.TOO_LONG);
        }
        if (displayName.indexOf('/') >= 0 || displayName.indexOf('\\') >= 0) {
            return invalid(Diagnostic.PATH_SEPARATOR);
        }
        if (displayName.codePoints().anyMatch(codePoint -> Character.getType(codePoint) == Character.CONTROL)) {
            return invalid(Diagnostic.CONTROL_CHARACTER);
        }
        String foldedCandidate = caseFold(displayName);
        for (String existingName : existingNames) {
            if (existingName != null && foldedCandidate.equals(caseFold(existingName.strip()))) {
                return invalid(Diagnostic.DUPLICATE_NAME);
            }
        }
        return new ValidationResult(true, displayName, null);
    }

    private static ValidationResult invalid(Diagnostic diagnostic) {
        return new ValidationResult(false, null, diagnostic);
    }

    /* Stable JDK v1 comparison key; this is deliberately not Unicode CaseFolding. */
    private static String caseFold(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFC)
                .toUpperCase(Locale.ROOT)
                .toLowerCase(Locale.ROOT);
    }

    public enum Diagnostic {
        MISSING_NAME,
        EMPTY_NAME,
        TOO_LONG,
        PATH_SEPARATOR,
        CONTROL_CHARACTER,
        DUPLICATE_NAME
    }

    public record ValidationResult(boolean valid, String displayName, Diagnostic diagnostic) {
        public ValidationResult {
            if (valid && (displayName == null || diagnostic != null)) {
                throw new IllegalArgumentException("A valid name result requires only a display name");
            }
            if (!valid && (displayName != null || diagnostic == null)) {
                throw new IllegalArgumentException("An invalid name result requires only a diagnostic");
            }
        }
    }
}
