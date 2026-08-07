package com.gaia.worlditem;

import com.gaia.interaction.feedback.CommittedPickupVisualAdapter;
import com.gaia.interaction.feedback.CommittedGameplayFeedback;
import com.overlord.inventory.api.BodySlot;
import com.overlord.physics.PhysicsBody;
import com.overlord.renderer.Camera;
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
    private final TargetOperation targeting;
    private final PickupOperation pickup;
    private final Consumer<WorldItemPickupResult> committedFeedback;
    private final float eyeHeight;
    private final float reach;
    private final Vector3f eyeScratch = new Vector3f();
    private final Vector3f directionScratch = new Vector3f();
    private boolean closed;

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
        this(runtime, playerBody, camera, activeSlot,
                Objects.requireNonNull(targeting, "targeting")::target,
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
        this(runtime, playerBody, camera, activeSlot,
                Objects.requireNonNull(targeting, "targeting")::target,
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
        this(runtime, playerBody, camera, activeSlot, targeting, pickup,
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
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.playerBody = Objects.requireNonNull(playerBody, "playerBody");
        this.camera = Objects.requireNonNull(camera, "camera");
        this.activeSlot = Objects.requireNonNull(activeSlot, "activeSlot");
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
        List<WorldItemPhysicalSnapshot> candidates = runtime.physicalSnapshots();
        Optional<WorldItemTarget> target = targeting.target(
                eyeScratch, directionScratch, reach, tick, candidates);
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
    }

    @FunctionalInterface
    interface TargetOperation {
        Optional<WorldItemTarget> target(
                Vector3fc eye,
                Vector3fc direction,
                float reach,
                long tick,
                List<WorldItemPhysicalSnapshot> candidates);
    }

    @FunctionalInterface
    interface PickupOperation {
        WorldItemPickupResult execute(WorldItemId itemId, BodySlot activeSlot, long tick);
    }
}
