package com.overlord.physics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.overlord.config.GameConfig;
import com.overlord.voxel.ChunkDetailMutation;
import com.overlord.voxel.ChunkDetailMutationOutcome;
import com.overlord.voxel.ChunkGenerationMode;
import com.overlord.voxel.ChunkKey;
import com.overlord.voxel.ChunkRepository;
import com.overlord.voxel.ChunkRepositoryRestoreResult;
import com.overlord.voxel.DetailCellState;
import com.overlord.voxel.FullCellState;
import com.overlord.voxel.LocalSubVoxelPosition;
import com.overlord.voxel.ParentCellObservation;
import com.overlord.voxel.World;
import java.util.Optional;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

class DetailCollisionWorldTest {
    private static final float EPSILON = 1.0e-4f;
    private static final float FIXED_STEP = 1.0f / 60.0f;
    private static final Aabb SMALL_COLLIDER =
            new Aabb(-0.1f, -0.1f, -0.1f, 0.1f, 0.1f, 0.1f);
    private static final Aabb PLAYER_COLLIDER =
            new Aabb(
                    -GameConfig.Player.WIDTH / 2.0f,
                    0,
                    -GameConfig.Player.WIDTH / 2.0f,
                    GameConfig.Player.WIDTH / 2.0f,
                    GameConfig.Player.HEIGHT,
                    GameConfig.Player.WIDTH / 2.0f);

    @Test
    void backingAirStillCollidesWithOccupiedQuarterButNotEmptyQuarter() {
        World world = detailWorld(
                0,
                0,
                0,
                bit(0, 0, 0),
                (byte) 7);
        CollisionWorld collisions = collisions(world);

        assertTrue(collisions.overlapsSolid(
                new Aabb(0.05f, 0.05f, 0.05f,
                        0.20f, 0.20f, 0.20f)));
        assertFalse(collisions.overlapsSolid(
                new Aabb(0.30f, 0.05f, 0.05f,
                        0.45f, 0.20f, 0.20f)));
    }

    @Test
    void fragmentedCheckerboardPreservesEveryEmittedCollisionBox() {
        World world = detailWorld(
                0,
                0,
                0,
                maskFor((x, y, z) -> ((x + y + z) & 1) == 0),
                (byte) 7);
        CollisionWorld collisions = collisions(world);

        for (int z = 0; z < 4; z++) {
            for (int y = 0; y < 4; y++) {
                for (int x = 0; x < 4; x++) {
                    float centerX = (x + 0.5f) * 0.25f;
                    float centerY = (y + 0.5f) * 0.25f;
                    float centerZ = (z + 0.5f) * 0.25f;
                    boolean occupied = ((x + y + z) & 1) == 0;

                    assertEquals(
                            occupied,
                            collisions.overlapsSolid(new Aabb(
                                    centerX - 0.01f,
                                    centerY - 0.01f,
                                    centerZ - 0.01f,
                                    centerX + 0.01f,
                                    centerY + 0.01f,
                                    centerZ + 0.01f)),
                            "cell " + x + "," + y + "," + z);
                }
            }
        }
    }

    @Test
    void fullDetailCubeMatchesFullParentSweepGeometry() {
        World detail = detailWorld(1, 0, 0, -1L, (byte) 7);
        World full = new World();
        assertTrue(full.setBlock(1, 0, 0, (byte) 7));

        SweepResult detailHit = collisions(detail)
                .sweep(
                        SMALL_COLLIDER,
                        new Vector3f(0.5f, 0.5f, 0.5f),
                        new Vector3f(2, 0, 0))
                .orElseThrow();
        SweepResult fullHit = collisions(full)
                .sweep(
                        SMALL_COLLIDER,
                        new Vector3f(0.5f, 0.5f, 0.5f),
                        new Vector3f(2, 0, 0))
                .orElseThrow();

        assertEquals(fullHit.fraction(), detailHit.fraction(), EPSILON);
        assertEquals(fullHit.normalX(), detailHit.normalX(), EPSILON);
        assertEquals(new Aabb(1, 0, 0, 2, 1, 1), detailHit.blockShape());
    }

