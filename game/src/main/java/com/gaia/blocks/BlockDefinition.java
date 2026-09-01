package com.gaia.blocks;

import com.overlord.assets.ResourceLocation;
import com.overlord.voxel.BlockFace;
import java.util.Map;
import java.util.Objects;

public record BlockDefinition(
        int id,
        ResourceLocation name,
        ResourceLocation material,
        Map<BlockFace, ResourceLocation> textures,
        float hardness,
        float structuralIntegrity,
        float tolerance,
        boolean gravity,
        boolean flammable,
        float blastResistance,
        ItemFormDefinition item,
        DetailSupportDefinition detailSupport) {
    public BlockDefinition {
        if (id < 0 || id > 255) {
            throw new IllegalArgumentException(
                    "Block id must be within 0..255");
        }
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(material, "material");
        textures = Map.copyOf(textures);
        requireFiniteNonNegative("hardness", hardness);
        requireFiniteNonNegative(
                "structuralIntegrity", structuralIntegrity);
        requireFiniteNonNegative("tolerance", tolerance);
        requireFiniteNonNegative(
                "blastResistance", blastResistance);
    }

    public BlockDefinition(
            int id,
            ResourceLocation name,
            ResourceLocation material,
            Map<BlockFace, ResourceLocation> textures,
            float hardness,
            float structuralIntegrity,
            float tolerance,
            boolean gravity,
            boolean flammable,
            float blastResistance,
            ItemFormDefinition item) {
        this(
                id,
                name,
                material,
                textures,
                hardness,
                structuralIntegrity,
                tolerance,
                gravity,
                flammable,
                blastResistance,
                item,
                null);
    }

    public boolean renderable() {
        return id != 0;
    }

    private static void requireFiniteNonNegative(
            String field, float value) {
        if (!Float.isFinite(value) || value < 0.0f) {
            throw new IllegalArgumentException(
                    field + " must be finite and non-negative");
        }
    }
}
