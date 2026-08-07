package com.gaia.worlditem;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.overlord.assets.ResourceLocation;
import com.overlord.config.GameConfig;
import com.overlord.core.thread.MainThreadGuard;
import com.overlord.core.time.FixedStepClock;
import com.overlord.inventory.api.ItemStack;
import com.overlord.physics.BlockCollisionShapeResolver;
import com.overlord.physics.CollisionWorld;
import com.overlord.physics.PhysicsBody;
import com.overlord.physics.PhysicsWorld;
import com.overlord.voxel.ChunkKey;
import com.overlord.voxel.ChunkRepository;
import com.overlord.voxel.World;
import com.overlord.worlditem.LogicalWorldItemService;
import com.overlord.worlditem.LogicalWorldItemTestAccess;
import com.overlord.worlditem.api.WorldItemPhysicalState;
import com.overlord.worlditem.api.WorldItemSnapshot;
import com.overlord.worlditem.api.WorldItemSpawnRequest;
import java.util.List;
import java.util.Optional;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

class PhysicalWorldItemCollisionTest {
    private static final ItemStack DIRT =
            new ItemStack(ResourceLocation.parse("gaia:dirt"), 1);
    private static final float DT = 1.0f / 60.0f;

    @Test
    void renderRatesProduceTheSameWorldItemFixedStepOutcome() {
        MotionTrace reference = simulateRenderRate(60);

        for (int renderFps : new int[] {10, 60, 144, 240}) {
            MotionTrace actual = simulateRenderRate(renderFps);
            assertEquals(60, actual.fixedSteps());
            assertEquals(reference.positionY(), actual.positionY(), 0.0001);
            assertEquals(reference.velocityY(), actual.velocityY(), 0.0001);
        }
    }

    @Test
    void renderFrameWithNoFixedStepDoesNotAdvanceWorldItems() {
        Fixture fixture = loadedFixture(32);
        WorldItemSnapshot item = fixture.spawn(
                2.5, 20.0, 2.5, 0.0, 8.0, 0.0);
        FixedStepClock clock = new FixedStepClock(1.0 / 60.0, 8);

        assertEquals(0, clock.advance(1.0 / 240.0));

        assertEquals(item, fixture.logical.snapshot(item.id()).orElseThrow());
        assertTrue(fixture.physics.bodies().isEmpty());
    }

    @Test
    void fixedStepAppliesGravityAndClampsTerminalVelocity() {
        Fixture fixture = loadedFixture(32);
        WorldItemSnapshot item = fixture.spawn(2.5, 12.0, 2.5, 0.0, -100.0, 0.0);

        fixture.step(1);

        var snapshot = fixture.logical.snapshot(item.id()).orElseThrow();
        assertEquals(-30.0, snapshot.velocityY(), 0.0001);
        assertEquals(12.0 - 30.0 * DT, snapshot.positionY(), 0.0001);
    }

    @Test
    void physicsConfigurationRejectsNonFiniteAndImpossibleValues() {
        assertThrows(IllegalArgumentException.class,
                () -> new WorldItemPhysicsConfig(
                        Float.NaN, -30.0f, 0.12f, 0.25f, 0.02f,
                        0.05f, 30, 8, 32, 3.5f));
        assertThrows(IllegalArgumentException.class,
                () -> new WorldItemPhysicsConfig(
                        0.50f, 1.0f, 0.12f, 0.25f, 0.02f,
                        0.05f, 30, 8, 32, 3.5f));
        assertThrows(IllegalArgumentException.class,
                () -> new WorldItemPhysicsConfig(
                        0.50f, -30.0f, 0.12f, 0.25f, 0.02f,
                        0.05f, 0, 8, 32, 3.5f));
        assertThrows(IllegalArgumentException.class,
                () -> new WorldItemPhysicsConfig(
                        0.50f, Float.NEGATIVE_INFINITY, 0.12f, 0.25f, 0.02f,
                        0.05f, 30, 8, 32, 3.5f));
        assertThrows(IllegalArgumentException.class,
                () -> new WorldItemPhysicsConfig(
                        0.50f, -30.0f, Float.NaN, 0.25f, 0.02f,
                        0.05f, 30, 8, 32, 3.5f));
        assertThrows(IllegalArgumentException.class,
                () -> new WorldItemPhysicsConfig(
                        0.50f, -30.0f, 0.12f, Float.POSITIVE_INFINITY, 0.02f,
                        0.05f, 30, 8, 32, 3.5f));
        assertThrows(IllegalArgumentException.class,
                () -> new WorldItemPhysicsConfig(
                        0.50f, -30.0f, 0.12f, 0.25f, Float.NaN,
                        0.05f, 30, 8, 32, 3.5f));
        assertThrows(IllegalArgumentException.class,
                () -> new WorldItemPhysicsConfig(
                        0.50f, -30.0f, 0.12f, 0.25f, 0.02f,
                        Float.POSITIVE_INFINITY, 30, 8, 32, 3.5f));
        assertThrows(IllegalArgumentException.class,
                () -> new WorldItemPhysicsConfig(
                        0.50f, -30.0f, 0.12f, 0.25f, 0.02f,
                        0.05f, 30, 8, GameConfig.Chunk.MAX_HEIGHT + 1, 3.5f));
    }