    @Test
    void quarterWallPreventsDirectDiagonalAndSupportedSpeedTunneling() {
        World world = detailWorld(
                1,
                0,
                0,
                maskFor((x, y, z) -> x == 0),
                (byte) 7);
        CollisionWorld collisions = collisions(world);
        Vector3f start = new Vector3f(0.5f, 0.5f, 0.5f);

        SweepResult direct = collisions.sweep(
                SMALL_COLLIDER, start, new Vector3f(2, 0, 0)).orElseThrow();
        SweepResult diagonal = collisions.sweep(
                SMALL_COLLIDER, start, new Vector3f(2, 0, 1)).orElseThrow();
        SweepResult supportedSpeed = collisions.sweep(
                SMALL_COLLIDER,
                new Vector3f(0.89f, 0.5f, 0.5f),
                new Vector3f(GameConfig.Player.MOVEMENT_SPEED * 0.25f, 0, 0))
                .orElseThrow();

        assertEquals(0.2f, direct.fraction(), EPSILON);
        assertEquals(direct.fraction(), diagonal.fraction(), EPSILON);
        assertTrue(supportedSpeed.fraction() < 0.01f);
        assertEquals(new Aabb(1, 0, 0, 1.25f, 1, 1), direct.blockShape());
    }

    @Test
    void exactWallContactAndFixedStepPlayerMovementDoNotTunnel() {
        World world = new World();
        fillFloor(world, -1, 4, -1, 1);
        addDetail(
                world,
                1,
                1,
                0,
                maskFor((x, y, z) -> x == 0),
                (byte) 7);
        addDetail(
                world,
                1,
                2,
                0,
                maskFor((x, y, z) -> x == 0),
                (byte) 7);
        SweepResult exactContact = collisions(world)
                .sweep(
                        SMALL_COLLIDER,
                        new Vector3f(0.9f, 1.5f, 0.5f),
                        new Vector3f(0.25f, 0, 0))
                .orElseThrow();
        PlayerController player = controller(world);
        player.teleport(new Vector3f(0.5f, 1, 0.5f));

        advance(player, 120, 1, 0);

        assertEquals(0.0f, exactContact.fraction(), EPSILON);
        assertTrue(player.body().position(new Vector3f()).x
                < 1.0f - GameConfig.Player.WIDTH / 2.0f);
        assertTrue(player.isGrounded());
        assertFalse(player.overlapsSolid());
    }

    @Test
    void moveAndSlideUsesDetailWallAndInsideCornerWithoutOrderJitter() {
        World world = detailWorld(
                1,
                0,
                0,
                maskFor((x, y, z) -> x == 0),
                (byte) 7);
        addDetail(
                world,
                0,
                0,
                1,
                maskFor((x, y, z) -> z == 0),
                (byte) 7);
        CollisionWorld collisions = collisions(world);

        MotionResult slide = collisions.moveAndSlide(
                SMALL_COLLIDER,
                new Vector3f(0.5f, 0.5f, 0.5f),
                new Vector3f(1, 0, 0.3f),
                4);
        MotionResult corner = collisions.moveAndSlide(
                SMALL_COLLIDER,
                new Vector3f(0.5f, 0.5f, 0.5f),
                new Vector3f(1, 0, 1),
                4);

        assertTrue(slide.x() < 0.91f);
        assertTrue(slide.z() > 0.7f);
        assertTrue(corner.x() < 0.91f);
        assertTrue(corner.z() < 0.91f);
        assertEquals(2, corner.contacts().size());
    }

    @Test
    void moveAndSlideResolvesSimultaneousVerticalAndHorizontalDetailContact() {
        World world = new World();
        world.generate(new ChunkKey(0, 0), ignored -> {});
        addDetail(
                world,
                0,
                0,
                0,
                maskFor((x, y, z) -> y == 0),
                (byte) 7);
        addDetail(
                world,
                1,
                0,
                0,
                maskFor((x, y, z) -> x == 0),
                (byte) 7);

        MotionResult result = collisions(world).moveAndSlide(
                SMALL_COLLIDER,
                new Vector3f(0.5f, 0.75f, 0.5f),
                new Vector3f(1, -1, 0),
                4);

        assertTrue(result.x() < 0.91f);
        assertTrue(result.y() >= 0.35f - EPSILON);
        assertEquals(2, result.contacts().size());
    }

