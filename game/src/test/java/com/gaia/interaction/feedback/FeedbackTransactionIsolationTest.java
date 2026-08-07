package com.gaia.interaction.feedback;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gaia.blocks.ItemFormDefinition;
import com.gaia.blocks.BlockDefinition;
import com.gaia.blocks.BlockRegistry;
import com.gaia.inventory.BodyInventoryService;
import com.gaia.interaction.BlockBreakResult;
import com.gaia.interaction.BlockBreakTransaction;
import com.gaia.interaction.BlockInteractionController;
import com.gaia.interaction.BlockPlacementTransaction;
import com.gaia.interaction.BlockPlacementWorldView;
import com.gaia.interaction.CreativeSelection;
import com.gaia.interaction.GameMode;
import com.gaia.interaction.GameModeManager;
import com.overlord.assets.ResourceLocation;
import com.overlord.config.GameConfig;
import com.overlord.core.input.InputSnapshot;
import com.overlord.core.thread.MainThreadGuard;
import com.overlord.interaction.BlockWorldAccess;
import com.overlord.interaction.BlockWorldMutationOutcome;
import com.overlord.interaction.DefaultWorldMutationService;
import com.overlord.interaction.SynchronousBlockChangeEventPublisher;
import com.overlord.interaction.api.BlockChangeDecision;
import com.overlord.interaction.api.BlockChangeDispatchException;
import com.overlord.interaction.api.BlockChangeRequest;
import com.overlord.interaction.api.BlockHitResult;
import com.overlord.interaction.api.EntityRef;
import com.overlord.inventory.api.BodySlot;
import com.overlord.inventory.api.InventoryService;
import com.overlord.inventory.api.ItemStack;
import com.overlord.renderer.particle.ParticleEmission;
import com.overlord.renderer.material.MaterialDefinition;
import com.overlord.renderer.material.RenderType;
import com.overlord.renderer.texture.TextureRegion;
import com.overlord.physics.Aabb;
import com.overlord.physics.MassProperties;
import com.overlord.physics.PhysicsBody;
import com.overlord.voxel.BlockRenderInfo;
import com.overlord.voxel.ChunkKey;
import com.overlord.voxel.ChunkRepository;
import com.overlord.voxel.DirtyChunkRevision;
import com.overlord.worlditem.LogicalWorldItemService;
import com.overlord.worlditem.api.WorldItemService;
import com.overlord.worlditem.api.WorldItemSpawnReservations;
import com.overlord.worlditem.api.WorldItemSpawnReservationAudit;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.joml.Vector3f;

class FeedbackTransactionIsolationTest {
    private static final EntityRef OWNER = new EntityRef(19);
    private static final ResourceLocation AIR = ResourceLocation.parse("gaia:air");
    private static final ResourceLocation STONE = ResourceLocation.parse("gaia:stone");
    private static final ResourceLocation DIRT = ResourceLocation.parse("gaia:dirt");
    private static final ResourceLocation MISSING = ResourceLocation.parse("gaia:missing");
    private static final TextureRegion REGION = new TextureRegion(
            ResourceLocation.parse("gaia:stone_top"), 0, 0, 16, 16, 16, 16);

    @Test
    void recoverableVisualFailureDoesNotEscapeOrChangeCommittedMutation() {
        RuntimeException visualFailure = new IllegalStateException("visual");
        List<Throwable> diagnostics = new ArrayList<>();
        MutableBlockWorld world = new MutableBlockWorld(STONE);
        CommittedBreakVisualAdapter adapter = new CommittedBreakVisualAdapter(
                AIR,
                ignored -> {
                    throw visualFailure;
                },
                emission -> {},
                (event, failure) -> diagnostics.add(failure));
        DefaultWorldMutationService mutations = mutationService(world, adapter);

        var result = mutations.changeBlock(request());

        assertEquals(com.overlord.interaction.api.BlockChangeResult.Status.APPLIED, result.status());
        assertEquals(AIR, world.block);
        assertEquals(List.of(visualFailure), diagnostics);
    }

