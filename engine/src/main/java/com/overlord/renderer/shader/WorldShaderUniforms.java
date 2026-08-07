package com.overlord.renderer.shader;

import java.util.ArrayList;
import java.util.List;

/** Required-uniform manifest shared by world-shader construction and contract tests. */
public final class WorldShaderUniforms {
    public static final int MAX_EXCLUDED_BLOCK_CELLS = 256;

    private static final List<String> REQUIRED = createRequiredUniforms();

    private WorldShaderUniforms() {}

    public static List<String> requiredUniforms() {
        return REQUIRED;
    }

    private static List<String> createRequiredUniforms() {
        List<String> uniforms = new ArrayList<>(List.of(
                "projection",
                "view",
                "model",
                "textureAtlas",
                "sunDirection",
                "ambientStrength",
                "directionalStrength",
                "fogColor",
                "fogStart",
                "fogEnd",
                "excludedBlockCount"));
        for (int index = 0; index < MAX_EXCLUDED_BLOCK_CELLS; index++) {
            uniforms.add("excludedBlockCells[" + index + "]");
        }
        return List.copyOf(uniforms);
    }
}
