package com.gaia.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_LEFT;
import static org.lwjgl.glfw.GLFW.GLFW_PRESS;
import static org.lwjgl.glfw.GLFW.GLFW_RELEASE;

import com.gaia.blocks.BlockDefinition;
import com.gaia.blocks.BlockRegistry;
import com.gaia.blocks.ItemFormDefinition;
import com.gaia.interaction.BlockBreakTransaction;
import com.gaia.interaction.BlockInteractionController;
import com.gaia.interaction.BlockInteractionViewModel;
import com.gaia.interaction.BlockPlacementTransaction;
import com.gaia.interaction.BlockPlacementWorldView;
import com.gaia.interaction.CreativeSelection;
import com.gaia.interaction.GameMode;
import com.gaia.interaction.GameModeManager;
import com.gaia.interaction.feedback.CommittedBreakVisualAdapter;
import com.gaia.interaction.feedback.InteractionFeedbackCoordinator;
import com.gaia.interaction.feedback.WorldItemVisualTracker;
import com.gaia.inventory.BodyInventoryService;
import com.overlord.assets.ResourceLocation;
import com.overlord.core.input.InputManager;
import com.overlord.core.input.InputManagerTestDriver;
import com.overlord.core.input.InputSnapshot;
import com.overlord.core.input.MouseDelta;
import com.overlord.core.thread.MainThreadGuard;
import com.overlord.core.time.FixedStepClock;
import com.overlord.interaction.api.BlockChangeResult;
import com.overlord.interaction.api.BlockHitResult;
import com.overlord.interaction.api.EntityRef;
import com.overlord.interaction.api.InteractionMode;
import com.overlord.physics.Aabb;
import com.overlord.physics.MassProperties;
import com.overlord.physics.PhysicsBody;
import com.overlord.physics.SpatialQueryResult;
import com.overlord.renderer.RenderFrameInput;
import com.overlord.renderer.feedback.FeedbackVisibility;
import com.overlord.renderer.feedback.InteractionFeedbackFrame;
import com.overlord.renderer.material.MaterialDefinition;
import com.overlord.renderer.material.RenderType;
import com.overlord.renderer.particle.ParticleSystem;
import com.overlord.renderer.texture.TextureRegion;
import com.overlord.voxel.BlockFace;
import com.overlord.voxel.BlockRenderInfo;
import com.overlord.voxel.ChunkKey;
import com.overlord.voxel.ChunkRepository;
import com.overlord.voxel.DirtyChunkRevision;
import com.overlord.voxel.FullCellState;
import com.overlord.voxel.ParentCellState;
import com.overlord.worlditem.LogicalWorldItemService;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

class GameSessionEligibilityBoundaryTest {
    private static final EntityRef OWNER = new EntityRef(42);
    private static final ResourceLocation AIR =
            ResourceLocation.parse("gaia:air");
    private static final ResourceLocation STONE =
            ResourceLocation.parse("gaia:stone");
    private static final ResourceLocation MISSING =
            ResourceLocation.parse("gaia:missing");
    private static final TextureRegion REGION =
            new TextureRegion(STONE, 0, 0, 16, 16, 16, 16);
    private static final FeedbackVisibility VISIBLE =
            new FeedbackVisibility(true, true, true, false);
    private static final GameSessionConfig CONFIG =
            new GameSessionConfig(91L, 1, GameMode.SURVIVAL, false);

    @Test
    void cursorReleaseClearsCanonicalSessionStateBeforeAnotherFixedStep() {
        EligibilityFixture fixture = new EligibilityFixture();
        GameSession session = fixture.readySession();
        try {
            fixture.pressPrimary();
            BlockInteractionViewModel active = fixture.beginBreak(session);

            fixture.input().resetMouseBaseline();
            fixture.input().discardFixedInputEdges();
            session.discardFixedTime();

            fixture.assertBoundaryCleared(active);
            fixture.assertHeldAttackCannotResumeUntilRelease(session);
        } finally {
            session.close();
        }
    }

    @Test
    void focusLossClearsCanonicalSessionStateBeforeAnotherFixedStep() {
        EligibilityFixture fixture = new EligibilityFixture();
        GameSession session = fixture.readySession();
        try {
            fixture.pressPrimary();
            BlockInteractionViewModel active = fixture.beginBreak(session);

            InputManagerTestDriver.windowFocus(fixture.input(), false);
            assertTrue(fixture.input().consumeMouseInteractionInvalidation());
            fixture.input().discardFixedInputEdges();
            session.discardFixedTime();

            fixture.assertBoundaryCleared(active);
            InputManagerTestDriver.windowFocus(fixture.input(), true);
            fixture.assertHeldAttackCannotResumeUntilRelease(session);
        } finally {
            session.close();
        }
    }

