package com.overlord.core.input;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER;
import static org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_LEFT;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class UiInputSnapshotTest {
    @Test
    void snapshotDefensivelyCopiesAllCallbackState() {
        Set<Integer> downKeys = new HashSet<>(Set.of(GLFW_KEY_ENTER));
        Set<Integer> pressedKeys = new HashSet<>(Set.of(GLFW_KEY_ENTER));
        Set<Integer> downButtons = new HashSet<>(Set.of(GLFW_MOUSE_BUTTON_LEFT));
        Set<Integer> pressedButtons = new HashSet<>(Set.of(GLFW_MOUSE_BUTTON_LEFT));
        List<Integer> scroll = new java.util.ArrayList<>(List.of(-2));

        UiInputSnapshot snapshot = new UiInputSnapshot(
                downKeys, pressedKeys, downButtons, pressedButtons, scroll,
                320.0, 180.0, true, 7L);
        downKeys.clear();
        pressedKeys.clear();
        downButtons.clear();
        pressedButtons.clear();
        scroll.clear();

        assertTrue(snapshot.isKeyDown(GLFW_KEY_ENTER));
        assertTrue(snapshot.isKeyPressed(GLFW_KEY_ENTER));
        assertTrue(snapshot.isMouseDown(GLFW_MOUSE_BUTTON_LEFT));
        assertTrue(snapshot.isMousePressed(GLFW_MOUSE_BUTTON_LEFT));
        assertEquals(List.of(-2), snapshot.scrollDeltas());
        assertEquals(320.0, snapshot.pointerX());
        assertEquals(180.0, snapshot.pointerY());
        assertTrue(snapshot.focused());
        assertEquals(7L, snapshot.sampleId());
        assertFalse(snapshot.isKeyDown(999));
        assertThrows(UnsupportedOperationException.class,
                () -> snapshot.downKeys().clear());
        assertThrows(UnsupportedOperationException.class,
                () -> snapshot.scrollDeltas().clear());
    }

    @Test
    void snapshotRejectsNonFinitePointersAndNegativeSampleIds() {
        assertThrows(IllegalArgumentException.class,
                () -> new UiInputSnapshot(
                        Set.of(), Set.of(), Set.of(), Set.of(), List.of(),
                        Double.NaN, 0.0, true, 0L));
        assertThrows(IllegalArgumentException.class,
                () -> new UiInputSnapshot(
                        Set.of(), Set.of(), Set.of(), Set.of(), List.of(),
                        0.0, Double.POSITIVE_INFINITY, false, 0L));
        assertThrows(IllegalArgumentException.class,
                () -> new UiInputSnapshot(
                        Set.of(), Set.of(), Set.of(), Set.of(), List.of(),
                        0.0, 0.0, false, -1L));
    }
}
