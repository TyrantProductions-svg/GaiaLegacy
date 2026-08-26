package com.gaia.worlditem;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.overlord.assets.ResourceLocation;
import com.overlord.core.thread.MainThreadGuard;
import com.overlord.inventory.api.ItemStack;
import com.overlord.physics.BlockCollisionShapeResolver;
import com.overlord.physics.CollisionWorld;
import com.overlord.physics.PhysicsBody;
import com.overlord.physics.PhysicsWorld;
import com.overlord.voxel.ChunkKey;
import com.overlord.voxel.World;
import com.overlord.worlditem.LogicalWorldItemService;
import com.overlord.worlditem.api.LogicalWorldItemSnapshot;
import com.overlord.worlditem.api.WorldItemActivationResult;
import com.overlord.worlditem.api.WorldItemHibernateResult;
import com.overlord.worlditem.api.SaveIdentity;
import com.overlord.worlditem.api.WorldItemDurableProof;
import com.overlord.worlditem.api.WorldItemHibernateTicket;
import com.overlord.worlditem.api.WorldItemMotionUpdate;
import com.overlord.worlditem.api.WorldItemPageCachePolicy;
import com.overlord.worlditem.api.WorldItemPageDescriptor;
import com.overlord.worlditem.api.WorldItemPageSnapshot;
import com.overlord.worlditem.api.WorldItemPersistencePlan;
import com.overlord.worlditem.api.WorldItemPersistenceTicket;
import com.overlord.worlditem.api.WorldItemPhysicalState;
import com.overlord.worlditem.api.WorldItemSnapshot;
import com.overlord.worlditem.api.WorldItemSpawnRequest;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.lang.reflect.InvocationTargetException;
import java.util.UUID;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class PhysicalWorldItemPagingTest {
    private static final ItemStack DIRT =
            new ItemStack(ResourceLocation.parse("gaia:dirt"), 1);

    @Test
    void linkedDurableHibernateRollsBackPartialProjectionRemovalAndRetriesExactProof() {
        AssertionError sentinel = new AssertionError(
                "injected linked hibernate removal failure");
        AtomicBoolean failOnce = new AtomicBoolean(true);
        Fixture fixture = pagedFixture(
                4,
                PhysicalWorldItemSystem::createDefaultBody,
                (stage, id) -> {
                    if (stage == PhysicalWorldItemSystem.ProjectionRemovalStage.AFTER_BODY_REMOVAL
                            && failOnce.getAndSet(false)) {
                        throw sentinel;
                    }
                });
        ChunkKey key = new ChunkKey(-2, 1);
        fixture.logical.deliverWorldTick(1L);
        WorldItemSnapshot item = fixture.spawn(key);
        fixture.system.reconcileRestoredCanonicalState(1L);
        WorldItemHibernateResult prepared = fixture.logical.prepareHibernate(
                key, Map.of(item.id(), item.revision()));
        WorldItemPersistencePlan plan = prepared.persistencePlan().orElseThrow();
        PhysicalProof proof = new PhysicalProof(
                plan.intendedCheckpoint().checkpointRevision(),
                plan.transactionDigest());
        LogicalWorldItemSnapshot logicalBefore = fixture.logical.canonicalSnapshot();
        var metadataBefore = fixture.logical.liveMetadata();
        var pagingBefore = fixture.logical.pagingMetrics();
        PhysicsBody body = fixture.physics.bodies().get(0);
        var presentationsBefore = fixture.system.presentationSnapshots();
        var physicalMetricsBefore = fixture.system.metrics();

        assertSame(sentinel, assertThrows(AssertionError.class, () ->
                commitLinked(fixture.system, fixture.logical,
                        prepared.ticket().orElseThrow(),
                        prepared.persistenceTicket().orElseThrow(), proof)));

        assertEquals(logicalBefore, fixture.logical.canonicalSnapshot());
        assertEquals(metadataBefore, fixture.logical.liveMetadata());
        assertEquals(pagingBefore, fixture.logical.pagingMetrics());
        assertEquals(List.of(body), fixture.physics.bodies());
        assertEquals(presentationsBefore, fixture.system.presentationSnapshots());
        assertEquals(physicalMetricsBefore, fixture.system.metrics());
        assertEquals(WorldItemHibernateResult.Status.COMMITTED,
                commitLinked(fixture.system, fixture.logical,
                        prepared.ticket().orElseThrow(),
                        prepared.persistenceTicket().orElseThrow(), proof).status());
        assertTrue(fixture.logical.snapshot(item.id()).isEmpty());
        assertTrue(fixture.physics.bodies().isEmpty());
        assertTrue(fixture.system.presentationSnapshots().isEmpty());
    }

    @Test
    void persistenceFailureOrCancellationKeepsExactActiveProjection() {
        Fixture fixture = fixture(4, PhysicalWorldItemSystem::createDefaultBody);
        WorldItemSnapshot item = fixture.spawn(new ChunkKey(-1, 0));
        fixture.system.reconcileRestoredCanonicalState(1);
        PhysicsBody body = fixture.physics.bodies().get(0);
        LogicalWorldItemSnapshot before = fixture.logical.canonicalSnapshot();
        WorldItemHibernateResult prepared = fixture.logical.prepareHibernate(
                new ChunkKey(-1, 0), Map.of(item.id(), item.revision()));

        // Persistence did not prove a durable commit: no logical or physical side effect.
        assertEquals(before, fixture.logical.canonicalSnapshot());
        assertEquals(1, fixture.physics.bodies().size());
        assertSame(body, fixture.physics.bodies().get(0));
        assertEquals(item.id(), fixture.system.presentationSnapshots().get(0).id());

        assertEquals(
                WorldItemHibernateResult.Status.CANCELED,
                fixture.logical.cancelHibernate(prepared.ticket().orElseThrow()).status());
        fixture.system.reconcileRestoredCanonicalState(2);
        assertEquals(before, fixture.logical.canonicalSnapshot());
        assertSame(body, fixture.physics.bodies().get(0));
    }

    @Test
    void pendingDurableHibernateKeepsProjectionAndRollsBackPhysicsMotion() {
        Fixture fixture = pagedFixture(
                4, PhysicalWorldItemSystem::createDefaultBody, (stage, id) -> {});
        ChunkKey key = new ChunkKey(-1, 1);
        fixture.logical.deliverWorldTick(1L);
        WorldItemSnapshot item = fixture.spawn(key);
        fixture.system.reconcileRestoredCanonicalState(1L);
        PhysicsBody body = fixture.physics.bodies().get(0);
        WorldItemHibernateResult prepared = fixture.logical.prepareHibernate(
                key, Map.of(item.id(), item.revision()));
        WorldItemPersistencePlan plan = prepared.persistencePlan().orElseThrow();

        assertTrue(fixture.logical.motionPinnedForPersistence(item.id()));
        body.setLinearVelocity(new Vector3f(1.0f, -1.0f, 0.5f));
        fixture.system.step(2L);

        assertEquals(item, fixture.logical.snapshot(item.id()).orElseThrow());
        assertEquals(1, fixture.system.metrics().liveProjections());
        assertSame(body, fixture.physics.bodies().get(0));
        assertEquals(WorldItemHibernateResult.Status.COMMITTED,
                commitLinked(
                        fixture.system,
                        fixture.logical,
                        prepared.ticket().orElseThrow(),
                        prepared.persistenceTicket().orElseThrow(),
                        new PhysicalProof(
                                plan.intendedCheckpoint().checkpointRevision(),
                                plan.transactionDigest())).status());
        assertTrue(fixture.physics.bodies().isEmpty());
    }

    @Test
    void projectionIsRemovedOnlyAfterSuccessfulLogicalHibernateCommit() {
        Fixture fixture = fixture(4, PhysicalWorldItemSystem::createDefaultBody);
        WorldItemSnapshot item = fixture.spawn(new ChunkKey(0, 0));
        fixture.system.reconcileRestoredCanonicalState(1);
        WorldItemHibernateResult prepared = fixture.logical.prepareHibernate(
                new ChunkKey(0, 0), Map.of(item.id(), item.revision()));

        assertEquals(1, fixture.physics.bodies().size());
        assertEquals(
                WorldItemHibernateResult.Status.COMMITTED,
                fixture.system.commitHibernate(
                        fixture.logical, prepared.ticket().orElseThrow()).status());

        assertTrue(fixture.logical.snapshot(item.id()).isEmpty());
        assertTrue(fixture.physics.bodies().isEmpty());
        assertTrue(fixture.system.presentationSnapshots().isEmpty());
        assertEquals(1L, fixture.system.metrics().destroyed());
    }

    @Test
    void exactTickExpiryReconciliationRemovesProjectionImmediately() {
        Fixture fixture = fixture(4, PhysicalWorldItemSystem::createDefaultBody);
        WorldItemSnapshot item = fixture.spawn(new ChunkKey(0, 0));
        fixture.system.reconcileRestoredCanonicalState(1L);
        assertEquals(1, fixture.physics.bodies().size());

        var expired = fixture.logical.deliverWorldTick(
                com.overlord.worlditem.api.WorldItemRuntimeSnapshot
                        .WORLD_ITEM_TTL_TICKS + 1L);
        assertEquals(List.of(item.id()), expired);
        fixture.system.removeExpiredProjections(expired);

        assertTrue(fixture.physics.bodies().isEmpty());
        assertTrue(fixture.system.presentationSnapshots().isEmpty());
    }

    @Test
    void activationProjectionFailureRollsLogicalStateBackExactly() {
        AtomicBoolean failActivation = new AtomicBoolean(false);
        AssertionError sentinel = new AssertionError("injected activation projection failure");
        Fixture fixture = fixture(4, snapshot -> {
            if (failActivation.get()) {
                throw sentinel;
            }
            return PhysicalWorldItemSystem.createDefaultBody(snapshot);
        });
        WorldItemSnapshot item = fixture.spawn(new ChunkKey(0, -1));
        fixture.system.reconcileRestoredCanonicalState(1);
        WorldItemHibernateResult hibernate = fixture.logical.prepareHibernate(
                new ChunkKey(0, -1), Map.of(item.id(), item.revision()));
        fixture.system.commitHibernate(
                fixture.logical, hibernate.ticket().orElseThrow());
        LogicalWorldItemSnapshot dormant = fixture.logical.canonicalSnapshot();
        WorldItemActivationResult activation = fixture.logical.prepareActivate(
                new ChunkKey(0, -1), hibernate.payload().orElseThrow());
        failActivation.set(true);

        assertSame(sentinel, assertThrows(
                AssertionError.class,
                () -> fixture.system.commitActivate(
                        fixture.logical,
                        activation.ticket().orElseThrow(),
                        2)));

        assertEquals(dormant, fixture.logical.canonicalSnapshot());
        assertTrue(fixture.logical.snapshot(item.id()).isEmpty());
        assertTrue(fixture.physics.bodies().isEmpty());
        assertTrue(fixture.system.presentationSnapshots().isEmpty());
        assertEquals(
                WorldItemActivationResult.Status.STALE_TICKET,
                fixture.logical.commitActivate(
                        activation.ticket().orElseThrow()).status());
    }

    @Test
    void successfulActivationPublishesOneSameIdProjection() {
        Fixture fixture = fixture(4, PhysicalWorldItemSystem::createDefaultBody);
        WorldItemSnapshot item = fixture.spawn(new ChunkKey(1, 0));
        fixture.system.reconcileRestoredCanonicalState(1);
        WorldItemHibernateResult hibernate = fixture.logical.prepareHibernate(
                new ChunkKey(1, 0), Map.of(item.id(), item.revision()));
        fixture.system.commitHibernate(
                fixture.logical, hibernate.ticket().orElseThrow());
        WorldItemActivationResult activation = fixture.logical.prepareActivate(
                new ChunkKey(1, 0), hibernate.payload().orElseThrow());

        assertEquals(
                WorldItemActivationResult.Status.COMMITTED,
                fixture.system.commitActivate(
                        fixture.logical,
                        activation.ticket().orElseThrow(),
                        2).status());

        assertEquals(item.id(), fixture.logical.snapshot(item.id()).orElseThrow().id());
        assertEquals(1, fixture.physics.bodies().size());
        assertEquals(1, fixture.system.presentationSnapshots().size());
        assertEquals(item.id(), fixture.system.presentationSnapshots().get(0).id());
    }

    @Test
    void pagingProjectionBoundaryRejectsWorkerThreadBeforeMutation() throws Exception {
        Fixture fixture = fixture(4, PhysicalWorldItemSystem::createDefaultBody);
        WorldItemSnapshot item = fixture.spawn(new ChunkKey(0, 0));
        fixture.system.reconcileRestoredCanonicalState(1);
        WorldItemHibernateResult prepared = fixture.logical.prepareHibernate(
                new ChunkKey(0, 0), Map.of(item.id(), item.revision()));
        LogicalWorldItemSnapshot before = fixture.logical.canonicalSnapshot();
        AtomicReference<Throwable> failure = new AtomicReference<>();

        Thread worker = new Thread(() -> {
            try {
                fixture.system.commitHibernate(
                        fixture.logical, prepared.ticket().orElseThrow());
            } catch (Throwable thrown) {
                failure.set(thrown);
            }
        }, "world-item-paging-worker");
        worker.start();
        worker.join();

        assertTrue(failure.get() instanceof IllegalStateException);
        assertEquals(before, fixture.logical.canonicalSnapshot());
        assertEquals(1, fixture.physics.bodies().size());
        assertEquals(item.id(), fixture.system.presentationSnapshots().get(0).id());
    }

    @Test
    void hibernateRemovalFailureRestoresExactProjectionAndKeepsTicketRetryable() {
        AssertionError sentinel = new AssertionError("injected hibernate removal failure");
        AtomicBoolean fail = new AtomicBoolean(true);
        Fixture fixture = fixture(
                4,
                PhysicalWorldItemSystem::createDefaultBody,
                stage -> {},
                (stage, id) -> {
                    if (stage == PhysicalWorldItemSystem.ProjectionRemovalStage.AFTER_BODY_REMOVAL
                            && fail.getAndSet(false)) {
                        throw sentinel;
                    }
                });
        ChunkKey key = new ChunkKey(-1, 1);
        WorldItemSnapshot item = fixture.spawn(key);
        fixture.system.reconcileRestoredCanonicalState(1L);
        PhysicsBody body = fixture.physics.bodies().get(0);
        var presentationBefore = fixture.system.presentationSnapshots();
        var metricsBefore = fixture.system.metrics();
        LogicalWorldItemSnapshot logicalBefore = fixture.logical.canonicalSnapshot();
        WorldItemHibernateResult prepared = fixture.logical.prepareHibernate(
                key, Map.of(item.id(), item.revision()));

        assertSame(sentinel, assertThrows(AssertionError.class, () ->
                fixture.system.commitHibernate(
                        fixture.logical, prepared.ticket().orElseThrow())));

        assertEquals(logicalBefore, fixture.logical.canonicalSnapshot());
        assertEquals(List.of(body), fixture.physics.bodies());
        assertEquals(presentationBefore, fixture.system.presentationSnapshots());
        assertEquals(metricsBefore, fixture.system.metrics());
        assertEquals(WorldItemHibernateResult.Status.COMMITTED,
                fixture.system.commitHibernate(
                        fixture.logical, prepared.ticket().orElseThrow()).status());
        assertTrue(fixture.physics.bodies().isEmpty());
    }

    @Test
    void expiryRemovalFailureRestoresExactTickProjectionAndCanRetrySameTick() {
        AssertionError sentinel = new AssertionError("injected expiry removal failure");
        AtomicBoolean fail = new AtomicBoolean(true);
        Fixture fixture = fixture(
                4,
                PhysicalWorldItemSystem::createDefaultBody,
                stage -> {},
                (stage, id) -> {
                    if (stage == PhysicalWorldItemSystem.ProjectionRemovalStage.AFTER_MAP_REMOVAL
                            && fail.getAndSet(false)) {
                        throw sentinel;
                    }
                });
        WorldItemSnapshot item = fixture.spawn(new ChunkKey(0, 0));
        fixture.system.reconcileRestoredCanonicalState(1L);
        PhysicsBody body = fixture.physics.bodies().get(0);
        var presentationBefore = fixture.system.presentationSnapshots();
        var metricsBefore = fixture.system.metrics();
        LogicalWorldItemSnapshot logicalBefore = fixture.logical.canonicalSnapshot();
        long tickBefore = fixture.logical.currentWorldTick();
        long expiryTick = fixture.logical.physicalSnapshot(item.id())
                .orElseThrow().runtime().expiresAtWorldTick();

        assertSame(sentinel, assertThrows(AssertionError.class, () ->
                fixture.system.deliverWorldTick(fixture.logical, expiryTick)));

        assertEquals(tickBefore, fixture.logical.currentWorldTick());
        assertEquals(logicalBefore, fixture.logical.canonicalSnapshot());
        assertEquals(List.of(body), fixture.physics.bodies());
        assertEquals(presentationBefore, fixture.system.presentationSnapshots());
        assertEquals(metricsBefore, fixture.system.metrics());
        assertEquals(List.of(item.id()),
                fixture.system.deliverWorldTick(fixture.logical, expiryTick));
        assertTrue(fixture.physics.bodies().isEmpty());
    }

    @Test
    void partialMultiItemRemovalFailureRestoresBodyIdentityOrderAndMetrics() {
        AssertionError sentinel = new AssertionError("injected second removal failure");
        AtomicInteger removals = new AtomicInteger();
        AtomicBoolean fail = new AtomicBoolean(true);
        Fixture fixture = fixture(
                4,
                PhysicalWorldItemSystem::createDefaultBody,
                stage -> {},
                (stage, id) -> {
                    if (stage == PhysicalWorldItemSystem.ProjectionRemovalStage.AFTER_BODY_REMOVAL
                            && removals.incrementAndGet() == 2
                            && fail.getAndSet(false)) {
                        throw sentinel;
                    }
                });
        ChunkKey key = new ChunkKey(1, -1);
        WorldItemSnapshot first = fixture.spawn(key);
        WorldItemSnapshot second = fixture.spawn(key);
        fixture.system.reconcileRestoredCanonicalState(1L);
        List<PhysicsBody> bodiesBefore = fixture.physics.bodies();
        var metricsBefore = fixture.system.metrics();
        LogicalWorldItemSnapshot logicalBefore = fixture.logical.canonicalSnapshot();
        WorldItemHibernateResult prepared = fixture.logical.prepareHibernate(
                key, Map.of(
                        first.id(), first.revision(),
                        second.id(), second.revision()));

        assertSame(sentinel, assertThrows(AssertionError.class, () ->
                fixture.system.commitHibernate(
                        fixture.logical, prepared.ticket().orElseThrow())));

        assertEquals(logicalBefore, fixture.logical.canonicalSnapshot());
        assertEquals(bodiesBefore, fixture.physics.bodies());
        assertSame(bodiesBefore.get(0), fixture.physics.bodies().get(0));
        assertSame(bodiesBefore.get(1), fixture.physics.bodies().get(1));
        assertEquals(metricsBefore, fixture.system.metrics());
        assertEquals(WorldItemHibernateResult.Status.COMMITTED,
                fixture.system.commitHibernate(
                        fixture.logical, prepared.ticket().orElseThrow()).status());
    }

    @ParameterizedTest
    @EnumSource(PhysicalWorldItemSystem.ProjectionConstructionStage.class)
    void activationFailureAtEveryConstructionStageRollsBackExactly(
            PhysicalWorldItemSystem.ProjectionConstructionStage failedStage) {
        AssertionError sentinel = new AssertionError("injected " + failedStage);
        AtomicBoolean fail = new AtomicBoolean(false);
        Fixture fixture = fixture(
                4,
                PhysicalWorldItemSystem::createDefaultBody,
                stage -> {
                    if (stage == failedStage && fail.get()) {
                        throw sentinel;
                    }
                },
                (stage, id) -> {});
        ChunkKey key = new ChunkKey(-2, 0);
        WorldItemSnapshot item = fixture.spawn(key);
        fixture.system.reconcileRestoredCanonicalState(1L);
        WorldItemHibernateResult hibernate = fixture.logical.prepareHibernate(
                key, Map.of(item.id(), item.revision()));
        fixture.system.commitHibernate(fixture.logical, hibernate.ticket().orElseThrow());
        LogicalWorldItemSnapshot dormant = fixture.logical.canonicalSnapshot();
        var metricsBefore = fixture.system.metrics();
        WorldItemActivationResult activation = fixture.logical.prepareActivate(
                key, hibernate.payload().orElseThrow());
        fail.set(true);

        assertSame(sentinel, assertThrows(AssertionError.class, () ->
                fixture.system.commitActivate(
                        fixture.logical, activation.ticket().orElseThrow(), 2L)));

        assertEquals(dormant, fixture.logical.canonicalSnapshot());
        assertTrue(fixture.physics.bodies().isEmpty());
        assertTrue(fixture.system.presentationSnapshots().isEmpty());
        assertEquals(metricsBefore, fixture.system.metrics());
        fail.set(false);
        WorldItemActivationResult retry = fixture.logical.prepareActivate(
                key, hibernate.payload().orElseThrow());
        assertEquals(WorldItemActivationResult.Status.COMMITTED,
                fixture.system.commitActivate(
                        fixture.logical, retry.ticket().orElseThrow(), 2L).status());
        assertEquals(item.id(), fixture.system.presentationSnapshots().get(0).id());
    }

    @Test
    void sameServiceMutationFromRemovalCallbackFailsBeforeMutationAndRollsBack() {
        AtomicReference<LogicalWorldItemService> authority = new AtomicReference<>();
        Fixture fixture = fixture(
                4,
                PhysicalWorldItemSystem::createDefaultBody,
                stage -> {},
                (stage, id) -> {
                    if (stage == PhysicalWorldItemSystem.ProjectionRemovalStage.AFTER_BODY_REMOVAL) {
                        authority.get().spawn(new WorldItemSpawnRequest(
                                DIRT, 0.5, 4.0, 0.5, 0.0, 0.0, 0.0,
                                Optional.empty(), 2L));
                    }
                });
        authority.set(fixture.logical);
        ChunkKey key = new ChunkKey(0, 1);
        WorldItemSnapshot item = fixture.spawn(key);
        fixture.system.reconcileRestoredCanonicalState(1L);
        LogicalWorldItemSnapshot before = fixture.logical.canonicalSnapshot();
        PhysicsBody body = fixture.physics.bodies().get(0);
        WorldItemHibernateResult prepared = fixture.logical.prepareHibernate(
                key, Map.of(item.id(), item.revision()));

        assertThrows(IllegalStateException.class, () -> fixture.system.commitHibernate(
                fixture.logical, prepared.ticket().orElseThrow()));

        assertEquals(before, fixture.logical.canonicalSnapshot());
        assertEquals(List.of(body), fixture.physics.bodies());
    }

    @Test
    void failedConstructionNeverRemovesBodyItDidNotRegister() {
        MainThreadGuard guard = MainThreadGuard.captureCurrentThread();
        LogicalWorldItemService logical = new LogicalWorldItemService(guard, 2, 0);
        WorldItemSnapshot item = logical.spawn(new WorldItemSpawnRequest(
                DIRT, 0.5, 4.0, 0.5, 0.0, 0.0, 0.0,
                Optional.empty(), 1L)).item().orElseThrow();
        PhysicsWorld physics = new PhysicsWorld(
                new CollisionWorld(
                        new World(), BlockCollisionShapeResolver.fullCubesForNonAir()),
                new Vector3f());
        WorldItemPhysicsConfig config = new WorldItemPhysicsConfig(0.50f, 2);
        PhysicsBody externallyOwned = PhysicalWorldItemSystem.createDefaultBody(
                logical.physicalSnapshot(item.id()).orElseThrow(), config);
        assertTrue(physics.addBody(externallyOwned));
        PhysicalWorldItemSystem system = new PhysicalWorldItemSystem(
                logical, physics, guard, config, snapshot -> externallyOwned);

        assertThrows(IllegalStateException.class,
                () -> system.reconcileRestoredCanonicalState(1L));

        assertEquals(List.of(externallyOwned), physics.bodies());
        assertTrue(system.presentationSnapshots().isEmpty());
    }

    @Test
    void splitExpiryRemovalFailureRestoresEveryProjectionAndCanRetry() {
        AssertionError sentinel = new AssertionError("split expiry removal failure");
        AtomicInteger removals = new AtomicInteger();
        AtomicBoolean fail = new AtomicBoolean(true);
        Fixture fixture = fixture(
                4,
                PhysicalWorldItemSystem::createDefaultBody,
                stage -> {},
                (stage, id) -> {
                    if (stage == PhysicalWorldItemSystem.ProjectionRemovalStage.AFTER_MAP_REMOVAL
                            && removals.incrementAndGet() == 2
                            && fail.getAndSet(false)) {
                        throw sentinel;
                    }
                });
        WorldItemSnapshot first = fixture.spawn(new ChunkKey(0, 0));
        WorldItemSnapshot second = fixture.spawn(new ChunkKey(1, 0));
        fixture.system.reconcileRestoredCanonicalState(1L);
        List<PhysicsBody> bodiesBefore = fixture.physics.bodies();
        var presentationsBefore = fixture.system.presentationSnapshots();
        var metricsBefore = fixture.system.metrics();
        long expiryTick = fixture.logical.physicalSnapshot(first.id())
                .orElseThrow().runtime().expiresAtWorldTick();
        List<com.overlord.worlditem.api.WorldItemId> expired =
                fixture.logical.deliverWorldTick(expiryTick);
        assertEquals(List.of(first.id(), second.id()), expired);

        assertSame(sentinel, assertThrows(AssertionError.class,
                () -> fixture.system.removeExpiredProjections(expired)));

        assertEquals(bodiesBefore, fixture.physics.bodies());
        assertEquals(presentationsBefore, fixture.system.presentationSnapshots());
        assertEquals(metricsBefore.liveProjections(),
                fixture.system.metrics().liveProjections());
        assertEquals(metricsBefore.destroyed(), fixture.system.metrics().destroyed());
        fixture.system.removeExpiredProjections(expired);
        assertTrue(fixture.physics.bodies().isEmpty());
    }

    @Test
    void lateReplacementPublicationFailureRestoresPreexistingProjectionExactly() {
        AssertionError sentinel = new AssertionError("late replacement failure");
        AtomicBoolean fail = new AtomicBoolean(false);
        Fixture fixture = fixture(
                2,
                PhysicalWorldItemSystem::createDefaultBody,
                stage -> {
                    if (stage == PhysicalWorldItemSystem.ProjectionConstructionStage.AFTER_MAP_INSERTION
                            && fail.get()) {
                        throw sentinel;
                    }
                },
                (stage, id) -> {});
        WorldItemSnapshot original = fixture.spawn(new ChunkKey(0, 0));
        fixture.system.reconcileRestoredCanonicalState(1L);
        PhysicsBody originalBody = fixture.physics.bodies().get(0);
        var presentationBefore = fixture.system.presentationSnapshots();
        var metricsBefore = fixture.system.metrics();
        assertEquals(
                com.overlord.worlditem.api.WorldItemMotionUpdateResult.Status.APPLIED,
                fixture.logical.updateMotion(new WorldItemMotionUpdate(
                        original.id(), original.revision(),
                        original.positionX() + 0.25, original.positionY(),
                        original.positionZ(), original.velocityX(),
                        original.velocityY(), original.velocityZ(),
                        WorldItemPhysicalState.ACTIVE)).status());
        fail.set(true);

        assertSame(sentinel, assertThrows(AssertionError.class,
                () -> fixture.system.reconcileRestoredCanonicalState(2L)));

        assertEquals(List.of(originalBody), fixture.physics.bodies());
        assertEquals(presentationBefore, fixture.system.presentationSnapshots());
        assertEquals(metricsBefore.created(), fixture.system.metrics().created());
        assertEquals(metricsBefore.destroyed(), fixture.system.metrics().destroyed());
        fail.set(false);
        fixture.system.reconcileRestoredCanonicalState(2L);
        assertEquals(1, fixture.physics.bodies().size());
    }

    @Test
    void reconciliationBuildsCompleteDetachedBatchBeforeRegisteringBodies() {
        MainThreadGuard guard = MainThreadGuard.captureCurrentThread();
        LogicalWorldItemService logical = new LogicalWorldItemService(guard, 2, 0);
        spawn(logical, new ChunkKey(0, 0));
        spawn(logical, new ChunkKey(1, 0));
        PhysicsWorld physics = new PhysicsWorld(
                new CollisionWorld(
                        new World(), BlockCollisionShapeResolver.fullCubesForNonAir()),
                new Vector3f());
        AtomicInteger builds = new AtomicInteger();
        PhysicalWorldItemSystem system = new PhysicalWorldItemSystem(
                logical,
                physics,
                guard,
                new WorldItemPhysicsConfig(0.50f, 2),
                snapshot -> {
                    assertTrue(physics.bodies().isEmpty(),
                            "no candidate body is visible while later bodies build");
                    builds.incrementAndGet();
                    return PhysicalWorldItemSystem.createDefaultBody(
                            snapshot, new WorldItemPhysicsConfig(0.50f, 2));
                });

        system.reconcileRestoredCanonicalState(1L);

        assertEquals(2, builds.get());
        assertEquals(2, physics.bodies().size());
    }

    private static Fixture fixture(
            int capacity, PhysicalWorldItemSystem.ProjectionFactory factory) {
        return fixture(
                capacity,
                factory,
                stage -> {},
                (stage, id) -> {});
    }

    private static Fixture fixture(
            int capacity,
            PhysicalWorldItemSystem.ProjectionFactory factory,
            PhysicalWorldItemSystem.ProjectionConstructionObserver constructionObserver,
            PhysicalWorldItemSystem.ProjectionRemovalObserver removalObserver) {
        MainThreadGuard guard = MainThreadGuard.captureCurrentThread();
        LogicalWorldItemService logical = new LogicalWorldItemService(guard, capacity, 0);
        PhysicsWorld physics = new PhysicsWorld(
                new CollisionWorld(
                        new World(), BlockCollisionShapeResolver.fullCubesForNonAir()),
                new Vector3f());
        PhysicalWorldItemSystem system = new PhysicalWorldItemSystem(
                logical,
                physics,
                guard,
                new WorldItemPhysicsConfig(0.50f, capacity),
                factory,
                constructionObserver,
                removalObserver);
        return new Fixture(logical, physics, system);
    }

    private static Fixture pagedFixture(
            int capacity,
            PhysicalWorldItemSystem.ProjectionFactory factory,
            PhysicalWorldItemSystem.ProjectionRemovalObserver removalObserver) {
        MainThreadGuard guard = MainThreadGuard.captureCurrentThread();
        LogicalWorldItemService logical = new LogicalWorldItemService(
                guard,
                capacity,
                0L,
                new SaveIdentity(UUID.fromString(
                        "123e4567-e89b-12d3-a456-426614174222")),
                new WorldItemPageCachePolicy(
                        1_024, 32, 16L * 1_024L * 1_024L,
                        64, 1_024, 16L * 1_024L * 1_024L,
                        64, 64L * 1_024L),
                (ticket, plan, proof) -> {
                    if (!(proof instanceof PhysicalProof checked)
                            || checked.checkpointRevision()
                                    != plan.intendedCheckpoint().checkpointRevision()
                            || !checked.transactionDigest()
                                    .equals(plan.transactionDigest())) {
                        throw new IllegalArgumentException("proof mismatch");
                    }
                },
                PhysicalWorldItemPagingTest::descriptor);
        PhysicsWorld physics = new PhysicsWorld(
                new CollisionWorld(
                        new World(), BlockCollisionShapeResolver.fullCubesForNonAir()),
                new Vector3f());
        PhysicalWorldItemSystem system = new PhysicalWorldItemSystem(
                logical,
                physics,
                guard,
                new WorldItemPhysicsConfig(0.50f, capacity),
                factory,
                stage -> {},
                removalObserver);
        return new Fixture(logical, physics, system);
    }

    private static WorldItemPageDescriptor descriptor(WorldItemPageSnapshot page) {
        long token = Integer.toUnsignedLong(java.util.Objects.hash(
                page.chunkKey(),
                page.pageRevision(),
                page.entries().stream()
                        .map(entry -> entry.runtime().item().id()).toList(),
                page.entries().stream()
                        .map(entry -> entry.runtime().item().revision()).toList()));
        return new WorldItemPageDescriptor(
                page.chunkKey(), page.pageRevision(), String.format("%064x", token),
                page.entries().size(), page.entries().size());
    }

    private static WorldItemHibernateResult commitLinked(
            PhysicalWorldItemSystem system,
            LogicalWorldItemService logical,
            WorldItemHibernateTicket hibernateTicket,
            WorldItemPersistenceTicket persistenceTicket,
            WorldItemDurableProof proof) {
        try {
            var method = PhysicalWorldItemSystem.class.getMethod(
                    "commitLinkedHibernate",
                    LogicalWorldItemService.class,
                    WorldItemHibernateTicket.class,
                    WorldItemPersistenceTicket.class,
                    WorldItemDurableProof.class);
            return (WorldItemHibernateResult) method.invoke(
                    system, logical, hibernateTicket, persistenceTicket, proof);
        } catch (NoSuchMethodException missing) {
            throw new AssertionError(
                    "missing rollback-safe physical linked hibernate API", missing);
        } catch (InvocationTargetException invoked) {
            Throwable cause = invoked.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new AssertionError(cause);
        } catch (ReflectiveOperationException reflectionFailure) {
            throw new AssertionError(reflectionFailure);
        }
    }

    private static WorldItemSnapshot spawn(
            LogicalWorldItemService logical, ChunkKey key) {
        return logical.spawn(new WorldItemSpawnRequest(
                DIRT,
                key.worldOriginX() + 0.5,
                4.0,
                key.worldOriginZ() + 0.5,
                0.0, 0.0, 0.0,
                Optional.empty(),
                1L)).item().orElseThrow();
    }

    private record Fixture(
            LogicalWorldItemService logical,
            PhysicsWorld physics,
            PhysicalWorldItemSystem system) {
        private WorldItemSnapshot spawn(ChunkKey key) {
            return logical.spawn(new WorldItemSpawnRequest(
                    DIRT,
                    key.worldOriginX() + 0.5,
                    4.0,
                    key.worldOriginZ() + 0.5,
                    0.0, 0.0, 0.0,
                    Optional.empty(),
                    1)).item().orElseThrow();
        }
    }

    private record PhysicalProof(
            long checkpointRevision,
            String transactionDigest) implements WorldItemDurableProof {}
}
