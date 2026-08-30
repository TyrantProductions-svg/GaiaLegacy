package com.gaia.interaction;

import com.gaia.blocks.BlockRegistry;
import com.overlord.assets.ResourceLocation;
import com.overlord.interaction.api.BlockHitResult;
import com.overlord.interaction.api.BlockRaycastService;
import com.overlord.interaction.api.SpatialBlockRaycastService;
import com.overlord.physics.BlockRaycast;
import com.overlord.physics.BlockRaycastHit;
import com.overlord.physics.SimulationOrigin;
import com.overlord.physics.SpatialQueryResult;
import java.util.Objects;
import java.util.Optional;
import java.util.function.IntFunction;
import java.util.function.Supplier;
import org.joml.Vector3fc;

/** Identity adapter over the unique Phase 6 shape-aware raycast. */
public final class GaiaBlockRaycastService
        implements BlockRaycastService, SpatialBlockRaycastService {
    private final OriginAwareRaycastDelegate raycast;
    private final IntFunction<ResourceLocation> blockIdentity;

    public GaiaBlockRaycastService(BlockRaycast raycast, BlockRegistry blocks) {
        this(
                availableDelegate(Objects.requireNonNull(raycast, "raycast")::cast),
                registryIdentity(blocks));
    }

    /**
     * Creates a Task 10 origin-aware adapter without owning the origin authority.
     * The supplier is sampled once per query and unavailable space fails closed.
     */
    public GaiaBlockRaycastService(
            BlockRaycast raycast,
            BlockRegistry blocks,
            Supplier<SimulationOrigin> simulationOrigin) {
        this(
                originAwareDelegate(raycast, simulationOrigin),
                registryIdentity(blocks));
    }

    private static IntFunction<ResourceLocation> registryIdentity(BlockRegistry blocks) {
        BlockRegistry registry = Objects.requireNonNull(blocks, "blocks");
        return id -> registry.require(id).name();
    }

    GaiaBlockRaycastService(
            RaycastDelegate raycast,
            IntFunction<ResourceLocation> blockIdentity) {
        this(availableDelegate(raycast), blockIdentity);
    }

    private GaiaBlockRaycastService(
            OriginAwareRaycastDelegate raycast,
            IntFunction<ResourceLocation> blockIdentity) {
        this.raycast = Objects.requireNonNull(raycast, "raycast");
        this.blockIdentity = Objects.requireNonNull(blockIdentity, "blockIdentity");
    }

    static GaiaBlockRaycastService originAware(
            OriginAwareRaycastDelegate raycast,
            IntFunction<ResourceLocation> blockIdentity) {
        return new GaiaBlockRaycastService(
                raycast, blockIdentity);
    }

    private static OriginAwareRaycastDelegate originAwareDelegate(
            BlockRaycast raycast,
            Supplier<SimulationOrigin> simulationOrigin) {
        BlockRaycast requiredRaycast = Objects.requireNonNull(raycast, "raycast");
        Supplier<SimulationOrigin> requiredOrigin =
                Objects.requireNonNull(simulationOrigin, "simulationOrigin");
        return (origin, direction, maxDistance) -> requiredRaycast.cast(
                Objects.requireNonNull(
                        requiredOrigin.get(), "simulationOrigin.get()"),
                origin,
                direction,
                maxDistance);
    }

    private static <T> Optional<T> availableResult(
            SpatialQueryResult<T> query) {
        SpatialQueryResult<T> required =
                Objects.requireNonNull(query, "query");
        if (required.status() != SpatialQueryResult.Status.AVAILABLE) {
            throw new IllegalStateException(
                    "block raycast space is "
                            + required.status()
                            + " at "
                            + required.unavailableKey().orElseThrow());
        }
        return required.result();
    }

    private static OriginAwareRaycastDelegate availableDelegate(
            RaycastDelegate raycast) {
        RaycastDelegate required = Objects.requireNonNull(raycast, "raycast");
        return (origin, direction, maxDistance) -> SpatialQueryResult.available(
                required.cast(origin, direction, maxDistance));
    }

    @Override
    public Optional<BlockHitResult> raycast(
            Vector3fc origin, Vector3fc direction, float maxDistance) {
        return availableResult(query(origin, direction, maxDistance));
    }

    @Override
    public SpatialQueryResult<BlockHitResult> query(
            Vector3fc origin, Vector3fc direction, float maximumDistance) {
        SpatialQueryResult<BlockRaycastHit> result = Objects.requireNonNull(
                raycast.cast(origin, direction, maximumDistance), "raycast result");
        if (result.status() != SpatialQueryResult.Status.AVAILABLE) {
            return SpatialQueryResult.unavailable(
                    result.status(), result.unavailableKey().orElseThrow());
        }
        return SpatialQueryResult.available(result.result().map(this::mapHit));
    }

    private BlockHitResult mapHit(BlockRaycastHit hit) {
        return new BlockHitResult(
                hit.blockX(), hit.blockY(), hit.blockZ(),
                hit.adjacentX(), hit.adjacentY(), hit.adjacentZ(),
                blockIdentity.apply(Byte.toUnsignedInt(hit.blockId())),
                (int) hit.normalX(), (int) hit.normalY(), (int) hit.normalZ(),
                hit.pointX(), hit.pointY(), hit.pointZ(), hit.distance(),
                hit.worldPointX(), hit.worldPointY(), hit.worldPointZ(),
                hit.chunkRevision(), hit.target());
    }

    @FunctionalInterface
    interface RaycastDelegate {
        Optional<BlockRaycastHit> cast(
                Vector3fc origin, Vector3fc direction, float maxDistance);
    }

    @FunctionalInterface
    interface OriginAwareRaycastDelegate {
        SpatialQueryResult<BlockRaycastHit> cast(
                Vector3fc origin, Vector3fc direction, float maxDistance);
    }
}
