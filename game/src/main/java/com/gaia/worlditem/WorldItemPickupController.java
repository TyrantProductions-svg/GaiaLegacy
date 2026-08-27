package com.gaia.worlditem;

import com.gaia.interaction.feedback.CommittedPickupVisualAdapter;
import com.gaia.interaction.feedback.CommittedGameplayFeedback;
import com.overlord.inventory.api.BodySlot;
import com.overlord.physics.PhysicsBody;
import com.overlord.physics.SimulationOrigin;
import com.overlord.physics.SpatialQueryResult;
import com.overlord.renderer.Camera;
import com.overlord.voxel.ChunkKey;
import com.overlord.voxel.GlobalPosition;
import com.overlord.worlditem.api.WorldItemId;
import com.overlord.worlditem.api.WorldItemPhysicalSnapshot;
import com.overlord.worlditem.api.WorldItemRuntimeAccess;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.joml.Vector3f;
import org.joml.Vector3fc;

/** One-edge fixed-step coordinator over authoritative player and runtime state. */
public final class WorldItemPickupController implements AutoCloseable {
    private final WorldItemRuntimeAccess runtime;
    private final PhysicsBody playerBody;
    private final Camera camera;
    private final Supplier<BodySlot> activeSlot;
    private final Supplier<SimulationOrigin> simulationOrigin;
    private final TargetOperation targeting;
    private final PickupOperation pickup;
    private final Consumer<WorldItemPickupResult> committedFeedback;
    private final float eyeHeight;
    private final float reach;
    private final Vector3f eyeScratch = new Vector3f();
    private final Vector3f directionScratch = new Vector3f();
    private boolean closed;
    private Optional<UnavailableOcclusionObservation> unavailableOcclusion = Optional.empty();

    public WorldItemPickupController(
            WorldItemRuntimeAccess runtime,
            PhysicsBody playerBody,
            Camera camera,
            Supplier<BodySlot> activeSlot,
            WorldItemTargetingService targeting,
            WorldItemPickupTransaction pickup,
            CommittedPickupVisualAdapter committedFeedback,
            float eyeHeight,
            float reach) {
        this(runtime, playerBody, camera, activeSlot, zeroSimulationOrigin(),
                canonicalTarget(Objects.requireNonNull(targeting, "targeting")),
                Objects.requireNonNull(pickup, "pickup")::execute,
                Objects.requireNonNull(committedFeedback, "committedFeedback")::onPickup,
                eyeHeight, reach);
    }

    public WorldItemPickupController(
            WorldItemRuntimeAccess runtime,
            PhysicsBody playerBody,
            Camera camera,
            Supplier<BodySlot> activeSlot,
            Supplier<SimulationOrigin> simulationOrigin,
            WorldItemTargetingService targeting,
            WorldItemPickupTransaction pickup,
            CommittedPickupVisualAdapter committedFeedback,
            float eyeHeight,
            float reach) {
        this(runtime, playerBody, camera, activeSlot, simulationOrigin,
                canonicalTarget(Objects.requireNonNull(targeting, "targeting")),
                Objects.requireNonNull(pickup, "pickup")::execute,
                Objects.requireNonNull(committedFeedback, "committedFeedback")::onPickup,
                eyeHeight, reach);
    }

    public WorldItemPickupController(
            WorldItemRuntimeAccess runtime,
            PhysicsBody playerBody,
            Camera camera,
            Supplier<BodySlot> activeSlot,
            WorldItemTargetingService targeting,
            WorldItemPickupTransaction pickup,
            CommittedGameplayFeedback committedFeedback,
            float eyeHeight,
            float reach) {
        this(runtime, playerBody, camera, activeSlot, zeroSimulationOrigin(),
                canonicalTarget(Objects.requireNonNull(targeting, "targeting")),
                Objects.requireNonNull(pickup, "pickup")::execute,
                result -> result.committedReceipt().ifPresent(
                        Objects.requireNonNull(
                                committedFeedback, "committedFeedback")::onPickupCommitted),
                eyeHeight, reach);
    }

    public WorldItemPickupController(
            WorldItemRuntimeAccess runtime,
            PhysicsBody playerBody,
            Camera camera,
            Supplier<BodySlot> activeSlot,
            Supplier<SimulationOrigin> simulationOrigin,
            WorldItemTargetingService targeting,
            WorldItemPickupTransaction pickup,
            CommittedGameplayFeedback committedFeedback,
            float eyeHeight,
            float reach) {
        this(runtime, playerBody, camera, activeSlot, simulationOrigin,
                canonicalTarget(Objects.requireNonNull(targeting, "targeting")),
                Objects.requireNonNull(pickup, "pickup")::execute,
                result -> result.committedReceipt().ifPresent(
                        Objects.requireNonNull(
                                committedFeedback, "committedFeedback")::onPickupCommitted),
                eyeHeight, reach);
    }

    WorldItemPickupController(
            WorldItemRuntimeAccess runtime,
            PhysicsBody playerBody,
            Camera camera,
            Supplier<BodySlot> activeSlot,
            TargetOperation targeting,
            PickupOperation pickup,
            float eyeHeight,
            float reach) {
        this(runtime, playerBody, camera, activeSlot, zeroSimulationOrigin(), targeting, pickup,
                ignored -> {}, eyeHeight, reach);
    }

