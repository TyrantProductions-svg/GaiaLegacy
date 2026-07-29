package com.overlord.renderer.ui;

import com.overlord.renderer.state.RenderStateSnapshot;
import com.overlord.renderer.state.ScissorBox;
import java.util.Optional;

/** GPU boundary used by the generic UI resources and renderer. */
public interface UiGpuBackend {
    int createProgram(String vertexSource, String fragmentSource);

    void useProgram(int program);

    void setFramebufferSize(int program, float width, float height);

    void setTextureSampler(int program, int textureUnit);

    void setTextureSamplingEnabled(int program, boolean enabled);

    void deleteProgram(int program);

    int createTexture(UiTextureData texture);

    void bindTextureUnitZero(int texture);

    void deleteTexture(int texture);

    int createVertexArray();

    int createBuffer();

    void configureBatch(int vertexArray, int vertexBuffer, int elementBuffer);

    void uploadBatch(
            int vertexArray,
            int vertexBuffer,
            int elementBuffer,
            float[] vertices,
            int[] indices);

    void drawBatch(int vertexArray, int indexCount);

    void deleteBuffer(int buffer);

    void deleteVertexArray(int vertexArray);

    RenderStateSnapshot captureState();

    void applyUiState(int framebufferWidth, int framebufferHeight);

    void setClip(Optional<ScissorBox> clip);

    void restoreState(RenderStateSnapshot snapshot);
}
