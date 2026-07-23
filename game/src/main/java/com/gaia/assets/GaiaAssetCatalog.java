package com.gaia.assets;

import com.gaia.blocks.BlockRegistry;
import com.overlord.assets.AssetLoadReport;
import com.overlord.assets.ResourceLocation;
import com.overlord.renderer.RenderAssets;
import com.overlord.renderer.material.MaterialDefinition;
import com.overlord.renderer.texture.TextureAtlasMetadata;
import java.util.Map;
import java.util.Objects;

public record GaiaAssetCatalog(
        BlockRegistry blockRegistry,
        Map<ResourceLocation, MaterialDefinition> materials,
        Map<ResourceLocation, TextureAtlasMetadata> atlases,
        TextureAtlasMetadata blockAtlas,
        RenderAssets renderAssets,
        AssetLoadReport report) {
    public GaiaAssetCatalog {
        Objects.requireNonNull(blockRegistry, "blockRegistry");
        materials =
                Map.copyOf(
                        Objects.requireNonNull(
                                materials, "materials"));
        atlases =
                Map.copyOf(
                        Objects.requireNonNull(atlases, "atlases"));
        Objects.requireNonNull(blockAtlas, "blockAtlas");
        Objects.requireNonNull(renderAssets, "renderAssets");
        Objects.requireNonNull(report, "report");
    }
}
