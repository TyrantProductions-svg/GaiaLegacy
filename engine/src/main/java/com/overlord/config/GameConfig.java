package com.overlord.config;

public class GameConfig {
    
    public static final class Window {
        public static final String TITLE = "Gaia Legacy";
        public static final int WIDTH = 1280;
        public static final int HEIGHT = 720;
        
        public static final int OPENGL_VERSION_MAJOR = 4;
        public static final int OPENGL_VERSION_MINOR = 1;
        public static final int RESIZABLE = org.lwjgl.glfw.GLFW.GLFW_TRUE;
        public static final boolean VSYNC = true;
    }
    
    public static final class Player {
        public static final float MOVEMENT_SPEED = 5.0f;
        public static final float JUMP_VELOCITY = 9.0f;
        public static final float NOCLIP_SPEED = 10.0f;
        public static final float WIDTH = 0.6f;
        public static final float HEIGHT = 1.8f;
        public static final float EYE_HEIGHT = 1.62f;
        public static final float MAX_STEP_HEIGHT = 1.0f;
        public static final float GROUND_SNAP_DISTANCE = 1.0f;
        public static final int NOCLIP_DOUBLE_TAP_STEPS = 15;
    }
    
    public static final class Physics {
        public static final float GRAVITY = -25.0f;
        public static final float TERMINAL_VELOCITY = -60.0f;
        public static final float COLLISION_TOLERANCE = 0.001f;
        public static final int MAX_SLIDE_ITERATIONS = 4;
        public static final int MAX_DEPENETRATION_ITERATIONS = 8;
    }
    
    public static final class Chunk {
        public static final int SIZE = 16;
        public static final int SUBCHUNK_HEIGHT = 16;
        public static final int MAX_HEIGHT = 256;
    }
    
    public static final class Rendering {
        public static final float FOV = 70.0f;
        public static final float NEAR_PLANE = 0.1f;
        public static final float FAR_PLANE = 1000.0f;
    }
    
    public static final class Core {
        public static final int RENDER = 0;
        public static final int PLAYER = 1;
        public static final int WORLD = 2;
        public static final int PHYSICS = 3;
    }
    
    public static final class Input {
        public static final float MOUSE_SENSITIVITY = 0.1f;
        public static final int KEY_FORWARD = org.lwjgl.glfw.GLFW.GLFW_KEY_W;
        public static final int KEY_BACKWARD = org.lwjgl.glfw.GLFW.GLFW_KEY_S;
        public static final int KEY_LEFT = org.lwjgl.glfw.GLFW.GLFW_KEY_A;
        public static final int KEY_RIGHT = org.lwjgl.glfw.GLFW.GLFW_KEY_D;
        public static final int KEY_JUMP = org.lwjgl.glfw.GLFW.GLFW_KEY_SPACE;
        public static final int KEY_DESCEND = org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT_SHIFT;
        public static final int KEY_PICKUP_MODIFIER_LEFT =
                org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT_SHIFT;
        public static final int KEY_PICKUP_MODIFIER_RIGHT =
                org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT_SHIFT;
        public static final int KEY_CLOSE = org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE;
        public static final int KEY_CURSOR_CAPTURE = org.lwjgl.glfw.GLFW.GLFW_KEY_F1;
        public static final int KEY_TOGGLE_HUD = org.lwjgl.glfw.GLFW.GLFW_KEY_F2;
        public static final int KEY_TOGGLE_DEBUG_HUD = org.lwjgl.glfw.GLFW.GLFW_KEY_F3;
        public static final int KEY_SELECT_LEFT = org.lwjgl.glfw.GLFW.GLFW_KEY_1;
        public static final int KEY_SELECT_RIGHT = org.lwjgl.glfw.GLFW.GLFW_KEY_2;
        public static final int KEY_SELECT_MOUTH = org.lwjgl.glfw.GLFW.GLFW_KEY_3;
        public static final int KEY_DROP = org.lwjgl.glfw.GLFW.GLFW_KEY_Q;
        public static final int KEY_DROP_ALL_LEFT =
                org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT_CONTROL;
        public static final int KEY_DROP_ALL_RIGHT =
                org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT_CONTROL;
        public static final int KEY_TOGGLE_GAME_MODE = org.lwjgl.glfw.GLFW.GLFW_KEY_F4;
        public static final int KEY_DEBUG_INVENTORY_SEED = org.lwjgl.glfw.GLFW.GLFW_KEY_F5;
        public static final int KEY_DEBUG_INVENTORY_CLEAR = org.lwjgl.glfw.GLFW.GLFW_KEY_F6;
        public static final int KEY_DEBUG_INVENTORY_FILL = org.lwjgl.glfw.GLFW.GLFW_KEY_F7;
        public static final int KEY_DEBUG_INVENTORY_PRINT = org.lwjgl.glfw.GLFW.GLFW_KEY_F8;
        public static final int KEY_DEBUG_DETAIL_INSPECT = org.lwjgl.glfw.GLFW.GLFW_KEY_F9;
        public static final int KEY_DEBUG_DETAIL_CONVERT = org.lwjgl.glfw.GLFW.GLFW_KEY_F10;
        public static final int KEY_DEBUG_DETAIL_FILL = org.lwjgl.glfw.GLFW.GLFW_KEY_F11;
        public static final int KEY_DEBUG_DETAIL_CLEAR = org.lwjgl.glfw.GLFW.GLFW_KEY_F12;
        public static final int KEY_DEBUG_DETAIL_FIXTURE_NEXT =
                org.lwjgl.glfw.GLFW.GLFW_KEY_9;
        public static final int KEY_DEBUG_DETAIL_FIXTURE_APPLY =
                org.lwjgl.glfw.GLFW.GLFW_KEY_0;
        public static final int KEY_DEBUG_DETAIL_MODIFIER_LEFT =
                org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT_CONTROL;
        public static final int KEY_DEBUG_DETAIL_MODIFIER_RIGHT =
                org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT_CONTROL;
        public static final int MOUSE_PRIMARY = org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_LEFT;
        public static final int MOUSE_SECONDARY = org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_RIGHT;
    }

    public static final class Interaction {
        public static final float REACH = 6.0f;
        public static final float WORLD_ITEM_PICKUP_REACH = 3.5f;
        public static final double BASE_BREAK_SPEED = 1.0;
        public static final int MAX_LOGICAL_WORLD_ITEMS = 1024;
        public static final long WORLD_ITEM_PICKUP_DELAY_TICKS = 20;
        public static final float WORLD_ITEM_EDGE_LENGTH = 0.50f;
    }
}
