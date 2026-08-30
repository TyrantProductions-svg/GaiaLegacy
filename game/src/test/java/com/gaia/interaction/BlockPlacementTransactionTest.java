package com.gaia.interaction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gaia.blocks.BlockDefinition;
import com.gaia.blocks.ItemFormDefinition;
import com.gaia.inventory.BodyInventoryService;
import com.overlord.assets.ResourceLocation;
import com.overlord.interaction.api.BlockChangeDispatchException;
import com.overlord.interaction.api.BlockChangeResult;
import com.overlord.interaction.api.BlockHitResult;
import com.overlord.interaction.api.EntityRef;
import com.overlord.inventory.api.BodySlot;
import com.overlord.inventory.api.ItemStack;
import com.overlord.physics.Aabb;
import com.overlord.physics.MassProperties;
import com.overlord.physics.PhysicsBody;
import com.overlord.voxel.BlockFace;
import com.overlord.voxel.ChunkKey;
import com.overlord.voxel.DirtyChunkRevision;
import com.overlord.voxel.FullCellState;
import com.overlord.voxel.ParentCellState;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

class BlockPlacementTransactionTest {
    private static final EntityRef OWNER = new EntityRef(12);
    private static final ResourceLocation AIR = ResourceLocation.parse("gaia:air");
    private static final ResourceLocation STONE = ResourceLocation.parse("gaia:stone");
    private static final BlockDefinition STONE_BLOCK = block(STONE);

    @Test
    void survivalReservesExtractionBeforeMutationAndCommitsExactlyOnce() {
        BodyInventoryService inventory = inventory();
        inventory.insert(OWNER, new ItemStack(STONE, 1));
        AtomicInteger mutations = new AtomicInteger();
        BlockPlacementTransaction transaction = transaction(
                request -> {
                    mutations.incrementAndGet();
                    assertEquals(1, inventory.totalCount(OWNER, STONE));
                    return applied(request);
                },
                inventory,
                world(true, AIR),
                bodyAt(0, 0, 0));

        BlockPlacementResult result = transaction.execute(
                hit(), Optional.of(new ItemStack(STONE, 1)),
                GameMode.SURVIVAL, BodySlot.LEFT_HAND, 4, 10);

        assertEquals(BlockPlacementResult.Status.APPLIED, result.status());
        assertEquals(1, mutations.get());
        assertEquals(1, result.inventoryCommitted());
        assertEquals(0, inventory.totalCount(OWNER, STONE));
    }

    @Test
    void mutationFailureRollsBackReservationAndDoesNotConsumeItem() {
        BodyInventoryService inventory = inventory();
        inventory.insert(OWNER, new ItemStack(STONE, 1));
        BlockPlacementTransaction transaction = transaction(
                request -> new BlockChangeResult(
                        request, BlockChangeResult.Status.CANCELLED,
                        Optional.of(AIR), List.of()),
                inventory, world(true, AIR), bodyAt(0, 0, 0));

        BlockPlacementResult result = transaction.execute(
                hit(), Optional.of(new ItemStack(STONE, 1)),
                GameMode.SURVIVAL, BodySlot.LEFT_HAND, 4, 10);

        assertEquals(BlockPlacementResult.Status.MUTATION_REJECTED, result.status());
        assertEquals(1, inventory.totalCount(OWNER, STONE));
    }

    @Test
    void survivalSelectionWithoutMatchingInventoryIsRejectedBeforeMutation() {
        BodyInventoryService inventory = inventory();
        AtomicInteger mutations = new AtomicInteger();
        BlockPlacementTransaction transaction = transaction(
                request -> {
                    mutations.incrementAndGet();
                    return applied(request);
                },
                inventory,
                world(true, AIR),
                bodyAt(0, 0, 0));

        BlockPlacementResult result = transaction.execute(
                hit(), Optional.of(new ItemStack(STONE, 1)),
                GameMode.SURVIVAL, BodySlot.LEFT_HAND, 4, 10);

        assertEquals(BlockPlacementResult.Status.INVENTORY_REJECTED, result.status());
        assertEquals(0, mutations.get());
        assertEquals(0, inventory.totalCount(OWNER, STONE));
    }

    @Test
    void creativePlacementDoesNotReadOrConsumeSurvivalInventory() {
        BodyInventoryService inventory = inventory();
        BlockPlacementTransaction transaction = transaction(
                BlockPlacementTransactionTest::applied,
                inventory, world(true, AIR), bodyAt(0, 0, 0));

        BlockPlacementResult result = transaction.execute(
                hit(), Optional.of(new ItemStack(STONE, 1)),
                GameMode.CREATIVE, BodySlot.MOUTH, 4, 10);

        assertEquals(BlockPlacementResult.Status.APPLIED, result.status());
        assertEquals(0, result.inventoryCommitted());
        assertEquals(0, inventory.totalCount(OWNER, STONE));
    }