    @Test
    void beforeCancellationRollsBackReservationWithoutChangedEventOrCompletionEmission() {
        List<String> trace = new ArrayList<>();
        List<ParticleEmission> emissions = new ArrayList<>();
        CommittedBreakVisualAdapter adapter = recordingAdapter(trace, emissions);
        MutableBlockWorld world = new MutableBlockWorld(STONE, trace);
        RecordingInventory inventory = new RecordingInventory(inventory(), trace);
        RecordingWorldItems worldItems = recordingWorldItems(2, trace);
        BlockBreakTransaction transaction = new BlockBreakTransaction(
                tracingMutationService(world, adapter, trace, BlockChangeDecision.CANCEL),
                inventory, OWNER, worldItems, AIR);

        BlockBreakResult result = transaction.execute(
                hit(), Optional.of(new ItemStack(STONE, 1)), BodySlot.LEFT_HAND, 41, 4100);

        assertEquals(BlockBreakResult.Status.MUTATION_REJECTED, result.status());
        assertEquals(STONE, world.block);
        assertEquals(List.of(), emissions);
        assertEquals(
                List.of(
                        "world.reserve",
                        "mutation.call",
                        "before",
                        "world.rollback"),
                trace);
        assertEquals(0, inventory.commits);
        assertEquals(0, inventory.rollbacks);
        assertEquals(0, worldItems.commits);
        assertEquals(1, worldItems.rollbacks);
    }

    @Test
    void mutationConflictRollsBackReservationWithoutPublishingCommittedEvents() {
        List<String> trace = new ArrayList<>();
        List<ParticleEmission> emissions = new ArrayList<>();
        CommittedBreakVisualAdapter adapter = recordingAdapter(trace, emissions);
        MutableBlockWorld world = new MutableBlockWorld(DIRT, trace);
        RecordingInventory inventory = new RecordingInventory(inventory(), trace);
        RecordingWorldItems worldItems = recordingWorldItems(2, trace);
        BlockBreakTransaction transaction = new BlockBreakTransaction(
                tracingMutationService(world, adapter, trace, BlockChangeDecision.ALLOW),
                inventory, OWNER, worldItems, AIR);

        BlockBreakResult result = transaction.execute(
                hit(), Optional.of(new ItemStack(STONE, 1)), BodySlot.LEFT_HAND, 41, 4100);

        assertEquals(BlockBreakResult.Status.MUTATION_REJECTED, result.status());
        assertEquals(DIRT, world.block);
        assertEquals(List.of(), emissions);
        assertEquals(
                List.of(
                        "world.reserve",
                        "mutation.call",
                        "world.rollback"),
                trace);
        assertEquals(0, inventory.commits);
        assertEquals(0, inventory.rollbacks);
        assertEquals(0, worldItems.commits);
        assertEquals(1, worldItems.rollbacks);
    }

