package com.gaia.interaction.feedback;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gaia.interaction.BlockInteractionSnapshot;
import com.gaia.interaction.BlockInteractionViewModel;
import com.gaia.interaction.GameMode;
import com.overlord.assets.ResourceLocation;
import com.overlord.interaction.api.BlockFace;
import com.overlord.interaction.api.BlockHitResult;
import com.overlord.interaction.api.InteractionMode;
import com.overlord.inventory.api.ItemStack;
import com.overlord.renderer.feedback.FeedbackVisibility;
import com.overlord.renderer.feedback.InteractionFeedbackFrame;
import com.overlord.renderer.feedback.ParticleVisual;
import com.overlord.renderer.particle.ParticleCategory;
import com.overlord.renderer.particle.ParticleSystem;
import com.overlord.renderer.texture.TextureRegion;
import com.overlord.worlditem.api.WorldItemId;
import com.overlord.worlditem.api.WorldItemSnapshot;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class InteractionFeedbackCoordinatorTest {
    private static final ResourceLocation AIR = ResourceLocation.parse("gaia:air");
    private static final ResourceLocation STONE = ResourceLocation.parse("gaia:stone");
    private static final ResourceLocation DIRT = ResourceLocation.parse("gaia:dirt");
    private static final TextureRegion STONE_REGION = region("gaia:stone_top", 0);
    private static final TextureRegion DIRT_REGION = region("gaia:dirt_top", 16);
    private static final FeedbackVisibility VISIBLE =
            new FeedbackVisibility(true, true, true, false);

    @Test
    void tenValidSurvivalFixedUpdatesEmitExactlyOneContinuousParticle() {
        Fixture fixture = fixture();
        BlockInteractionViewModel view = breaking(1, 2, 3, STONE, 0.5, 4);

        for (int step = 0; step < 9; step++) {
            fixture.coordinator.fixedUpdate(view, true, step);
        }
        assertEquals(List.of(), continuous(fixture.coordinator.snapshot(view, List.of(), VISIBLE)));

        fixture.coordinator.fixedUpdate(view, true, 9);

        List<ParticleVisual> continuous =
                continuous(fixture.coordinator.snapshot(view, List.of(), VISIBLE));
        assertEquals(1, continuous.size());
        assertEquals(ParticleCategory.BREAK_CONTINUOUS, continuous.get(0).category());
        assertEquals(STONE_REGION, continuous.get(0).region());
        assertTrue(continuous.get(0).x() >= 1.4f && continuous.get(0).x() <= 1.6f);
        assertTrue(continuous.get(0).y() >= 2.5f && continuous.get(0).y() <= 2.65f);
        assertTrue(continuous.get(0).z() >= 3.4f && continuous.get(0).z() <= 3.6f);

        Fixture repeated = fixture();
        for (int step = 0; step < 10; step++) {
            repeated.coordinator.fixedUpdate(view, true, step);
        }
        assertEquals(
                continuous,
                continuous(repeated.coordinator.snapshot(view, List.of(), VISIBLE)));
    }

    @Test
    void creativeDisabledAndTargetChangeResetCadence() {
        Fixture fixture = fixture();
        BlockInteractionViewModel stone = breaking(1, 2, 3, STONE, 0.5, 4);
        BlockInteractionViewModel dirt = breaking(2, 2, 3, DIRT, 0.5, 4);

        for (int step = 0; step < 9; step++) {
            fixture.coordinator.fixedUpdate(stone, true, step);
        }
        fixture.coordinator.fixedUpdate(dirt, true, 9);
        for (int step = 10; step < 19; step++) {
            fixture.coordinator.fixedUpdate(dirt, true, step);
        }
        assertEquals(1, continuous(fixture.coordinator.snapshot(dirt, List.of(), VISIBLE)).size());
        assertEquals(DIRT_REGION,
                continuous(fixture.coordinator.snapshot(dirt, List.of(), VISIBLE)).get(0).region());

        fixture.coordinator.clearAll();
        for (int step = 0; step < 20; step++) {
            fixture.coordinator.fixedUpdate(creativeBreaking(), true, step);
            fixture.coordinator.fixedUpdate(stone, false, step);
        }
        assertEquals(List.of(), continuous(fixture.coordinator.snapshot(stone, List.of(), VISIBLE)));
    }

    @Test
    void sameBlockFaceChangeRequiresTenFreshValidStepsBeforeEmission() {
        Fixture fixture = fixture();
        BlockInteractionViewModel east =
                breakingOnFace(1, 2, 3, STONE, 0.5, 4, BlockFace.EAST);
        BlockInteractionViewModel west =
                breakingOnFace(1, 2, 3, STONE, 0.5, 4, BlockFace.WEST);

        for (int step = 0; step < 9; step++) {
            fixture.coordinator.fixedUpdate(east, true, step);
        }
        fixture.coordinator.fixedUpdate(west, true, 9);

        assertEquals(
                List.of(),
                continuous(fixture.coordinator.snapshot(west, List.of(), VISIBLE)));

        for (int step = 10; step < 19; step++) {
            fixture.coordinator.fixedUpdate(west, true, step);
        }
        assertEquals(
                1,
                continuous(fixture.coordinator.snapshot(west, List.of(), VISIBLE)).size());
    }

    @Test
    void sameTargetProgressRegressionRequiresTenFreshValidStepsBeforeEmission() {
        Fixture fixture = fixture();
        BlockInteractionViewModel advanced = breaking(1, 2, 3, STONE, 0.8, 7);
        BlockInteractionViewModel restarted = breaking(1, 2, 3, STONE, 0.1, 0);

        for (int step = 0; step < 9; step++) {
            fixture.coordinator.fixedUpdate(advanced, true, step);
        }
        fixture.coordinator.fixedUpdate(restarted, true, 9);

        assertEquals(
                List.of(),
                continuous(fixture.coordinator.snapshot(restarted, List.of(), VISIBLE)));

        for (int step = 10; step < 19; step++) {
            fixture.coordinator.fixedUpdate(restarted, true, step);
        }
        assertEquals(
                1,
                continuous(fixture.coordinator.snapshot(restarted, List.of(), VISIBLE)).size());
    }

    @Test
    void zeroStepTransientClearHidesDamageAndRequiresIdleBeforeOldProgressCanRearm() {
        Fixture fixture = fixture();
        BlockInteractionViewModel active = breaking(1, 2, 3, STONE, 0.5, 4);
        fixture.coordinator.fixedUpdate(active, true, 0);
        assertTrue(fixture.coordinator.snapshot(active, List.of(), VISIBLE).blockDamage().isPresent());

        fixture.coordinator.clearTransient();

        assertFalse(fixture.coordinator.snapshot(active, List.of(), VISIBLE).blockDamage().isPresent());
        fixture.coordinator.fixedUpdate(active, true, 1);
        assertFalse(fixture.coordinator.snapshot(active, List.of(), VISIBLE).blockDamage().isPresent());
        fixture.coordinator.fixedUpdate(idle(), true, 2);
        fixture.coordinator.fixedUpdate(active, true, 3);
        assertTrue(fixture.coordinator.snapshot(active, List.of(), VISIBLE).blockDamage().isPresent());
    }

    @Test
    void hiddenLifecycleVisibilityClearsOverlayWithoutRemovingCommittedParticles() {
        for (FeedbackVisibility hidden : List.of(
                new FeedbackVisibility(true, false, true, false),
                new FeedbackVisibility(true, true, false, false),
                new FeedbackVisibility(false, true, true, false),
                new FeedbackVisibility(true, true, true, true))) {
            Fixture fixture = fixture();
            BlockInteractionViewModel active = breaking(1, 2, 3, STONE, 0.5, 4);
            fixture.particles.emit(new com.overlord.renderer.particle.ParticleEmission(
                    ParticleCategory.BREAK_COMMITTED,
                    1.5f, 2.5f, 3.5f, STONE_REGION, 1, 4));
            fixture.coordinator.fixedUpdate(active, true, 0);

            fixture.coordinator.clearTransient();
            InteractionFeedbackFrame frame =
                    fixture.coordinator.snapshot(active, List.of(), hidden);

            assertFalse(frame.blockDamage().isPresent());
            assertEquals(1, committed(frame).size());
        }
    }

    @Test
    void committedParticlesContinueAgingAcrossTransientClearButClearAllRemovesEverything() {
        Fixture fixture = fixture();
        fixture.particles.emit(new com.overlord.renderer.particle.ParticleEmission(
                ParticleCategory.BREAK_COMMITTED,
                1.5f, 2.5f, 3.5f, STONE_REGION, 1, 4));
        ParticleVisual before = committed(
                fixture.coordinator.snapshot(idle(), List.of(), VISIBLE)).get(0);

        fixture.coordinator.clearTransient();
        fixture.coordinator.fixedUpdate(idle(), true, 1);
        ParticleVisual after = committed(
                fixture.coordinator.snapshot(idle(), List.of(), VISIBLE)).get(0);

        assertEquals(before.spawnSequence(), after.spawnSequence());
        assertNotEquals(before.y(), after.y());
        fixture.coordinator.clearAll();
        assertEquals(List.of(), fixture.coordinator.snapshot(idle(), List.of(), VISIBLE)
                .particles().particles());
    }

    @Test
    void worldSnapshotsReconcileAddUpdateRemoveAndFrameListsAreImmutable() {
        Fixture fixture = fixture();
        WorldItemSnapshot first = worldItem(7, STONE, 1, 2, 3, 0);
        WorldItemSnapshot second = worldItem(2, DIRT, 4, 5, 6, 0);
        List<WorldItemSnapshot> input = new ArrayList<>(List.of(first, second));

        InteractionFeedbackFrame initial =
                fixture.coordinator.snapshot(idle(), input, VISIBLE);
        input.clear();

        assertEquals(List.of(2L, 7L), initial.worldItems().stream()
                .map(visual -> visual.id().value()).toList());
        assertThrows(UnsupportedOperationException.class,
                () -> initial.worldItems().clear());

        InteractionFeedbackFrame updated = fixture.coordinator.snapshot(
                idle(), List.of(worldItem(7, STONE, 9, 2, 3, 1)), VISIBLE);
        assertEquals(1, updated.worldItems().size());
        assertEquals(7L, updated.worldItems().get(0).id().value());
        assertEquals(9.0, updated.worldItems().get(0).x());
        assertEquals(1, updated.worldItems().get(0).sourceRevision());
    }

    @Test
    void interactionBlockStateProvidesOnlyReadOnlyBlockedBoundary() {
        InteractionBlockState state = () -> true;

        assertTrue(state.blocked());
        assertFalse(InteractionBlockState.unblocked().blocked());
    }

    @Test
    void movementPresentationAndActionImpulseRemainSeparateFrameLayers() {
        Fixture fixture = fixture();
        FirstPersonMovementState moving =
                new FirstPersonMovementState(2.0f, 4.0f, 0.0f, true, false);
        for (int step = 0; step < 8; step++) {
            fixture.coordinator.fixedMovementUpdate(
                    FirstPersonMovementPresentation.FIXED_STEP_SECONDS, moving);
        }
        BlockHitResult target = breaking(1, 2, 3, STONE, 0.5, 4)
                .target().orElseThrow();
        fixture.coordinator.onPlacementCommitted(target, STONE, 41L);
        fixture.coordinator.renderUpdate(1.0 / 120.0);

        InteractionFeedbackFrame frame = fixture.coordinator.snapshotPhysical(
                idleWithItem(STONE), List.of(), 0.5f, VISIBLE);

        assertNotEquals(
                com.overlord.renderer.feedback.FirstPersonMovementVisual.identity(),
                frame.movementVisual());
        assertNotEquals(
                com.overlord.renderer.feedback.CameraImpulseVisual.identity(),
                frame.cameraImpulse());
    }

    @Test
    void committedReceiptCreatesAllBoundedPresentationAndCloseIsIdempotent() {
        Fixture fixture = fixture();
        BlockHitResult target = breaking(1, 2, 3, STONE, 0.5, 4)
                .target().orElseThrow();

        fixture.coordinator.onPlacementCommitted(target, STONE, 41L);
        fixture.coordinator.renderUpdate(1.0 / 120.0);
        InteractionFeedbackFrame frame = fixture.coordinator.snapshot(
                idleWithItem(STONE), List.of(), VISIBLE);

        assertEquals(1, frame.transientBlocks().size());
        assertEquals(1, frame.excludedBlockCells().size());
        assertTrue(frame.firstPersonItem().isPresent());
        assertNotEquals(
                com.overlord.renderer.feedback.VisualTransform.identity(),
                frame.firstPersonItem().orElseThrow().transform());
        assertNotEquals(
                com.overlord.renderer.feedback.CameraImpulseVisual.identity(),
                frame.cameraImpulse());
        assertEquals(6, frame.particles().particles().stream()
                .filter(particle -> particle.category()
                        == ParticleCategory.PLACEMENT_DEBRIS)
                .count());
        assertEquals(2, frame.particles().particles().stream()
                .filter(particle -> particle.category()
                        == ParticleCategory.PLACEMENT_ASTRAL)
                .count());

        fixture.coordinator.close();
        fixture.coordinator.close();
        fixture.coordinator.fixedMovementUpdate(
                FirstPersonMovementPresentation.FIXED_STEP_SECONDS,
                new FirstPersonMovementState(2.0f, 4.0f, 0.0f, true, false));
        fixture.coordinator.onPlacementCommitted(target, STONE, 42L);
        fixture.coordinator.onBreakCommitted(target, STONE, 43L);
        fixture.coordinator.onDropCommitted(STONE, 44L);
        InteractionFeedbackFrame closed = fixture.coordinator.snapshot(
                idleWithItem(STONE), List.of(worldItem(9, STONE, 1, 2, 3, 0)), VISIBLE);
        assertTrue(closed.transientBlocks().isEmpty());
        assertTrue(closed.excludedBlockCells().isEmpty());
        assertTrue(closed.firstPersonItem().isEmpty());
        assertEquals(
                com.overlord.renderer.feedback.FirstPersonMovementVisual.identity(),
                closed.movementVisual());
        assertEquals(
                com.overlord.renderer.feedback.CameraImpulseVisual.identity(),
                closed.cameraImpulse());
        assertTrue(closed.worldItems().isEmpty());
        assertTrue(closed.particles().particles().isEmpty());
        assertTrue(fixture.particles.snapshot().particles().isEmpty());
    }

    private static Fixture fixture() {
        ParticleSystem particles = new ParticleSystem();
        GaiaVisualRegionResolverStub resolver = new GaiaVisualRegionResolverStub();
        CommittedBreakVisualAdapter committed = new CommittedBreakVisualAdapter(
                AIR, resolver::resolve, particles, (event, failure) -> {});
        WorldItemVisualTracker tracker = new WorldItemVisualTracker(
                item -> com.overlord.renderer.feedback.WorldItemFaceRegions.uniform(
                        resolver.resolve(item)));
        InteractionFeedbackCoordinator coordinator = new InteractionFeedbackCoordinator(
                committed, particles, tracker, resolver::resolve);
        return new Fixture(coordinator, particles);
    }

    private static List<ParticleVisual> continuous(InteractionFeedbackFrame frame) {
        return frame.particles().particles().stream()
                .filter(particle -> particle.category() == ParticleCategory.BREAK_CONTINUOUS)
                .toList();
    }

    private static List<ParticleVisual> committed(InteractionFeedbackFrame frame) {
        return frame.particles().particles().stream()
                .filter(particle -> particle.category() == ParticleCategory.BREAK_COMMITTED)
                .toList();
    }

    private static BlockInteractionViewModel breaking(
            int x,
            int y,
            int z,
            ResourceLocation block,
            double progress,
            int stage) {
        return breakingOnFace(x, y, z, block, progress, stage, BlockFace.EAST);
    }

    private static BlockInteractionViewModel breakingOnFace(
            int x,
            int y,
            int z,
            ResourceLocation block,
            double progress,
            int stage,
            BlockFace face) {
        int normalX = face == BlockFace.EAST ? 1 : -1;
        BlockHitResult hit = new BlockHitResult(
                x, y, z, x + normalX, y, z, block,
                normalX, 0, 0, x + 0.5f + normalX * 0.5f, y + 0.5f, z + 0.5f, 2);
        return new BlockInteractionSnapshot(
                Optional.of(hit), Optional.of(face), progress,
                InteractionMode.BREAKING, Optional.empty(), Optional.empty(),
                stage, GameMode.SURVIVAL);
    }

    private static BlockInteractionViewModel creativeBreaking() {
        BlockInteractionViewModel survival = breaking(1, 2, 3, STONE, 0.5, 4);
        return new BlockInteractionSnapshot(
                survival.target(), survival.hitFace(), survival.progress(), survival.mode(),
                survival.activeItem(), survival.failureReason(), survival.crackStage(),
                GameMode.CREATIVE);
    }

    private static BlockInteractionViewModel idle() {
        return new BlockInteractionSnapshot(
                Optional.empty(), Optional.empty(), 0,
                InteractionMode.NONE, Optional.empty(), Optional.empty(),
                0, GameMode.SURVIVAL);
    }

    private static BlockInteractionViewModel idleWithItem(ResourceLocation item) {
        return new BlockInteractionSnapshot(
                Optional.empty(), Optional.empty(), 0,
                InteractionMode.NONE,
                Optional.of(new ItemStack(item, 1)),
                Optional.empty(),
                0, GameMode.SURVIVAL);
    }

    private static WorldItemSnapshot worldItem(
            long id,
            ResourceLocation item,
            double x,
            double y,
            double z,
            long revision) {
        return new WorldItemSnapshot(
                new WorldItemId(id), new ItemStack(item, 1),
                x, y, z, 0, 0, 0, revision);
    }

    private static TextureRegion region(String id, int x) {
        return new TextureRegion(ResourceLocation.parse(id), x, 0, 16, 16, 32, 16);
    }

    private record Fixture(
            InteractionFeedbackCoordinator coordinator,
            ParticleSystem particles) {}

    private static final class GaiaVisualRegionResolverStub {
        private TextureRegion resolve(ResourceLocation block) {
            return block.equals(DIRT) ? DIRT_REGION : STONE_REGION;
        }
    }
}