    private static final class EligibilityFixture {
        private final InputManager input = new InputManager();
        private final FixedStepClock clock =
                new FixedStepClock(1.0 / 60.0, 8);
        private final BlockInteractionController interaction =
                interactionController();
        private final InteractionFeedbackCoordinator feedback = feedback();
        private final BoundaryRuntime runtime =
                new BoundaryRuntime(input, clock, interaction, feedback);
        private final GameSessionFactory factory =
                new GameSessionFactory(
                        (config, world, shutdown) -> {
                            shutdown.register("feedback", feedback::close);
                            return runtime;
                        });

        GameSession readySession() {
            GameSession session = factory.create(CONFIG);
            session.pollLoad();
            assertEquals(GameSessionState.READY, session.state());
            return session;
        }

        InputManager input() {
            return input;
        }

        void pressPrimary() {
            InputManagerTestDriver.mouseButton(
                    input, GLFW_MOUSE_BUTTON_LEFT, GLFW_PRESS);
        }

        BlockInteractionViewModel beginBreak(GameSession session) {
            session.advancePlaying(0.020, MouseDelta.ZERO, true);
            BlockInteractionViewModel active = interaction.viewModel();
            assertEquals(InteractionMode.BREAKING, active.mode());
            assertTrue(active.progress() > 0.0);
            assertTrue(
                    feedback.snapshot(active, List.of(), VISIBLE)
                            .blockDamage()
                            .isPresent());
            assertTrue(clock.remainderSeconds() > 0.0);
            return active;
        }

        void assertBoundaryCleared(BlockInteractionViewModel activeBefore) {
            assertEquals(
                    InteractionMode.NONE,
                    interaction.viewModel().mode());
            assertEquals(0.0, interaction.viewModel().progress());
            assertFalse(
                    feedback.snapshot(activeBefore, List.of(), VISIBLE)
                            .blockDamage()
                            .isPresent(),
                    "transient feedback must clear even when an old active view is sampled");
            assertEquals(0.0, clock.remainderSeconds());
        }

        void assertHeldAttackCannotResumeUntilRelease(GameSession session) {
            InputManagerTestDriver.mouseButton(
                    input, GLFW_MOUSE_BUTTON_LEFT, GLFW_PRESS);
            session.advancePlaying(1.0 / 60.0, MouseDelta.ZERO, true);
            assertEquals(
                    InteractionMode.NONE,
                    interaction.viewModel().mode());
            assertEquals(0.0, interaction.viewModel().progress());

            InputManagerTestDriver.mouseButton(
                    input, GLFW_MOUSE_BUTTON_LEFT, GLFW_RELEASE);
            InputManagerTestDriver.mouseButton(
                    input, GLFW_MOUSE_BUTTON_LEFT, GLFW_PRESS);
            session.advancePlaying(1.0 / 60.0, MouseDelta.ZERO, true);
            assertEquals(
                    InteractionMode.BREAKING,
                    interaction.viewModel().mode());
            assertTrue(interaction.viewModel().progress() > 0.0);
        }
    }

    private static final class BoundaryRuntime
            implements GameSessionFactory.SessionRuntime {
        private final InputManager input;
        private final FixedStepClock clock;
        private final BlockInteractionController interaction;
        private final InteractionFeedbackCoordinator feedback;
        private long tick;
        private GameSessionFrame lastFrame = frame(InteractionFeedbackFrame.hidden());

        private BoundaryRuntime(
                InputManager input,
                FixedStepClock clock,
                BlockInteractionController interaction,
                InteractionFeedbackCoordinator feedback) {
            this.input = input;
            this.clock = clock;
            this.interaction = interaction;
            this.feedback = feedback;
        }

        @Override
        public boolean pollLoad() {
            return true;
        }

        @Override
        public GameSessionFrame advancePlaying(
                double frameDeltaSeconds,
                MouseDelta look,
                boolean focused) {
            int fixedSteps = clock.advance(frameDeltaSeconds);
            if (fixedSteps > 0) {
                InputSnapshot frameInput = input.consumeFixedInput();
                for (int step = 0; step < fixedSteps; step++) {
                    InputSnapshot stepInput =
                            step == 0 ? frameInput : frameInput.heldOnly();
                    tick++;
                    interaction.fixedUpdate(
                            stepInput,
                            clock.fixedStepSeconds(),
                            tick,
                            tick,
                            focused);
                    feedback.fixedUpdate(
                            interaction.viewModel(), focused, tick);
                }
            }
            lastFrame =
                    frame(
                            feedback.snapshot(
                                    interaction.viewModel(),
                                    List.of(),
                                    VISIBLE));
            return lastFrame;
        }

        @Override
        public GameSessionFrame capturePaused() {
            return lastFrame.copy();
        }

        @Override
        public void discardGameplayEligibility() {
            interaction.cancel();
            feedback.clearTransient();
        }

        @Override
        public void discardFixedTime() {
            clock.discardRemainder();
        }

        private static GameSessionFrame frame(
                InteractionFeedbackFrame feedback) {
            return new GameSessionFrame(
                    new RenderFrameInput(List.of(), 0.0, 0, feedback));
        }
    }

