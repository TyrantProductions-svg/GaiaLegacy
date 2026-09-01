package com.gaia.interaction;

import com.overlord.assets.ResourceLocation;
import com.overlord.interaction.api.BlockFace;
import com.overlord.physics.Aabb;
import com.overlord.voxel.LocalSubVoxelPosition;
import java.util.Objects;
import java.util.Optional;

/** Immutable presentation input derived from one canonical precision observation. */
public record DetailPlacementPreview(
        BlockInteractionRoute action,
        ResourceLocation tool,
        DetailPrecisionTarget source,
        int parentX,
        int parentY,
        int parentZ,
        LocalSubVoxelPosition localPosition,
        BlockFace face,
        ResourceLocation material,
        long observedRevision,
        DetailPreviewValidity validity,
        Optional<String> reason,
        Aabb quarterBounds) {
    private static final int MAX_REASON_LENGTH = 64;

    public DetailPlacementPreview {
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(tool, "tool");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(localPosition, "localPosition");
        Objects.requireNonNull(face, "face");
        Objects.requireNonNull(material, "material");
        Objects.requireNonNull(validity, "validity");
        reason = Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(quarterBounds, "quarterBounds");
        if (action != BlockInteractionRoute.DETAIL_PRECISION_REMOVE
                && action != BlockInteractionRoute.DETAIL_PRECISION_PLACE) {
            throw new IllegalArgumentException("preview action must be precision remove or place");
        }
        if (observedRevision < 0) {
            throw new IllegalArgumentException("observedRevision must be nonnegative");
        }
        reason.ifPresent(value -> {
            if (value.isBlank() || value.length() > MAX_REASON_LENGTH) {
                throw new IllegalArgumentException("preview reason must be bounded and nonblank");
            }
        });
        if ((validity == DetailPreviewValidity.VALID) == reason.isPresent()) {
            throw new IllegalArgumentException("valid preview has no reason; invalid preview requires one");
        }
    }

    public boolean valid() {
        return validity == DetailPreviewValidity.VALID;
    }

    public static DetailPlacementPreview forPlacement(
            ResourceLocation tool, DetailPlacementCandidate candidate) {
        Objects.requireNonNull(candidate, "candidate");
        DetailPreviewValidity validity = switch (candidate.status()) {
            case VALID_DETAIL_EMPTY, VALID_FULL_AIR -> DetailPreviewValidity.VALID;
            case OCCUPIED -> DetailPreviewValidity.OCCUPIED;
            case UNKNOWN -> DetailPreviewValidity.UNKNOWN;
            case FAILED -> DetailPreviewValidity.FAILED;
            case OUT_OF_BOUNDS -> DetailPreviewValidity.OUT_OF_BOUNDS;
        };
        Optional<String> reason = validity == DetailPreviewValidity.VALID
                ? Optional.empty()
                : Optional.of(candidate.status().name().toLowerCase());
        return create(
                BlockInteractionRoute.DETAIL_PRECISION_PLACE,
                tool,
                candidate.source(),
                candidate.parentX(), candidate.parentY(), candidate.parentZ(),
                candidate.localPosition(), candidate.material(),
                candidate.source().observedChunkRevision(), validity, reason);
    }

    public static DetailPlacementPreview forRemoval(
            ResourceLocation tool, DetailPrecisionTarget target) {
        Objects.requireNonNull(target, "target");
        return create(
                BlockInteractionRoute.DETAIL_PRECISION_REMOVE,
                tool,
                target,
                target.parentX(), target.parentY(), target.parentZ(),
                target.localPosition(), target.material(),
                target.observedChunkRevision(), DetailPreviewValidity.VALID, Optional.empty());
    }

    private static DetailPlacementPreview create(
            BlockInteractionRoute action,
            ResourceLocation tool,
            DetailPrecisionTarget source,
            int parentX,
            int parentY,
            int parentZ,
            LocalSubVoxelPosition local,
            ResourceLocation material,
            long revision,
            DetailPreviewValidity validity,
            Optional<String> reason) {
        float quarter = 1.0f / 4.0f;
        float minX = local.x() * quarter;
        float minY = local.y() * quarter;
        float minZ = local.z() * quarter;
        return new DetailPlacementPreview(
                action, tool, source, parentX, parentY, parentZ, local, source.face(), material,
                revision, validity, reason,
                new Aabb(minX, minY, minZ, minX + quarter, minY + quarter, minZ + quarter));
    }

}
