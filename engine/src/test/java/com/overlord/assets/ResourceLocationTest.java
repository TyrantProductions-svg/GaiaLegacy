package com.overlord.assets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ResourceLocationTest {
    @Test
    void parsesAndMapsACompleteIdentifier() {
        ResourceLocation location =
                ResourceLocation.parse("gaia:textures/atlas.png");

        assertEquals("gaia", location.namespace());
        assertEquals("textures/atlas.png", location.path());
        assertEquals(
                "assets/gaia/textures/atlas.png",
                location.toClasspathPath());
        assertEquals("gaia:textures/atlas.png", location.toString());
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "grass",
                ":grass",
                "gaia:",
                "Gaia:grass",
                "gaia:Grass",
                "gaia:/grass",
                "gaia:grass/",
                "gaia:blocks//grass",
                "gaia:blocks/../grass",
                "gaia:blocks/./grass",
                "gaia:\\blocks\\grass",
                "gaia:C:/grass"
            })
    void rejectsIncompleteOrUnsafeIdentifiers(String text) {
        assertThrows(
                IllegalArgumentException.class,
                () -> ResourceLocation.parse(text));
    }
}