    private static BlockInteractionController interactionController() {
        BlockRegistry blocks = blocks();
        ChunkRepository chunks = new ChunkRepository();
        chunks.generate(new ChunkKey(0, 0), ignored -> {});
        BodyInventoryService inventory =
                new BodyInventoryService(
                        OWNER,
                        blocks,
                        MainThreadGuard.captureCurrentThread(),
                        event -> {});
        LogicalWorldItemService worldItems =
                new LogicalWorldItemService(
                        MainThreadGuard.captureCurrentThread(), 16, 10);
        com.overlord.interaction.api.WorldMutationService mutation =
                request ->
                        new BlockChangeResult(
                                request,
                                BlockChangeResult.Status.APPLIED,
                                Optional.of(request.expectedBlock()),
                                List.of(
                                        new DirtyChunkRevision(
                                                new ChunkKey(0, 0), 2)));
        PhysicsBody body =
                new PhysicsBody(
                        new Aabb(-0.3f, 0, -0.3f, 0.3f, 1.8f, 0.3f),
                        MassProperties.dynamic(1));
        body.teleport(new Vector3f());
        BlockPlacementWorldView placementWorld =
                new BlockPlacementWorldView() {
                    @Override
                    public boolean isLoaded(int x, int y, int z) {
                        return true;
                    }

                    @Override
                    public ParentCellState parentStateAt(int x, int y, int z) {
                        return new FullCellState((byte) 0);
                    }

                    @Override
                    public ResourceLocation blockAt(int x, int y, int z) {
                        return AIR;
                    }
                };
        return new BlockInteractionController(
                new GameModeManager(GameMode.SURVIVAL, event -> {}),
                () -> SpatialQueryResult.available(Optional.of(hit())),
                chunks,
                blocks,
                inventory,
                OWNER,
                new CreativeSelection(blocks, Optional.of(STONE)),
                new BlockBreakTransaction(
                        mutation, inventory, OWNER, worldItems, AIR),
                new BlockPlacementTransaction(
                        mutation,
                        inventory,
                        OWNER,
                        blocks,
                        placementWorld,
                        body,
                        AIR),
                1.0);
    }

    private static InteractionFeedbackCoordinator feedback() {
        ParticleSystem particles = new ParticleSystem();
        CommittedBreakVisualAdapter committed =
                new CommittedBreakVisualAdapter(
                        AIR,
                        ignored -> REGION,
                        particles,
                        (event, failure) -> {});
        return new InteractionFeedbackCoordinator(
                committed,
                particles,
                new WorldItemVisualTracker(
                        ignored ->
                                com.overlord.renderer.feedback.WorldItemFaceRegions
                                        .uniform(REGION)),
                ignored -> REGION);
    }

    private static BlockHitResult hit() {
        return new BlockHitResult(
                1,
                2,
                3,
                2,
                2,
                3,
                STONE,
                1,
                0,
                0,
                2,
                2.5f,
                3.5f,
                2);
    }

    private static BlockRegistry blocks() {
        MaterialDefinition material =
                new MaterialDefinition(
                        ResourceLocation.parse("gaia:opaque"),
                        ResourceLocation.parse("gaia:blocks"),
                        RenderType.OPAQUE,
                        0.5f,
                        MISSING);
        TextureRegion region =
                new TextureRegion(MISSING, 0, 0, 1, 1, 1, 1);
        return BlockRegistry.create(
                List.of(
                        definition(0, AIR, material.id()),
                        definition(1, STONE, material.id())),
                Map.of(
                        0,
                        BlockRenderInfo.nonRenderable(material, region),
                        1,
                        renderInfo(material, region)));
    }

    private static BlockDefinition definition(
            int id,
            ResourceLocation name,
            ResourceLocation material) {
        EnumMap<BlockFace, ResourceLocation> textures =
                new EnumMap<>(BlockFace.class);
        for (BlockFace face : BlockFace.values()) {
            textures.put(face, MISSING);
        }
        return new BlockDefinition(
                id,
                name,
                material,
                textures,
                1,
                1,
                1,
                false,
                false,
                1,
                id == 0
                        ? null
                        : new ItemFormDefinition(
                                name, 64, false, false));
    }

    private static BlockRenderInfo renderInfo(
            MaterialDefinition material,
            TextureRegion region) {
        EnumMap<BlockFace, TextureRegion> faces =
                new EnumMap<>(BlockFace.class);
        for (BlockFace face : BlockFace.values()) {
            faces.put(face, region);
        }
        return new BlockRenderInfo(material, faces, true);
    }
}
