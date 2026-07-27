package com.overlord.core.input;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_W;
import static org.lwjgl.glfw.GLFW.GLFW_PRESS;
import static org.lwjgl.glfw.GLFW.GLFW_RELEASE;
import static org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_LEFT;
import static org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_RIGHT;

import java.util.List;
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
        manager.onScroll(0.0, 3.0);

        manager.onWindowFocus(false);
        InputSnapshot snapshot = manager.consumeFixedInput();

        assertFalse(snapshot.isKeyDown(GLFW_KEY_W));
        assertFalse(snapshot.isKeyPressed(GLFW_KEY_W));
        assertEquals(0, snapshot.scrollSteps());
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

    @Test
    void scrollIsLatchedForOneFixedInputSampleThenCleared() {
        InputManager manager = new InputManager();

        manager.onScroll(0.0, 1.0);
        manager.onScroll(0.0, 1.0);
        manager.onScroll(0.0, -1.0);

        assertEquals(1, manager.consumeFixedInput().scrollSteps());
        assertEquals(0, manager.consumeFixedInput().scrollSteps());
    }

    @Test
    void preservesOpposingScrollCallbacksInOrderWithinOneFixedSample() {
        InputManager manager = new InputManager();

        manager.onScroll(0.0, 1.0);
        manager.onScroll(0.0, -1.0);
        manager.onScroll(0.0, 1.0);

        InputSnapshot snapshot = manager.consumeFixedInput();
        assertEquals(List.of(1, -1, 1), snapshot.scrollDeltas());
        assertEquals(1, snapshot.scrollSteps());
        assertTrue(manager.consumeFixedInput().scrollDeltas().isEmpty());
    }

    @Test
    void boundsMalformedLargeScrollOffsetsPerFixedSample() {
        InputManager manager = new InputManager();

        manager.onScroll(0.0, 10_000.0);
        manager.onScroll(0.0, -1.0);

        InputSnapshot snapshot = manager.consumeFixedInput();
        assertEquals(List.of(InputSnapshot.MAX_SCROLL_STEPS_PER_SAMPLE),
                snapshot.scrollDeltas());
        assertEquals(InputSnapshot.MAX_SCROLL_STEPS_PER_SAMPLE,
                snapshot.scrollSteps());
    }

    @Test
    void discardingGameplayEdgesClearsPressesAndScrollButPreservesHeldKeys() {
        InputManager manager = new InputManager();
        manager.onKey(GLFW_KEY_W, GLFW_PRESS);
        manager.onKey(org.lwjgl.glfw.GLFW.GLFW_KEY_Q, GLFW_PRESS);
        manager.onKey(org.lwjgl.glfw.GLFW.GLFW_KEY_Q, GLFW_RELEASE);
        manager.onScroll(0.0, 2.0);

        manager.discardFixedInputEdges();
        InputSnapshot snapshot = manager.consumeFixedInput();

        assertTrue(snapshot.isKeyDown(GLFW_KEY_W));
        assertFalse(snapshot.isKeyPressed(GLFW_KEY_W));
        assertFalse(snapshot.isKeyPressed(org.lwjgl.glfw.GLFW.GLFW_KEY_Q));
        assertEquals(0, snapshot.scrollSteps());
    }

    @Test
    void mousePressEdgeIsLatchedOnceWhileHoldSurvivesFixedSamples() {
        InputManager manager = new InputManager();
        manager.onMouseButton(GLFW_MOUSE_BUTTON_LEFT, GLFW_PRESS);

        InputSnapshot first = manager.consumeFixedInput();
        InputSnapshot second = manager.consumeFixedInput();

        assertTrue(first.isMouseButtonDown(GLFW_MOUSE_BUTTON_LEFT));
        assertTrue(first.isMouseButtonPressed(GLFW_MOUSE_BUTTON_LEFT));
        assertTrue(second.isMouseButtonDown(GLFW_MOUSE_BUTTON_LEFT));
        assertFalse(second.isMouseButtonPressed(GLFW_MOUSE_BUTTON_LEFT));
    }

    @Test
    void focusLossClearsHeldAndPressedMouseButtons() {
        InputManager manager = new InputManager();
        manager.onMouseButton(GLFW_MOUSE_BUTTON_LEFT, GLFW_PRESS);

        manager.onWindowFocus(false);
        InputSnapshot snapshot = manager.consumeFixedInput();

        assertFalse(snapshot.isMouseButtonDown(GLFW_MOUSE_BUTTON_LEFT));
        assertFalse(snapshot.isMouseButtonPressed(GLFW_MOUSE_BUTTON_LEFT));
    }

    @Test
    void focusLossPublishesOneMouseInteractionInvalidation() {
        InputManager manager = new InputManager();

        manager.onWindowFocus(false);

        assertTrue(manager.consumeMouseInteractionInvalidation());
        assertFalse(manager.consumeMouseInteractionInvalidation());
    }

    @Test
    void cursorCaptureResetClearsHeldAndPressedMouseButtons() {
        InputManager manager = new InputManager();
        manager.onMouseButton(GLFW_MOUSE_BUTTON_LEFT, GLFW_PRESS);

        manager.resetMouseBaseline();
        InputSnapshot snapshot = manager.consumeFixedInput();

        assertFalse(snapshot.isMouseButtonDown(GLFW_MOUSE_BUTTON_LEFT));
        assertFalse(snapshot.isMouseButtonPressed(GLFW_MOUSE_BUTTON_LEFT));
    }

    @Test
    void cursorCaptureResetRejectsAnotherPressUntilPhysicalRelease() {
        InputManager manager = new InputManager();
        manager.onMouseButton(GLFW_MOUSE_BUTTON_LEFT, GLFW_PRESS);

        manager.resetMouseBaseline();
        manager.onMouseButton(GLFW_MOUSE_BUTTON_LEFT, GLFW_PRESS);
        InputSnapshot stillSuppressed = manager.consumeFixedInput();

        assertFalse(stillSuppressed.isMouseButtonDown(GLFW_MOUSE_BUTTON_LEFT));
        assertFalse(stillSuppressed.isMouseButtonPressed(GLFW_MOUSE_BUTTON_LEFT));

        manager.onMouseButton(GLFW_MOUSE_BUTTON_LEFT, GLFW_RELEASE);
        manager.onMouseButton(GLFW_MOUSE_BUTTON_LEFT, GLFW_PRESS);
        InputSnapshot rearmed = manager.consumeFixedInput();

        assertTrue(rearmed.isMouseButtonDown(GLFW_MOUSE_BUTTON_LEFT));
        assertTrue(rearmed.isMouseButtonPressed(GLFW_MOUSE_BUTTON_LEFT));
    }

    @Test
    void focusLossRejectsAnotherPressUntilPhysicalRelease() {
        InputManager manager = new InputManager();
        manager.onMouseButton(GLFW_MOUSE_BUTTON_RIGHT, GLFW_PRESS);

        manager.onWindowFocus(false);
        manager.onMouseButton(GLFW_MOUSE_BUTTON_RIGHT, GLFW_RELEASE);
        manager.onWindowFocus(true);
        manager.onMouseButton(GLFW_MOUSE_BUTTON_RIGHT, GLFW_PRESS);
        InputSnapshot stillSuppressed = manager.consumeFixedInput();

        assertFalse(stillSuppressed.isMouseButtonDown(GLFW_MOUSE_BUTTON_RIGHT));
        assertFalse(stillSuppressed.isMouseButtonPressed(GLFW_MOUSE_BUTTON_RIGHT));

        manager.onMouseButton(GLFW_MOUSE_BUTTON_RIGHT, GLFW_RELEASE);
        manager.onMouseButton(GLFW_MOUSE_BUTTON_RIGHT, GLFW_PRESS);
        InputSnapshot rearmed = manager.consumeFixedInput();

        assertTrue(rearmed.isMouseButtonDown(GLFW_MOUSE_BUTTON_RIGHT));
        assertTrue(rearmed.isMouseButtonPressed(GLFW_MOUSE_BUTTON_RIGHT));
    }

    @Test
    void discardingEdgesPreservesHeldMouseButtonButClearsItsPress() {
        InputManager manager = new InputManager();
        manager.onMouseButton(GLFW_MOUSE_BUTTON_LEFT, GLFW_PRESS);

        manager.discardFixedInputEdges();
        InputSnapshot snapshot = manager.consumeFixedInput();

        assertTrue(snapshot.isMouseButtonDown(GLFW_MOUSE_BUTTON_LEFT));
        assertFalse(snapshot.isMouseButtonPressed(GLFW_MOUSE_BUTTON_LEFT));
    }
}
