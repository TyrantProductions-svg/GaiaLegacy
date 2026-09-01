package com.gaia.interaction.feedback;

import com.gaia.blocks.BlockRegistry;
import com.gaia.blocks.ItemVisualReference;
import com.gaia.blocks.ItemVisualType;
import com.overlord.assets.ResourceLocation;
import com.overlord.renderer.feedback.WorldItemFaceRegions;
import com.overlord.renderer.texture.TextureAtlasMetadata;
import com.overlord.renderer.texture.TextureRegion;
import com.overlord.voxel.BlockFace;
import com.overlord.voxel.BlockRenderInfo;
import java.util.EnumMap;
import java.util.Objects;

/** Resolves canonical block-item identity to all six immutable atlas faces. */
public final class GaiaWorldItemFaceResolver {
    private final BlockRegistry blocks;
    private final TextureAtlasMetadata blockAtlas;
    private final WorldItemFaceRegions missingFaces;
    private final VisualRegionDiagnostics diagnostics;

    public GaiaWorldItemFaceResolver(
            BlockRegistry blocks,
            TextureRegion missingRegion,
            VisualRegionDiagnostics diagnostics) {
        this.blocks = Objects.requireNonNull(blocks, "blocks");
        this.blockAtlas = null;
        missingFaces = WorldItemFaceRegions.uniform(
                Objects.requireNonNull(missingRegion, "missingRegion"));
        this.diagnostics = VisualRegionDiagnostics.safe(
                Objects.requireNonNull(diagnostics, "diagnostics"));
    }

    public GaiaWorldItemFaceResolver(
            BlockRegistry blocks,
            TextureAtlasMetadata blockAtlas,
            VisualRegionDiagnostics diagnostics) {
        this.blocks = Objects.requireNonNull(blocks, "blocks");
        this.blockAtlas = Objects.requireNonNull(blockAtlas, "blockAtlas");
        missingFaces = WorldItemFaceRegions.uniform(
                blockAtlas.requireRegion(
                        ResourceLocation.parse("gaia:missing")));
        this.diagnostics = VisualRegionDiagnostics.safe(
                Objects.requireNonNull(diagnostics, "diagnostics"));
    }

    public WorldItemFaceRegions resolve(ResourceLocation itemId) {
        Objects.requireNonNull(itemId, "itemId");
        ItemVisualReference explicitVisual =
                blocks.itemVisual(itemId).orElse(null);
        if (explicitVisual != null) {
            return resolveExplicit(itemId, explicitVisual);
        }
        var block = blocks.blockForItem(itemId).orElse(null);
        if (block == null) {
            return fallback(itemId, new IllegalArgumentException("Unknown item: " + itemId));
        }
        BlockRenderInfo renderInfo = blocks.resolve(block.id());
        if (!block.renderable() || !renderInfo.renderable()) {
            return fallback(
                    itemId,
                    new IllegalStateException("Non-renderable block for item: " + itemId));
        }
        EnumMap<BlockFace, TextureRegion> regions = new EnumMap<>(BlockFace.class);
        for (BlockFace face : BlockFace.values()) {
            regions.put(face, renderInfo.region(face));
        }
        return new WorldItemFaceRegions(regions);
    }

    private WorldItemFaceRegions resolveExplicit(
            ResourceLocation itemId,
            ItemVisualReference visual) {
        if (visual.type() != ItemVisualType.ATLAS_REGION
                || blockAtlas == null
                || !blockAtlas.id().equals(visual.atlas())) {
            return fallback(
                    itemId,
                    new IllegalStateException(
                            "Unsupported standalone item visual for " + itemId));
        }
        TextureRegion region = blockAtlas.regions().get(visual.region());
        if (region == null) {
            return fallback(
                    itemId,
                    new IllegalStateException(
                            "Missing atlas region "
                                    + visual.region()
                                    + " for item: "
                                    + itemId));
        }
        return WorldItemFaceRegions.uniform(region);
    }

    private WorldItemFaceRegions fallback(ResourceLocation itemId, Throwable cause) {
        diagnostics.report(itemId, cause);
        return missingFaces;
    }
}