    @Test
    void overlapAndDepenetrationUseAllBoxesWithoutMutatingDetail() {
        World world = detailWorld(
                0,
                0,
                0,
                bit(0, 0, 0) | bit(2, 0, 0),
                (byte) 7);
        CollisionWorld collisions = collisions(world);
        Aabb wideCollider = new Aabb(-0.35f, -0.1f, -0.1f,
                0.35f, 0.1f, 0.1f);
        Vector3f position = new Vector3f(0.375f, 0.125f, 0.125f);
        long revisionBefore = world.chunks().revision(new ChunkKey(0, 0));

        assertTrue(collisions.overlapsSolid(wideCollider.translated(position)));
        Vector3f recovered = collisions.depenetrate(
                wideCollider, position, 8).orElseThrow();

        assertFalse(collisions.overlapsSolid(wideCollider.translated(recovered)));
        assertEquals(revisionBefore, world.chunks().revision(new ChunkKey(0, 0)));
    }

    @Test
    void depenetrationSpansDetailDetailAndDetailFullParentSeams() {
        Aabb seamCollider = new Aabb(
                -0.2f, -0.1f, -0.1f, 0.2f, 0.1f, 0.1f);

        World detailDetail = new World();
        detailDetail.generate(new ChunkKey(0, 0), ignored -> {});
        addDetail(detailDetail, 0, 0, 0,
                maskFor((x, y, z) -> x == 3), (byte) 7);
        addDetail(detailDetail, 1, 0, 0,
                maskFor((x, y, z) -> x == 0), (byte) 9);
        CollisionWorld detailDetailCollisions = collisions(detailDetail);
        Vector3f detailDetailStart = new Vector3f(1, 0.125f, 0.125f);

        assertTrue(detailDetailCollisions.overlapsSolid(
                seamCollider.translated(detailDetailStart)));
        Vector3f detailDetailRecovered = detailDetailCollisions.depenetrate(
                seamCollider, detailDetailStart, 8).orElseThrow();
        assertFalse(detailDetailCollisions.overlapsSolid(
                seamCollider.translated(detailDetailRecovered)));

        World detailFull = new World();
        detailFull.generate(new ChunkKey(0, 0), ignored -> {});
        addDetail(detailFull, 0, 0, 0,
                maskFor((x, y, z) -> x == 3), (byte) 7);
        assertTrue(detailFull.setBlock(1, 0, 0, (byte) 9));
        CollisionWorld detailFullCollisions = collisions(detailFull);
        Vector3f detailFullStart = new Vector3f(1, 0.125f, 0.125f);

        assertTrue(detailFullCollisions.overlapsSolid(
                seamCollider.translated(detailFullStart)));
        Vector3f detailFullRecovered = detailFullCollisions.depenetrate(
                seamCollider, detailFullStart, 8).orElseThrow();
        assertFalse(detailFullCollisions.overlapsSolid(
                seamCollider.translated(detailFullRecovered)));
    }

    @Test
    void detailAndFullVolumesRemainContinuousAcrossParentAndChunkBoundaries() {
        World world = new World();
        world.generate(new ChunkKey(0, 0), ignored -> {});
        world.generate(new ChunkKey(1, 0), ignored -> {});
        addDetail(world, 15, 0, 0,
                maskFor((x, y, z) -> x == 3), (byte) 7);
        addDetail(world, 16, 0, 0,
                maskFor((x, y, z) -> x == 0 || x == 3), (byte) 9);
        assertTrue(world.setBlock(17, 0, 0, (byte) 7));
        CollisionWorld collisions = collisions(world);

        assertTrue(collisions.overlapsSolid(
                new Aabb(15.99f, 0.1f, 0.1f, 16.01f, 0.2f, 0.2f)));
        assertTrue(collisions.overlapsSolid(
                new Aabb(16.24f, 0.1f, 0.1f, 16.26f, 0.2f, 0.2f)));
        assertTrue(collisions.overlapsSolid(
                new Aabb(16.99f, 0.1f, 0.1f, 17.01f, 0.2f, 0.2f)));
    }

