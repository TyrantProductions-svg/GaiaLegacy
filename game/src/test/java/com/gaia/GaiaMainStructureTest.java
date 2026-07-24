package com.gaia;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class GaiaMainStructureTest {
    @Test
    void remainsOnlyAStartupEntryPoint() throws IOException {
        String source =
                Files.readString(Path.of("src/main/java/com/gaia/GaiaMain.java"));

        assertTrue(source.contains("new GameBootstrap().run()"));
        for (String forbidden :
                new String[] {
                    "Engine",
                    "World",
                    "Mesh",
                    "GLFW",
                    "PhysicsManager",
                    "PlayerManager",
                    "CollisionWorld",
                    "PhysicsWorld",
                    "PlayerController",
                    "BlockRaycast"
                }) {
            assertFalse(source.contains(forbidden), "GaiaMain must not reference " + forbidden);
        }
    }
}
