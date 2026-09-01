package com.gaia.blocks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.overlord.assets.ResourceLocation;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class StandaloneItemDefinitionTest {
    private static final ResourceLocation CHISEL =
            ResourceLocation.parse("gaia:chisel");
    private static final ResourceLocation ATLAS =
            ResourceLocation.parse("gaia:blocks");

    @Test
    void snapshotsCapabilitiesAndKeepsPresentationSeparate() {
        HashSet<ItemCapability> capabilities =
                new HashSet<>(Set.of(ItemCapability.DETAIL_PRECISION));
        ItemVisualReference visual =
                new ItemVisualReference(
                        ItemVisualType.ATLAS_REGION, ATLAS, CHISEL);

        StandaloneItemDefinition definition =
                new StandaloneItemDefinition(
                        new ItemFormDefinition(CHISEL, 1, false, false),
                        capabilities,
                        visual);
        capabilities.clear();

        assertEquals(
                Set.of(ItemCapability.DETAIL_PRECISION),
                definition.capabilities());
        assertEquals(visual, definition.visual());
        assertThrows(
                UnsupportedOperationException.class,
                () -> definition.capabilities().clear());
    }

    @Test
    void rejectsMissingCanonicalParts() {
        ItemFormDefinition form =
                new ItemFormDefinition(CHISEL, 1, false, false);
        ItemVisualReference visual =
                new ItemVisualReference(
                        ItemVisualType.ATLAS_REGION, ATLAS, CHISEL);

        assertThrows(
                NullPointerException.class,
                () -> new StandaloneItemDefinition(null, Set.of(), visual));
        assertThrows(
                NullPointerException.class,
                () -> new StandaloneItemDefinition(form, null, visual));
        assertThrows(
                NullPointerException.class,
                () -> new StandaloneItemDefinition(form, Set.of(), null));
        assertThrows(
                NullPointerException.class,
                () -> new ItemVisualReference(null, ATLAS, CHISEL));
        assertThrows(
                NullPointerException.class,
                () -> new ItemVisualReference(
                        ItemVisualType.ATLAS_REGION, null, CHISEL));
        assertThrows(
                NullPointerException.class,
                () -> new ItemVisualReference(
                        ItemVisualType.ATLAS_REGION, ATLAS, null));
    }
}