    @Test
    void highSpeedFloorCollisionBouncesWithRestitution() {
        Fixture fixture = floorFixture(32, 0, 0, 0);
        WorldItemSnapshot item = fixture.spawn(2.5, 1.70, 2.5, 0.0, -100.0, 0.0);

        fixture.step(1);

        var snapshot = fixture.logical.snapshot(item.id()).orElseThrow();
        assertEquals(1.251, snapshot.positionY(), 0.0002);
        assertEquals(3.6, snapshot.velocityY(), 0.0002);
        assertEquals(
                WorldItemPhysicalState.ACTIVE,
                fixture.logical.physicalSnapshot(item.id()).orElseThrow().state());
    }

    @Test
    void horizontalFrictionAndStaticWallCollisionDoNotCreateBodyBodyCollision() {
        Fixture fixture = wallFixture(32);
        WorldItemSnapshot first = fixture.spawn(3.5, 2.5, 2.5, -100.0, 0.0, 4.0);
        WorldItemSnapshot second = fixture.spawn(4.5, 2.5, 2.5, 0.0, 0.0, 0.0);

        fixture.system.prepareStep(1);
        fixture.physics.step(DT);
        fixture.system.finishStep();

        var firstSnapshot = fixture.logical.snapshot(first.id()).orElseThrow();
        var secondSnapshot = fixture.logical.snapshot(second.id()).orElseThrow();
        assertEquals(3.251, firstSnapshot.positionX(), 0.0002);
        assertEquals(12.0, firstSnapshot.velocityX(), 0.0002);
        assertEquals(4.0, firstSnapshot.velocityZ(), 0.0001);
        assertEquals(4.5, secondSnapshot.positionX(), 0.0001);
    }

    @Test
    void frictionDampsHorizontalMotionOnlyWhileSupportedWithoutReversingIt() {
        Fixture fixture = floorFixture(32, 0, 0, 0);
        WorldItemSnapshot item = fixture.spawn(
                2.5, 1.25, 2.5, 4.0, 0.0, -2.0);

        fixture.step(1);

        var snapshot = fixture.logical.snapshot(item.id()).orElseThrow();
        assertEquals(3.0, snapshot.velocityX(), 0.0001);
        assertEquals(-1.5, snapshot.velocityZ(), 0.0001);
        assertEquals(
                WorldItemPhysicalState.GROUNDED,
                fixture.logical.physicalSnapshot(item.id()).orElseThrow().state());
    }

    @Test
    void crossChunkFloorCollisionAndGroundSnapAreDeterministic() {
        Fixture fixture = floorFixture(32, 0, 0, 0);
        fixture.chunks.generate(new ChunkKey(1, 0), ignored -> ignored.setBlock(0, 0, 0, (byte) 1));
        WorldItemSnapshot item = fixture.spawn(15.99, 1.26, 0.5, 5.0, 0.0, 0.0);

        fixture.step(1);

        var snapshot = fixture.logical.snapshot(item.id()).orElseThrow();
        assertTrue(snapshot.positionX() > 15.75);
        assertEquals(1.25, snapshot.positionY(), 0.01);
        assertEquals(WorldItemPhysicalState.GROUNDED, fixture.logical.physicalSnapshot(item.id()).orElseThrow().state());
    }

