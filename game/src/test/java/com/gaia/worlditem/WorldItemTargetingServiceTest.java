package com.gaia.worlditem;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.overlord.assets.ResourceLocation;
import com.overlord.interaction.api.BlockHitResult;
import com.overlord.interaction.api.SpatialBlockRaycastService;
import com.overlord.inventory.api.ItemStack;
import com.overlord.physics.SpatialQueryResult;
import com.overlord.voxel.ChunkCoordinatePolicy;
import com.overlord.voxel.ChunkKey;
import com.overlord.voxel.GlobalPosition;
import com.overlord.worlditem.api.WorldItemId;
import com.overlord.worlditem.api.WorldItemPhysicalSnapshot;
import com.overlord.worlditem.api.WorldItemPhysicalState;
import com.overlord.worlditem.api.WorldItemRuntimeSnapshot;
import com.overlord.worlditem.api.WorldItemSnapshot;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.joml.Vector3f;
import org.joml.Vector3fc;
import org.junit.jupiter.api.Test;

final class WorldItemTargetingServiceTest {
    private static final ResourceLocation DIRT = ResourceLocation.of("gaia", "dirt");

    @Test
    void nearestVisibleEligibleItemWinsAndStableIdBreaksEqualDistances() {
        WorldItemPhysicalSnapshot farther = physical(1, 0.0, 1.62, -4.0, 0, false,
                WorldItemPhysicalState.ACTIVE);
        WorldItemPhysicalSnapshot higherId = physical(9, 0.0, 1.62, -3.0, 0, false,
                WorldItemPhysicalState.ACTIVE);
        WorldItemPhysicalSnapshot lowerId = physical(4, 0.0, 1.62, -3.0, 0, false,
                WorldItemPhysicalState.ACTIVE);

        WorldItemTarget target = targeting(Optional.empty()).target(
                eye(), forward(), 4.5f, 10, List.of(farther, higherId, lowerId))
                .result().orElseThrow();

        assertEquals(new WorldItemId(4), target.itemId());
        assertEquals(lowerId, target.snapshot());
        assertEquals(2.75f, target.distance(), 0.0001f);
    }

    @Test
    void maximumDistanceEqualityIsEligibleAndAabbMissIsRejected() {
        WorldItemPhysicalSnapshot atLimit = physical(1, 0.0, 1.62, -3.25, 0, false,
                WorldItemPhysicalState.ACTIVE);
        WorldItemPhysicalSnapshot miss = physical(2, 0.26, 1.62, -2.0, 0, false,
                WorldItemPhysicalState.ACTIVE);

        Optional<WorldItemTarget> target = targeting(Optional.empty()).target(
                eye(), forward(), 3.0f, 10, List.of(miss, atLimit)).result();

        assertEquals(new WorldItemId(1), target.orElseThrow().itemId());
        assertEquals(3.0f, target.orElseThrow().distance(), 0.0001f);
    }

    @Test
    void opaqueBlockDistanceIsExclusiveVisibilityCeiling() {
        WorldItemPhysicalSnapshot before = physical(1, 0.0, 1.62, -2.75, 0, false,
                WorldItemPhysicalState.ACTIVE);
        WorldItemPhysicalSnapshot atSurface = physical(2, 0.0, 1.62, -3.25, 0, false,
                WorldItemPhysicalState.ACTIVE);
        WorldItemPhysicalSnapshot behind = physical(3, 0.0, 1.62, -4.0, 0, false,
                WorldItemPhysicalState.ACTIVE);

        WorldItemTarget target = targeting(Optional.of(blockHit(3.0f))).target(
                eye(), forward(), 6.0f, 10, List.of(behind, atSurface, before))
                .result().orElseThrow();

        assertEquals(new WorldItemId(1), target.itemId());
        assertEquals(2.5f, target.distance(), 0.0001f);
        assertTrue(targeting(Optional.of(blockHit(3.0f))).target(
                eye(), forward(), 6.0f, 10, List.of(atSurface, behind))
                .result().isEmpty());
    }