    @Test
    void rejectsEveryValidationFailureBeforeReservationOrMutation() {
        BodyInventoryService inventory = inventory();
        inventory.insert(OWNER, new ItemStack(STONE, 1));
        AtomicInteger mutations = new AtomicInteger();

        assertRejected(BlockPlacementResult.Status.NO_ITEM,
                transaction(request -> { mutations.incrementAndGet(); return applied(request); },
                        inventory, world(true, AIR), bodyAt(0, 0, 0)),
                Optional.empty());
        assertRejected(BlockPlacementResult.Status.CHUNK_NOT_LOADED,
                transaction(request -> { mutations.incrementAndGet(); return applied(request); },
                        inventory, world(false, AIR), bodyAt(0, 0, 0)),
                Optional.of(new ItemStack(STONE, 1)));
        assertRejected(BlockPlacementResult.Status.NOT_REPLACEABLE,
                transaction(request -> { mutations.incrementAndGet(); return applied(request); },
                        inventory, world(true, STONE), bodyAt(0, 0, 0)),
                Optional.of(new ItemStack(STONE, 1)));
        assertRejected(BlockPlacementResult.Status.PLAYER_INTERSECTION,
                transaction(request -> { mutations.incrementAndGet(); return applied(request); },
                        inventory, world(true, AIR), bodyAt(2.5f, 2, 3.5f)),
                Optional.of(new ItemStack(STONE, 1)));

        BlockPlacementTransaction unknown = new BlockPlacementTransaction(
                request -> { mutations.incrementAndGet(); return applied(request); },
                inventory, OWNER, item -> Optional.empty(),
                world(true, AIR), bodyAt(0, 0, 0), AIR);
        assertRejected(BlockPlacementResult.Status.UNKNOWN_ITEM, unknown,
                Optional.of(new ItemStack(ResourceLocation.parse("gaia:unknown"), 1)));

        assertEquals(0, mutations.get());
        assertEquals(1, inventory.totalCount(OWNER, STONE));
    }

    @Test
    void appliedPostWriteFailureStillCommitsReservedExtractionOnce() {
        BodyInventoryService inventory = inventory();
        inventory.insert(OWNER, new ItemStack(STONE, 1));
        AtomicInteger mutations = new AtomicInteger();
        BlockPlacementTransaction transaction = transaction(
                request -> {
                    mutations.incrementAndGet();
                    throw new BlockChangeDispatchException(
                            "post", new IllegalStateException("subscriber"), true);
                }, inventory, world(true, AIR), bodyAt(0, 0, 0));

        BlockPlacementResult result = transaction.execute(
                hit(), Optional.of(new ItemStack(STONE, 1)),
                GameMode.SURVIVAL, BodySlot.LEFT_HAND, 4, 10);

        assertEquals(BlockPlacementResult.Status.APPLIED_WITH_NOTIFICATION_FAILURE,
                result.status());
        assertEquals(1, mutations.get());
        assertEquals(0, inventory.totalCount(OWNER, STONE));
        assertTrue(result.failure().isPresent());
    }

    private static void assertRejected(
            BlockPlacementResult.Status status,
            BlockPlacementTransaction transaction,
            Optional<ItemStack> selected) {
        BlockPlacementResult result = transaction.execute(
                hit(), selected, GameMode.SURVIVAL,
                BodySlot.LEFT_HAND, 4, 10);
        assertEquals(status, result.status());
        assertEquals(0, result.inventoryCommitted());
    }

    private static BlockPlacementTransaction transaction(
            com.overlord.interaction.api.WorldMutationService mutations,
            BodyInventoryService inventory,
            BlockPlacementWorldView world,
            PhysicsBody body) {
        return new BlockPlacementTransaction(
                mutations, inventory, OWNER,
                item -> item.equals(STONE) ? Optional.of(STONE_BLOCK) : Optional.empty(),
                world, body, AIR);
    }

    private static BlockPlacementWorldView world(
            boolean loaded, ResourceLocation block) {
        return new BlockPlacementWorldView() {
            @Override
            public boolean isLoaded(int x, int y, int z) {
                return loaded;
            }

            @Override
            public ParentCellState parentStateAt(int x, int y, int z) {
                return new FullCellState((byte) (block.equals(AIR) ? 0 : 1));
            }

            @Override
            public ResourceLocation blockAt(int x, int y, int z) {
                return block;
            }
        };
    }

    private static PhysicsBody bodyAt(float x, float y, float z) {
        PhysicsBody body = new PhysicsBody(
                new Aabb(-0.3f, 0, -0.3f, 0.3f, 1.8f, 0.3f),
                MassProperties.dynamic(1));
        body.teleport(new Vector3f(x, y, z));
        return body;
    }

    private static BodyInventoryService inventory() {
        return new BodyInventoryService(
                OWNER,
                item -> item.equals(STONE)
                        ? Optional.of(new ItemFormDefinition(STONE, 64, false, false))
                        : Optional.empty(),
                event -> {});
    }

    private static BlockHitResult hit() {
        return new BlockHitResult(
                1, 2, 3, 2, 2, 3,
                STONE, 1, 0, 0,
                2, 2.5f, 3.5f, 2);
    }

    private static BlockChangeResult applied(
            com.overlord.interaction.api.BlockChangeRequest request) {
        return new BlockChangeResult(
                request, BlockChangeResult.Status.APPLIED,
                Optional.of(request.replacementBlock()),
                List.of(new DirtyChunkRevision(new ChunkKey(0, 0), 3)));
    }

    private static BlockDefinition block(ResourceLocation location) {
        ResourceLocation texture = ResourceLocation.parse("gaia:missing");
        EnumMap<BlockFace, ResourceLocation> textures = new EnumMap<>(BlockFace.class);
        for (BlockFace face : BlockFace.values()) {
            textures.put(face, texture);
        }
        return new BlockDefinition(
                3, location, ResourceLocation.parse("gaia:opaque"),
                textures, 1, 1, 1, false, false, 1,
                new ItemFormDefinition(location, 64, false, false));
    }
}
