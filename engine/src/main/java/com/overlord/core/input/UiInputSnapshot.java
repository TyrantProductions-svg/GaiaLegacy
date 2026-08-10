package com.overlord.core.input;

import java.util.List;
import java.util.Set;

/**
 * Immutable callback-owned input state for the UI pass of a frame.
 * Pointer coordinates use GLFW logical window coordinates, not framebuffer pixels.
 */
public record UiInputSnapshot(
        Set<Integer> downKeys,
        Set<Integer> pressedKeys,
        Set<Integer> downMouseButtons,
        Set<Integer> pressedMouseButtons,
        List<Integer> scrollDeltas,
        double pointerX,
        double pointerY,
        boolean focused,
        long sampleId) {
    public UiInputSnapshot {
        downKeys = Set.copyOf(downKeys);
        pressedKeys = Set.copyOf(pressedKeys);
        downMouseButtons = Set.copyOf(downMouseButtons);
        pressedMouseButtons = Set.copyOf(pressedMouseButtons);
        scrollDeltas = List.copyOf(scrollDeltas);
        if (!Double.isFinite(pointerX) || !Double.isFinite(pointerY) || sampleId < 0) {
            throw new IllegalArgumentException(
                    "UI pointer must be finite and sampleId non-negative");
        }
    }

    public boolean isKeyDown(int key) {
        return downKeys.contains(key);
    }

    public boolean isKeyPressed(int key) {
        return pressedKeys.contains(key);
    }

    public boolean isMouseDown(int button) {
        return downMouseButtons.contains(button);
    }

    public boolean isMousePressed(int button) {
        return pressedMouseButtons.contains(button);
    }
}
