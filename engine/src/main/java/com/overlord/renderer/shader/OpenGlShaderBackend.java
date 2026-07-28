package com.overlord.renderer.shader;

import static org.lwjgl.opengl.GL30C.GL_COMPILE_STATUS;
import static org.lwjgl.opengl.GL30C.GL_FRAGMENT_SHADER;
import static org.lwjgl.opengl.GL30C.GL_LINK_STATUS;
import static org.lwjgl.opengl.GL30C.GL_TRUE;
import static org.lwjgl.opengl.GL30C.GL_VERTEX_SHADER;
import static org.lwjgl.opengl.GL30C.glAttachShader;
import static org.lwjgl.opengl.GL30C.glCompileShader;
import static org.lwjgl.opengl.GL30C.glCreateProgram;
import static org.lwjgl.opengl.GL30C.glCreateShader;
import static org.lwjgl.opengl.GL30C.glDeleteProgram;
import static org.lwjgl.opengl.GL30C.glDeleteShader;
import static org.lwjgl.opengl.GL30C.glGetProgramInfoLog;
import static org.lwjgl.opengl.GL30C.glGetProgrami;
import static org.lwjgl.opengl.GL30C.glGetShaderInfoLog;
import static org.lwjgl.opengl.GL30C.glGetShaderi;
import static org.lwjgl.opengl.GL30C.glGetUniformLocation;
import static org.lwjgl.opengl.GL30C.glLinkProgram;
import static org.lwjgl.opengl.GL30C.glShaderSource;
import static org.lwjgl.opengl.GL30C.glUniform1i;
import static org.lwjgl.opengl.GL30C.glUniform1f;
import static org.lwjgl.opengl.GL30C.glUniform2f;
import static org.lwjgl.opengl.GL30C.glUniform3f;
import static org.lwjgl.opengl.GL30C.glUniformMatrix4fv;
import static org.lwjgl.opengl.GL30C.glUseProgram;

final class OpenGlShaderBackend implements ShaderBackend {
    @Override
    public int createShader(ShaderStage stage) {
        return glCreateShader(
                switch (stage) {
                    case VERTEX -> GL_VERTEX_SHADER;
                    case FRAGMENT -> GL_FRAGMENT_SHADER;
                });
    }

    @Override
    public void setSource(int shaderId, String source) {
        glShaderSource(shaderId, source);
    }

    @Override
    public void compile(int shaderId) {
        glCompileShader(shaderId);
    }

    @Override
    public boolean compileSucceeded(int shaderId) {
        return glGetShaderi(shaderId, GL_COMPILE_STATUS) == GL_TRUE;
    }

    @Override
    public String shaderInfoLog(int shaderId) {
        return glGetShaderInfoLog(shaderId);
    }

    @Override
    public void deleteShader(int shaderId) {
        glDeleteShader(shaderId);
    }

    @Override
    public int createProgram() {
        return glCreateProgram();
    }

    @Override
    public void attach(int programId, int shaderId) {
        glAttachShader(programId, shaderId);
    }

    @Override
    public void link(int programId) {
        glLinkProgram(programId);
    }

    @Override
    public boolean linkSucceeded(int programId) {
        return glGetProgrami(programId, GL_LINK_STATUS) == GL_TRUE;
    }

    @Override
    public String programInfoLog(int programId) {
        return glGetProgramInfoLog(programId);
    }

    @Override
    public int uniformLocation(int programId, String name) {
        return glGetUniformLocation(programId, name);
    }

    @Override
    public void useProgram(int programId) {
        glUseProgram(programId);
    }

    @Override
    public void uploadMatrix4(int location, float[] columnMajor) {
        glUniformMatrix4fv(location, false, columnMajor);
    }

    @Override
    public void uploadInt(int location, int value) {
        glUniform1i(location, value);
    }

    @Override
    public void uploadFloat(int location, float value) {
        glUniform1f(location, value);
    }

    @Override
    public void uploadVector2(int location, float x, float y) {
        glUniform2f(location, x, y);
    }

    @Override
    public void uploadVector3(int location, float x, float y, float z) {
        glUniform3f(location, x, y, z);
    }

    @Override
    public void deleteProgram(int programId) {
        glDeleteProgram(programId);
    }
}