    @Test
    void cornerCollisionSlidesDeterministicallyAlongBothStaticFaces() {
        Fixture fixture = wallFixture(32);
        for (int y = 0; y < 32; y++) {
            for (int wallX = 0; wallX < GameConfig.Chunk.SIZE; wallX++) {
                fixture.world.setBlock(wallX, y, 2, (byte) 1);
            }
        }
        WorldItemSnapshot item = fixture.spawn(3.5, 2.5, 3.5, -100.0, 0.0, -100.0);

        fixture.step(1);

        var snapshot = fixture.logical.snapshot(item.id()).orElseThrow();
        assertTrue(snapshot.positionX() >= 2.24);
        assertTrue(snapshot.positionZ() >= 2.24);
    }

    @Test
    void groundedItemSleepsAfterStableStepsAndWakesWhenSupportIsRemoved() {
        Fixture fixture = floorFixture(32, 0, 0, 0);
        WorldItemSnapshot item = fixture.spawn(2.5, 1.25, 2.5, 0.0, 0.0, 0.0);

        for (int tick = 1; tick <= 90; tick++) {
            fixture.step(tick);
        }

        assertEquals(WorldItemPhysicalState.SLEEPING, fixture.logical.physicalSnapshot(item.id()).orElseThrow().state());
        assertTrue(fixture.body(item).isSleeping());

        fixture.world.setBlock(2, 0, 2, (byte) 0);
        fixture.step(91);

        assertEquals(WorldItemPhysicalState.ACTIVE, fixture.logical.physicalSnapshot(item.id()).orElseThrow().state());
        assertFalse(fixture.body(item).isSleeping());
    }

    @Test
    void sleepOccursOnExactlyTheThirtiethStableStepWithoutRevisionChurn() {
        Fixture fixture = floorFixture(32, 0, 0, 0);
        WorldItemSnapshot item = fixture.spawn(
                2.5, 1.25, 2.5, 0.0, 0.0, 0.0);

        for (int tick = 1; tick < 30; tick++) {
            fixture.step(tick);
            assertFalse(fixture.body(item).isSleeping(), "tick " + tick);
        }
        fixture.step(30);

        assertTrue(fixture.body(item).isSleeping());
        assertEquals(
                WorldItemPhysicalState.SLEEPING,
                fixture.logical.physicalSnapshot(item.id()).orElseThrow().state());
        long sleepingRevision = fixture.logical.snapshot(item.id()).orElseThrow().revision();
        long sleepingWrites = fixture.system.metrics().appliedWrites();

        for (int tick = 31; tick <= 60; tick++) {
            fixture.step(tick);
        }
        assertEquals(
                sleepingRevision,
                fixture.logical.snapshot(item.id()).orElseThrow().revision());
        assertEquals(sleepingWrites, fixture.system.metrics().appliedWrites());
    }

    @Test
    void oneUnstableSupportedStepResetsTheSleepCounter() {
        Fixture fixture = floorFixture(32, 0, 0, 0);
        WorldItemSnapshot item = fixture.spawn(
                2.5, 1.25, 2.5, 0.0, 0.0, 0.0);
        for (int tick = 1; tick <= 15; tick++) {
            fixture.step(tick);
        }

        fixture.body(item).setLinearVelocity(new Vector3f(1.0f, 0.0f, 0.0f));
        fixture.step(16);
        fixture.body(item).setLinearVelocity(new Vector3f());
        for (int tick = 17; tick <= 45; tick++) {
            fixture.step(tick);
        }

        assertFalse(fixture.body(item).isSleeping());
        fixture.step(46);
        assertTrue(fixture.body(item).isSleeping());
    }

    @Test
    void sleepingItemWakesForAnExplicitSmallVelocityChange() {
        Fixture fixture = floorFixture(32, 0, 0, 0);
        WorldItemSnapshot item = fixture.spawn(2.5, 1.25, 2.5, 0.0, 0.0, 0.0);
        for (int tick = 1; tick <= 90; tick++) {
            fixture.step(tick);
        }

        fixture.body(item).setLinearVelocity(new Vector3f(0.0f, 0.01f, 0.0f));
        fixture.step(91);

        assertTrue(fixture.logical.physicalSnapshot(item.id()).orElseThrow().state()
                != WorldItemPhysicalState.SLEEPING);
        assertFalse(fixture.body(item).isSleeping());
    }

