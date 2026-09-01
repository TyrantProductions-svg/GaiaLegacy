package com.gaia.interaction.feedback;

import com.gaia.blocks.BlockRegistry;
import com.gaia.blocks.ItemVisualReference;
import com.gaia.blocks.ItemVisualType;
import com.overlord.assets.ResourceLocation;
import com.overlord.renderer.texture.TextureAtlasMetadata;
import com.overlord.renderer.texture.TextureRegion;
import com.overlord.voxel.BlockFace;
import java.util.Objects;

/** Resolves canonical Gaia item identities to existing block-atlas regions. */
public final class GaiaVisualRegionResolver {
    private static final ResourceLocation MISSING_REGION =
            ResourceLocation.parse("gaia:missing");

    private final BlockRegistry blocks;
    private final TextureAtlasMetadata blockAtlas;
    private final TextureRegion missingRegion;
    private final VisualRegionDiagnostics diagnostics;

    public GaiaVisualRegionResolver(
            BlockRegistry blocks,
            TextureAtlasMetadata blockAtlas,
            VisualRegionDiagnostics diagnostics) {
        this.blocks = Objects.requireNonNull(blocks, "blocks");
        this.blockAtlas = Objects.requireNonNull(blockAtlas, "blockAtlas");
        this.missingRegion = blockAtlas.requireRegion(MISSING_REGION);
        this.diagnostics = VisualRegionDiagnostics.safe(
                Objects.requireNonNull(diagnostics, "diagnostics"));
    }

    public TextureRegion resolve(ResourceLocation itemId) {
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
        if (!block.renderable()) {
            return fallback(
                    itemId,
                    new IllegalStateException("Non-renderable block for item: " + itemId));
        }
        ResourceLocation upTexture = block.textures().get(BlockFace.UP);
        if (upTexture == null) {
            return fallback(
                    itemId,
                    new IllegalStateException("Missing UP texture for item: " + itemId));
        }
        TextureRegion region = blockAtlas.regions().get(upTexture);
        if (region == null) {
            return fallback(
                    itemId,
                    new IllegalStateException(
                            "Missing atlas region " + upTexture + " for item: " + itemId));
        }
        return region;
    }

    private TextureRegion resolveExplicit(
            ResourceLocation itemId,
            ItemVisualReference visual) {
        if (visual.type() != ItemVisualType.ATLAS_REGION) {
            return fallback(
                    itemId,
                    new IllegalStateException(
                            "Unsupported item visual type " + visual.type()));
        }
        if (!blockAtlas.id().equals(visual.atlas())) {
            return fallback(
                    itemId,
                    new IllegalStateException(
                            "Missing item atlas " + visual.atlas()));
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
        return region;
    }

    private TextureRegion fallback(ResourceLocation itemId, Throwable cause) {
        diagnostics.report(itemId, cause);
        return missingRegion;
    }
}
