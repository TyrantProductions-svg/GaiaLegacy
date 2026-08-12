package com.gaia.shell.world;

import com.gaia.save.format.SaveGameId;
import com.gaia.session.NewWorldRequest;
import com.gaia.shell.save.SaveCatalog;
import com.gaia.shell.save.SaveSummary;
import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/** Owner-thread editor and validator for the New World product form. */
public final class NewWorldDraftController {
    private static final String DEFAULT_NAME = "New World";
    private static final String DEFAULT_SEED = "12345";
    private static final int MAX_NAME_CODE_POINTS = 40;

    private final SaveCatalog catalog;
    private String name;
    private String seedText;
    private NewWorldDraftSnapshot.Field focusedField;
    private NewWorldDraftSnapshot.Diagnostic diagnostic;

    public NewWorldDraftController(SaveCatalog catalog) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        reset();
    }

    public NewWorldDraftSnapshot snapshot() {
        return new NewWorldDraftSnapshot(
                name, seedText, focusedField, Optional.ofNullable(diagnostic));
    }

    public void selectField(NewWorldDraftSnapshot.Field field) {
        focusedField = Objects.requireNonNull(field, "field");
        diagnostic = null;
    }

    public void acceptCodePoints(List<Integer> codePoints) {
        Objects.requireNonNull(codePoints, "codePoints");
        StringBuilder accepted = new StringBuilder();
        for (int codePoint : codePoints) {
            if (!isUnicodeScalarValue(codePoint)) {
                throw new IllegalArgumentException("text input must contain Unicode scalar values");
            }
            accepted.appendCodePoint(codePoint);
        }
        if (focusedField == NewWorldDraftSnapshot.Field.NAME) {
            name += accepted;
        } else {
            seedText += accepted;
        }
        diagnostic = null;
    }

    public void backspace() {
        if (focusedField == NewWorldDraftSnapshot.Field.NAME) {
            name = removeLastCodePoint(name);
        } else {
            seedText = removeLastCodePoint(seedText);
        }
        diagnostic = null;
    }

    public Optional<NewWorldRequest> createRequest(Supplier<SaveGameId> ids) {
        Objects.requireNonNull(ids, "ids");
        String validatedName = name.strip();
        if (!validName(validatedName)) {
            diagnostic = NewWorldDraftSnapshot.Diagnostic.INVALID_NAME;
            return Optional.empty();
        }
        String foldedName = fold(validatedName);
        if (catalog.summaries().stream()
                .map(SaveSummary::name)
                .map(NewWorldDraftController::fold)
                .anyMatch(foldedName::equals)) {
            diagnostic = NewWorldDraftSnapshot.Diagnostic.DUPLICATE_NAME;
            return Optional.empty();
        }
        final long seed;
        try {
            seed = Long.parseLong(seedText);
        } catch (NumberFormatException invalidSeed) {
            diagnostic = NewWorldDraftSnapshot.Diagnostic.INVALID_SEED;
            return Optional.empty();
        }
        diagnostic = null;
        return Optional.of(new NewWorldRequest(
                Objects.requireNonNull(ids.get(), "saveGameId"), validatedName, seed));
    }

    public void reset() {
        name = DEFAULT_NAME;
        seedText = DEFAULT_SEED;
        focusedField = NewWorldDraftSnapshot.Field.NAME;
        diagnostic = null;
    }

    private static boolean validName(String candidate) {
        int count = candidate.codePointCount(0, candidate.length());
        if (count < 1 || count > MAX_NAME_CODE_POINTS) {
            return false;
        }
        return candidate.codePoints().noneMatch(codePoint ->
                Character.isISOControl(codePoint) || codePoint == '/' || codePoint == '\\');
    }

    private static String fold(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFKC)
                .toUpperCase(Locale.ROOT)
                .toLowerCase(Locale.ROOT);
    }

    private static String removeLastCodePoint(String value) {
        if (value.isEmpty()) {
            return value;
        }
        return value.substring(0, value.offsetByCodePoints(value.length(), -1));
    }

    private static boolean isUnicodeScalarValue(int codePoint) {
        return Character.isValidCodePoint(codePoint)
                && (codePoint < Character.MIN_SURROGATE
                        || codePoint > Character.MAX_SURROGATE);
    }
}