    @Test
    void delayExtractionLockAndFrozenStateAreIneligible() {
        WorldItemPhysicalSnapshot delayed = physical(1, 0.0, 1.62, -2.0, 11, false,
                WorldItemPhysicalState.ACTIVE);
        WorldItemPhysicalSnapshot locked = physical(2, 0.0, 1.62, -2.0, 0, true,
                WorldItemPhysicalState.ACTIVE);
        WorldItemPhysicalSnapshot frozen = physical(3, 0.0, 1.62, -2.0, 0, false,
                WorldItemPhysicalState.FROZEN_UNLOADED);

        WorldItemTargetingService service = targeting(Optional.empty());
        assertTrue(service.target(eye(), forward(), 6.0f, 10,
                List.of(delayed, locked, frozen)).result().isEmpty());
        assertEquals(new WorldItemId(1), service.target(eye(), forward(), 6.0f, 11,
                List.of(delayed, locked, frozen)).result().orElseThrow().itemId());
    }

    @Test
    void candidateInputIsNotModifiedOrReordered() {
        ArrayList<WorldItemPhysicalSnapshot> candidates = new ArrayList<>(List.of(
                physical(9, 0.0, 1.62, -3.0, 0, false, WorldItemPhysicalState.ACTIVE),
                physical(4, 0.0, 1.62, -3.0, 0, false, WorldItemPhysicalState.ACTIVE)));
        List<WorldItemPhysicalSnapshot> before = List.copyOf(candidates);

        targeting(Optional.empty()).target(eye(), forward(), 6.0f, 10, candidates);

        assertEquals(before, candidates);
    }

    @Test
    void invalidRayArgumentsAreRejected() {
        WorldItemTargetingService service = targeting(Optional.empty());
        List<WorldItemPhysicalSnapshot> candidates = List.of();
        assertThrows(IllegalArgumentException.class,
                () -> service.target(eye(), new Vector3f(), 6.0f, 0, candidates));
        assertThrows(IllegalArgumentException.class,
                () -> service.target(eye(), new Vector3f(Float.NaN, 0, 0), 6.0f, 0, candidates));
        assertThrows(IllegalArgumentException.class,
                () -> service.target(eye(), forward(), -1.0f, 0, candidates));
        assertThrows(IllegalArgumentException.class,
                () -> service.target(eye(), forward(), 1.0f, -1, candidates));
        assertFalse(service.target(eye(), forward(), 0.0f, 0, candidates)
                .result().isPresent());
    }

    @Test
    void canonicalIntersectionRetainsPrecisionAtLargeNegativeChunkCoordinates() {
        ChunkKey eyeChunk = new ChunkKey(-120_000_000, 120_000_000);
        GlobalPosition canonicalEye =
                new GlobalPosition(eyeChunk, 15.75, 70.0, 0.25);
        double eyeX = ChunkCoordinatePolicy.worldOriginX(eyeChunk) + 15.75;
        double eyeZ = ChunkCoordinatePolicy.worldOriginZ(eyeChunk) + 0.25;
        WorldItemPhysicalSnapshot candidate = physical(
                91, eyeX, 70.0, eyeZ - 3.0, 0, false,
                WorldItemPhysicalState.ACTIVE);
        AtomicReference<Vector3f> blockOrigin = new AtomicReference<>();
        WorldItemTargetingService service = new WorldItemTargetingService(
                (origin, direction, maximumDistance) -> {
                    blockOrigin.set(new Vector3f(origin));
                    return SpatialQueryResult.available(Optional.empty());
                });

        WorldItemTarget target = service.target(
                canonicalEye,
                new Vector3f(15.75f, 70.0f, 0.25f),
                forward(),
                3.5f,
                10,
                List.of(candidate)).result().orElseThrow();

        assertEquals(new WorldItemId(91), target.itemId());
        assertEquals(2.75f, target.distance(), 0.0001f);
        assertEquals(new Vector3f(15.75f, 70.0f, 0.25f), blockOrigin.get());
    }