    @Test
    void negativeCoordinatesAndSimulationOriginRebaseResolveSameDetailShape() {
        ChunkKey canonicalKey = new ChunkKey(100_000_001, -100_000_000);
        int parentX = canonicalKey.worldOriginX();
        int parentZ = canonicalKey.worldOriginZ();
        World world = detailWorld(
                parentX,
                0,
                parentZ,
                bit(0, 0, 0),
                (byte) 7);
        world.generate(new ChunkKey(100_000_000, -100_000_000), ignored -> {});
        CollisionWorld collisions = collisions(world);

        SpatialQueryResult<Boolean> before = collisions.overlapsSolid(
                new SimulationOrigin(new ChunkKey(100_000_000, -100_000_000)),
                new Aabb(16.05f, 0.05f, 0.05f, 16.2f, 0.2f, 0.2f));
        SpatialQueryResult<Boolean> after = collisions.overlapsSolid(
                new SimulationOrigin(canonicalKey),
                new Aabb(0.05f, 0.05f, 0.05f, 0.2f, 0.2f, 0.2f));
        SpatialQueryResult<SweepResult> sweepBefore = collisions.sweep(
                new SimulationOrigin(new ChunkKey(100_000_000, -100_000_000)),
                SMALL_COLLIDER,
                new Vector3f(15.5f, 0.125f, 0.125f),
                new Vector3f(1, 0, 0));
        SpatialQueryResult<SweepResult> sweepAfter = collisions.sweep(
                new SimulationOrigin(canonicalKey),
                SMALL_COLLIDER,
                new Vector3f(-0.5f, 0.125f, 0.125f),
                new Vector3f(1, 0, 0));

        assertEquals(SpatialQueryResult.Status.AVAILABLE, before.status());
        assertEquals(before.result(), after.result());
        assertEquals(Optional.of(true), after.result());
        assertEquals(
                sweepBefore.result().orElseThrow().fraction(),
                sweepAfter.result().orElseThrow().fraction(),
                EPSILON);
        assertEquals(
                sweepBefore.result().orElseThrow().blockX(),
                sweepAfter.result().orElseThrow().blockX());

        World negative = detailWorld(
                -1, 0, -1, bit(3, 0, 3), (byte) 7);
        assertTrue(collisions(negative).overlapsSolid(
                new Aabb(-0.2f, 0.05f, -0.2f, -0.05f, 0.2f, -0.05f)));
    }

    @Test
    void negativeChunkBoundaryHasNoQuarterGridCrack() {
        World world = new World();
        world.generate(new ChunkKey(-2, 0), ignored -> {});
        world.generate(new ChunkKey(-1, 0), ignored -> {});
        addDetail(world, -17, 0, 0,
                maskFor((x, y, z) -> x == 3), (byte) 7);
        addDetail(world, -16, 0, 0,
                maskFor((x, y, z) -> x == 0), (byte) 9);

        assertTrue(collisions(world).overlapsSolid(
                new Aabb(-16.01f, 0.05f, 0.05f,
                        -15.99f, 0.2f, 0.2f)));
    }

    @Test
    void detailGapFollowedByUnknownOrFailedSpaceRemainsUnavailable() {
        World unknownWorld = detailWorld(
                15, 0, 0, bit(0, 3, 3), (byte) 7);
        CollisionWorld unknownCollisions = collisions(unknownWorld);
        SpatialQueryResult<SweepResult> unknown = unknownCollisions.sweep(
                new SimulationOrigin(new ChunkKey(0, 0)),
                SMALL_COLLIDER,
                new Vector3f(15.5f, 0.125f, 0.125f),
                new Vector3f(1, 0, 0));

        World failedWorld = detailWorld(
                15, 0, 0, bit(0, 3, 3), (byte) 7);
        ChunkKey failedKey = new ChunkKey(1, 0);
        failedWorld.chunks().failGeneration(
                failedWorld.chunks().beginGeneration(
                        failedKey, ChunkGenerationMode.INITIAL),
                new IllegalStateException("fixture failure"));
        SpatialQueryResult<SweepResult> failed = collisions(failedWorld).sweep(
                new SimulationOrigin(new ChunkKey(0, 0)),
                SMALL_COLLIDER,
                new Vector3f(15.5f, 0.125f, 0.125f),
                new Vector3f(1, 0, 0));

        assertEquals(SpatialQueryResult.Status.UNKNOWN, unknown.status());
        assertEquals(SpatialQueryResult.Status.FAILED, failed.status());
    }

