package com.overlord.core.input;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_W;
import static org.lwjgl.glfw.GLFW.GLFW_PRESS;
import static org.lwjgl.glfw.GLFW.GLFW_RELEASE;

import org.junit.jupiter.api.Test;

class InputManagerTest {
    @Test
    void accumulatesMouseMovementAfterInitialBaseline() {
        InputManager manager = new InputManager();

        manager.onCursorPosition(100.0, 100.0);
        assertEquals(MouseDelta.ZERO, manager.consumeMouseDelta());

        manager.onCursorPosition(108.0, 94.0);
        assertEquals(new MouseDelta(8.0, 6.0), manager.consumeMouseDelta());
        assertEquals(MouseDelta.ZERO, manager.consumeMouseDelta());
    }

    @Test
    void focusChangeResetsMouseBaseline() {
        InputManager manager = new InputManager();
        manager.onCursorPosition(100.0, 100.0);
        manager.onCursorPosition(110.0, 90.0);
        assertEquals(new MouseDelta(10.0, 10.0), manager.consumeMouseDelta());

        manager.onWindowFocus(false);
        manager.onWindowFocus(true);
        manager.onCursorPosition(400.0, 300.0);

        assertEquals(MouseDelta.ZERO, manager.consumeMouseDelta());
    }

    @Test
    void losingFocusClearsHeldAndPressedKeys() {
        InputManager manager = new InputManager();
        manager.onKey(GLFW_KEY_W, GLFW_PRESS);

        manager.onWindowFocus(false);
        InputSnapshot snapshot = manager.consumeFixedInput();

        assertFalse(snapshot.isKeyDown(GLFW_KEY_W));
        assertFalse(snapshot.isKeyPressed(GLFW_KEY_W));
    }

    @Test
    void keyPressEdgeRemainsLatchedUntilFixedUpdateConsumesIt() {
        InputManager manager = new InputManager();
        manager.onKey(GLFW_KEY_W, GLFW_PRESS);
        manager.onKey(GLFW_KEY_W, GLFW_RELEASE);

        assertTrue(manager.isKeyPressed(GLFW_KEY_W));
        InputSnapshot first = manager.consumeFixedInput();
        InputSnapshot second = manager.consumeFixedInput();

        assertFalse(first.isKeyDown(GLFW_KEY_W));
        assertTrue(first.isKeyPressed(GLFW_KEY_W));
        assertFalse(second.isKeyDown(GLFW_KEY_W));
        assertFalse(second.isKeyPressed(GLFW_KEY_W));
    }

    @Test
    void consumesOneShortcutEdgeWithoutClearingOtherPressedKeys() {
        InputManager manager = new InputManager();
        manager.onKey(GLFW_KEY_W, GLFW_PRESS);
        manager.onKey(org.lwjgl.glfw.GLFW.GLFW_KEY_F1, GLFW_PRESS);

        assertTrue(manager.consumeKeyPress(org.lwjgl.glfw.GLFW.GLFW_KEY_F1));
        assertFalse(manager.consumeKeyPress(org.lwjgl.glfw.GLFW.GLFW_KEY_F1));
        assertTrue(manager.consumeFixedInput().isKeyPressed(GLFW_KEY_W));
    }
}
