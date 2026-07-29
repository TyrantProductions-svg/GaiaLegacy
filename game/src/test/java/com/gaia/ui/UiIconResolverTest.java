package com.gaia.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.overlord.assets.ResourceLocation;
import com.overlord.renderer.ui.UiUvRect;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class UiIconResolverTest {
    @Test
    void resolvesCanonicalDefinitionsWithoutDiagnostics() {
        List<ResourceLocation> diagnostics = new ArrayList<>();
        UiIconAtlas atlas = atlas();
        UiIconResolver resolver = new UiIconResolver(atlas, diagnostics::add);

        UiIconDefinition resolved = resolver.resolve(ResourceLocation.parse("gaia:grass"));

        assertSame(atlas.icons().get(ResourceLocation.parse("gaia:grass")), resolved);
        assertEquals(List.of(), diagnostics);
    }

    @Test
    void unknownIdentityUsesExplicitMissingAndDiagnosesItOnce() {
        List<ResourceLocation> diagnostics = new ArrayList<>();
        UiIconAtlas atlas = atlas();
        UiIconResolver resolver = new UiIconResolver(atlas, diagnostics::add);
        ResourceLocation unknown = ResourceLocation.parse("gaia:not_registered");

        assertSame(atlas.fallback(), resolver.resolve(unknown));
        assertSame(atlas.fallback(), resolver.resolve(unknown));
        assertEquals(List.of(unknown), diagnostics);
    }

    @Test
    void differentUnknownIdentitiesAreDiagnosedIndependently() {
        List<ResourceLocation> diagnostics = new ArrayList<>();
        UiIconResolver resolver = new UiIconResolver(atlas(), diagnostics::add);
        ResourceLocation first = ResourceLocation.parse("gaia:first_unknown");
        ResourceLocation second = ResourceLocation.parse("gaia:second_unknown");

        resolver.resolve(first);
        resolver.resolve(second);
        resolver.resolve(first);

        assertEquals(List.of(first, second), diagnostics);
    }

    @Test
    void atlasDefensivelyCopiesCallerMetadata() {
        ResourceLocation grass = ResourceLocation.parse("gaia:grass");
        UiIconDefinition grassIcon = new UiIconDefinition(
                grass, "Grass", new UiUvRect(0.0f, 0.0f, 0.25f, 0.5f));
        UiIconDefinition fallback = new UiIconDefinition(
                ResourceLocation.parse("gaia:missing"), "Missing",
                new UiUvRect(0.25f, 0.5f, 0.5f, 1.0f));
        Map<ResourceLocation, UiIconDefinition> source = new LinkedHashMap<>();
        source.put(grass, grassIcon);
        source.put(fallback.itemId(), fallback);
        List<Integer> unassigned = new ArrayList<>(List.of(6, 7));

        UiIconAtlas atlas = new UiIconAtlas(
                ResourceLocation.parse("gaia:ui/ui_icons.png"), 128, 64,
                source, fallback, unassigned);
        source.clear();
        unassigned.clear();

        assertSame(grassIcon, atlas.icons().get(grass));
        assertEquals(List.of(6, 7), atlas.unassignedCells());
        assertThrows(UnsupportedOperationException.class,
                () -> atlas.icons().clear());
        assertThrows(UnsupportedOperationException.class,
                () -> atlas.unassignedCells().add(5));
    }

    private static UiIconAtlas atlas() {
        ResourceLocation grass = ResourceLocation.parse("gaia:grass");
        UiIconDefinition grassIcon = new UiIconDefinition(
                grass, "Grass", new UiUvRect(0.0f, 0.0f, 0.25f, 0.5f));
        UiIconDefinition fallback = new UiIconDefinition(
                ResourceLocation.parse("gaia:missing"), "Missing",
                new UiUvRect(0.25f, 0.5f, 0.5f, 1.0f));
        return new UiIconAtlas(
                ResourceLocation.parse("gaia:ui/ui_icons.png"), 128, 64,
                Map.of(grass, grassIcon, fallback.itemId(), fallback),
                fallback, List.of(6, 7));
    }
}