    @Test
    void initialOverlapAndLowerBoundRecoverToFiniteSafePositions() {
        Fixture fixture = floorFixture(16, 0, 0, 0);
        WorldItemSnapshot overlap = fixture.spawn(2.5, 0.5, 2.5, 0.0, 0.0, 0.0);
        WorldItemSnapshot below = fixture.spawn(5.5, -20.0, 5.5, 0.0, 0.0, 0.0);

        fixture.system.prepareStep(1);

        PhysicsBody overlapBody = fixture.body(overlap);
        PhysicsBody belowBody = fixture.body(below);
        assertFalse(fixture.collisions.overlapsSolid(
                overlapBody.collider().translated(overlapBody.position(new Vector3f()))));
        assertTrue(belowBody.position(new Vector3f()).y >= 0.25f);
        assertTrue(belowBody.position(new Vector3f()).y <= 15.75f);
    }

    @Test
    void sideOverlapUsesBoundedDeterministicDepenetration() {
        Fixture fixture = loadedFixture(16);
        fixture.world.setBlock(2, 1, 2, (byte) 1);
        WorldItemSnapshot item = fixture.spawn(
                1.9, 1.5, 2.5, 0.0, 0.0, 0.0);

        fixture.system.prepareStep(1);

        PhysicsBody body = fixture.body(item);
        Vector3f recovered = body.position(new Vector3f());
        assertEquals(1.75, recovered.x, 0.0001);
        assertFalse(fixture.collisions.overlapsSolid(
                body.collider().translated(recovered)));
    }

    @Test
    void postPhysicsLowerBoundRecoveryDoesNotPublishAnOutOfWorldCenter() {
        Fixture fixture = loadedFixture(16);
        WorldItemSnapshot item = fixture.spawn(2.5, 0.26, 2.5, 0.0, -100.0, 0.0);

        fixture.step(1);

        assertTrue(fixture.logical.snapshot(item.id()).orElseThrow().positionY() >= 0.25);
    }

    @Test
    void unloadedChunkFreezesCanonicalMotionAndReloadRebuildsStableId() {
        Fixture fixture = loadedFixture(32);
        WorldItemSnapshot item = fixture.spawn(2.5, 5.0, 2.5, 0.0, -2.0, 0.0);
        fixture.step(1);
        var beforeUnload = fixture.logical.snapshot(item.id()).orElseThrow();

        assertTrue(fixture.chunks.beginUnload(new ChunkKey(0, 0)));
        assertTrue(fixture.chunks.completeUnload(new ChunkKey(0, 0)));
        fixture.system.prepareStep(2);

        var frozen = fixture.logical.physicalSnapshot(item.id()).orElseThrow();
        assertEquals(WorldItemPhysicalState.FROZEN_UNLOADED, frozen.state());
        assertEquals(beforeUnload.positionX(), frozen.runtime().item().positionX(), 0.0001);
        assertEquals(beforeUnload.positionY(), frozen.runtime().item().positionY(), 0.0001);
        assertEquals(beforeUnload.velocityY(), frozen.runtime().item().velocityY(), 0.0001);
        assertTrue(fixture.physics.bodies().isEmpty());

        fixture.chunks.generate(new ChunkKey(0, 0), ignored -> {});
        fixture.system.prepareStep(3);

        assertEquals(1, fixture.physics.bodies().size());
        assertEquals(WorldItemPhysicalState.ACTIVE,
                fixture.logical.physicalSnapshot(item.id()).orElseThrow().state());
        assertEquals(item.id(), fixture.system.presentationSnapshots().get(0).id());
    }

    @Test
    void spawnInUnavailableChunkTransitionsCanonicalStateToFrozenWithoutABody() {
        Fixture fixture = loadedFixture(32);
        WorldItemSnapshot item = fixture.spawn(
                16.5, 5.0, 2.5, 1.0, -2.0, 0.0);

        fixture.system.prepareStep(1);

        var frozen = fixture.logical.physicalSnapshot(item.id()).orElseThrow();
        assertEquals(WorldItemPhysicalState.FROZEN_UNLOADED, frozen.state());
        assertEquals(item.positionX(), frozen.runtime().item().positionX());
        assertEquals(item.velocityY(), frozen.runtime().item().velocityY());
        assertTrue(fixture.physics.bodies().isEmpty());
    }

    @Test
    void colliderStraddlingUnavailableNeighborFreezesBeforeCollisionQueries() {
        Fixture fixture = loadedFixture(32);
        WorldItemSnapshot item = fixture.spawn(
                15.9, 5.0, 2.5, 0.0, -2.0, 0.0);

        fixture.system.prepareStep(1);

        assertEquals(
                WorldItemPhysicalState.FROZEN_UNLOADED,
                fixture.logical.physicalSnapshot(item.id()).orElseThrow().state());
        assertTrue(fixture.physics.bodies().isEmpty());
    }

