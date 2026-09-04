package com.gaia.tools.viewer;

import org.joml.Matrix4fc;

/** Minimal diagnostic shader contract; implemented by the engine ShaderProgram adapter. */
interface ViewerShader extends AutoCloseable {
    void use();
    void projection(Matrix4fc value);
    void modelView(Matrix4fc value);
    void baseColor(float[] value);
    void lineColor(float[] value);
    void textured(boolean value);
    void unlit(boolean value);
    @Override void close();
}