    @Test
    void inventoryAndWorldCapacityRejectionStopBeforeMutationAndEmitNothing() {
        BodyInventoryService fullInventory = inventory();
        fullInventory.insert(OWNER, new ItemStack(DIRT, 128));
        LogicalWorldItemService fullWorldItems = new LogicalWorldItemService(
                MainThreadGuard.captureCurrentThread(), 1, 10);
        fullWorldItems.spawn(new com.overlord.worlditem.api.WorldItemSpawnRequest(
                new ItemStack(STONE, 1), 0, 0, 0, 0, 0, 0,
                Optional.of(OWNER), 1));
        List<String> trace = new ArrayList<>();
        List<ParticleEmission> emissions = new ArrayList<>();
        RecordingInventory inventory = new RecordingInventory(fullInventory, trace);
        RecordingWorldItems worldItems = new RecordingWorldItems(fullWorldItems, trace);
        CommittedBreakVisualAdapter adapter = recordingAdapter(trace, emissions);
        BlockBreakTransaction transaction = new BlockBreakTransaction(
                tracingMutationService(
                        new MutableBlockWorld(STONE, trace),
                        adapter,
                        trace,
                        BlockChangeDecision.ALLOW),
                inventory, OWNER, worldItems, AIR);

        BlockBreakResult result = transaction.execute(
                hit(), Optional.of(new ItemStack(STONE, 1)), BodySlot.LEFT_HAND, 41, 4100);

        assertEquals(BlockBreakResult.Status.RESERVATION_REJECTED, result.status());
        assertEquals(List.of(), emissions);
        assertEquals(
                List.of("world.reserve"),
                trace);
        assertEquals(0, inventory.commits);
        assertEquals(0, inventory.rollbacks);
        assertEquals(0, worldItems.commits);
        assertEquals(0, worldItems.rollbacks);
    }

    @Test
    void ordinaryControllerReleaseCancelsSessionBeforeTransactionOrCommittedEvent() {
        List<String> trace = new ArrayList<>();
        List<ParticleEmission> emissions = new ArrayList<>();
        CommittedBreakVisualAdapter adapter = recordingAdapter(trace, emissions);
        MutableBlockWorld world = new MutableBlockWorld(STONE, trace);
        BlockRegistry blocks = blocks();
        ChunkRepository chunks = new ChunkRepository();
        chunks.generate(new ChunkKey(0, 0), ignored -> {});
        BodyInventoryService inventory = new BodyInventoryService(
                OWNER, blocks, MainThreadGuard.captureCurrentThread(), ignored -> {});
        LogicalWorldItemService worldItems = new LogicalWorldItemService(
                MainThreadGuard.captureCurrentThread(), 4, 10);
        com.overlord.interaction.api.WorldMutationService mutations =
                tracingMutationService(world, adapter, trace, BlockChangeDecision.ALLOW);
        PhysicsBody body = new PhysicsBody(
                new Aabb(-0.3f, 0, -0.3f, 0.3f, 1.8f, 0.3f),
                MassProperties.dynamic(1));
        body.teleport(new Vector3f(0, 0, 0));
        BlockPlacementWorldView placementWorld = new BlockPlacementWorldView() {
            @Override
            public boolean isLoaded(int x, int y, int z) {
                return true;
            }

            @Override
            public ResourceLocation blockAt(int x, int y, int z) {
                return AIR;
            }
        };
        BlockInteractionController controller = new BlockInteractionController(
                new GameModeManager(GameMode.SURVIVAL, ignored -> {}),
                () -> Optional.of(hit()),
                chunks,
                blocks,
                inventory,
                OWNER,
                new CreativeSelection(blocks, Optional.of(STONE)),
                new BlockBreakTransaction(mutations, inventory, OWNER, worldItems, AIR),
                new BlockPlacementTransaction(
                        mutations, inventory, OWNER, blocks, placementWorld, body, AIR),
                1);
        InputSnapshot held = new InputSnapshot(
                Set.of(), Set.of(),
                Set.of(GameConfig.Input.MOUSE_PRIMARY),
                Set.of(GameConfig.Input.MOUSE_PRIMARY),
                List.of());

        controller.fixedUpdate(held, 1.0 / 60.0, 1, 1, true);
        assertTrue(controller.viewModel().progress() > 0);
        controller.fixedUpdate(
                new InputSnapshot(Set.of(), Set.of(), Set.of(), Set.of(), List.of()),
                1.0 / 60.0,
                2,
                2,
                true);

        assertEquals(0.0, controller.viewModel().progress());
        assertEquals(com.overlord.interaction.api.InteractionMode.NONE,
                controller.viewModel().mode());
        assertEquals(List.of(), emissions);
        assertEquals(List.of(), trace);
        assertEquals(STONE, world.block);
    }