    @Test
    void sweptEntryIntoUnavailableChunkFreezesBeforeIntegration() {
        Fixture fixture = loadedFixture(32);
        WorldItemSnapshot item = fixture.spawn(
                15.5, 5.0, 2.5, 60.0, 0.0, 0.0);

        fixture.system.prepareStep(1);

        var frozen = fixture.logical.physicalSnapshot(item.id()).orElseThrow();
        assertEquals(WorldItemPhysicalState.FROZEN_UNLOADED, frozen.state());
        assertEquals(item.positionX(), frozen.runtime().item().positionX());
        assertTrue(fixture.physics.bodies().isEmpty());
    }

    @Test
    void sweptUnavailableNeighborRemainsFrozenUntilTheEntireSweepIsLoaded() {
        Fixture fixture = loadedFixture(32);
        WorldItemSnapshot item = fixture.spawn(
                15.5, 5.0, 2.5, 60.0, 0.0, 0.0);

        fixture.system.prepareStep(1);
        var frozen = fixture.logical.physicalSnapshot(item.id()).orElseThrow();
        long frozenRevision = frozen.runtime().item().revision();
        WorldItemPhysicsMetrics frozenMetrics = fixture.system.metrics();

        for (int tick = 2; tick <= 6; tick++) {
            fixture.system.prepareStep(tick);
        }

        var unchanged = fixture.logical.physicalSnapshot(item.id()).orElseThrow();
        assertEquals(WorldItemPhysicalState.FROZEN_UNLOADED, unchanged.state());
        assertEquals(frozenRevision, unchanged.runtime().item().revision());
        assertEquals(frozenMetrics.created(), fixture.system.metrics().created());
        assertEquals(frozenMetrics.destroyed(), fixture.system.metrics().destroyed());
        assertEquals(frozenMetrics.appliedWrites(), fixture.system.metrics().appliedWrites());
        assertTrue(fixture.physics.bodies().isEmpty());

        fixture.chunks.generate(new ChunkKey(1, 0), ignored -> {});
        fixture.system.prepareStep(7);
        PhysicsBody rebuilt = fixture.body(item);
        var active = fixture.logical.physicalSnapshot(item.id()).orElseThrow();

        assertEquals(WorldItemPhysicalState.ACTIVE, active.state());
        assertEquals(frozenRevision + 1, active.runtime().item().revision());
        assertEquals(item.id(), fixture.system.presentationSnapshots().get(0).id());
        assertEquals(frozenMetrics.created() + 1, fixture.system.metrics().created());
        assertEquals(frozenMetrics.destroyed(), fixture.system.metrics().destroyed());
        assertEquals(frozenMetrics.appliedWrites() + 1,
                fixture.system.metrics().appliedWrites());

        fixture.system.prepareStep(8);
        assertTrue(fixture.body(item) == rebuilt);
        assertEquals(frozenMetrics.created() + 1, fixture.system.metrics().created());
        assertEquals(frozenMetrics.destroyed(), fixture.system.metrics().destroyed());
    }

