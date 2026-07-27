package com.overlord.core.input;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_SPACE;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_W;
import static org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_LEFT;
import static org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_RIGHT;

import java.util.HashSet;
import java.util.List;
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

    @Test
    void heldOnlySnapshotClearsScrollEdgesToo() {
        InputSnapshot original = new InputSnapshot(
                Set.of(GLFW_KEY_SPACE), Set.of(GLFW_KEY_SPACE), -2);

        assertEquals(-2, original.scrollSteps());
        assertEquals(0, original.heldOnly().scrollSteps());
        assertTrue(original.heldOnly().scrollDeltas().isEmpty());
    }

    @Test
    void scrollSequenceIsImmutableOrderedAndBounded() {
        InputSnapshot snapshot = new InputSnapshot(
                Set.of(), Set.of(), List.of(1, -2, 3));

        assertEquals(List.of(1, -2, 3), snapshot.scrollDeltas());
        assertEquals(2, snapshot.scrollSteps());
        assertThrows(UnsupportedOperationException.class,
                () -> snapshot.scrollDeltas().add(1));
        assertThrows(IllegalArgumentException.class,
                () -> new InputSnapshot(
                        Set.of(), Set.of(),
                        List.of(InputSnapshot.MAX_SCROLL_STEPS_PER_SAMPLE, 1)));
    }

    @Test
    void heldOnlyRetainsMouseHoldsAndClearsMousePressEdges() {
        InputSnapshot snapshot = new InputSnapshot(
                Set.of(),
                Set.of(),
                Set.of(GLFW_MOUSE_BUTTON_LEFT),
                Set.of(GLFW_MOUSE_BUTTON_LEFT, GLFW_MOUSE_BUTTON_RIGHT),
                List.of());

        InputSnapshot heldOnly = snapshot.heldOnly();

        assertTrue(heldOnly.isMouseButtonDown(GLFW_MOUSE_BUTTON_LEFT));
        assertFalse(heldOnly.isMouseButtonPressed(GLFW_MOUSE_BUTTON_LEFT));
        assertFalse(heldOnly.isMouseButtonPressed(GLFW_MOUSE_BUTTON_RIGHT));
        assertThrows(UnsupportedOperationException.class,
                () -> snapshot.downMouseButtons().add(GLFW_MOUSE_BUTTON_RIGHT));
        assertThrows(UnsupportedOperationException.class,
                () -> snapshot.pressedMouseButtons().clear());
    }
}