    @Test
    void fatalVisualFailureRemainsMutationAppliedAndGuaranteedDropCommitRunsOnce() {
        AssertionError fatal = new AssertionError("fatal");
        List<String> trace = new ArrayList<>();
        int[] emissionCalls = {0};
        MutableBlockWorld world = new MutableBlockWorld(STONE, trace);
        CommittedBreakVisualAdapter adapter = new CommittedBreakVisualAdapter(
                AIR,
                ignored -> REGION,
                emission -> {
                    trace.add("emission");
                    emissionCalls[0]++;
                    throw fatal;
                },
                (event, failure) -> trace.add("diagnostic:" + failure.getMessage()));
        com.overlord.interaction.api.WorldMutationService mutations =
                tracingMutationService(world, adapter, trace, BlockChangeDecision.ALLOW);
        RecordingInventory inventory = new RecordingInventory(inventory(), trace);
        RecordingWorldItems worldItems = new RecordingWorldItems(
                new LogicalWorldItemService(
                        MainThreadGuard.captureCurrentThread(), 2, 10),
                trace);
        BlockBreakTransaction transaction = new BlockBreakTransaction(
                mutations, inventory, OWNER, worldItems, AIR);

        BlockBreakResult result = transaction.execute(
                hit(), Optional.of(new ItemStack(STONE, 1)), BodySlot.LEFT_HAND, 41, 4100);

        assertEquals(BlockBreakResult.Status.APPLIED_WITH_NOTIFICATION_FAILURE, result.status());
        assertEquals(AIR, world.block);
        assertEquals(1, emissionCalls[0]);
        assertEquals(0, inventory.totalCount(OWNER, STONE));
        assertEquals(0, result.inventoryCommitted());
        assertEquals(1, result.worldItemCommitted());
        assertEquals(0, inventory.commits);
        assertEquals(0, inventory.rollbacks);
        assertEquals(1, worldItems.commits);
        assertEquals(0, worldItems.rollbacks);
        assertEquals(
                List.of(
                        "world.reserve",
                        "mutation.call",
                        "before",
                        "mutation.apply",
                        "changed",
                        "emission",
                        "diagnostic:fatal",
                        "dirty",
                        "world.commit"),
                trace);
        assertTrue(result.failure().isPresent());
        BlockChangeDispatchException dispatch = assertInstanceOf(
                BlockChangeDispatchException.class, result.failure().orElseThrow());
        assertTrue(dispatch.mutationApplied());
        assertSame(fatal, dispatch.getCause());
    }