    WorldItemPickupController(
            WorldItemRuntimeAccess runtime,
            PhysicsBody playerBody,
            Camera camera,
            Supplier<BodySlot> activeSlot,
            Supplier<SimulationOrigin> simulationOrigin,
            TargetOperation targeting,
            PickupOperation pickup,
            float eyeHeight,
            float reach) {
        this(runtime, playerBody, camera, activeSlot, simulationOrigin, targeting, pickup,
                ignored -> {}, eyeHeight, reach);
    }

    WorldItemPickupController(
            WorldItemRuntimeAccess runtime,
            PhysicsBody playerBody,
            Camera camera,
            Supplier<BodySlot> activeSlot,
            TargetOperation targeting,
            PickupOperation pickup,
            Consumer<WorldItemPickupResult> committedFeedback,
            float eyeHeight,
            float reach) {
        this(runtime, playerBody, camera, activeSlot, zeroSimulationOrigin(),
                targeting, pickup, committedFeedback, eyeHeight, reach);
    }

    WorldItemPickupController(
            WorldItemRuntimeAccess runtime,
            PhysicsBody playerBody,
            Camera camera,
            Supplier<BodySlot> activeSlot,
            Supplier<SimulationOrigin> simulationOrigin,
            TargetOperation targeting,
            PickupOperation pickup,
            Consumer<WorldItemPickupResult> committedFeedback,
            float eyeHeight,
            float reach) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.playerBody = Objects.requireNonNull(playerBody, "playerBody");
        this.camera = Objects.requireNonNull(camera, "camera");
        this.activeSlot = Objects.requireNonNull(activeSlot, "activeSlot");
        this.simulationOrigin = Objects.requireNonNull(
                simulationOrigin, "simulationOrigin");
        this.targeting = Objects.requireNonNull(targeting, "targeting");
        this.pickup = Objects.requireNonNull(pickup, "pickup");
        this.committedFeedback = Objects.requireNonNull(
                committedFeedback, "committedFeedback");
        if (!Float.isFinite(eyeHeight) || eyeHeight < 0.0f) {
            throw new IllegalArgumentException("eyeHeight must be finite and non-negative");
        }
        if (!Float.isFinite(reach) || reach < 0.0f) {
            throw new IllegalArgumentException("reach must be finite and non-negative");
        }
        this.eyeHeight = eyeHeight;
        this.reach = reach;
    }

    public Optional<WorldItemPickupResult> fixedUpdate(boolean pickupPressed, long tick) {
        if (tick < 0) {
            throw new IllegalArgumentException("tick must be non-negative");
        }
        if (closed || !pickupPressed) {
            return Optional.empty();
        }
        playerBody.position(eyeScratch);
        eyeScratch.y += eyeHeight;
        camera.getForward(directionScratch);
        GlobalPosition canonicalEye = Objects.requireNonNull(
                simulationOrigin.get(), "simulationOrigin.get()")
                .toGlobal(eyeScratch);
        List<WorldItemPhysicalSnapshot> candidates = runtime.physicalSnapshots();
        SpatialQueryResult<WorldItemTarget> targetQuery = Objects.requireNonNull(
                targeting.target(
                        canonicalEye,
                        eyeScratch,
                        directionScratch,
                        reach,
                        tick,
                        candidates),
                "targeting result");
        if (targetQuery.status() != SpatialQueryResult.Status.AVAILABLE) {
            unavailableOcclusion = Optional.of(new UnavailableOcclusionObservation(
                    targetQuery.status(), targetQuery.unavailableKey().orElseThrow()));
            return Optional.empty();
        }
        unavailableOcclusion = Optional.empty();
        Optional<WorldItemTarget> target = targetQuery.result();
        if (target.isEmpty()) {
            return Optional.empty();
        }
        WorldItemTarget selected = target.orElseThrow();
        WorldItemPickupResult result = pickup.execute(
                selected.itemId(), activeSlot.get(), tick);
        committedFeedback.accept(result);
        return Optional.of(result);
    }

    @Override
    public void close() {
        closed = true;
        unavailableOcclusion = Optional.empty();
    }

    public Optional<UnavailableOcclusionObservation> unavailableOcclusion() {
        return unavailableOcclusion;
    }

    @FunctionalInterface
    interface TargetOperation {
        SpatialQueryResult<WorldItemTarget> target(
                GlobalPosition canonicalEye,
                Vector3fc residentEye,
                Vector3fc direction,
                float reach,
                long tick,
                List<WorldItemPhysicalSnapshot> candidates);
    }

    private static Supplier<SimulationOrigin> zeroSimulationOrigin() {
        SimulationOrigin zero = new SimulationOrigin(new ChunkKey(0, 0));
        return () -> zero;
    }

    private static TargetOperation canonicalTarget(
            WorldItemTargetingService targeting) {
        return targeting::target;
    }

    @FunctionalInterface
    interface PickupOperation {
        WorldItemPickupResult execute(WorldItemId itemId, BodySlot activeSlot, long tick);
    }

    public record UnavailableOcclusionObservation(
            SpatialQueryResult.Status status,
            ChunkKey key) {
        public UnavailableOcclusionObservation {
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(key, "key");
            if (status == SpatialQueryResult.Status.AVAILABLE) {
                throw new IllegalArgumentException(
                        "unavailable occlusion status cannot be AVAILABLE");
            }
        }
    }
}
