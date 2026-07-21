package com.overlord.renderer;

import org.joml.Matrix4f;

import static org.lwjgl.opengl.GL30C.*;

public class Shader {
    
    private int programId;
    
    public Shader(String vertexSource, String fragmentSource) {
        int vertexShader = compileShader(vertexSource, GL_VERTEX_SHADER);
        int fragmentShader = compileShader(fragmentSource, GL_FRAGMENT_SHADER);
        
        programId = glCreateProgram();
        glAttachShader(programId, vertexShader);
        glAttachShader(programId, fragmentShader);
        glLinkProgram(programId);
        
        if (glGetProgrami(programId, GL_LINK_STATUS) == GL_FALSE) {
            String error = glGetProgramInfoLog(programId, 1024);
            throw new RuntimeException("Failed to link shader program: " + error);
        }
        
        glDeleteShader(vertexShader);
        glDeleteShader(fragmentShader);
    }
    
    private int compileShader(String source, int type) {
        int shader = glCreateShader(type);
        glShaderSource(shader, source);
        glCompileShader(shader);
        
        if (glGetShaderi(shader, GL_COMPILE_STATUS) == GL_FALSE) {
            String error = glGetShaderInfoLog(shader, 1024);
            throw new RuntimeException("Failed to compile shader: " + error);
        }
        
        return shader;
    }
    
    public void use() {
        glUseProgram(programId);
    }
    
    public void setUniformMat4f(String name, Matrix4f matrix) {
        int location = glGetUniformLocation(programId, name);
        glUniformMatrix4fv(location, false, matrix.get(new float[16]));
    }
    
    public int getProgramId() {
        return programId;
    }
    
    public void cleanup() {
        glDeleteProgram(programId);
    }
}