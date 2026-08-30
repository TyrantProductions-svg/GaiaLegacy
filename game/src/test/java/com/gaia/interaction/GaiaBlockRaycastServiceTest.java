package com.gaia.interaction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.overlord.assets.ResourceLocation;
import com.overlord.interaction.api.BlockFace;
import com.overlord.interaction.api.BlockHitResult;
import com.overlord.physics.BlockRaycastHit;
import com.overlord.physics.BlockRaycast;
import com.overlord.physics.BlockCollisionShapeResolver;
import com.overlord.physics.DetailRaycastTarget;
import com.overlord.physics.SimulationOrigin;
import com.overlord.physics.SpatialQueryResult;
import com.overlord.voxel.ChunkKey;
import com.overlord.voxel.LocalSubVoxelPosition;
import com.overlord.voxel.VoxelScale;
import com.overlord.voxel.World;
import java.util.Optional;
import org.joml.Vector3f;
import org.joml.Vector3fc;
import org.junit.jupiter.api.Test;

class GaiaBlockRaycastServiceTest {
    @Test
    void adapterHasNoWorldRepositoryOrIndependentTraversalDependency() {
        for (java.lang.reflect.Field field
                : GaiaBlockRaycastService.class.getDeclaredFields()) {
            assertTrue(field.getType() != World.class);
            assertTrue(field.getType()
                    != com.overlord.voxel.ChunkRepository.class);
            assertTrue(field.getType() != BlockCollisionShapeResolver.class);
        }
        assertTrue(com.overlord.interaction.api.BlockRaycastService.class
                .isAssignableFrom(GaiaBlockRaycastService.class));
        assertTrue(com.overlord.interaction.api.SpatialBlockRaycastService.class
                .isAssignableFrom(GaiaBlockRaycastService.class));
    }

    @Test
    void delegatesToPhaseSixRaycastAndMapsStoredIdentityExactly() {
        GaiaBlockRaycastService service = new GaiaBlockRaycastService(
                (origin, direction, distance) -> Optional.of(new BlockRaycastHit(
                        1, 2, 3,
                        0, 2, 3,
                        (byte) 200,
                        -1, 0, 0,
                        1, 2.5f, 3.5f,
                        4.5f)),
                id -> {
                    assertEquals(200, id);
                    return ResourceLocation.parse("gaia:high_id");
                });

        BlockHitResult hit = service.raycast(
                new Vector3f(), new Vector3f(1, 0, 0), 6).orElseThrow();

        assertEquals(ResourceLocation.parse("gaia:high_id"), hit.block());
        assertEquals(BlockFace.WEST, BlockFace.fromHit(hit));
        assertEquals(4.5f, hit.distance());
    }

    @Test
    void preservesPhaseSixMiss() {
        GaiaBlockRaycastService service = new GaiaBlockRaycastService(
                (origin, direction, distance) -> Optional.empty(),
                ignored -> ResourceLocation.parse("gaia:air"));

        assertTrue(service.raycast(
                new Vector3f(), new Vector3f(0, 0, -1), 6).isEmpty());
    }

    @Test
    void typedAvailableHitMapsStoredIdentityAndCoordinatesExactly() {
        GaiaBlockRaycastService service = new GaiaBlockRaycastService(
                (origin, direction, distance) -> Optional.of(new BlockRaycastHit(
                        1, 2, 3,
                        0, 2, 3,
                        (byte) 200,
                        -1, 0, 0,
                        1, 2.5f, 3.5f,
                        4.5f)),
                id -> ResourceLocation.parse("gaia:high_id"));

        SpatialQueryResult<BlockHitResult> query = typedQuery(
                service, new Vector3f(), new Vector3f(1, 0, 0), 6);

        assertEquals(SpatialQueryResult.Status.AVAILABLE, query.status());
        BlockHitResult hit = query.result().orElseThrow();
        assertEquals(ResourceLocation.parse("gaia:high_id"), hit.block());
        assertEquals(1, hit.blockX());
        assertEquals(2, hit.blockY());
        assertEquals(3, hit.blockZ());
        assertEquals(4.5f, hit.distance());
    }

    @Test
    void typedAvailableMissRemainsAvailableEmpty() {
        GaiaBlockRaycastService service = new GaiaBlockRaycastService(
                (origin, direction, distance) -> Optional.empty(),
                ignored -> ResourceLocation.parse("gaia:air"));

        SpatialQueryResult<BlockHitResult> query = typedQuery(
                service, new Vector3f(), new Vector3f(0, 0, -1), 6);

        assertEquals(SpatialQueryResult.Status.AVAILABLE, query.status());
        assertTrue(query.result().isEmpty());
        assertTrue(query.unavailableKey().isEmpty());
    }

    @Test
    void mapsDetailTargetRevisionAndCanonicalPointWithoutLosingIdentity() {
        DetailRaycastTarget target = new DetailRaycastTarget(
                VoxelScale.DETAIL_4,
                new LocalSubVoxelPosition(2, 1, 3));
        GaiaBlockRaycastService service = new GaiaBlockRaycastService(
                (origin, direction, distance) -> Optional.of(new BlockRaycastHit(
                        1, 2, 3,
                        0, 2, 3,
                        (byte) 200,
                        -1, 0, 0,
                        1.5f, 2.375f, 3.875f,
                        4.5f,
                        1_600_000_001.5,
                        2.375,
                        -1_599_999_996.125,
                        73L,
                        target)),
                id -> ResourceLocation.parse("gaia:detail_material"));

        BlockHitResult hit = service.raycast(
                new Vector3f(), new Vector3f(1, 0, 0), 6).orElseThrow();

        assertEquals(ResourceLocation.parse("gaia:detail_material"), hit.block());
        assertEquals(target, hit.target());
        assertEquals(73L, hit.chunkRevision());
        assertEquals(1_600_000_001.5, hit.worldPointX(), 1.0e-12);
        assertEquals(2.375, hit.worldPointY(), 1.0e-12);
        assertEquals(-1_599_999_996.125, hit.worldPointZ(), 1.0e-12);
    }

