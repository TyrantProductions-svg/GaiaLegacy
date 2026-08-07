package com.gaia;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gaia.interaction.BlockInteractionSnapshot;
import com.gaia.interaction.BlockInteractionViewModel;
import com.gaia.interaction.GameMode;
import com.gaia.interaction.feedback.CommittedBreakVisualAdapter;
import com.gaia.interaction.feedback.InteractionFeedbackCoordinator;
import com.gaia.interaction.feedback.WorldItemVisualTracker;
import com.gaia.worlditem.WorldItemPresentationSnapshot;
import com.overlord.assets.ResourceLocation;
import com.overlord.interaction.api.BlockFace;
import com.overlord.interaction.api.BlockHitResult;
import com.overlord.interaction.api.InteractionMode;
import com.overlord.inventory.api.ItemStack;
import com.overlord.renderer.feedback.InteractionFeedbackFrame;
import com.overlord.renderer.particle.ParticleCategory;
import com.overlord.renderer.particle.ParticleEmission;
import com.overlord.renderer.particle.ParticleSystem;
import com.overlord.renderer.texture.TextureRegion;
import com.overlord.worlditem.api.WorldItemId;
import com.overlord.worlditem.api.WorldItemPhysicalSnapshot;
import com.overlord.worlditem.api.WorldItemPhysicalState;
import com.overlord.worlditem.api.WorldItemRuntimeSnapshot;
import com.overlord.worlditem.api.WorldItemSnapshot;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class InteractionFeedbackGameLoopTest {
    private static final ResourceLocation AIR = ResourceLocation.parse("gaia:air");
    private static final ResourceLocation STONE = ResourceLocation.parse("gaia:stone");
    private static final TextureRegion REGION =
            new TextureRegion(ResourceLocation.parse("gaia:stone"), 0, 0, 16, 16, 16, 16);

    @Test
    void renderSnapshotCopiesWorldItemsAndSamplesThePostInteractionView() {
        Fixture fixture = fixture();
        BlockInteractionViewModel active = breaking();
        fixture.coordinator.fixedUpdate(active, true, 4);
        List<WorldItemSnapshot> snapshots = new ArrayList<>(List.of(worldItem(7)));

        InteractionFeedbackFrame frame = GameLoop.feedbackSnapshot(
                fixture.coordinator, active, snapshots, true, true, true, false);
        snapshots.clear();

        assertTrue(frame.visibility().showGameplayFeedback());
        assertTrue(frame.blockDamage().isPresent());
        assertEquals(4, frame.blockDamage().orElseThrow().crackStage());
        assertEquals(List.of(7L), frame.worldItems().stream()
                .map(item -> item.id().value()).toList());
        assertThrows(UnsupportedOperationException.class, () -> frame.worldItems().clear());
    }

    @Test
    void productionFeedbackUsesInterpolatedPhysicalPresentationNotLogicalPosition() {
        Fixture fixture = fixture();
        WorldItemSnapshot canonical = worldItem(8);
        WorldItemPresentationSnapshot physical = new WorldItemPresentationSnapshot(
                new WorldItemPhysicalSnapshot(
                        new WorldItemRuntimeSnapshot(
                                canonical, Optional.empty(), 0, 0),
                        WorldItemPhysicalState.ACTIVE,
                        false),
                0, 1, 2,
                4, 5, 6);

        InteractionFeedbackFrame frame = GameLoop.feedbackSnapshotPhysical(
                fixture.coordinator,
                idle(),
                List.of(physical),
                0.25f,
                true,
                true,
                true,
                false);

        assertEquals(1.0, frame.worldItems().get(0).x());
        assertEquals(2.0, frame.worldItems().get(0).y());
        assertEquals(3.0, frame.worldItems().get(0).z());
        assertEquals(4.0, canonical.positionX());
    }

    @Test
    void zeroStepLifecycleClearImmediatelyHidesStaleProgressAcrossRecapture() {
        Fixture fixture = fixture();
        BlockInteractionViewModel active = breaking();
        fixture.coordinator.fixedUpdate(active, true, 1);
        assertTrue(GameLoop.feedbackSnapshot(
                fixture.coordinator, active, List.of(), true, true, true, false)
                .blockDamage().isPresent());

        GameLoop.clearFeedbackForLifecycleBoundary(fixture.coordinator);

        InteractionFeedbackFrame hidden = GameLoop.feedbackSnapshot(
                fixture.coordinator, active, List.of(), true, false, true, false);
        InteractionFeedbackFrame recaptured = GameLoop.feedbackSnapshot(
                fixture.coordinator, active, List.of(), true, true, true, false);
        assertFalse(hidden.blockDamage().isPresent());
        assertFalse(recaptured.blockDamage().isPresent());
    }

    @Test
    void focusLoadingAndBlockingVisibilityAreConjoinedWithoutDeletingCommittedParticles() {
        for (boolean[] state : List.of(
                new boolean[] {false, true, true, false},
                new boolean[] {true, false, true, false},
                new boolean[] {true, true, false, false},
                new boolean[] {true, true, true, true})) {
            Fixture fixture = fixture();
            fixture.particles.emit(new ParticleEmission(
                    ParticleCategory.BREAK_COMMITTED,
                    1.5f, 2.5f, 3.5f, REGION, 1, 9));
            fixture.coordinator.fixedUpdate(breaking(), true, 1);
            GameLoop.clearFeedbackForLifecycleBoundary(fixture.coordinator);

            InteractionFeedbackFrame frame = GameLoop.feedbackSnapshot(
                    fixture.coordinator,
                    breaking(),
                    List.of(),
                    state[0], state[1], state[2], state[3]);

            assertFalse(frame.visibility().showGameplayFeedback());
            assertFalse(frame.blockDamage().isPresent());
            assertEquals(1, frame.particles().particles().size());
            assertEquals(
                    ParticleCategory.BREAK_COMMITTED,
                    frame.particles().particles().get(0).category());
        }
    }

    @Test
    void loadingPresentationOmitsWorldItemsButRetainsCommittedParticles() {
        Fixture fixture = fixture();
        fixture.particles.emit(new ParticleEmission(
                ParticleCategory.BREAK_COMMITTED,
                1.5f, 2.5f, 3.5f, REGION, 1, 9));
        WorldItemSnapshot item = worldItem(7);

        InteractionFeedbackFrame loading = GameLoop.feedbackSnapshot(
                fixture.coordinator,
                breaking(),
                List.of(item),
                false,
                true,
                true,
                false);
        InteractionFeedbackFrame running = GameLoop.feedbackSnapshot(
                fixture.coordinator,
                breaking(),
                List.of(item),
                true,
                true,
                true,
                false);

        assertTrue(loading.worldItems().isEmpty());
        assertEquals(1, loading.particles().particles().size());
        assertEquals(List.of(7L), running.worldItems().stream()
                .map(snapshot -> snapshot.id().value())
                .toList());
    }

    @Test
    void interactionEnablementRequiresRunningCaptureFocusAndNoBlock() {
        assertTrue(GameLoop.interactionEnabled(true, true, true, false));
        assertFalse(GameLoop.interactionEnabled(false, true, true, false));
        assertFalse(GameLoop.interactionEnabled(true, false, true, false));
        assertFalse(GameLoop.interactionEnabled(true, true, false, false));
        assertFalse(GameLoop.interactionEnabled(true, true, true, true));
    }

    @Test
    void zeroStepLifecycleGateClearsForF1FocusLoadingAndBlockingTransitions() {
        for (boolean[] lifecycle : List.of(
                new boolean[] {true, true, true, true, false},
                new boolean[] {false, true, true, false, false},
                new boolean[] {false, false, true, true, false},
                new boolean[] {false, true, true, true, true})) {
            Fixture fixture = fixture();
            BlockInteractionViewModel active = breaking();
            fixture.coordinator.fixedUpdate(active, true, 1);

            GameLoop.handleFeedbackLifecycle(
                    fixture.coordinator,
                    lifecycle[0],
                    lifecycle[1],
                    lifecycle[2],
                    lifecycle[3],
                    lifecycle[4]);

            assertFalse(GameLoop.feedbackSnapshot(
                    fixture.coordinator, active, List.of(), true, true, true, false)
                    .blockDamage().isPresent());
        }
    }

    @Test
    void frameFeedbackDispatchClearsThenSnapshotsThenRendersWithoutStaleDamage() {
        Fixture fixture = fixture();
        BlockInteractionViewModel active = breaking();
        fixture.coordinator.fixedUpdate(active, true, 1);
        List<String> trace = new ArrayList<>();

        InteractionFeedbackFrame rendered = GameLoop.dispatchFeedbackFrame(
                () -> {
                    trace.add("clearTransient");
                    GameLoop.handleFeedbackLifecycle(
                            fixture.coordinator, true, true, true, true, false);
                },
                () -> {
                    trace.add("snapshot");
                    return GameLoop.feedbackSnapshot(
                            fixture.coordinator,
                            active,
                            List.of(),
                            true,
                            false,
                            true,
                            false);
                },
                frame -> {
                    trace.add("render");
                    assertFalse(frame.blockDamage().isPresent());
                });

        assertEquals(List.of("clearTransient", "snapshot", "render"), trace);
        assertFalse(rendered.blockDamage().isPresent());
    }

    @Test
    void fixedBatchRunsFeedbackAndOtherSystemsExactlyOncePerStepWhenBlocked() {
        List<String> trace = new ArrayList<>();
        AtomicInteger physics = new AtomicInteger();
        AtomicInteger feedback = new AtomicInteger();
        AtomicInteger modules = new AtomicInteger();

        GameLoop.runFixedBatch(3, step -> GameLoop.runFixedSystemStep(
                () -> {
                    physics.incrementAndGet();
                    trace.add("physics-" + step);
                },
                () -> false,
                enabled -> {
                    assertFalse(enabled);
                    trace.add("interaction-" + step);
                },
                enabled -> {
                    assertFalse(enabled);
                    feedback.incrementAndGet();
                    trace.add("feedback-" + step);
                },
                () -> {
                    modules.incrementAndGet();
                    trace.add("modules-" + step);
                }));

        assertEquals(3, physics.get());
        assertEquals(3, feedback.get());
        assertEquals(3, modules.get());
        assertEquals(List.of(
                "physics-0", "interaction-0", "feedback-0", "modules-0",
                "physics-1", "interaction-1", "feedback-1", "modules-1",
                "physics-2", "interaction-2", "feedback-2", "modules-2"), trace);
    }

    @Test
    void uiBlockChangedByEitherFixedSystemSideHidesSameFrameAndClearsCadence() {
        for (boolean blockInLeadingSystem : List.of(true, false)) {
            Fixture fixture = fixture();
            BlockInteractionViewModel active = breaking();
            fixture.coordinator.fixedUpdate(active, true, 0);
            assertTrue(GameLoop.feedbackSnapshot(
                            fixture.coordinator, active, List.of(), true, true, true, false)
                    .blockDamage()
                    .isPresent());
            AtomicBoolean blocked = new AtomicBoolean(false);
            AtomicInteger leadingCalls = new AtomicInteger();
            AtomicInteger trailingCalls = new AtomicInteger();

            GameLoop.runFixedSystemStep(
                    () -> {
                        leadingCalls.incrementAndGet();
                        if (blockInLeadingSystem) {
                            blocked.set(true);
                        }
                    },
                    () -> !blocked.get(),
                    enabled -> {},
                    enabled -> fixture.coordinator.fixedUpdate(active, enabled, 1),
                    () -> {
                        trailingCalls.incrementAndGet();
                        if (!blockInLeadingSystem) {
                            blocked.set(true);
                        }
                    });

            InteractionFeedbackFrame rendered = GameLoop.dispatchFeedbackFrame(
                    () -> GameLoop.handleFeedbackLifecycle(
                            fixture.coordinator,
                            false,
                            true,
                            true,
                            true,
                            blocked.get()),
                    () -> GameLoop.feedbackSnapshot(
                            fixture.coordinator,
                            active,
                            List.of(),
                            true,
                            true,
                            true,
                            blocked.get()),
                    frame -> {});

            assertFalse(rendered.visibility().showGameplayFeedback());
            assertFalse(rendered.blockDamage().isPresent());
            assertEquals(1, leadingCalls.get());
            assertEquals(1, trailingCalls.get());

            fixture.coordinator.fixedUpdate(idle(), false, 2);
            int particlesBeforeRestart = fixture.particles.snapshot().particles().size();
            for (int step = 0; step < 9; step++) {
                fixture.coordinator.fixedUpdate(active, true, 3 + step);
            }
            assertEquals(
                    particlesBeforeRestart,
                    fixture.particles.snapshot().particles().size(),
                    "a blocked lifecycle must reset continuous particle cadence");
            fixture.coordinator.fixedUpdate(active, true, 12);
            assertEquals(
                    particlesBeforeRestart + 1,
                    fixture.particles.snapshot().particles().size());
        }
    }

    private static Fixture fixture() {
        ParticleSystem particles = new ParticleSystem();
        CommittedBreakVisualAdapter adapter = new CommittedBreakVisualAdapter(
                AIR, ignored -> REGION, particles, (event, failure) -> {});
        InteractionFeedbackCoordinator coordinator = new InteractionFeedbackCoordinator(
                adapter,
                particles,
                new WorldItemVisualTracker(
                        ignored -> com.overlord.renderer.feedback.WorldItemFaceRegions.uniform(REGION)),
                ignored -> REGION);
        return new Fixture(coordinator, particles);
    }

    private static BlockInteractionViewModel breaking() {
        BlockHitResult hit = new BlockHitResult(
                1, 2, 3, 2, 2, 3, STONE,
                1, 0, 0, 2, 2.5f, 3.5f, 2);
        return new BlockInteractionSnapshot(
                Optional.of(hit),
                Optional.of(BlockFace.EAST),
                0.5,
                InteractionMode.BREAKING,
                Optional.empty(),
                Optional.empty(),
                4,
                GameMode.SURVIVAL);
    }

    private static BlockInteractionViewModel idle() {
        return new BlockInteractionSnapshot(
                Optional.empty(),
                Optional.empty(),
                0,
                InteractionMode.NONE,
                Optional.empty(),
                Optional.empty(),
                0,
                GameMode.SURVIVAL);
    }

    private static WorldItemSnapshot worldItem(long id) {
        return new WorldItemSnapshot(
                new WorldItemId(id),
                new ItemStack(STONE, 1),
                4, 5, 6, 0, 0, 0, 0);
    }

    private record Fixture(
            InteractionFeedbackCoordinator coordinator,
            ParticleSystem particles) {}
}
