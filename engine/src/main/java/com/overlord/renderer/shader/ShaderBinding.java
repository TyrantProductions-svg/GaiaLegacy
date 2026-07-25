package com.overlord.renderer.shader;

import org.joml.Matrix4fc;

public interface ShaderBinding {
    int programId();

    void use();

    void setMatrix4(String uniform, Matrix4fc value);

    void setInt(String uniform, int value);
}
