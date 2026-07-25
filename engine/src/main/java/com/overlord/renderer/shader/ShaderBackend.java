package com.overlord.renderer.shader;

interface ShaderBackend {
    int createShader(ShaderStage stage);

    void setSource(int shaderId, String source);

    void compile(int shaderId);

    boolean compileSucceeded(int shaderId);

    String shaderInfoLog(int shaderId);

    void deleteShader(int shaderId);

    int createProgram();

    void attach(int programId, int shaderId);

    void link(int programId);

    boolean linkSucceeded(int programId);

    String programInfoLog(int programId);

    int uniformLocation(int programId, String name);

    void useProgram(int programId);

    void uploadMatrix4(int location, float[] columnMajor);

    void uploadInt(int location, int value);

    void deleteProgram(int programId);
}