    @Test
    void fullyEnclosedRecoveryRetriesOnlyAfterRelevantCollisionDataChanges() {
        Fixture fixture = loadedFixture(16);
        for (int x = 1; x <= 3; x++) {
            for (int y = 0; y < 16; y++) {
                for (int z = 1; z <= 3; z++) {
                    fixture.world.setBlock(x, y, z, (byte) 1);
                }
            }
        }
        WorldItemSnapshot item = fixture.spawn(
                2.5, 0.5, 2.5, 0.0, 0.0, 0.0);

        fixture.system.prepareStep(1);
        WorldItemPhysicsMetrics blockedMetrics = fixture.system.metrics();
        assertEquals(1L, blockedMetrics.recoveryFailures());
        assertEquals(List.of(item.id()), blockedMetrics.recoveryBlockedIds());

        for (int tick = 2; tick <= 6; tick++) {
            fixture.system.prepareStep(tick);
        }

        assertEquals(item, fixture.logical.snapshot(item.id()).orElseThrow());
        assertTrue(fixture.physics.bodies().isEmpty());
        assertTrue(fixture.system.presentationSnapshots().isEmpty());
        assertEquals(blockedMetrics.created(), fixture.system.metrics().created());
        assertEquals(blockedMetrics.destroyed(), fixture.system.metrics().destroyed());
        assertEquals(blockedMetrics.appliedWrites(), fixture.system.metrics().appliedWrites());
        assertEquals(blockedMetrics.recoveryFailures(),
                fixture.system.metrics().recoveryFailures());
        assertEquals(List.of(item.id()),
                fixture.system.metrics().recoveryBlockedIds());

        assertTrue(fixture.world.setBlock(2, 15, 2, (byte) 0));
        fixture.system.prepareStep(7);

        assertEquals(item, fixture.logical.snapshot(item.id()).orElseThrow());
        assertEquals(1, fixture.physics.bodies().size());
        assertEquals(item.id(), fixture.system.presentationSnapshots().get(0).id());
        PhysicsBody recovered = fixture.body(item);
        assertEquals(15.25f, recovered.position(new Vector3f()).y, 0.0001f);
        assertFalse(fixture.collisions.overlapsSolid(
                recovered.collider().translated(recovered.position(new Vector3f()))));
        assertEquals(blockedMetrics.created() + 1, fixture.system.metrics().created());
        assertEquals(blockedMetrics.destroyed(), fixture.system.metrics().destroyed());
        assertEquals(blockedMetrics.recoveryFailures(),
                fixture.system.metrics().recoveryFailures());
        assertTrue(fixture.system.metrics().recoveryBlockedIds().isEmpty());
    }

    @Test
    void blockedWorldTopDoesNotRetainAnOverlappingRecovery() {
        Fixture fixture = loadedFixture(16);
        fixture.world.setBlock(2, 15, 2, (byte) 1);
        WorldItemSnapshot item = fixture.spawn(
                2.5, 100.0, 2.5, 0.0, 0.0, 0.0);

        fixture.system.prepareStep(1);

        assertEquals(item, fixture.logical.snapshot(item.id()).orElseThrow());
        assertTrue(fixture.physics.bodies().isEmpty());
    }

    @Test
    void ceilingCollisionReflectsOnlyVerticalVelocityAndRemainsActive() {
        Fixture fixture = loadedFixture(32);
        fixture.world.setBlock(2, 3, 2, (byte) 1);
        WorldItemSnapshot item = fixture.spawn(
                2.5, 2.2, 2.5, 3.0, 100.0, -4.0);

        fixture.step(1);

        var result = fixture.logical.snapshot(item.id()).orElseThrow();
        assertTrue(result.positionY() <= 2.751);
        assertTrue(result.velocityY() < 0.0);
        assertEquals(3.0, result.velocityX(), 0.0001);
        assertEquals(-4.0, result.velocityZ(), 0.0001);
        assertEquals(
                WorldItemPhysicalState.ACTIVE,
                fixture.logical.physicalSnapshot(item.id()).orElseThrow().state());
    }

    @Test
    void negativeCoordinateFloorCollisionUsesTheLoadedNegativeChunk() {
        Fixture fixture = loadedFixture(32);
        fixture.chunks.generate(new ChunkKey(-1, 0), ignored -> {
        });
        fixture.world.setBlock(-1, 0, 0, (byte) 1);
        WorldItemSnapshot item = fixture.spawn(
                -0.5, 1.26, 0.5, 0.0, 0.0, 0.0);

        fixture.step(1);

        var result = fixture.logical.snapshot(item.id()).orElseThrow();
        assertEquals(1.25, result.positionY(), 0.01);
        assertEquals(
                WorldItemPhysicalState.GROUNDED,
                fixture.logical.physicalSnapshot(item.id()).orElseThrow().state());
    }

    @Test
    void maximumSpeedSweepStopsAtAOneBlockObstacle() {
        Fixture fixture = loadedFixture(32);
        fixture.world.setBlock(2, 1, 2, (byte) 1);
        WorldItemSnapshot item = fixture.spawn(
                1.0, 1.5, 2.5, 100.0, 0.0, 0.0);

        fixture.step(1);

        var result = fixture.logical.snapshot(item.id()).orElseThrow();
        assertTrue(result.positionX() <= 1.751);
        assertTrue(result.velocityX() <= 0.0);
    }

