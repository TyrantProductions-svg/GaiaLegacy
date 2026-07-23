package com.overlord.renderer;

import com.overlord.core.thread.MainThreadGuard;
import java.util.Objects;
import org.joml.Matrix4f;

import static org.lwjgl.opengl.GL30C.*;

public class Shader {
    private final MainThreadGuard mainThreadGuard;
    private int programId;

    public Shader(
            MainThreadGuard mainThreadGuard, String vertexSource, String fragmentSource) {
        this.mainThreadGuard = Objects.requireNonNull(mainThreadGuard, "mainThreadGuard");
        this.mainThreadGuard.assertMainThread("shader creation");
        int vertexShader = 0;
        int fragmentShader = 0;
        int createdProgram = 0;
        try {
            vertexShader = compileShader(vertexSource, GL_VERTEX_SHADER);
            fragmentShader = compileShader(fragmentSource, GL_FRAGMENT_SHADER);

            createdProgram = glCreateProgram();
            glAttachShader(createdProgram, vertexShader);
            glAttachShader(createdProgram, fragmentShader);
            glLinkProgram(createdProgram);

            if (glGetProgrami(createdProgram, GL_LINK_STATUS) == GL_FALSE) {
                String error = glGetProgramInfoLog(createdProgram, 1024);
                throw new RuntimeException("Failed to link shader program: " + error);
            }
            programId = createdProgram;
        } finally {
            if (vertexShader != 0) {
                glDeleteShader(vertexShader);
            }
            if (fragmentShader != 0) {
                glDeleteShader(fragmentShader);
            }
            if (programId == 0 && createdProgram != 0) {
                glDeleteProgram(createdProgram);
            }
        }
    }

    private int compileShader(String source, int type) {
        int shader = glCreateShader(type);
        glShaderSource(shader, source);
        glCompileShader(shader);

        if (glGetShaderi(shader, GL_COMPILE_STATUS) == GL_FALSE) {
            String error = glGetShaderInfoLog(shader, 1024);
            glDeleteShader(shader);
            throw new RuntimeException("Failed to compile shader: " + error);
        }

        return shader;
    }

    public void use() {
        mainThreadGuard.assertMainThread("shader use");
        glUseProgram(programId);
    }

    public void setUniformMat4f(String name, Matrix4f matrix) {
        mainThreadGuard.assertMainThread("shader matrix uniform upload");
        int location = glGetUniformLocation(programId, name);
        glUniformMatrix4fv(location, false, matrix.get(new float[16]));
    }

    public void setUniform(String name, int value) {
        mainThreadGuard.assertMainThread("shader integer uniform upload");
        int location = glGetUniformLocation(programId, name);
        glUniform1i(location, value);
    }

    public int getProgramId() {
        return programId;
    }

    public void cleanup() {
        mainThreadGuard.assertMainThread("shader cleanup");
        if (programId != 0) {
            glDeleteProgram(programId);
            programId = 0;
        }
    }
}
