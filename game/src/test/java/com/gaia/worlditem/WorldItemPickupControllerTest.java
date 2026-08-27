package com.gaia.worlditem;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.overlord.assets.ResourceLocation;
import com.overlord.inventory.api.BodySlot;
import com.overlord.inventory.api.ItemStack;
import com.overlord.physics.Aabb;
import com.overlord.physics.MassProperties;
import com.overlord.physics.PhysicsBody;
import com.overlord.physics.SimulationOrigin;
import com.overlord.physics.SpatialQueryResult;
import com.overlord.renderer.Camera;
import com.overlord.voxel.ChunkKey;
import com.overlord.voxel.GlobalPosition;
import com.overlord.worlditem.api.WorldItemId;
import com.overlord.worlditem.api.WorldItemMotionUpdate;
import com.overlord.worlditem.api.WorldItemMotionUpdateResult;
import com.overlord.worlditem.api.WorldItemPhysicalSnapshot;
import com.overlord.worlditem.api.WorldItemPhysicalState;
import com.overlord.worlditem.api.WorldItemRuntimeAccess;
import com.overlord.worlditem.api.WorldItemRuntimeSnapshot;
import com.overlord.worlditem.api.WorldItemSnapshot;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

final class WorldItemPickupControllerTest {
    @Test
    void onePickupEdgeCapturesAuthoritativeEyeTargetsOnceAndExecutesOnce() {
        WorldItemPhysicalSnapshot candidate = physical(4, 0, 2, -2);
        RuntimeStub runtime = new RuntimeStub(List.of(candidate));
        PhysicsBody body = bodyAt(3, 5, 7);
        Camera camera = new Camera();
        camera.setYaw(0);
        camera.setPitch(0);
        AtomicInteger targetCalls = new AtomicInteger();
        AtomicInteger pickupCalls = new AtomicInteger();
        AtomicReference<Vector3f> eye = new AtomicReference<>();
        AtomicReference<Vector3f> direction = new AtomicReference<>();
        WorldItemPickupResult applied = picked(candidate, 11);

        WorldItemPickupController controller = new WorldItemPickupController(
                runtime,
                body,
                camera,
                () -> BodySlot.RIGHT_HAND,
                (canonicalEye, residentEye, forward, reach, tick, candidates) -> {
                    targetCalls.incrementAndGet();
                    eye.set(new Vector3f(residentEye));
                    direction.set(new Vector3f(forward));
                    assertEquals(new GlobalPosition(
                            new ChunkKey(0, 0), 3.0,
                            (double) (5.0f + 1.62f), 7.0), canonicalEye);
                    assertEquals(List.of(candidate), candidates);
                    return SpatialQueryResult.available(Optional.of(
                            new WorldItemTarget(candidate.id(), candidate, 1.0f)));
                },
                (itemId, slot, tick) -> {
                    pickupCalls.incrementAndGet();
                    assertEquals(candidate.id(), itemId);
                    assertEquals(BodySlot.RIGHT_HAND, slot);
                    assertEquals(11, tick);
                    return applied;
                },
                1.62f,
                3.5f);

        Optional<WorldItemPickupResult> result = controller.fixedUpdate(true, 11);

        assertEquals(applied, result.orElseThrow());
        assertEquals(1, targetCalls.get());
        assertEquals(1, pickupCalls.get());
        assertEquals(new Vector3f(3, 6.62f, 7), eye.get());
        assertEquals(new Vector3f(1, 0, 0), direction.get());
    }

    @Test
    void noEdgeAndFailedTargetNeverExecuteTransaction() {
        RuntimeStub runtime = new RuntimeStub(List.of(physical(4, 0, 2, -2)));
        AtomicInteger targetCalls = new AtomicInteger();
        AtomicInteger pickupCalls = new AtomicInteger();
        WorldItemPickupController controller = new WorldItemPickupController(
                runtime,
                bodyAt(0, 0, 0),
                new Camera(),
                () -> BodySlot.LEFT_HAND,
                (canonicalEye, residentEye, direction, reach, tick, candidates) -> {
                    targetCalls.incrementAndGet();
                    return SpatialQueryResult.available(Optional.empty());
                },
                (itemId, slot, tick) -> {
                    pickupCalls.incrementAndGet();
                    throw new AssertionError("transaction must not run without a target");
                },
                1.62f,
                3.5f);

        assertFalse(controller.fixedUpdate(false, 1).isPresent());
        assertFalse(controller.fixedUpdate(true, 2).isPresent());

        assertEquals(1, targetCalls.get());
        assertEquals(0, pickupCalls.get());
    }

