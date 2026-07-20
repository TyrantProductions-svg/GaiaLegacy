package com.overlord.renderer;

import org.joml.Matrix4f;

import static org.lwjgl.opengl.GL30C.*;

public class Renderer {
    
    private Shader shader;
    private Mesh mesh;
    private Mesh testMesh;
    private Camera camera;
    
    private Matrix4f projectionMatrix;
    
    public void init(Camera camera, int width, int height) {
        this.camera = camera;
        
        glEnable(GL_DEPTH_TEST);
        glClearColor(0.1f, 0.1f, 0.15f, 1.0f);
        
        projectionMatrix = new Matrix4f().perspective(
            (float) Math.toRadians(60.0f),
            (float) width / height,
            0.1f,
            1000.0f
        );
        
        String vertexSource = 
            "#version 410 core\n" +
            "layout (location = 0) in vec3 aPos;\n" +
            "layout (location = 1) in vec3 aColor;\n" +
            "uniform mat4 projection;\n" +
            "uniform mat4 view;\n" +
            "uniform mat4 model;\n" +
            "out vec3 ourColor;\n" +
            "void main() {\n" +
            "    gl_Position = projection * view * model * vec4(aPos, 1.0);\n" +
            "    ourColor = aColor;\n" +
            "}\n";
        
        String fragmentSource = 
            "#version 410 core\n" +
            "in vec3 ourColor;\n" +
            "out vec4 FragColor;\n" +
            "void main() {\n" +
            "    FragColor = vec4(ourColor, 1.0);\n" +
            "}\n";
        
        shader = new Shader(vertexSource, fragmentSource);
        
        float[] vertices = {
            -0.5f, -0.5f, -0.5f,  1.0f, 0.0f, 0.0f,
             0.5f, -0.5f, -0.5f,  1.0f, 0.0f, 0.0f,
             0.5f,  0.5f, -0.5f,  1.0f, 0.0f, 0.0f,
             0.5f,  0.5f, -0.5f,  1.0f, 0.0f, 0.0f,
            -0.5f,  0.5f, -0.5f,  1.0f, 0.0f, 0.0f,
            -0.5f, -0.5f, -0.5f,  1.0f, 0.0f, 0.0f,

            -0.5f, -0.5f,  0.5f,  0.0f, 1.0f, 0.0f,
             0.5f, -0.5f,  0.5f,  0.0f, 1.0f, 0.0f,
             0.5f,  0.5f,  0.5f,  0.0f, 1.0f, 0.0f,
             0.5f,  0.5f,  0.5f,  0.0f, 1.0f, 0.0f,
            -0.5f,  0.5f,  0.5f,  0.0f, 1.0f, 0.0f,
            -0.5f, -0.5f,  0.5f,  0.0f, 1.0f, 0.0f,

            -0.5f,  0.5f,  0.5f,  0.0f, 0.0f, 1.0f,
            -0.5f,  0.5f, -0.5f,  0.0f, 0.0f, 1.0f,
            -0.5f, -0.5f, -0.5f,  0.0f, 0.0f, 1.0f,
            -0.5f, -0.5f, -0.5f,  0.0f, 0.0f, 1.0f,
            -0.5f, -0.5f,  0.5f,  0.0f, 0.0f, 1.0f,
            -0.5f,  0.5f,  0.5f,  0.0f, 0.0f, 1.0f,

             0.5f,  0.5f,  0.5f,  1.0f, 1.0f, 0.0f,
             0.5f,  0.5f, -0.5f,  1.0f, 1.0f, 0.0f,
             0.5f, -0.5f, -0.5f,  1.0f, 1.0f, 0.0f,
             0.5f, -0.5f, -0.5f,  1.0f, 1.0f, 0.0f,
             0.5f, -0.5f,  0.5f,  1.0f, 1.0f, 0.0f,
             0.5f,  0.5f,  0.5f,  1.0f, 1.0f, 0.0f,

            -0.5f, -0.5f, -0.5f,  1.0f, 0.0f, 1.0f,
             0.5f, -0.5f, -0.5f,  1.0f, 0.0f, 1.0f,
             0.5f, -0.5f,  0.5f,  1.0f, 0.0f, 1.0f,
             0.5f, -0.5f,  0.5f,  1.0f, 0.0f, 1.0f,
            -0.5f, -0.5f,  0.5f,  1.0f, 0.0f, 1.0f,
            -0.5f, -0.5f, -0.5f,  1.0f, 0.0f, 1.0f,

            -0.5f,  0.5f, -0.5f,  0.0f, 1.0f, 1.0f,
             0.5f,  0.5f, -0.5f,  0.0f, 1.0f, 1.0f,
             0.5f,  0.5f,  0.5f,  0.0f, 1.0f, 1.0f,
             0.5f,  0.5f,  0.5f,  0.0f, 1.0f, 1.0f,
            -0.5f,  0.5f,  0.5f,  0.0f, 1.0f, 1.0f,
            -0.5f,  0.5f, -0.5f,  0.0f, 1.0f, 1.0f
        };
        
        mesh = new Mesh(vertices);
        testMesh = mesh;
    }
    
    public Mesh getTestMesh() {
        return testMesh;
    }
    
    public void setMesh(Mesh mesh) {
        this.mesh = mesh;
    }
    
    public void render() {
        glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
        
        if (mesh == null) return;
        
        shader.use();
        
        shader.setUniformMat4f("projection", projectionMatrix);
        shader.setUniformMat4f("view", camera.getViewMatrix());
        shader.setUniformMat4f("model", new Matrix4f());
        
        mesh.draw();
    }
    
    public void cleanup() {
        if (mesh != null) {
            mesh.cleanup();
        }
        if (shader != null) {
            shader.cleanup();
        }
    }
}