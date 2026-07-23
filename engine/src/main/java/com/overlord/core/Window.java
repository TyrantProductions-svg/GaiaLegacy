package com.overlord.core;

import com.overlord.config.GameConfig;
import org.lwjgl.glfw.GLFWVidMode;
import org.lwjgl.opengl.GL;

import static org.lwjgl.glfw.GLFW.*;

public class Window {
    
    private final String title;
    private final int width;
    private final int height;
    private long window;
    
    public Window() {
        this(GameConfig.Window.TITLE, GameConfig.Window.WIDTH, GameConfig.Window.HEIGHT);
    }
    
    public Window(String title, int width, int height) {
        this.title = title;
        this.width = width;
        this.height = height;
        
        init();
    }
    
    private void init() {
        if (!glfwInit()) {
            throw new IllegalStateException("Unable to initialize GLFW");
        }
        
        glfwDefaultWindowHints();
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        glfwWindowHint(GLFW_RESIZABLE, GameConfig.Window.RESIZABLE);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, GameConfig.Window.OPENGL_VERSION_MAJOR);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, GameConfig.Window.OPENGL_VERSION_MINOR);
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
        glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT, GLFW_TRUE);
        
        window = glfwCreateWindow(width, height, title, 0, 0);
        if (window == 0) {
            throw new RuntimeException("Failed to create the GLFW window");
        }
        
        GLFWVidMode vidmode = glfwGetVideoMode(glfwGetPrimaryMonitor());
        if (vidmode != null) {
            glfwSetWindowPos(
                window,
                (vidmode.width() - width) / 2,
                (vidmode.height() - height) / 2
            );
        }
        
        glfwMakeContextCurrent(window);
        glfwShowWindow(window);
        
        GL.createCapabilities();
    }
    
    public void setCursorCaptured(boolean captured) {
        if (captured) {
            glfwSetInputMode(window, GLFW_CURSOR, GLFW_CURSOR_DISABLED);
        } else {
            glfwSetInputMode(window, GLFW_CURSOR, GLFW_CURSOR_NORMAL);
        }
    }
    
    public boolean shouldClose() {
        return glfwWindowShouldClose(window);
    }
    
    public void destroy() {
        glfwDestroyWindow(window);
        glfwTerminate();
    }
    
    public long getWindow() {
        return window;
    }
    
    public int getWidth() {
        return width;
    }
    
    public int getHeight() {
        return height;
    }
}