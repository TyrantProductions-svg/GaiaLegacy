package com.overlord.config;

public class GameConfig {
    
    public static final class Window {
        public static final String TITLE = "Gaia Legacy";
        public static final int WIDTH = 1280;
        public static final int HEIGHT = 720;
        
        public static final int OPENGL_VERSION_MAJOR = 4;
        public static final int OPENGL_VERSION_MINOR = 1;
        public static final int RESIZABLE = org.lwjgl.glfw.GLFW.GLFW_TRUE;
    }
    
    public static final class Player {
        public static final float MOVEMENT_SPEED = 5.0f;
        public static final float JUMP_VELOCITY = 9.0f;
        public static final float NOCLIP_SPEED = 10.0f;
        public static final float WIDTH = 0.6f;
        public static final float HEIGHT = 1.8f;
        public static final float EYE_HEIGHT = 1.62f;
        public static final float MAX_STEP_HEIGHT = 0.5f;
    }
    
    public static final class Physics {
        public static final float GRAVITY = -25.0f;
        public static final float TERMINAL_VELOCITY = -60.0f;
        public static final float COLLISION_TOLERANCE = 0.001f;
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
    
    public static final class WorldGeneration {
        public static final int SEED = 12345;
        public static final int OCTAVES = 4;
        public static final double PERSISTENCE = 0.5;
        public static final double SCALE = 0.02;
        public static final int BASE_HEIGHT = 20;
        public static final int HEIGHT_VARIATION = 30;
    }
    
    public static final class Core {
        public static final int RENDER = 0;
        public static final int PLAYER = 1;
        public static final int WORLD = 2;
        public static final int PHYSICS = 3;
    }
    
    public static final class Input {
        public static final int KEY_FORWARD = org.lwjgl.glfw.GLFW.GLFW_KEY_W;
        public static final int KEY_BACKWARD = org.lwjgl.glfw.GLFW.GLFW_KEY_S;
        public static final int KEY_LEFT = org.lwjgl.glfw.GLFW.GLFW_KEY_A;
        public static final int KEY_RIGHT = org.lwjgl.glfw.GLFW.GLFW_KEY_D;
        public static final int KEY_JUMP = org.lwjgl.glfw.GLFW.GLFW_KEY_SPACE;
        public static final int KEY_DESCEND = org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT_SHIFT;
        public static final int KEY_CLOSE = org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE;
        public static final int KEY_CURSOR_CAPTURE = org.lwjgl.glfw.GLFW.GLFW_KEY_F1;
    }
}