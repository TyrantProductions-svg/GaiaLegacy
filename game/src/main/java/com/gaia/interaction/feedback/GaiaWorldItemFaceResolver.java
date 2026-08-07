package com.gaia.interaction.feedback;

import com.gaia.blocks.BlockRegistry;
import com.overlord.assets.ResourceLocation;
import com.overlord.renderer.feedback.WorldItemFaceRegions;
import com.overlord.renderer.texture.TextureRegion;
import com.overlord.voxel.BlockFace;
import com.overlord.voxel.BlockRenderInfo;
import java.util.EnumMap;
import java.util.Objects;

/** Resolves canonical block-item identity to all six immutable atlas faces. */
public final class GaiaWorldItemFaceResolver {
    private final BlockRegistry blocks;
    private final WorldItemFaceRegions missingFaces;
    private final VisualRegionDiagnostics diagnostics;

    public GaiaWorldItemFaceResolver(
            BlockRegistry blocks,
            TextureRegion missingRegion,
            VisualRegionDiagnostics diagnostics) {
        this.blocks = Objects.requireNonNull(blocks, "blocks");
        missingFaces = WorldItemFaceRegions.uniform(
                Objects.requireNonNull(missingRegion, "missingRegion"));
        this.diagnostics = VisualRegionDiagnostics.safe(
                Objects.requireNonNull(diagnostics, "diagnostics"));
    }

    public WorldItemFaceRegions resolve(ResourceLocation itemId) {
        Objects.requireNonNull(itemId, "itemId");
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

    private WorldItemFaceRegions fallback(ResourceLocation itemId, Throwable cause) {
        diagnostics.report(itemId, cause);
        return missingFaces;
    }
}
