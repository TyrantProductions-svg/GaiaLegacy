package com.overlord;

import com.overlord.core.Engine;
import com.overlord.core.PlayerManager;

import static org.lwjgl.glfw.GLFW.*;

public class Main {
    
    private static double lastX;
    private static double lastY;
    private static boolean firstMouse = true;
    
    public static void main(String[] args) {
        Engine engine = new Engine();
        engine.init();
        
        PlayerManager playerManager = new PlayerManager(
            engine.getCamera(), 
            engine.getWindow().getWindow()
        );
        
        glfwSetCursorPosCallback(engine.getWindow().getWindow(), (win, xpos, ypos) -> {
            if (firstMouse) {
                lastX = xpos;
                lastY = ypos;
                firstMouse = false;
            }
            
            double xoffset = xpos - lastX;
            double yoffset = lastY - ypos;
            
            lastX = xpos;
            lastY = ypos;
            
            engine.getCamera().processMouseMovement((float) xoffset, (float) yoffset);
        });
        
        engine.getCamera().setPosition(new org.joml.Vector3f(0, 0, 5));
        
        while (engine.isRunning()) {
            engine.submitToCore(Engine.CORE_PLAYER, playerManager::update);
            
            if (playerManager.shouldClose()) {
                break;
            }
            
            engine.getRenderer().render();
            glfwSwapBuffers(engine.getWindow().getWindow());
            glfwPollEvents();
        }
        
        engine.shutdown();
    }
}