    @Test
    void repeatedHeldStepsRequireASeparateRoutedPressEdge() {
        AtomicInteger pickupCalls = new AtomicInteger();
        WorldItemPhysicalSnapshot candidate = physical(4, 0, 2, -2);
        WorldItemPickupController controller = new WorldItemPickupController(
                new RuntimeStub(List.of(candidate)),
                bodyAt(0, 0, 0),
                new Camera(),
                () -> BodySlot.LEFT_HAND,
                (canonicalEye, residentEye, direction, reach, tick, candidates) ->
                        SpatialQueryResult.available(Optional.of(
                                new WorldItemTarget(candidate.id(), candidate, 1.0f))),
                (itemId, slot, tick) -> {
                    pickupCalls.incrementAndGet();
                    return picked(candidate, tick);
                },
                1.62f,
                3.5f);

        assertTrue(controller.fixedUpdate(true, 1).isPresent());
        assertFalse(controller.fixedUpdate(false, 2).isPresent());
        assertFalse(controller.fixedUpdate(false, 3).isPresent());
        assertEquals(1, pickupCalls.get());
    }

    @Test
    void closeIdempotentlyStopsNewTargetingWork() {
        AtomicInteger targetCalls = new AtomicInteger();
        WorldItemPickupController controller = new WorldItemPickupController(
                new RuntimeStub(List.of()),
                bodyAt(0, 0, 0),
                new Camera(),
                () -> BodySlot.LEFT_HAND,
                (canonicalEye, residentEye, direction, reach, tick, candidates) -> {
                    targetCalls.incrementAndGet();
                    return SpatialQueryResult.available(Optional.empty());
                },
                (itemId, slot, tick) -> { throw new AssertionError(); },
                1.62f,
                3.5f);

        controller.close();
        controller.close();

        assertTrue(controller.fixedUpdate(true, 1).isEmpty());
        assertEquals(0, targetCalls.get());
    }

    @Test
    void unknownOcclusionSuppressesPickupWithoutEscapingFixedStep() {
        unavailableOcclusionSuppressesPickup(SpatialQueryResult.Status.UNKNOWN);
    }

    @Test
    void failedOcclusionSuppressesPickupWithoutEscapingFixedStep() {
        unavailableOcclusionSuppressesPickup(SpatialQueryResult.Status.FAILED);
    }

    @ParameterizedTest
    @CsvSource({"17,-23", "-31,29"})
    void rebasedResidentEyeIsConvertedToExactCanonicalGlobalPosition(
            int originX, int originZ) {
        SimulationOrigin simulationOrigin =
                new SimulationOrigin(new ChunkKey(originX, originZ));
        PhysicsBody body = bodyAt(15.75f, 5.0f, 0.25f);
        Camera camera = new Camera();
        camera.setYaw(180.0f);
        camera.setPitch(30.0f);
        WorldItemPhysicalSnapshot candidate = physical(
                4,
                originX * 16.0 + 15.75,
                6.62,
                originZ * 16.0 + 0.25);
        AtomicReference<GlobalPosition> capturedCanonicalEye = new AtomicReference<>();
        AtomicReference<Vector3f> capturedResidentEye = new AtomicReference<>();

        WorldItemPickupController controller = new WorldItemPickupController(
                new RuntimeStub(List.of(candidate)),
                body,
                camera,
                () -> BodySlot.LEFT_HAND,
                () -> simulationOrigin,
                (canonicalEye, residentEye, direction, reach, tick, candidates) -> {
                    capturedCanonicalEye.set(canonicalEye);
                    capturedResidentEye.set(new Vector3f(residentEye));
                    return SpatialQueryResult.available(Optional.of(
                            new WorldItemTarget(candidate.id(), candidate, 1.0f)));
                },
                (itemId, slot, tick) -> picked(candidate, tick),
                1.62f,
                3.5f);

        assertTrue(controller.fixedUpdate(true, 21).isPresent());
        assertEquals(
                new GlobalPosition(
                        new ChunkKey(originX, originZ), 15.75,
                        (double) (5.0f + 1.62f), 0.25),
                capturedCanonicalEye.get());
        assertEquals(new Vector3f(15.75f, 6.62f, 0.25f), capturedResidentEye.get());
    }