    @Test
    void repositoryRepresentationMutationsAreVisibleWithoutCollisionCache() {
        World world = new World();
        assertTrue(world.setBlock(0, 0, 0, (byte) 7));
        CollisionWorld collisions = collisions(world);
        Aabb firstQuarter = new Aabb(
                0.05f, 0.05f, 0.05f, 0.2f, 0.2f, 0.2f);
        Aabb lastQuarter = new Aabb(
                0.8f, 0.8f, 0.8f, 0.95f, 0.95f, 0.95f);
        assertTrue(collisions.overlapsSolid(firstQuarter));

        ParentCellObservation full = observe(world, 0, 0, 0);
        ChunkDetailMutationOutcome converted = world.chunks().mutateDetail(
                new ChunkDetailMutation.ConvertFullToDetail(
                        0, 0, 0, full.chunkRevision(), (byte) 7));
        assertEquals(ChunkDetailMutationOutcome.Status.APPLIED, converted.status());
        ParentCellObservation detail = observe(world, 0, 0, 0);
        assertEquals(
                ChunkDetailMutationOutcome.Status.APPLIED,
                world.chunks().mutateDetail(
                                new ChunkDetailMutation.SetSubVoxel(
                                        0,
                                        0,
                                        0,
                                        detail.chunkRevision(),
                                        detail.state(),
                                        new LocalSubVoxelPosition(0, 0, 0),
                                        (byte) 0))
                        .status());

        assertFalse(collisions.overlapsSolid(firstQuarter));
        assertTrue(collisions.overlapsSolid(lastQuarter));

        ParentCellObservation oneRemaining = observe(world, 0, 0, 0);
        DetailCellState detailState = (DetailCellState) oneRemaining.state();
        for (int index = 1; index < 63; index++) {
            if (detailState.blockIdAtIndex(index) != 0) {
                clearDetail(world, 0, 0, 0,
                        LocalSubVoxelPosition.fromIndex(index));
                detailState = (DetailCellState) observe(world, 0, 0, 0).state();
            }
        }
        clearDetail(world, 0, 0, 0, new LocalSubVoxelPosition(3, 3, 3));

        assertEquals(new FullCellState((byte) 0), observe(world, 0, 0, 0).state());
        assertFalse(collisions.overlapsSolid(lastQuarter));
    }

    @Test
    void uniformDetailCompactionImmediatelyResumesFullCollisionSemantics() {
        World world = detailWorld(0, 0, 0, -1L, (byte) 7);
        CollisionWorld collisions = collisions(world);
        Aabb probe = new Aabb(0.1f, 0.1f, 0.1f, 0.9f, 0.9f, 0.9f);
        assertTrue(collisions.overlapsSolid(probe));
        ParentCellObservation detail = observe(world, 0, 0, 0);

        ChunkDetailMutationOutcome compacted = world.chunks().mutateDetail(
                new ChunkDetailMutation.CompactDetailToFull(
                        0,
                        0,
                        0,
                        detail.chunkRevision(),
                        (DetailCellState) detail.state(),
                        (byte) 7));

        assertEquals(ChunkDetailMutationOutcome.Status.APPLIED, compacted.status());
        assertEquals(new FullCellState((byte) 7), observe(world, 0, 0, 0).state());
        assertTrue(collisions.overlapsSolid(probe));
    }

    @Test
    void canonicalRestorePlacesPlayerAgainstRestoredDetailGeometry() {
        World source = detailWorld(
                0,
                0,
                0,
                maskFor((x, y, z) -> y == 0),
                (byte) 7);
        ChunkRepository restoredRepository = new ChunkRepository();
        assertEquals(
                ChunkRepositoryRestoreResult.Status.RESTORED,
                restoredRepository.restoreCanonical(
                                source.chunks().canonicalSnapshot())
                        .status());
        World restored = new World(restoredRepository);
        PlayerController player = controller(restored);
        player.teleport(new Vector3f(0.5f, 2, 0.5f));

        advance(player, 120, 0, 0);

        assertEquals(0.25f, player.body().position(new Vector3f()).y,
                GameConfig.Physics.COLLISION_TOLERANCE + EPSILON);
        assertTrue(player.isGrounded());
        assertFalse(player.overlapsSolid());
    }

    @Test
    void playerStandsStablyOnQuarterHalfThreeQuarterAndFullDetailSurfaces() {
        for (int occupiedLayers = 1; occupiedLayers <= 4; occupiedLayers++) {
            int layers = occupiedLayers;
            World world = detailWorld(
                    0,
                    0,
                    0,
                    maskFor((x, y, z) -> y < layers),
                    (byte) 7);
            PlayerController player = controller(world);
            player.teleport(new Vector3f(0.5f, 3, 0.5f));

            advance(player, 180, 0, 0);
            float expectedHeight = occupiedLayers * 0.25f;

            assertEquals(
                    expectedHeight,
                    player.body().position(new Vector3f()).y,
                    GameConfig.Physics.COLLISION_TOLERANCE + EPSILON);
            assertTrue(player.isGrounded());
            player.fixedUpdate(FIXED_STEP, 0, 0, false, false, false);
            assertEquals(
                    expectedHeight,
                    player.body().position(new Vector3f()).y,
                    GameConfig.Physics.COLLISION_TOLERANCE + EPSILON);
        }
    }