    @Test
    void fatalDiagnosticAfterRecoverableVisualFailureRemainsObservableAndCommitsOnce() {
        RuntimeException visualFailure = new IllegalStateException("recoverable visual");
        AssertionError fatalDiagnostic = new AssertionError("fatal diagnostic");
        List<String> trace = new ArrayList<>();
        int[] emissionCalls = {0};
        int[] diagnosticCalls = {0};
        MutableBlockWorld world = new MutableBlockWorld(STONE, trace);
        CommittedBreakVisualAdapter adapter = new CommittedBreakVisualAdapter(
                AIR,
                ignored -> REGION,
                emission -> {
                    trace.add("emission");
                    emissionCalls[0]++;
                    throw visualFailure;
                },
                (event, failure) -> {
                    trace.add("diagnostic:" + failure.getMessage());
                    diagnosticCalls[0]++;
                    assertSame(visualFailure, failure);
                    throw fatalDiagnostic;
                });
        com.overlord.interaction.api.WorldMutationService mutations =
                tracingMutationService(world, adapter, trace, BlockChangeDecision.ALLOW);
        RecordingInventory inventory = new RecordingInventory(inventory(), trace);
        RecordingWorldItems worldItems = new RecordingWorldItems(
                new LogicalWorldItemService(
                        MainThreadGuard.captureCurrentThread(), 2, 10),
                trace);
        BlockBreakTransaction transaction = new BlockBreakTransaction(
                mutations, inventory, OWNER, worldItems, AIR);

        BlockBreakResult result = transaction.execute(
                hit(), Optional.of(new ItemStack(STONE, 1)), BodySlot.LEFT_HAND, 41, 4100);

        assertEquals(BlockBreakResult.Status.APPLIED_WITH_NOTIFICATION_FAILURE, result.status());
        assertEquals(AIR, world.block);
        assertEquals(1, emissionCalls[0]);
        assertEquals(1, diagnosticCalls[0]);
        assertEquals(0, inventory.totalCount(OWNER, STONE));
        assertEquals(0, result.inventoryCommitted());
        assertEquals(1, result.worldItemCommitted());
        assertEquals(0, inventory.commits);
        assertEquals(0, inventory.rollbacks);
        assertEquals(1, worldItems.commits);
        assertEquals(0, worldItems.rollbacks);
        assertEquals(
                List.of(
                        "world.reserve",
                        "mutation.call",
                        "before",
                        "mutation.apply",
                        "changed",
                        "emission",
                        "diagnostic:recoverable visual",
                        "dirty",
                        "world.commit"),
                trace);
        BlockChangeDispatchException dispatch = assertInstanceOf(
                BlockChangeDispatchException.class, result.failure().orElseThrow());
        assertTrue(dispatch.mutationApplied());
        assertSame(fatalDiagnostic, dispatch.getCause());
        assertEquals(1, fatalDiagnostic.getSuppressed().length);
        assertSame(visualFailure, fatalDiagnostic.getSuppressed()[0]);
    }

    private static DefaultWorldMutationService mutationService(
            MutableBlockWorld world, CommittedBreakVisualAdapter adapter) {
        return new DefaultWorldMutationService(
                MainThreadGuard.captureCurrentThread(),
                world,
                new SynchronousBlockChangeEventPublisher(
                        ignored -> BlockChangeDecision.ALLOW,
                        adapter::onBlockChanged,
                        ignored -> {}));
    }

    private static CommittedBreakVisualAdapter recordingAdapter(
            List<String> trace, List<ParticleEmission> emissions) {
        return new CommittedBreakVisualAdapter(
                AIR,
                ignored -> REGION,
                emission -> {
                    trace.add("emission");
                    emissions.add(emission);
                },
                (event, failure) -> trace.add("diagnostic:" + failure.getMessage()));
    }

    private static RecordingWorldItems recordingWorldItems(
            int capacity, List<String> trace) {
        return new RecordingWorldItems(
                new LogicalWorldItemService(
                        MainThreadGuard.captureCurrentThread(), capacity, 10),
                trace);
    }

    private static com.overlord.interaction.api.WorldMutationService tracingMutationService(
            MutableBlockWorld world,
            CommittedBreakVisualAdapter adapter,
            List<String> trace,
            BlockChangeDecision beforeDecision) {
        DefaultWorldMutationService delegate = new DefaultWorldMutationService(
                MainThreadGuard.captureCurrentThread(),
                world,
                new SynchronousBlockChangeEventPublisher(
                        ignored -> {
                            trace.add("before");
                            return beforeDecision;
                        },
                        event -> {
                            trace.add("changed");
                            adapter.onBlockChanged(event);
                        },
                        ignored -> trace.add("dirty")));
        return request -> {
            trace.add("mutation.call");
            return delegate.changeBlock(request);
        };
    }

    private static BlockChangeRequest request() {
        return new BlockChangeRequest(
                new com.gaia.interaction.GaiaInteractionContext(
                        OWNER, BodySlot.LEFT_HAND,
                        com.overlord.interaction.api.InteractionAction.PRIMARY,
                        41, 4100),
                1, 2, 3, STONE, AIR);
    }

