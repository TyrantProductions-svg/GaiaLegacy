package com.gaia.tools.viewer;

import com.overlord.assets.AssetManager;
import com.overlord.assets.ResourceLocation;
import com.overlord.core.thread.MainThreadGuard;
import com.overlord.renderer.shader.ShaderProgram;
import com.overlord.renderer.shader.ShaderResourceLoader;
import java.util.List;
import org.joml.Matrix4fc;
import org.joml.Vector3f;

/** Adapter retaining the engine's existing shader compile/link authority. */
final class ViewerShaderProgram implements ViewerShader {
    private static final ResourceLocation VERTEX =
            ResourceLocation.of("gaia", "model-viewer/preview.vert");
    private static final ResourceLocation FRAGMENT =
            ResourceLocation.of("gaia", "model-viewer/preview.frag");
    private static final List<String> UNIFORMS = List.of(
            "projection", "modelView", "baseColorTexture", "baseColorFactor",
            "lineColor", "textured", "unlit");
    private final ShaderProgram program;

    ViewerShaderProgram(MainThreadGuard guard) {
        var assets = new AssetManager(ViewerShaderProgram.class.getClassLoader());
        var sources = new ShaderResourceLoader(assets).load("Gaia Model Inspector", VERTEX, FRAGMENT);
        program = new ShaderProgram(guard, sources, UNIFORMS);
        program.use();
        program.setInt("baseColorTexture", 0);
    }

    @Override public void use() { program.use(); }
    @Override public void projection(Matrix4fc value) { program.setMatrix4("projection", value); }
    @Override public void modelView(Matrix4fc value) { program.setMatrix4("modelView", value); }
    @Override public void baseColor(float[] value) {
        program.setVector3("baseColorFactor", new Vector3f(value[0], value[1], value[2]));
    }
    @Override public void lineColor(float[] value) {
        program.setVector3("lineColor", new Vector3f(value[0], value[1], value[2]));
    }
    @Override public void textured(boolean value) { program.setInt("textured", value ? 1 : 0); }
    @Override public void unlit(boolean value) { program.setInt("unlit", value ? 1 : 0); }
    @Override public void close() { program.close(); }
}