    @Test
    void groundingIsStableAcrossDetailDetailAndDetailFullParentSeams() {
        World detailDetail = new World();
        detailDetail.generate(new ChunkKey(0, 0), ignored -> {});
        addDetail(detailDetail, 0, 0, 0,
                maskFor((x, y, z) -> y < 2), (byte) 7);
        addDetail(detailDetail, 1, 0, 0,
                maskFor((x, y, z) -> y < 2), (byte) 9);
        PlayerController detailPlayer = controller(detailDetail);
        detailPlayer.teleport(new Vector3f(1.0f, 3, 0.5f));
        advance(detailPlayer, 180, 0, 0);

        assertEquals(0.5f, detailPlayer.body().position(new Vector3f()).y,
                GameConfig.Physics.COLLISION_TOLERANCE + EPSILON);
        assertTrue(detailPlayer.isGrounded());

        World detailFull = new World();
        detailFull.generate(new ChunkKey(0, 0), ignored -> {});
        addDetail(detailFull, 0, 0, 0, -1L, (byte) 7);
        assertTrue(detailFull.setBlock(1, 0, 0, (byte) 9));
        PlayerController fullPlayer = controller(detailFull);
        fullPlayer.teleport(new Vector3f(1.0f, 3, 0.5f));
        advance(fullPlayer, 180, 0, 0);

        assertEquals(1.0f, fullPlayer.body().position(new Vector3f()).y,
                GameConfig.Physics.COLLISION_TOLERANCE + EPSILON);
        assertTrue(fullPlayer.isGrounded());
    }

    @Test
    void playerStepsOntoQuarterGeometryButCannotStepAboveConfiguredLimit() {
        World below = new World();
        fillFloor(below, -1, 4, -1, 1);
        for (int x = 1; x <= 4; x++) {
            addDetail(
                    below,
                    x,
                    1,
                    0,
                    maskFor((subX, y, z) -> y == 0),
                    (byte) 7);
        }
        PlayerController belowPlayer = controller(below);
        belowPlayer.teleport(new Vector3f(0.5f, 1, 0.5f));
        advance(belowPlayer, 30, 1, 0);

        assertTrue(belowPlayer.body().position(new Vector3f()).x > 1.5f);
        assertEquals(1.25f, belowPlayer.body().position(new Vector3f()).y,
                GameConfig.Physics.COLLISION_TOLERANCE + EPSILON);
        assertTrue(belowPlayer.isGrounded());

        World above = new World();
        fillFloor(above, -1, 4, -1, 1);
        for (int x = 1; x <= 4; x++) {
            assertTrue(above.setBlock(x, 1, 0, (byte) 7));
            addDetail(
                    above,
                    x,
                    2,
                    0,
                    maskFor((subX, y, z) -> y == 0),
                    (byte) 7);
        }
        PlayerController abovePlayer = controller(above);
        abovePlayer.teleport(new Vector3f(0.5f, 1, 0.5f));
        advance(abovePlayer, 30, 1, 0);

        assertTrue(abovePlayer.body().position(new Vector3f()).x
                < 1.0f - GameConfig.Player.WIDTH / 2.0f + EPSILON);
        assertEquals(1.0f, abovePlayer.body().position(new Vector3f()).y,
                GameConfig.Physics.COLLISION_TOLERANCE + EPSILON);
    }

