package com.gaia.debug;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gaia.blocks.BlockDefinition;
import com.gaia.blocks.BlockRegistry;
import com.gaia.blocks.ItemFormDefinition;
import com.gaia.interaction.GaiaDetailMutationService;
import com.gaia.interaction.GaiaInteractionContext;
import com.overlord.assets.ResourceLocation;
import com.overlord.config.GameConfig;
import com.overlord.core.input.InputSnapshot;
import com.overlord.core.thread.MainThreadGuard;
import com.overlord.interaction.api.BlockHitResult;
import com.overlord.interaction.api.EntityRef;
import com.overlord.interaction.api.InteractionAction;
import com.overlord.inventory.api.BodySlot;
import com.overlord.physics.DetailCollisionBoxMerger;
import com.overlord.physics.DetailRaycastTarget;
import com.overlord.physics.FullRaycastTarget;
import com.overlord.physics.SpatialQueryResult;
import com.overlord.renderer.material.MaterialDefinition;
import com.overlord.renderer.material.RenderType;
import com.overlord.renderer.texture.TextureRegion;
import com.overlord.voxel.BlockFace;
import com.overlord.voxel.BlockRenderInfo;
import com.overlord.voxel.ChunkDirtyTracker;
import com.overlord.voxel.ChunkKey;
import com.overlord.voxel.ChunkRepository;
import com.overlord.voxel.DetailCellState;
import com.overlord.voxel.LocalSubVoxelPosition;
import com.overlord.voxel.VoxelScale;
import com.overlord.voxel.World;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class DetailDebugInputControllerTest {
    private static final ResourceLocation AIR = ResourceLocation.parse("gaia:air");
    private static final ResourceLocation STONE = ResourceLocation.parse("gaia:stone");
    private static final ResourceLocation DIRT = ResourceLocation.parse("gaia:dirt");
    private static final ResourceLocation MISSING = ResourceLocation.parse("gaia:missing");
    private static final MaterialDefinition MATERIAL = new MaterialDefinition(
            ResourceLocation.parse("gaia:opaque"), ResourceLocation.parse("gaia:blocks"),
            RenderType.OPAQUE, 0.5f, MISSING);
    private static final TextureRegion REGION =
            new TextureRegion(MISSING, 0, 0, 1, 1, 1, 1);

    @Test
    void fixtureModifierAvoidsTheWindowsAltF9RecordingShortcut() {
        assertEquals(
                org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT_CONTROL,
                GameConfig.Input.KEY_DEBUG_DETAIL_MODIFIER_LEFT);
        assertEquals(
                org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT_CONTROL,
                GameConfig.Input.KEY_DEBUG_DETAIL_MODIFIER_RIGHT);
        assertNotEquals(
                org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT_ALT,
                GameConfig.Input.KEY_DEBUG_DETAIL_MODIFIER_LEFT);
        assertEquals(
                org.lwjgl.glfw.GLFW.GLFW_KEY_9,
                GameConfig.Input.KEY_DEBUG_DETAIL_FIXTURE_NEXT);
        assertEquals(
                org.lwjgl.glfw.GLFW.GLFW_KEY_0,
                GameConfig.Input.KEY_DEBUG_DETAIL_FIXTURE_APPLY);
    }

    @Test
    void developmentKeysDelegateConvertFillClearToCanonicalServices() {
        Fixture fixture = fixture((byte) 2);
        AtomicReference<com.gaia.interaction.BlockTargetProvider> target =
                new AtomicReference<>(() -> SpatialQueryResult.available(Optional.of(fullHit())));
        DetailDebugInputController controller = controller(fixture, () -> target.get().target());

        String converted = controller.handle(
                pressed(GameConfig.Input.KEY_DEBUG_DETAIL_CONVERT), context()).orElseThrow();
        target.set(() -> SpatialQueryResult.available(Optional.of(detailHit(2, 1, 3))));
        String filled = controller.handle(
                pressed(GameConfig.Input.KEY_DEBUG_DETAIL_FILL), context()).orElseThrow();
        String cleared = controller.handle(
                pressed(GameConfig.Input.KEY_DEBUG_DETAIL_CLEAR), context()).orElseThrow();

        assertTrue(converted.contains("APPLIED"));
        assertTrue(filled.contains("APPLIED"));
        assertTrue(cleared.contains("APPLIED"));
        DetailCellState state = assertInstanceOf(
                DetailCellState.class,
                fixture.tools().inspect(4, 7, 6, new LocalSubVoxelPosition(2, 1, 3)).state());
        assertEquals(0, state.blockId(new LocalSubVoxelPosition(2, 1, 3)));
        assertTrue(fixture.repository().voxelModified(new ChunkKey(0, 0)));
    }

    @Test
    void inspectAndFixtureControlsAreBoundedAndDeterministic() {
        Fixture fixture = fixture((byte) 0);
        DetailDebugInputController controller = controller(
                fixture,
                () -> SpatialQueryResult.available(Optional.of(detailHit(0, 0, 0))));

        String inspected = controller.handle(
                pressed(GameConfig.Input.KEY_DEBUG_DETAIL_INSPECT), context()).orElseThrow();
        String cycled = controller.handle(
                pressed(GameConfig.Input.KEY_DEBUG_DETAIL_FIXTURE_NEXT), context()).orElseThrow();
        String applied = controller.handle(
                pressed(GameConfig.Input.KEY_DEBUG_DETAIL_FIXTURE_APPLY), context()).orElseThrow();

        assertTrue(inspected.contains("parent=[4,7,6]"));
        assertTrue(inspected.contains("selected=[0,0,0]"));
        assertTrue(cycled.contains("QUARTER_SLAB"));
        assertTrue(applied.contains("fixture=QUARTER_SLAB"));
        assertEquals(
                DetailFixturePattern.canonicalHash(
                        DetailFixturePattern.QUARTER_SLAB.state((byte) 1, (byte) 2)),
                fixture.tools().inspect(4, 7, 6, LocalSubVoxelPosition.fromIndex(0))
                        .canonicalHash());
    }

    @Test
    void unavailableAndNoTargetRemainExplicitAndNoKeyDoesNothing() {
        Fixture fixture = fixture((byte) 0);
        AtomicReference<com.gaia.interaction.BlockTargetProvider> target =
                new AtomicReference<>(() -> SpatialQueryResult.available(
                        Optional.of(detailHit(0, 0, 0))));
        DetailDebugInputController controller = controller(fixture, () -> target.get().target());
        assertTrue(controller.handle(new InputSnapshot(Set.of(), Set.of()), context()).isEmpty());

        target.set(() -> SpatialQueryResult.unavailable(
                SpatialQueryResult.Status.UNKNOWN, new ChunkKey(3, -2)));
        assertTrue(controller.handle(
                pressed(GameConfig.Input.KEY_DEBUG_DETAIL_INSPECT), context())
                .orElseThrow().contains("UNKNOWN"));

        target.set(() -> SpatialQueryResult.available(Optional.empty()));
        assertTrue(controller.handle(
                pressed(GameConfig.Input.KEY_DEBUG_DETAIL_INSPECT), context())
                .orElseThrow().contains("NO_TARGET"));
    }

    private static DetailDebugInputController controller(
            Fixture fixture, com.gaia.interaction.BlockTargetProvider targeting) {
        return new DetailDebugInputController(targeting, fixture.tools(), STONE, DIRT);
    }

    private static InputSnapshot pressed(int key) {
        return new InputSnapshot(Set.of(key), Set.of(key));
    }

    private static InputSnapshot modifierPressed(int key) {
        return new InputSnapshot(
                Set.of(key, GameConfig.Input.KEY_DEBUG_DETAIL_MODIFIER_LEFT), Set.of(key));
    }

    private static BlockHitResult fullHit() {
        return hit(FullRaycastTarget.INSTANCE);
    }

    private static BlockHitResult detailHit(int x, int y, int z) {
        return hit(new DetailRaycastTarget(
                VoxelScale.DETAIL_4, new LocalSubVoxelPosition(x, y, z)));
    }

    private static BlockHitResult hit(com.overlord.physics.RaycastCellTarget target) {
        return new BlockHitResult(
                4, 7, 6, 4, 8, 6, STONE,
                0, 1, 0,
                4.5f, 8.0f, 6.5f, 2.0f,
                4.5, 8.0, 6.5, 1L, target);
    }

    private static Fixture fixture(byte fullId) {
        ChunkRepository repository = new ChunkRepository(32, new ChunkDirtyTracker());
        repository.generate(new ChunkKey(0, 0), chunk -> chunk.setBlock(4, 7, 6, fullId));
        BlockRegistry blocks = registry();
        DetailDebugTools tools = new DetailDebugTools(
                new World(repository), blocks,
                new GaiaDetailMutationService(MainThreadGuard.captureCurrentThread(), blocks, repository),
                com.overlord.physics.BlockCollisionShapeResolver.fullCubesForNonAir(),
                new DetailCollisionBoxMerger());
        return new Fixture(repository, tools);
    }

    private static GaiaInteractionContext context() {
        return new GaiaInteractionContext(
                new EntityRef(42), BodySlot.RIGHT_HAND, InteractionAction.USE, 91L, 123L);
    }

    private static BlockRegistry registry() {
        return BlockRegistry.create(
                List.of(definition(0, AIR), definition(1, STONE), definition(2, DIRT)),
                Map.of(0, renderInfo(false), 1, renderInfo(true), 2, renderInfo(true)));
    }

    private static BlockDefinition definition(int id, ResourceLocation name) {
        return new BlockDefinition(
                id, name, MATERIAL.id(), textures(), 1.0f, 1.0f, 1.0f,
                false, false, 1.0f,
                id == 0 ? null : new ItemFormDefinition(name, 64, false, false));
    }

    private static EnumMap<BlockFace, ResourceLocation> textures() {
        EnumMap<BlockFace, ResourceLocation> textures = new EnumMap<>(BlockFace.class);
        for (BlockFace face : BlockFace.values()) {
            textures.put(face, MISSING);
        }
        return textures;
    }

    private static BlockRenderInfo renderInfo(boolean renderable) {
        EnumMap<BlockFace, TextureRegion> faces = new EnumMap<>(BlockFace.class);
        for (BlockFace face : BlockFace.values()) {
            faces.put(face, REGION);
        }
        return renderable
                ? new BlockRenderInfo(MATERIAL, faces, true)
                : BlockRenderInfo.nonRenderable(MATERIAL, REGION);
    }

    private static final class Fixture {
        private final ChunkRepository repository;
        private final DetailDebugTools tools;
        private Fixture(ChunkRepository repository, DetailDebugTools tools) {
            this.repository = repository;
            this.tools = tools;
        }

        private ChunkRepository repository() { return repository; }
        private DetailDebugTools tools() { return tools; }
    }
}
