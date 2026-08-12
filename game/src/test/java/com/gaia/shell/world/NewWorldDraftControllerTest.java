package com.gaia.shell.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.gaia.save.format.SaveGameId;
import com.gaia.session.NewWorldRequest;
import com.gaia.shell.save.SaveCatalog;
import com.gaia.shell.save.SaveSummary;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class NewWorldDraftControllerTest {
    private static final SaveGameId NEW_ID = SaveGameId.parse(
            "00000000-0000-0000-0000-000000000014");

    @Test
    void defaultsEditUnicodeByCodePointAndBackspaceNeverSplitsSupplementaryText() {
        NewWorldDraftController controller = controller(List.of());

        assertEquals("New World", controller.snapshot().name());
        assertEquals("12345", controller.snapshot().seedText());
        assertEquals(NewWorldDraftSnapshot.Field.NAME, controller.snapshot().focusedField());

        replaceFocused(controller, "Gaia 🌍");
        controller.backspace();
        assertEquals("Gaia ", controller.snapshot().name());

        controller.selectField(NewWorldDraftSnapshot.Field.SEED);
        replaceFocused(controller, "-42");
        assertEquals("-42", controller.snapshot().seedText());
        assertEquals(NewWorldDraftSnapshot.Field.SEED, controller.snapshot().focusedField());
    }

    @Test
    void acceptsOneAndFortyCodePointsButRejectsEmptyFortyOneControlAndPathNames() {
        assertValidName("A");
        assertValidName("x".repeat(40));
        assertInvalidName("");
        assertInvalidName("x".repeat(41));
        assertInvalidName("bad\nname");
        assertInvalidName("bad/name");
        assertInvalidName("bad\\name");
    }

    @Test
    void trimsTheDisplayNameAndRejectsUnicodeCaseFoldedCatalogDuplicates() {
        SaveSummary existing = summary("Straße", "00000000-0000-0000-0000-000000000001");
        NewWorldDraftController duplicate = controller(List.of(existing));
        replaceFocused(duplicate, "  STRASSE  ");

        assertTrue(duplicate.createRequest(() -> NEW_ID).isEmpty());
        assertEquals(
                Optional.of(NewWorldDraftSnapshot.Diagnostic.DUPLICATE_NAME),
                duplicate.snapshot().diagnostic());

        NewWorldDraftController trimmed = controller(List.of());
        replaceFocused(trimmed, "  Gaia 世界  ");
        NewWorldRequest request = trimmed.createRequest(() -> NEW_ID).orElseThrow();
        assertEquals("Gaia 世界", request.displayName());
    }

    @Test
    void parsesTheCompleteSignedLongDomainAndRejectsOverflowWithoutAllocatingAnId() {
        assertValidSeed(Long.toString(Long.MIN_VALUE), Long.MIN_VALUE);
        assertValidSeed(Long.toString(Long.MAX_VALUE), Long.MAX_VALUE);

        NewWorldDraftController overflow = controller(List.of());
        overflow.selectField(NewWorldDraftSnapshot.Field.SEED);
        replaceFocused(overflow, "9223372036854775808");
        AtomicInteger ids = new AtomicInteger();

        assertTrue(overflow.createRequest(() -> {
            ids.incrementAndGet();
            return NEW_ID;
        }).isEmpty());
        assertEquals(0, ids.get());
        assertEquals(
                Optional.of(NewWorldDraftSnapshot.Diagnostic.INVALID_SEED),
                overflow.snapshot().diagnostic());
    }

    @Test
    void resetClearsStaleTextFocusAndDiagnosticsBeforeTheScreenIsReentered() {
        NewWorldDraftController controller = controller(List.of());
        replaceFocused(controller, "");
        assertTrue(controller.createRequest(() -> NEW_ID).isEmpty());
        controller.selectField(NewWorldDraftSnapshot.Field.SEED);

        controller.reset();

        assertEquals("New World", controller.snapshot().name());
        assertEquals("12345", controller.snapshot().seedText());
        assertEquals(NewWorldDraftSnapshot.Field.NAME, controller.snapshot().focusedField());
        assertTrue(controller.snapshot().diagnostic().isEmpty());
    }

    @Test
    void publicRequestCannotBypassTheValidatedWorldNameShape() {
        assertThrows(IllegalArgumentException.class,
                () -> new NewWorldRequest(NEW_ID, "bad/name", 1L));
        assertThrows(IllegalArgumentException.class,
                () -> new NewWorldRequest(NEW_ID, "x".repeat(41), 1L));
        assertThrows(IllegalArgumentException.class,
                () -> new NewWorldRequest(NEW_ID, "bad\nname", 1L));
    }

    private static void assertValidName(String name) {
        NewWorldDraftController controller = controller(List.of());
        replaceFocused(controller, name);
        assertEquals(name, controller.createRequest(() -> NEW_ID).orElseThrow().displayName());
    }

    private static void assertInvalidName(String name) {
        NewWorldDraftController controller = controller(List.of());
        replaceFocused(controller, name);
        assertTrue(controller.createRequest(() -> NEW_ID).isEmpty());
        assertEquals(
                Optional.of(NewWorldDraftSnapshot.Diagnostic.INVALID_NAME),
                controller.snapshot().diagnostic());
    }

    private static void assertValidSeed(String text, long expected) {
        NewWorldDraftController controller = controller(List.of());
        controller.selectField(NewWorldDraftSnapshot.Field.SEED);
        replaceFocused(controller, text);
        assertEquals(expected, controller.createRequest(() -> NEW_ID).orElseThrow().seed());
    }

    private static NewWorldDraftController controller(List<SaveSummary> saves) {
        SaveCatalog catalog = () -> saves;
        return new NewWorldDraftController(catalog);
    }

    private static void replaceFocused(NewWorldDraftController controller, String value) {
        String current = controller.snapshot().focusedField() == NewWorldDraftSnapshot.Field.NAME
                ? controller.snapshot().name()
                : controller.snapshot().seedText();
        current.codePoints().forEach(ignored -> controller.backspace());
        controller.acceptCodePoints(value.codePoints().boxed().toList());
    }

    private static SaveSummary summary(String name, String id) {
        return new SaveSummary(
                SaveGameId.parse(id),
                name,
                Optional.empty(),
                Instant.parse("2026-08-12T00:00:00Z"),
                Optional.of(12345L),
                Optional.empty(),
                SaveSummary.Health.VALID,
                List.of());
    }
}