    @Test
    void playerTraversesQuarterGridStaircaseUpAndDownUsingExistingStepLogic() {
        World world = new World();
        fillFloor(world, -1, 7, -1, 1);
        for (int step = 1; step <= 4; step++) {
            int layers = step;
            addDetail(
                    world,
                    step,
                    1,
                    0,
                    maskFor((x, y, z) -> y < layers),
                    (byte) 7);
        }
        for (int x = 5; x <= 7; x++) {
            addDetail(world, x, 1, 0, -1L, (byte) 7);
        }
        PlayerController player = controller(world);
        player.teleport(new Vector3f(0.5f, 1, 0.5f));

        advance(player, 66, 1, 0);

        assertTrue(player.body().position(new Vector3f()).x > 5.0f);
        assertEquals(2.0f, player.body().position(new Vector3f()).y,
                GameConfig.Physics.COLLISION_TOLERANCE + EPSILON);
        assertTrue(player.isGrounded());

        advance(player, 66, -1, 0);

        assertTrue(player.body().position(new Vector3f()).x < 1.0f);
        assertEquals(1.0f, player.body().position(new Vector3f()).y,
                GameConfig.Physics.COLLISION_TOLERANCE + EPSILON);
        assertTrue(player.isGrounded());
    }

    private static CollisionWorld collisions(World world) {
        return new CollisionWorld(
                world, BlockCollisionShapeResolver.fullCubesForNonAir());
    }

    private static PlayerController controller(World world) {
        PhysicsBody body = new PhysicsBody(
                PLAYER_COLLIDER, MassProperties.dynamic(1.0f));
        return new PlayerController(
                body,
                collisions(world),
                GameConfig.Player.MOVEMENT_SPEED,
                GameConfig.Player.NOCLIP_SPEED,
                GameConfig.Player.JUMP_VELOCITY,
                GameConfig.Physics.GRAVITY,
                GameConfig.Physics.TERMINAL_VELOCITY);
    }

    private static void advance(
            PlayerController player, int steps, float moveX, float moveZ) {
        for (int step = 0; step < steps; step++) {
            player.fixedUpdate(
                    FIXED_STEP, moveX, moveZ, false, false, false);
        }
    }

    private static World detailWorld(
            int parentX,
            int parentY,
            int parentZ,
            long mask,
            byte blockId) {
        World world = new World();
        world.generate(ChunkKey.fromWorld(parentX, parentZ), ignored -> {});
        addDetail(world, parentX, parentY, parentZ, mask, blockId);
        return world;
    }

    private static void addDetail(
            World world,
            int parentX,
            int parentY,
            int parentZ,
            long mask,
            byte blockId) {
        for (int index = 0; index < DetailCellState.CELL_COUNT; index++) {
            if ((mask & (1L << index)) == 0L) {
                continue;
            }
            ParentCellObservation observation = observe(
                    world, parentX, parentY, parentZ);
            ChunkDetailMutationOutcome outcome = world.chunks().mutateDetail(
                    new ChunkDetailMutation.SetSubVoxel(
                            parentX,
                            parentY,
                            parentZ,
                            observation.chunkRevision(),
                            observation.state(),
                            LocalSubVoxelPosition.fromIndex(index),
                            blockId));
            assertEquals(ChunkDetailMutationOutcome.Status.APPLIED, outcome.status());
        }
    }

    private static void clearDetail(
            World world,
            int parentX,
            int parentY,
            int parentZ,
            LocalSubVoxelPosition position) {
        ParentCellObservation observation = observe(
                world, parentX, parentY, parentZ);
        ChunkDetailMutationOutcome outcome = world.chunks().mutateDetail(
                new ChunkDetailMutation.SetSubVoxel(
                        parentX,
                        parentY,
                        parentZ,
                        observation.chunkRevision(),
                        observation.state(),
                        position,
                        (byte) 0));
        assertEquals(ChunkDetailMutationOutcome.Status.APPLIED, outcome.status());
    }

    private static ParentCellObservation observe(
            World world, int x, int y, int z) {
        return world.observeCell(x, y, z).observation().orElseThrow();
    }

    private static void fillFloor(
            World world, int minX, int maxX, int minZ, int maxZ) {
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                if (world.getBlock(x, 0, z) == 0) {
                    assertTrue(world.setBlock(x, 0, z, (byte) 1));
                }
            }
        }
    }

    private static long maskFor(CellPredicate predicate) {
        long mask = 0L;
        for (int z = 0; z < 4; z++) {
            for (int y = 0; y < 4; y++) {
                for (int x = 0; x < 4; x++) {
                    if (predicate.test(x, y, z)) {
                        mask |= bit(x, y, z);
                    }
                }
            }
        }
        return mask;
    }

    private static long bit(int x, int y, int z) {
        return 1L << (x + 4 * y + 16 * z);
    }

    @FunctionalInterface
    private interface CellPredicate {
        boolean test(int x, int y, int z);
    }
}
