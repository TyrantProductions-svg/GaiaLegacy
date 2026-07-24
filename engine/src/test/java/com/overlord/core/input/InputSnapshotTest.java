package com.overlord.core.input;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_SPACE;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_W;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class InputSnapshotTest {
    @Test
    void heldOnlySnapshotCopiesHeldKeysAndClearsPressEdges() {
        Set<Integer> sourceKeysDown =
                new HashSet<>(Set.of(GLFW_KEY_SPACE));
        InputSnapshot original =
                new InputSnapshot(
                        sourceKeysDown, Set.of(GLFW_KEY_SPACE));

        InputSnapshot heldOnly = original.heldOnly();
        sourceKeysDown.add(GLFW_KEY_W);

        assertTrue(heldOnly.isKeyDown(GLFW_KEY_SPACE));
        assertFalse(heldOnly.isKeyDown(GLFW_KEY_W));
        assertFalse(heldOnly.isKeyPressed(GLFW_KEY_SPACE));
        assertThrows(
                UnsupportedOperationException.class,
                () -> heldOnly.downKeys().add(GLFW_KEY_W));
        assertThrows(
                UnsupportedOperationException.class,
                () -> heldOnly.pressedKeys().add(GLFW_KEY_SPACE));
    }
}