    @Test
    void targetResultRetainsAvailableSpatialStatus() {
        WorldItemPhysicalSnapshot candidate = physical(
                4, 0.0, 1.62, -3.0, 0, false,
                WorldItemPhysicalState.ACTIVE);

        Object raw = targeting(Optional.empty()).target(
                eye(), forward(), 4.0f, 10, List.of(candidate));

        SpatialQueryResult<?> query = assertInstanceOf(
                SpatialQueryResult.class, raw,
                "WorldItem targeting must retain the block-occlusion query status");
        assertEquals(SpatialQueryResult.Status.AVAILABLE, query.status());
        assertEquals(new WorldItemId(4),
                ((WorldItemTarget) query.result().orElseThrow()).itemId());
    }

    @Test
    void unknownBlockOcclusionPreservesStatusAndCanonicalKey() {
        ChunkKey unavailableKey = new ChunkKey(17, 2);

        SpatialQueryResult<WorldItemTarget> query = typedTarget(
                SpatialQueryResult.Status.UNKNOWN, unavailableKey);

        assertEquals(SpatialQueryResult.Status.UNKNOWN, query.status());
        assertEquals(Optional.of(unavailableKey), query.unavailableKey());
        assertTrue(query.result().isEmpty());
    }

    @Test
    void failedBlockOcclusionPreservesStatusAndCanonicalKey() {
        ChunkKey unavailableKey = new ChunkKey(-17, -2);

        SpatialQueryResult<WorldItemTarget> query = typedTarget(
                SpatialQueryResult.Status.FAILED, unavailableKey);

        assertEquals(SpatialQueryResult.Status.FAILED, query.status());
        assertEquals(Optional.of(unavailableKey), query.unavailableKey());
        assertTrue(query.result().isEmpty());
    }

    @SuppressWarnings("unchecked")
    private static SpatialQueryResult<WorldItemTarget> typedTarget(
            SpatialQueryResult.Status status, ChunkKey unavailableKey) {
        return assertDoesNotThrow(() -> {
            Class<?> spatialContract = Class.forName(
                    "com.overlord.interaction.api.SpatialBlockRaycastService");
            Object blocks = Proxy.newProxyInstance(
                    spatialContract.getClassLoader(),
                    new Class<?>[] {spatialContract},
                    (proxy, method, arguments) -> SpatialQueryResult.unavailable(
                            status, unavailableKey));
            Object service = WorldItemTargetingService.class
                    .getConstructor(spatialContract)
                    .newInstance(blocks);
            Object raw = WorldItemTargetingService.class.getMethod(
                            "target",
                            GlobalPosition.class,
                            Vector3fc.class,
                            Vector3fc.class,
                            float.class,
                            long.class,
                            List.class)
                    .invoke(
                            service,
                            new GlobalPosition(new ChunkKey(0, 0), 0, 1.62, 0),
                            eye(),
                            forward(),
                            4.0f,
                            10L,
                            List.of(physical(
                                    4, 0.0, 1.62, -3.0, 0, false,
                                    WorldItemPhysicalState.ACTIVE)));
            return (SpatialQueryResult<WorldItemTarget>) assertInstanceOf(
                    SpatialQueryResult.class, raw);
        });
    }

    private static WorldItemTargetingService targeting(Optional<BlockHitResult> hit) {
        SpatialBlockRaycastService blocks = (origin, direction, maximumDistance) ->
                SpatialQueryResult.available(hit);
        return new WorldItemTargetingService(blocks);
    }

    private static WorldItemPhysicalSnapshot physical(
            long id,
            double x,
            double y,
            double z,
            long pickupAvailableTick,
            boolean extractionReserved,
            WorldItemPhysicalState state) {
        WorldItemSnapshot item = new WorldItemSnapshot(
                new WorldItemId(id), new ItemStack(DIRT, 1), x, y, z,
                0, 0, 0, 0);
        return new WorldItemPhysicalSnapshot(
                new WorldItemRuntimeSnapshot(item, Optional.empty(), 0, pickupAvailableTick),
                state,
                extractionReserved);
    }

    private static Vector3f eye() {
        return new Vector3f(0.0f, 1.62f, 0.0f);
    }

    private static Vector3f forward() {
        return new Vector3f(0.0f, 0.0f, -1.0f);
    }

    private static BlockHitResult blockHit(float distance) {
        return new BlockHitResult(0, 1, -3, 0, 1, -2, DIRT,
                0, 0, 1, 0.0f, 1.62f, -distance, distance);
    }
}
