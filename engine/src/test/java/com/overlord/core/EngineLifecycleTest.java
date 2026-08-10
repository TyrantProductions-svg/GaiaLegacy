package com.overlord.core;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.overlord.voxel.World;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class EngineLifecycleTest {
    @Test
    void engineHasNoWorldFieldOrAccessor() {
        assertFalse(
                Arrays.stream(Engine.class.getDeclaredFields())
                        .anyMatch(field -> field.getType() == World.class));
        assertThrows(
                NoSuchMethodException.class,
                () -> Engine.class.getDeclaredMethod("getWorld"));
    }

    @Test
    void engineNeitherConstructsNorRegistersAWorld() throws IOException {
        String source =
                Files.readString(
                        Path.of(
                                "src/main/java/com/overlord/core/Engine.java"));

        assertFalse(source.contains("new World"));
        assertFalse(source.contains("World world"));
        assertFalse(source.contains("services.register(World.class"));
    }
}
