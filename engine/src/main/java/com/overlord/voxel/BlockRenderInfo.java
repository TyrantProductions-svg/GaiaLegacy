package com.overlord.voxel;

import com.overlord.renderer.material.MaterialDefinition;
import com.overlord.renderer.texture.TextureRegion;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

public record BlockRenderInfo(
        MaterialDefinition material,
        Map<BlockFace, TextureRegion> regions,
        boolean renderable) {
    public BlockRenderInfo {
        Objects.requireNonNull(material, "material");
        Objects.requireNonNull(regions, "regions");
        EnumMap<BlockFace, TextureRegion> copied =
                new EnumMap<>(BlockFace.class);
        for (Map.Entry<BlockFace, TextureRegion> entry
                : regions.entrySet()) {
            copied.put(
                    Objects.requireNonNull(
                            entry.getKey(), "region face"),
                    Objects.requireNonNull(
                            entry.getValue(), "texture region"));
        }
        if (renderable) {
            for (BlockFace face : BlockFace.values()) {
                if (!copied.containsKey(face)) {
                    throw new IllegalArgumentException(
                            "Renderable blocks require all six faces");
                }
            }
        }
        regions = Collections.unmodifiableMap(copied);
    }

    public TextureRegion region(BlockFace face) {
        Objects.requireNonNull(face, "face");
        TextureRegion region = regions.get(face);
        if (region == null) {
            throw new IllegalArgumentException(
                    "Unknown block face: " + face);
        }
        return region;
    }

    public static BlockRenderInfo nonRenderable(
            MaterialDefinition material,
            TextureRegion fallback) {
        Objects.requireNonNull(fallback, "fallback");
        EnumMap<BlockFace, TextureRegion> regions =
                new EnumMap<>(BlockFace.class);
        for (BlockFace face : BlockFace.values()) {
            regions.put(face, fallback);
        }
        return new BlockRenderInfo(material, regions, false);
    }
}