    private static BlockHitResult hit() {
        return new BlockHitResult(
                1, 2, 3, 2, 2, 3, STONE,
                1, 0, 0, 2, 2.5f, 3.5f, 2);
    }

    private static BodyInventoryService inventory() {
        Map<ResourceLocation, ItemFormDefinition> forms = Map.of(
                STONE, new ItemFormDefinition(STONE, 64, false, false),
                DIRT, new ItemFormDefinition(DIRT, 64, false, false));
        return new BodyInventoryService(
                OWNER, id -> Optional.ofNullable(forms.get(id)), ignored -> {});
    }

    private static BlockRegistry blocks() {
        MaterialDefinition material = new MaterialDefinition(
                ResourceLocation.parse("gaia:opaque"),
                ResourceLocation.parse("gaia:blocks"),
                RenderType.OPAQUE,
                0.5f,
                MISSING);
        TextureRegion region = new TextureRegion(MISSING, 0, 0, 1, 1, 1, 1);
        BlockDefinition air = blockDefinition(0, AIR, material.id());
        BlockDefinition stone = blockDefinition(1, STONE, material.id());
        return BlockRegistry.create(
                List.of(air, stone),
                Map.of(
                        0, BlockRenderInfo.nonRenderable(material, region),
                        1, renderInfo(material, region)));
    }

    private static BlockDefinition blockDefinition(
            int id, ResourceLocation name, ResourceLocation material) {
        EnumMap<com.overlord.voxel.BlockFace, ResourceLocation> textures =
                new EnumMap<>(com.overlord.voxel.BlockFace.class);
        for (com.overlord.voxel.BlockFace face : com.overlord.voxel.BlockFace.values()) {
            textures.put(face, MISSING);
        }
        return new BlockDefinition(
                id, name, material, textures, 1, 1, 1,
                false, false, 1,
                id == 0 ? null : new ItemFormDefinition(name, 64, false, false));
    }

    private static BlockRenderInfo renderInfo(
            MaterialDefinition material, TextureRegion region) {
        EnumMap<com.overlord.voxel.BlockFace, TextureRegion> faces =
                new EnumMap<>(com.overlord.voxel.BlockFace.class);
        for (com.overlord.voxel.BlockFace face : com.overlord.voxel.BlockFace.values()) {
            faces.put(face, region);
        }
        return new BlockRenderInfo(material, faces, true);
    }

    private static final class MutableBlockWorld implements BlockWorldAccess {
        private ResourceLocation block;
        private final List<String> trace;

        private MutableBlockWorld(ResourceLocation block) {
            this(block, new ArrayList<>());
        }

        private MutableBlockWorld(ResourceLocation block, List<String> trace) {
            this.block = block;
            this.trace = trace;
        }

        @Override
        public boolean isWithinBounds(int x, int y, int z) {
            return true;
        }

        @Override
        public boolean isKnownBlock(ResourceLocation candidate) {
            return candidate.equals(AIR) || candidate.equals(STONE) || candidate.equals(DIRT);
        }

        @Override
        public ResourceLocation blockAt(int x, int y, int z) {
            return block;
        }

        @Override
        public BlockWorldMutationOutcome compareAndSetBlock(
                int x,
                int y,
                int z,
                ResourceLocation expectedBlock,
                ResourceLocation replacementBlock) {
            trace.add("mutation.apply");
            ResourceLocation previous = block;
            block = replacementBlock;
            return new BlockWorldMutationOutcome(
                    BlockWorldMutationOutcome.Status.APPLIED,
                    previous,
                    List.of(new DirtyChunkRevision(new ChunkKey(0, 0), 1)));
        }
    }

    private static final class RecordingInventory implements InventoryService {
        private final BodyInventoryService delegate;
        private final List<String> trace;
        private int commits;
        private int rollbacks;

        private RecordingInventory(BodyInventoryService delegate, List<String> trace) {
            this.delegate = delegate;
            this.trace = trace;
        }

