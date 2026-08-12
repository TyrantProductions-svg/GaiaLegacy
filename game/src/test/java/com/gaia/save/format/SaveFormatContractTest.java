package com.gaia.save.format;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class SaveFormatContractTest {
    private static final String SHA = "a".repeat(64);
    private static final SaveGameId SAVE_ID = SaveGameId.parse("123e4567-e89b-12d3-a456-426614174000");
    private static final Instant CREATED = Instant.parse("2026-08-10T12:00:00Z");

    @Test
    void exposesTheExactV1SectionVocabulary() {
        assertEquals("chunks", SaveSectionId.CHUNKS.value());
        assertEquals("player", SaveSectionId.PLAYER.value());
        assertEquals("inventory", SaveSectionId.INVENTORY.value());
        assertEquals("world-items", SaveSectionId.WORLD_ITEMS.value());
        assertEquals("discovery-lore", SaveSectionId.DISCOVERY_LORE.value());
        assertEquals("detail-blocks", SaveSectionId.DETAIL_BLOCKS.value());
        assertEquals(1, SaveFormatVersion.CURRENT.value());
    }

    @Test
    void saveGameIdAcceptsOnlyCanonicalLowercaseUuidText() {
        assertEquals("123e4567-e89b-12d3-a456-426614174000", SAVE_ID.value());
        assertThrows(IllegalArgumentException.class,
                () -> SaveGameId.parse("123E4567-E89B-12D3-A456-426614174000"));
        assertThrows(IllegalArgumentException.class,
                () -> SaveGameId.parse("not-a-uuid"));
    }

    @Test
    void sectionDescriptorRequiresNonnegativeSizeAndLowercaseSha256() {
        assertEquals(123L, descriptor(SaveSectionId.CHUNKS, 1, true, 123L).uncompressedSize());
        assertThrows(IllegalArgumentException.class,
                () -> descriptor(SaveSectionId.CHUNKS, 1, true, -1L));
        assertThrows(IllegalArgumentException.class,
                () -> new SaveSectionDescriptor(SaveSectionId.CHUNKS, 1, true, 0L, "A".repeat(64)));
        assertThrows(IllegalArgumentException.class,
                () -> new SaveSectionDescriptor(SaveSectionId.CHUNKS, 1, true, 0L, "a".repeat(63)));
    }

    @Test
    void sectionDescriptorRejectsContradictoryRequirednessForEveryKnownV1Section() {
        assertAll(
                () -> assertThrows(IllegalArgumentException.class,
                        () -> descriptor(SaveSectionId.CHUNKS, 1, false, 0L)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> descriptor(SaveSectionId.PLAYER, 1, false, 0L)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> descriptor(SaveSectionId.INVENTORY, 1, false, 0L)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> descriptor(SaveSectionId.WORLD_ITEMS, 1, false, 0L)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> descriptor(SaveSectionId.DISCOVERY_LORE, 1, true, 0L)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> descriptor(SaveSectionId.DETAIL_BLOCKS, 1, true, 0L)));
    }

    @Test
    void manifestCopiesDescriptorsAndRequiresEachV1SectionExactlyOnce() {
        SaveGameManifest manifest = manifest(requiredDescriptors());

        assertEquals(List.copyOf(requiredDescriptors()), manifest.sections());
        assertThrows(UnsupportedOperationException.class, () -> manifest.sections().clear());
        assertThrows(IllegalArgumentException.class,
                () -> manifest(List.of(
                        descriptor(SaveSectionId.CHUNKS, 1, true, 1L),
                        descriptor(SaveSectionId.PLAYER, 1, true, 1L),
                        descriptor(SaveSectionId.INVENTORY, 1, true, 1L))));
    }

    @Test
    void manifestRejectsDuplicateRequiredSectionIds() {
        SaveSectionDescriptor chunks = descriptor(SaveSectionId.CHUNKS, 1, true, 1L);
        assertThrows(IllegalArgumentException.class,
                () -> manifest(List.of(chunks, chunks, descriptor(SaveSectionId.PLAYER, 1, true, 1L),
                        descriptor(SaveSectionId.INVENTORY, 1, true, 1L),
                        descriptor(SaveSectionId.WORLD_ITEMS, 1, true, 1L))));
    }

    @Test
    void manifestRejectsMissingOrReverseUtcInstants() {
        assertThrows(IllegalArgumentException.class,
                () -> manifest(null, CREATED, requiredDescriptors()));
        assertThrows(IllegalArgumentException.class,
                () -> manifest(CREATED, CREATED.minusSeconds(1), requiredDescriptors()));
    }

    @Test
    void manifestRejectsInvalidBoundsAndAnOversizedSummary() {
        assertThrows(IllegalArgumentException.class,
                () -> manifest(SaveFormatVersion.CURRENT, 0, 256, 20L, null, requiredDescriptors()));
        assertThrows(IllegalArgumentException.class,
                () -> manifest(SaveFormatVersion.CURRENT, 4, 0, 20L, null, requiredDescriptors()));
        assertThrows(IllegalArgumentException.class,
                () -> manifest(SaveFormatVersion.CURRENT, 4, 256, -1L, null, requiredDescriptors()));
        assertThrows(IllegalArgumentException.class,
                () -> manifest(SaveFormatVersion.CURRENT, 4, 256, 20L, "x".repeat(281), requiredDescriptors()));
    }

    @Test
    void manifestAcceptsTheExactV1SummaryCodePointLimit() {
        SaveGameManifest manifest = manifest(
                SaveFormatVersion.CURRENT, 4, 256, 20L, "x".repeat(280), requiredDescriptors());

        assertEquals("x".repeat(280), manifest.summary());
    }

    @Test
    void manifestAllowsOptionalReservedSectionsAfterRequiredV1Sections() {
        List<SaveSectionDescriptor> sections = List.of(
                descriptor(SaveSectionId.CHUNKS, 1, true, 1L),
                descriptor(SaveSectionId.PLAYER, 1, true, 1L),
                descriptor(SaveSectionId.INVENTORY, 1, true, 1L),
                descriptor(SaveSectionId.WORLD_ITEMS, 1, true, 1L),
                descriptor(SaveSectionId.DISCOVERY_LORE, 1, false, 1L));

        assertFalse(manifest(sections).sections().isEmpty());
    }

    @Test
    void manifestRejectsReservedOptionalSectionsMarkedRequired() {
        for (SaveSectionId reservedOptional : List.of(
                SaveSectionId.DISCOVERY_LORE, SaveSectionId.DETAIL_BLOCKS)) {
            assertThrows(IllegalArgumentException.class,
                    () -> manifest(List.of(
                            descriptor(SaveSectionId.CHUNKS, 1, true, 1L),
                            descriptor(SaveSectionId.PLAYER, 1, true, 1L),
                            descriptor(SaveSectionId.INVENTORY, 1, true, 1L),
                            descriptor(SaveSectionId.WORLD_ITEMS, 1, true, 1L),
                            descriptor(reservedOptional, 1, true, 1L))));
        }
    }

    @Test
    void manifestUsesSaveNameValidatorForDirectDisplayNames() {
        assertEquals("World One", manifestWithDisplayName("  World One  ").displayName());
        assertThrows(IllegalArgumentException.class, () -> manifestWithDisplayName("😀".repeat(41)));
        assertThrows(IllegalArgumentException.class, () -> manifestWithDisplayName("bad/name"));
        assertThrows(IllegalArgumentException.class, () -> manifestWithDisplayName("bad\\name"));
        assertThrows(IllegalArgumentException.class, () -> manifestWithDisplayName("bad\u0000name"));
    }

    private static List<SaveSectionDescriptor> requiredDescriptors() {
        return List.of(
                descriptor(SaveSectionId.CHUNKS, 1, true, 1L),
                descriptor(SaveSectionId.PLAYER, 1, true, 1L),
                descriptor(SaveSectionId.INVENTORY, 1, true, 1L),
                descriptor(SaveSectionId.WORLD_ITEMS, 1, true, 1L));
    }

    private static SaveSectionDescriptor descriptor(
            SaveSectionId id, int codecVersion, boolean required, long size) {
        return new SaveSectionDescriptor(id, codecVersion, required, size, SHA);
    }

    private static SaveGameManifest manifest(List<SaveSectionDescriptor> sections) {
        return manifest(CREATED, CREATED.plusSeconds(1), sections);
    }

    private static SaveGameManifest manifest(
            Instant created, Instant modified, List<SaveSectionDescriptor> sections) {
        return new SaveGameManifest(
                SaveFormatVersion.CURRENT, "0.2.0-alpha.1", SAVE_ID, "World One", created, modified,
                12345L, "v1", "b".repeat(64), 4, 256, 20L, "A valid world", sections);
    }

    private static SaveGameManifest manifestWithDisplayName(String displayName) {
        return new SaveGameManifest(
                SaveFormatVersion.CURRENT, "0.2.0-alpha.1", SAVE_ID, displayName,
                CREATED, CREATED.plusSeconds(1), 12345L, "v1", "b".repeat(64),
                4, 256, 20L, "A valid world", requiredDescriptors());
    }

    private static SaveGameManifest manifest(
            SaveFormatVersion version, int chunkRadius, int worldHeight, long fixedTick,
            String summary, List<SaveSectionDescriptor> sections) {
        return new SaveGameManifest(
                version, "0.2.0-alpha.1", SAVE_ID, "World One", CREATED, CREATED.plusSeconds(1),
                12345L, "v1", "b".repeat(64), chunkRadius, worldHeight, fixedTick, summary, sections);
    }
}