    @Test
    void repeatedFloorBouncesLoseEnergy() {
        Fixture fixture = floorFixture(32, 0, 0, 0);
        WorldItemSnapshot item = fixture.spawn(
                2.5, 1.7, 2.5, 0.0, -30.0, 0.0);
        fixture.step(1);
        double firstBounce = fixture.logical.snapshot(item.id()).orElseThrow().velocityY();
        boolean descended = false;
        double secondBounce = 0.0;

        for (int tick = 2; tick <= 120; tick++) {
            fixture.step(tick);
            double velocity = fixture.logical.snapshot(item.id()).orElseThrow().velocityY();
            if (velocity < 0.0) {
                descended = true;
            } else if (descended && velocity > 0.0) {
                secondBounce = velocity;
                break;
            }
        }

        assertTrue(firstBounce > 0.0);
        assertTrue(secondBounce >= 0.0);
        assertTrue(secondBounce < firstBounce);
    }

    @Test
    void collisionAndRecoveryReadsDoNotChangeChunkRevision() {
        Fixture fixture = floorFixture(32, 0, 0, 0);
        ChunkKey key = new ChunkKey(0, 0);
        long before = fixture.chunks.revision(key);
        fixture.spawn(2.5, 0.5, 2.5, 0.0, -30.0, 0.0);

        fixture.step(1);

        assertEquals(before, fixture.chunks.revision(key));
    }

    @Test
    void unloadReloadLoopsPreserveStableIdAndDoNotLeakBodies() {
        Fixture fixture = loadedFixture(32);
        WorldItemSnapshot item = fixture.spawn(
                2.5, 5.0, 2.5, 0.0, 0.0, 0.0);
        fixture.system.prepareStep(1);
        PhysicsBody previous = fixture.body(item);

        for (int cycle = 1; cycle <= 3; cycle++) {
            assertTrue(fixture.chunks.beginUnload(new ChunkKey(0, 0)));
            assertTrue(fixture.chunks.completeUnload(new ChunkKey(0, 0)));
            fixture.system.prepareStep(cycle * 2L);
            assertTrue(fixture.physics.bodies().isEmpty());
            assertEquals(
                    WorldItemPhysicalState.FROZEN_UNLOADED,
                    fixture.logical.physicalSnapshot(item.id()).orElseThrow().state());

            fixture.chunks.generate(new ChunkKey(0, 0), ignored -> {
            });
            fixture.system.prepareStep(cycle * 2L + 1L);
            assertEquals(1, fixture.physics.bodies().size());
            PhysicsBody reloaded = fixture.body(item);
            assertTrue(reloaded != previous);
            previous = reloaded;
            assertEquals(item.id(), fixture.system.presentationSnapshots().get(0).id());
        }

        assertEquals(4L, fixture.system.metrics().created());
        assertEquals(3L, fixture.system.metrics().destroyed());
        assertEquals(1, fixture.logical.snapshots().size());
    }

    @Test
    void unloadAtRevisionExhaustionDropsBodyAndReloadsTheSameStableId() {
        Fixture fixture = loadedFixture(32);
        WorldItemSnapshot item = fixture.spawn(
                2.5, 5.0, 2.5, 0.0, -2.0, 0.0);
        LogicalWorldItemTestAccess.forceRevision(
                fixture.logical, item.id(), Long.MAX_VALUE);
        fixture.system.prepareStep(1);
        PhysicsBody original = fixture.body(item);
        WorldItemSnapshot canonical = fixture.logical.snapshot(item.id()).orElseThrow();

        assertTrue(fixture.chunks.beginUnload(new ChunkKey(0, 0)));
        assertTrue(fixture.chunks.completeUnload(new ChunkKey(0, 0)));
        fixture.system.prepareStep(2);

        assertTrue(fixture.physics.bodies().isEmpty());
        assertEquals(canonical, fixture.logical.snapshot(item.id()).orElseThrow());

        fixture.chunks.generate(new ChunkKey(0, 0), ignored -> {
        });
        fixture.system.prepareStep(3);
        assertEquals(1, fixture.physics.bodies().size());
        assertTrue(fixture.body(item) != original);
        assertEquals(item.id(), fixture.system.presentationSnapshots().get(0).id());
    }

    @Test
    void upwardVelocityIsNotClampedAndHugeFiniteHeightFailsClosed() {
        Fixture fixture = loadedFixture(32);
        WorldItemSnapshot upward = fixture.spawn(
                2.5, 5.0, 2.5, 0.0, 100.0, 0.0);
        WorldItemSnapshot huge = fixture.spawn(
                3.5, Float.MAX_VALUE, 3.5, 0.0, 0.0, 0.0);

        fixture.system.prepareStep(1);
        assertEquals(1, fixture.physics.bodies().size());
        fixture.physics.step(DT);
        fixture.system.finishStep();

        assertTrue(fixture.logical.snapshot(upward.id()).orElseThrow().velocityY() > 0.0);
        assertEquals(huge, fixture.logical.snapshot(huge.id()).orElseThrow());
        assertEquals(1, fixture.physics.bodies().size());
    }

