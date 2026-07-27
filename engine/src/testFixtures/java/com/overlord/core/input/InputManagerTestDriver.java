package com.overlord.core.input;

/** Test-only access to the callback boundary owned by {@link InputManager}. */
public final class InputManagerTestDriver {
    private InputManagerTestDriver() {}

    public static void mouseButton(InputManager input, int button, int action) {
        input.onMouseButton(button, action);
    }

    public static void windowFocus(InputManager input, boolean focused) {
        input.onWindowFocus(focused);
    }
}