    private static PhysicsBody bodyAt(float x, float y, float z) {
        PhysicsBody body = new PhysicsBody(
                new Aabb(-0.25f, 0, -0.25f, 0.25f, 1.8f, 0.25f),
                MassProperties.dynamic(1));
        body.setPosition(new Vector3f(x, y, z));
        return body;
    }

    private static void unavailableOcclusionSuppressesPickup(
            SpatialQueryResult.Status status) {
        WorldItemPhysicalSnapshot candidate = physical(4, 0, 2, -2);
        AtomicInteger pickupCalls = new AtomicInteger();
        ChunkKey unavailableKey = new ChunkKey(17, 2);
        WorldItemPickupController.TargetOperation targeting =
                (WorldItemPickupController.TargetOperation) Proxy.newProxyInstance(
                        WorldItemPickupController.TargetOperation.class.getClassLoader(),
                        new Class<?>[] {WorldItemPickupController.TargetOperation.class},
                        (proxy, method, arguments) -> SpatialQueryResult.unavailable(
                                status, unavailableKey));
        WorldItemPickupController controller = new WorldItemPickupController(
                new RuntimeStub(List.of(candidate)),
                bodyAt(0, 0, 0),
                new Camera(),
                () -> BodySlot.LEFT_HAND,
                targeting,
                (itemId, slot, tick) -> {
                    pickupCalls.incrementAndGet();
                    throw new AssertionError("unavailable occlusion must suppress pickup");
                },
                1.62f,
                3.5f);

        Optional<WorldItemPickupResult> result = assertDoesNotThrow(
                () -> controller.fixedUpdate(true, 1));

        assertTrue(result.isEmpty());
        assertEquals(0, pickupCalls.get());
    }

    private static WorldItemPhysicalSnapshot physical(long id, double x, double y, double z) {
        WorldItemSnapshot item = new WorldItemSnapshot(
                new WorldItemId(id),
                new ItemStack(ResourceLocation.parse("gaia:dirt"), 1),
                x, y, z, 0, 0, 0, 0);
        return new WorldItemPhysicalSnapshot(
                new WorldItemRuntimeSnapshot(item, Optional.empty(), 0, 0),
                WorldItemPhysicalState.ACTIVE,
                false);
    }

    private static WorldItemPickupResult picked(WorldItemPhysicalSnapshot item, long tick) {
        WorldItemSnapshot snapshot = item.runtime().item();
        WorldItemPickupReceipt receipt = new WorldItemPickupReceipt(
                snapshot.id(), snapshot.stack(), snapshot.positionX(), snapshot.positionY(),
                snapshot.positionZ(), tick);
        return new WorldItemPickupResult(
                WorldItemPickupResult.Status.PICKED_ALL,
                snapshot.id(), 1, 1, 0, Optional.of(receipt), Optional.empty());
    }

    private record RuntimeStub(List<WorldItemPhysicalSnapshot> snapshots)
            implements WorldItemRuntimeAccess {
        private RuntimeStub {
            snapshots = List.copyOf(snapshots);
        }

        @Override
        public List<WorldItemPhysicalSnapshot> physicalSnapshots() {
            return snapshots;
        }

        @Override
        public Optional<WorldItemPhysicalSnapshot> physicalSnapshot(WorldItemId itemId) {
            return snapshots.stream().filter(snapshot -> snapshot.id().equals(itemId)).findFirst();
        }

        @Override
        public WorldItemMotionUpdateResult updateMotion(WorldItemMotionUpdate update) {
            throw new UnsupportedOperationException();
        }
    }
}