        private int totalCount(EntityRef owner, ResourceLocation itemId) {
            return delegate.totalCount(owner, itemId);
        }

        @Override
        public Optional<com.overlord.inventory.api.InventoryView> snapshot(EntityRef owner) {
            return delegate.snapshot(owner);
        }

        @Override
        public com.overlord.inventory.api.InventoryChangeResult replaceSlot(
                com.overlord.inventory.api.InventoryChangeRequest request) {
            return delegate.replaceSlot(request);
        }

        @Override
        public com.overlord.inventory.api.InventoryReserveResult reserve(
                com.overlord.inventory.api.InventoryReservationRequest request) {
            trace.add("inventory.reserve:" + request.slot());
            return delegate.reserve(request);
        }

        @Override
        public com.overlord.inventory.api.InventoryReservationResult commit(
                com.overlord.inventory.api.InventoryReservationId reservationId) {
            trace.add("inventory.commit");
            commits++;
            return delegate.commit(reservationId);
        }

        @Override
        public com.overlord.inventory.api.InventoryReservationResult rollback(
                com.overlord.inventory.api.InventoryReservationId reservationId) {
            trace.add("inventory.rollback");
            rollbacks++;
            return delegate.rollback(reservationId);
        }
    }

    private static final class RecordingWorldItems
            implements WorldItemService,
                    WorldItemSpawnReservations,
                    WorldItemSpawnReservationAudit {
        private final LogicalWorldItemService delegate;
        private final List<String> trace;
        private int commits;
        private int rollbacks;

        private RecordingWorldItems(
                LogicalWorldItemService delegate, List<String> trace) {
            this.delegate = delegate;
            this.trace = trace;
        }

        @Override
        public com.overlord.worlditem.api.WorldItemSpawnResult spawn(
                com.overlord.worlditem.api.WorldItemSpawnRequest request) {
            return delegate.spawn(request);
        }

        @Override
        public Optional<com.overlord.worlditem.api.WorldItemSnapshot> snapshot(
                com.overlord.worlditem.api.WorldItemId itemId) {
            return delegate.snapshot(itemId);
        }

        @Override
        public com.overlord.worlditem.api.WorldItemReservationResult reserve(
                com.overlord.worlditem.api.WorldItemId itemId, int count) {
            return delegate.reserve(itemId, count);
        }

        @Override
        public com.overlord.worlditem.api.WorldItemReservationResult commit(
                com.overlord.worlditem.api.WorldItemReservationId reservationId) {
            return delegate.commit(reservationId);
        }

        @Override
        public com.overlord.worlditem.api.WorldItemReservationResult rollback(
                com.overlord.worlditem.api.WorldItemReservationId reservationId) {
            return delegate.rollback(reservationId);
        }

        @Override
        public com.overlord.worlditem.api.WorldItemSpawnReserveResult reserveSpawn(
                com.overlord.worlditem.api.WorldItemSpawnRequest request) {
            trace.add("world.reserve");
            return delegate.reserveSpawn(request);
        }

        @Override
        public com.overlord.worlditem.api.WorldItemSpawnCommitResult commitSpawn(
                com.overlord.worlditem.api.WorldItemSpawnReservationId reservationId) {
            trace.add("world.commit");
            commits++;
            return delegate.commitSpawn(reservationId);
        }

        @Override
        public com.overlord.worlditem.api.WorldItemSpawnCommitResult rollbackSpawn(
                com.overlord.worlditem.api.WorldItemSpawnReservationId reservationId) {
            trace.add("world.rollback");
            rollbacks++;
            return delegate.rollbackSpawn(reservationId);
        }

        @Override
        public Optional<com.overlord.worlditem.api.WorldItemSpawnReservationAuditSnapshot>
                spawnReservationAudit(
                        com.overlord.worlditem.api.WorldItemSpawnReservationId reservationId) {
            return delegate.spawnReservationAudit(reservationId);
        }
    }
}