    @Test
    void typedUnknownPreservesCanonicalKeyWithoutThrowing() {
        ChunkKey key = new ChunkKey(100_000_000, -100_000_000);
        SimulationOrigin origin = new SimulationOrigin(key);
        World world = new World();
        BlockRaycast raycast = new BlockRaycast(
                world, BlockCollisionShapeResolver.fullCubesForNonAir());
        GaiaBlockRaycastService service = GaiaBlockRaycastService.originAware(
                (start, direction, distance) ->
                        raycast.cast(origin, start, direction, distance),
                ignored -> ResourceLocation.parse("gaia:stone"));

        SpatialQueryResult<BlockHitResult> unavailable = typedQuery(
                service,
                new Vector3f(0.5f, 1.5f, 0.5f),
                new Vector3f(1, 0, 0),
                4);

        assertEquals(SpatialQueryResult.Status.UNKNOWN, unavailable.status());
        assertEquals(Optional.of(key), unavailable.unavailableKey());
        assertTrue(unavailable.result().isEmpty());

        world.generate(key, chunk -> chunk.setBlock(2, 1, 0, (byte) 1));
        BlockHitResult hit = typedQuery(
                service,
                new Vector3f(0.5f, 1.5f, 0.5f),
                new Vector3f(1, 0, 0),
                4).result().orElseThrow();
        assertEquals(1_600_000_002, hit.blockX());
        assertEquals(-1_600_000_000, hit.blockZ());
    }

    @Test
    void typedFailedPreservesCanonicalKeyWithoutBecomingAvailableEmpty() {
        ChunkKey key = new ChunkKey(17, 2);
        GaiaBlockRaycastService service = GaiaBlockRaycastService.originAware(
                (origin, direction, distance) -> SpatialQueryResult.unavailable(
                        SpatialQueryResult.Status.FAILED, key),
                ignored -> ResourceLocation.parse("gaia:stone"));

        SpatialQueryResult<BlockHitResult> query = typedQuery(
                service, new Vector3f(), new Vector3f(1, 0, 0), 6);

        assertEquals(SpatialQueryResult.Status.FAILED, query.status());
        assertEquals(Optional.of(key), query.unavailableKey());
        assertTrue(query.result().isEmpty());
    }

    @Test
    void legacyAdapterFailsClosedWhenCanonicalSpaceHasFailed() {
        World world = new World();
        ChunkKey key = new ChunkKey(0, 0);
        world.generate(key, chunk -> chunk.setBlock(0, 1, 0, (byte) 1));
        long revision = world.chunks().revision(key);
        world.chunks().claimMeshing(key).orElseThrow();
        world.chunks().markMeshingFailure(
                key, revision, new IllegalStateException("fixture failure"));
        BlockRaycast raycast = new BlockRaycast(
                world, BlockCollisionShapeResolver.fullCubesForNonAir());
        GaiaBlockRaycastService service = new GaiaBlockRaycastService(
                (origin, direction, distance) ->
                        raycast.cast(origin, direction, distance),
                ignored -> ResourceLocation.parse("gaia:stone"));

        assertThrows(
                IllegalStateException.class,
                () -> service.raycast(
                        new Vector3f(0.5f, 1.5f, 0.5f),
                        new Vector3f(1, 0, 0),
                        1));
    }

    @Test
    void blockIdentityInvariantFailureStillPropagates() {
        IllegalStateException invariant = new IllegalStateException("registry invariant");
        GaiaBlockRaycastService service = new GaiaBlockRaycastService(
                (origin, direction, distance) -> Optional.of(new BlockRaycastHit(
                        1, 2, 3,
                        0, 2, 3,
                        (byte) 1,
                        -1, 0, 0,
                        1, 2.5f, 3.5f,
                        4.5f)),
                ignored -> { throw invariant; });

        assertEquals(invariant, assertThrows(IllegalStateException.class, () ->
                service.raycast(
                        new Vector3f(), new Vector3f(1, 0, 0), 6)));
    }

    @SuppressWarnings("unchecked")
    private static SpatialQueryResult<BlockHitResult> typedQuery(
            GaiaBlockRaycastService service,
            Vector3fc origin,
            Vector3fc direction,
            float maximumDistance) {
        return assertDoesNotThrow(() -> {
            Class<?> contract = Class.forName(
                    "com.overlord.interaction.api.SpatialBlockRaycastService");
            assertTrue(contract.isInstance(service),
                    "Gaia adapter must implement the typed spatial-query contract");
            Object result = contract.getMethod(
                            "query", Vector3fc.class, Vector3fc.class, float.class)
                    .invoke(service, origin, direction, maximumDistance);
            return (SpatialQueryResult<BlockHitResult>) assertInstanceOf(
                    SpatialQueryResult.class, result);
        });
    }
}
