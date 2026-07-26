package com.overlord.renderer.shader;

import org.joml.Matrix4fc;
import org.joml.Vector3fc;

public interface ShaderBinding {
    int programId();

    void use();

    void setMatrix4(String uniform, Matrix4fc value);

    void setInt(String uniform, int value);

    void setFloat(String uniform, float value);

    void setVector3(String uniform, Vector3fc value);
}
