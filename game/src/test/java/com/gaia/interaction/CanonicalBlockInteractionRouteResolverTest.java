package com.gaia.interaction;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.gaia.blocks.ItemCapability;
import com.overlord.assets.ResourceLocation;
import com.overlord.interaction.api.BlockHitResult;
import com.overlord.physics.DetailRaycastTarget;
import com.overlord.physics.FullRaycastTarget;
import com.overlord.physics.RaycastCellTarget;
import com.overlord.physics.SpatialQueryResult;
import com.overlord.voxel.ChunkKey;
import com.overlord.voxel.LocalSubVoxelPosition;
import com.overlord.voxel.VoxelScale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class CanonicalBlockInteractionRouteResolverTest {
    private static final ResourceLocation ORDINARY =
            ResourceLocation.parse("gaia:stone");
    private static final ResourceLocation CHISEL =
            ResourceLocation.parse("gaia:chisel");
    private final CanonicalBlockInteractionRouteResolver resolver =
            new CanonicalBlockInteractionRouteResolver();

    @ParameterizedTest(name = "{0}")
    @MethodSource("routes")
    void resolvesOneDeterministicCanonicalRoute(
            String name,
            BlockInteractionRouteRequest request,
            BlockInteractionRoute expected) {
        assertEquals(expected, resolver.resolve(request).route());
    }

    private static Stream<Arguments> routes() {
        return Stream.of(
                Arguments.of(
                        "ordinary FULL primary",
                        request(fullHit(), ordinary(), true, false, false),
                        BlockInteractionRoute.FULL_NORMAL),
                Arguments.of(
                        "ordinary FULL secondary",
                        request(fullHit(), ordinary(), false, true, false),
                        BlockInteractionRoute.FULL_NORMAL),
                Arguments.of(
                        "ordinary DETAIL primary is coarse",
                        request(detailHit(), ordinary(), true, false, false),
                        BlockInteractionRoute.DETAIL_COARSE_REMOVE),
                Arguments.of(
                        "ordinary DETAIL secondary is rejected",
                        request(detailHit(), ordinary(), false, true, false),
                        BlockInteractionRoute.REJECTED),
                Arguments.of(
                        "chisel FULL primary",
                        request(fullHit(), precision(), true, false, false),
                        BlockInteractionRoute.DETAIL_PRECISION_REMOVE),
                Arguments.of(
                        "chisel FULL secondary",
                        request(fullHit(), precision(), false, true, false),
                        BlockInteractionRoute.DETAIL_PRECISION_PLACE),
                Arguments.of(
                        "chisel DETAIL primary",
                        request(detailHit(), precision(), true, false, false),
                        BlockInteractionRoute.DETAIL_PRECISION_REMOVE),
                Arguments.of(
                        "chisel DETAIL secondary",
                        request(detailHit(), precision(), false, true, false),
                        BlockInteractionRoute.DETAIL_PRECISION_PLACE),
                Arguments.of(
                        "simultaneous edges choose primary once",
                        request(detailHit(), precision(), true, true, false),
                        BlockInteractionRoute.DETAIL_PRECISION_REMOVE),
                Arguments.of(
                        "pickup consumes secondary before block route",
                        request(detailHit(), precision(), false, true, true),
                        BlockInteractionRoute.REJECTED),
                Arguments.of(
                        "available no target",
                        new BlockInteractionRouteRequest(
                                GameMode.SURVIVAL,
                                Optional.of(CHISEL),
                                Set.of(ItemCapability.DETAIL_PRECISION),
                                SpatialQueryResult.available(Optional.empty()),
                                new BlockInteractionIntent(true, false),
                                false),
                        BlockInteractionRoute.REJECTED),
                Arguments.of(
                        "UNKNOWN is unavailable",
                        unavailable(SpatialQueryResult.Status.UNKNOWN),
                        BlockInteractionRoute.UNAVAILABLE),
                Arguments.of(
                        "FAILED is unavailable",
                        unavailable(SpatialQueryResult.Status.FAILED),
                        BlockInteractionRoute.UNAVAILABLE),
                Arguments.of(
                        "no pressed edge",
                        request(detailHit(), precision(), false, false, false),
                        BlockInteractionRoute.REJECTED));
    }

    private static BlockInteractionRouteRequest request(
            BlockHitResult hit,
            ActiveItem active,
            boolean primary,
            boolean secondary,
            boolean pickupConsumed) {
        return new BlockInteractionRouteRequest(
                GameMode.SURVIVAL,
                Optional.of(active.id()),
                active.capabilities(),
                SpatialQueryResult.available(Optional.of(hit)),
                new BlockInteractionIntent(primary, secondary),
                pickupConsumed);
    }

    private static BlockInteractionRouteRequest unavailable(
            SpatialQueryResult.Status status) {
        return new BlockInteractionRouteRequest(
                GameMode.CREATIVE,
                Optional.of(CHISEL),
                Set.of(ItemCapability.DETAIL_PRECISION),
                SpatialQueryResult.unavailable(status, new ChunkKey(-1, 2)),
                new BlockInteractionIntent(true, false),
                false);
    }

    private static ActiveItem ordinary() {
        return new ActiveItem(ORDINARY, Set.of());
    }

    private static ActiveItem precision() {
        return new ActiveItem(
                CHISEL, Set.of(ItemCapability.DETAIL_PRECISION));
    }

    private static BlockHitResult fullHit() {
        return hit(FullRaycastTarget.INSTANCE, 0L);
    }

    private static BlockHitResult detailHit() {
        return hit(
                new DetailRaycastTarget(
                        VoxelScale.DETAIL_4,
                        new LocalSubVoxelPosition(1, 2, 3)),
                7L);
    }

    private static BlockHitResult hit(
            RaycastCellTarget target, long revision) {
        return new BlockHitResult(
                1, 2, 3,
                2, 2, 3,
                ORDINARY,
                1, 0, 0,
                2.0f, 2.5f, 3.5f,
                1.0f,
                2.0, 2.5, 3.5,
                revision,
                target);
    }

    private record ActiveItem(
            ResourceLocation id,
            Set<ItemCapability> capabilities) {}
}
