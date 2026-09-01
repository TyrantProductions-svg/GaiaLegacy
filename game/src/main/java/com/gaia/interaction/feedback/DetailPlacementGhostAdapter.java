package com.gaia.interaction.feedback;

import com.gaia.interaction.DetailPlacementPreview;
import com.overlord.assets.ResourceLocation;
import com.overlord.renderer.RenderOrigin;
import com.overlord.renderer.feedback.BlockVisualCoordinate;
import com.overlord.renderer.feedback.TransientBlockVisual;
import com.overlord.renderer.feedback.VisualTransform;
import com.overlord.renderer.feedback.WorldItemFaceRegions;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/** Maps one immutable preview to one owner-thread render-only quarter visual. */
public final class DetailPlacementGhostAdapter {
    private static final float QUARTER = 0.25f;
    private static final float ALPHA = 0.55f;
    private final Function<ResourceLocation, WorldItemFaceRegions> faceResolver;

    public DetailPlacementGhostAdapter(
            Function<ResourceLocation, WorldItemFaceRegions> faceResolver) {
        this.faceResolver = Objects.requireNonNull(faceResolver, "faceResolver");
    }

    public List<TransientBlockVisual> visuals(
            Optional<DetailPlacementPreview> current, RenderOrigin origin) {
        Objects.requireNonNull(current, "current");
        Objects.requireNonNull(origin, "origin");
        if (current.isEmpty()) {
            return List.of();
        }
        DetailPlacementPreview preview = current.orElseThrow();
        long localX = Math.subtractExact((long) preview.parentX(), origin.worldOriginX());
        long localZ = Math.subtractExact((long) preview.parentZ(), origin.worldOriginZ());
        BlockVisualCoordinate coordinate = new BlockVisualCoordinate(
                Math.toIntExact(localX), preview.parentY(), Math.toIntExact(localZ));
        float x = quarterCenterTranslation(preview.localPosition().x());
        float y = quarterCenterTranslation(preview.localPosition().y());
        float z = quarterCenterTranslation(preview.localPosition().z());
        return List.of(new TransientBlockVisual(
                coordinate,
                Objects.requireNonNull(faceResolver.apply(preview.material()), "preview faces"),
                TransientBlockVisual.Type.PREVIEW,
                preview.observedRevision(),
                new VisualTransform(x, y, z, 0, 0, 0, QUARTER, ALPHA)));
    }

    private static float quarterCenterTranslation(int localCoordinate) {
        return (localCoordinate + 0.5f) * QUARTER - 0.5f;
    }

    public List<BlockVisualCoordinate> excludedCells(
            Optional<DetailPlacementPreview> current) {
        Objects.requireNonNull(current, "current");
        return List.of();
    }
}
