package com.gaia.interaction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.overlord.assets.ResourceLocation;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class CreativeSelectionTest {
    private static final ResourceLocation DIRT = ResourceLocation.parse("gaia:dirt");
    private static final ResourceLocation STONE = ResourceLocation.parse("gaia:stone");

    @Test
    void selectionUsesRegistryBackedBlockItemAndRejectsUnknownIdentity() {
        CreativeSelection selection = new CreativeSelection(
                item -> item.equals(DIRT) || item.equals(STONE),
                Optional.of(DIRT));

        assertTrue(selection.select(STONE));
        assertFalse(selection.select(ResourceLocation.parse("gaia:not_an_item")));

        assertEquals(STONE, selection.selected().orElseThrow().itemId());
        assertEquals(1, selection.selected().orElseThrow().count());
    }

    @Test
    void clearDoesNotMutateAnyInventoryState() {
        CreativeSelection selection = new CreativeSelection(
                DIRT::equals, Optional.of(DIRT));

        selection.clear();

        assertTrue(selection.selected().isEmpty());
    }
}
