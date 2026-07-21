package com.overlord.core;

import static org.lwjgl.glfw.GLFW.*;

public class Input {
    
    private static boolean[] keys = new boolean[GLFW_KEY_LAST];
    private static boolean[] previousKeys = new boolean[GLFW_KEY_LAST];
    
    public static void init(long window) {
        glfwSetKeyCallback(window, (win, key, scancode, action, mods) -> {
            if (key >= 0 && key < keys.length) {
                keys[key] = action != GLFW_RELEASE;
            }
        });
    }
    
    public static void update() {
        System.arraycopy(keys, 0, previousKeys, 0, keys.length);
    }
    
    public static boolean isKeyPressed(int keyCode) {
        return keyCode >= 0 && keyCode < keys.length && keys[keyCode];
    }
    
    public static boolean isKeyJustPressed(int keyCode) {
        return keyCode >= 0 && keyCode < keys.length && keys[keyCode] && !previousKeys[keyCode];
    }
}