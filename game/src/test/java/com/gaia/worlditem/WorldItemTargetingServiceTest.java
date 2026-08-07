package com.gaia.worlditem;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.overlord.assets.ResourceLocation;
import com.overlord.interaction.api.BlockHitResult;
import com.overlord.interaction.api.BlockRaycastService;
import com.overlord.inventory.api.ItemStack;
import com.overlord.worlditem.api.WorldItemId;
import com.overlord.worlditem.api.WorldItemPhysicalSnapshot;
import com.overlord.worlditem.api.WorldItemPhysicalState;
import com.overlord.worlditem.api.WorldItemRuntimeSnapshot;
import com.overlord.worlditem.api.WorldItemSnapshot;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.joml.Vector3f;
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
                .orElseThrow();

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
                eye(), forward(), 3.0f, 10, List.of(miss, atLimit));

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
                .orElseThrow();

        assertEquals(new WorldItemId(1), target.itemId());
        assertEquals(2.5f, target.distance(), 0.0001f);
        assertTrue(targeting(Optional.of(blockHit(3.0f))).target(
                eye(), forward(), 6.0f, 10, List.of(atSurface, behind)).isEmpty());
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
                List.of(delayed, locked, frozen)).isEmpty());
        assertEquals(new WorldItemId(1), service.target(eye(), forward(), 6.0f, 11,
                List.of(delayed, locked, frozen)).orElseThrow().itemId());
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
        assertFalse(service.target(eye(), forward(), 0.0f, 0, candidates).isPresent());
    }

    private static WorldItemTargetingService targeting(Optional<BlockHitResult> hit) {
        BlockRaycastService blocks = (origin, direction, maximumDistance) -> hit;
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
