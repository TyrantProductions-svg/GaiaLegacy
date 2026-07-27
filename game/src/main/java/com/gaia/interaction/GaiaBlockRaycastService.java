package com.gaia.interaction;

import com.gaia.blocks.BlockRegistry;
import com.overlord.assets.ResourceLocation;
import com.overlord.interaction.api.BlockHitResult;
import com.overlord.interaction.api.BlockRaycastService;
import com.overlord.physics.BlockRaycast;
import com.overlord.physics.BlockRaycastHit;
import java.util.Objects;
import java.util.Optional;
import java.util.function.IntFunction;
import org.joml.Vector3fc;

/** Identity adapter over the unique Phase 6 shape-aware raycast. */
public final class GaiaBlockRaycastService implements BlockRaycastService {
    private final RaycastDelegate raycast;
    private final IntFunction<ResourceLocation> blockIdentity;

    public GaiaBlockRaycastService(BlockRaycast raycast, BlockRegistry blocks) {
        this(
                Objects.requireNonNull(raycast, "raycast")::cast,
                registryIdentity(blocks));
    }

    private static IntFunction<ResourceLocation> registryIdentity(BlockRegistry blocks) {
        BlockRegistry registry = Objects.requireNonNull(blocks, "blocks");
        return id -> registry.require(id).name();
    }

    GaiaBlockRaycastService(
            RaycastDelegate raycast,
            IntFunction<ResourceLocation> blockIdentity) {
        this.raycast = Objects.requireNonNull(raycast, "raycast");
        this.blockIdentity = Objects.requireNonNull(blockIdentity, "blockIdentity");
    }

    @Override
    public Optional<BlockHitResult> raycast(
            Vector3fc origin, Vector3fc direction, float maxDistance) {
        return raycast.cast(origin, direction, maxDistance).map(this::mapHit);
    }

    private BlockHitResult mapHit(BlockRaycastHit hit) {
        return new BlockHitResult(
                hit.blockX(), hit.blockY(), hit.blockZ(),
                hit.adjacentX(), hit.adjacentY(), hit.adjacentZ(),
                blockIdentity.apply(Byte.toUnsignedInt(hit.blockId())),
                (int) hit.normalX(), (int) hit.normalY(), (int) hit.normalZ(),
                hit.pointX(), hit.pointY(), hit.pointZ(), hit.distance());
    }

    @FunctionalInterface
    interface RaycastDelegate {
        Optional<BlockRaycastHit> cast(
                Vector3fc origin, Vector3fc direction, float maxDistance);
    }
}