    private static Fixture loadedFixture(int worldHeight) {
        ChunkRepository chunks = new ChunkRepository(worldHeight, new com.overlord.voxel.ChunkDirtyTracker());
        chunks.generate(new ChunkKey(0, 0), ignored -> {});
        World world = new World(chunks);
        MainThreadGuard guard = MainThreadGuard.captureCurrentThread();
        CollisionWorld collisions = new CollisionWorld(world, BlockCollisionShapeResolver.fullCubesForNonAir());
        PhysicsWorld physics = new PhysicsWorld(collisions, new Vector3f(0.0f, GameConfig.Physics.GRAVITY, 0.0f));
        LogicalWorldItemService logical = new LogicalWorldItemService(guard, 32, 0);
        PhysicalWorldItemSystem system = new PhysicalWorldItemSystem(
                logical, physics, chunks, guard,
                new WorldItemPhysicsConfig(
                        0.50f, -30.0f, 0.12f, 0.25f, 0.02f,
                        0.05f, 30, 8, worldHeight, 3.5f));
        return new Fixture(chunks, world, collisions, physics, logical, system);
    }

    private static MotionTrace simulateRenderRate(int renderFps) {
        Fixture fixture = loadedFixture(32);
        WorldItemSnapshot item = fixture.spawn(
                2.5, 20.0, 2.5, 0.0, 8.0, 0.0);
        FixedStepClock clock = new FixedStepClock(1.0 / 60.0, 8);
        double elapsed = 0.0;
        int fixedSteps = 0;
        long tick = 0;
        while (elapsed < 1.0) {
            double next = Math.min(1.0, elapsed + 1.0 / renderFps);
            int frameSteps = clock.advance(next - elapsed);
            elapsed = next;
            for (int step = 0; step < frameSteps; step++) {
                fixture.system.step(++tick);
                fixedSteps++;
            }
        }
        WorldItemSnapshot result = fixture.logical.snapshot(item.id()).orElseThrow();
        return new MotionTrace(fixedSteps, result.positionY(), result.velocityY());
    }

    private static Fixture floorFixture(int worldHeight, int x, int y, int z) {
        Fixture fixture = loadedFixture(worldHeight);
        for (int floorX = 0; floorX < GameConfig.Chunk.SIZE; floorX++) {
            for (int floorZ = 0; floorZ < GameConfig.Chunk.SIZE; floorZ++) {
                fixture.world.setBlock(floorX, y, floorZ, (byte) 1);
            }
        }
        fixture.world.setBlock(x, y, z, (byte) 1);
        return fixture;
    }

    private static Fixture wallFixture(int worldHeight) {
        Fixture fixture = loadedFixture(worldHeight);
        for (int y = 0; y < worldHeight; y++) {
            for (int wallZ = 0; wallZ < GameConfig.Chunk.SIZE; wallZ++) {
                fixture.world.setBlock(2, y, wallZ, (byte) 1);
            }
        }
        return fixture;
    }

    private record Fixture(
            ChunkRepository chunks,
            World world,
            CollisionWorld collisions,
            PhysicsWorld physics,
            LogicalWorldItemService logical,
            PhysicalWorldItemSystem system) {
        private WorldItemSnapshot spawn(
                double x, double y, double z,
                double velocityX, double velocityY, double velocityZ) {
            return logical.spawn(new WorldItemSpawnRequest(
                    DIRT, x, y, z, velocityX, velocityY, velocityZ,
                    Optional.empty(), 1)).item().orElseThrow();
        }

        private void step(long tick) {
            system.prepareStep(tick);
            physics.step(1.0f / 60.0f);
            system.finishStep();
        }

        private PhysicsBody body(WorldItemSnapshot item) {
            int index = system.presentationSnapshots().stream()
                    .map(WorldItemPresentationSnapshot::id)
                    .toList()
                    .indexOf(item.id());
            if (index < 0) {
                throw new IllegalArgumentException("item is not projected: " + item.id());
            }
            return physics.bodies().get(index);
        }
    }

    private record MotionTrace(int fixedSteps, double positionY, double velocityY) {
    }
}
