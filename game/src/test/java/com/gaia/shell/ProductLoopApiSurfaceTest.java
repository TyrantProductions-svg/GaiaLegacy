package com.gaia.shell;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gaia.audio.MusicManager;
import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class ProductLoopApiSurfaceTest {
    @Test
    void everyPublicProductionConstructorRequiresExplicitMusicManagerOwnership() {
        Constructor<?>[] publicConstructors = ProductLoop.class.getConstructors();

        assertEquals(1, publicConstructors.length);
        assertTrue(hasParameter(publicConstructors[0], MusicManager.class));
        assertTrue(hasParameter(publicConstructors[0], Runnable.class));
        assertFalse(
                Arrays.stream(ProductLoop.class.getDeclaredConstructors())
                        .filter(constructor -> !hasParameter(constructor, MusicManager.class))
                        .anyMatch(constructor -> Modifier.isPublic(constructor.getModifiers())),
                "audio-disabled compatibility constructors must not be public production API");
    }

    private static boolean hasParameter(Constructor<?> constructor, Class<?> type) {
        return Arrays.asList(constructor.getParameterTypes()).contains(type);
    }
}
