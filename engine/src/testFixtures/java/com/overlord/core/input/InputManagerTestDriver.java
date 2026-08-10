package com.overlord.core.input;

/** Test-only access to the callback boundary owned by {@link InputManager}. */
public final class InputManagerTestDriver {
    private InputManagerTestDriver() {}

    public static void key(InputManager input, int key, int action) {
        input.onKey(key, action);
    }

    public static void mouseButton(InputManager input, int button, int action) {
        input.onMouseButton(button, action);
    }

    public static void cursor(InputManager input, double x, double y) {
        input.onCursorPosition(x, y);
    }

    public static void scroll(InputManager input, double xOffset, double yOffset) {
        input.onScroll(xOffset, yOffset);
    }

    public static void windowFocus(InputManager input, boolean focused) {
        input.onWindowFocus(focused);
    }
}
