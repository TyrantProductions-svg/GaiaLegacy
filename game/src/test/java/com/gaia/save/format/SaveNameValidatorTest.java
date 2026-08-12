package com.gaia.save.format;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class SaveNameValidatorTest {
    @Test
    void trimsAndAcceptsOneToFortyUnicodeCodePoints() {
        SaveNameValidator.ValidationResult result = SaveNameValidator.validate("  世界  ", List.of());
        SaveNameValidator.ValidationResult maximum = SaveNameValidator.validate("😀".repeat(40), List.of());

        assertTrue(result.valid());
        assertEquals("世界", result.displayName());
        assertTrue(maximum.valid());
    }

    @Test
    void rejectsEmptyOverlongAndUnsafeNames() {
        assertFalse(SaveNameValidator.validate("   ", List.of()).valid());
        assertFalse(SaveNameValidator.validate("😀".repeat(41), List.of()).valid());
        assertFalse(SaveNameValidator.validate("bad/name", List.of()).valid());
        assertFalse(SaveNameValidator.validate("bad\\name", List.of()).valid());
        assertFalse(SaveNameValidator.validate("bad\u0000name", List.of()).valid());
    }

    @Test
    void usesTheStableJdkV1ComparisonKeyForSharpSAndDotlessI() {
        SaveNameValidator.ValidationResult sharpS = SaveNameValidator.validate("  strasse ", List.of("Straße"));
        SaveNameValidator.ValidationResult dotlessI = SaveNameValidator.validate("I", List.of("ı"));
        SaveNameValidator.ValidationResult dottedCapitalI = SaveNameValidator.validate("i", List.of("İ"));

        assertFalse(sharpS.valid());
        assertEquals(SaveNameValidator.Diagnostic.DUPLICATE_NAME, sharpS.diagnostic());
        assertFalse(dotlessI.valid());
        assertEquals(SaveNameValidator.Diagnostic.DUPLICATE_NAME, dotlessI.diagnostic());
        assertTrue(dottedCapitalI.valid());
    }
}
