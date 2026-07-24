package com.overlord.interaction.api;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.overlord.assets.ResourceLocation;
import com.overlord.inventory.api.BodySlot;
import com.overlord.inventory.api.ItemStack;
import com.overlord.inventory.api.ItemStackView;
import com.overlord.inventory.testing.TestItemStackView;
import com.overlord.interaction.testing.StubInteractionViewModel;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class InteractionContractTest {
    private static final ResourceLocation STONE =
            ResourceLocation.parse("gaia:stone");

    @Test
    void blockHitRequiresMatchingAdjacentCellAndAxisNormal() {
        BlockHitResult hit =
                new BlockHitResult(
                        4,
                        5,
                        6,
                        5,
                        5,
                        6,
                        STONE,
                        1,
                        0,
                        0,
                        5.0f,
                        5.5f,
                        6.5f,
                        3.0f);

        assertEquals(5, hit.adjacentX());
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new BlockHitResult(
                                4,
                                5,
                                6,
                                4,
                                5,
                                6,
                                STONE,
                                1,
                                0,
                                0,
                                5.0f,
                                5.5f,
                                6.5f,
                                3.0f));
    }

    @Test
    void blockHitRejectsAdjacentCoordinatesThatOnlyMatchAfterIntegerOverflow() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new BlockHitResult(
                                Integer.MAX_VALUE,
                                0,
                                0,
                                Integer.MIN_VALUE,
                                0,
                                0,
                                STONE,
                                1,
                                0,
                                0,
                                0.5f,
                                0.5f,
                                0.5f,
                                1.0f));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new BlockHitResult(
                                Integer.MIN_VALUE,
                                0,
                                0,
                                Integer.MAX_VALUE,
                                0,
                                0,
                                STONE,
                                -1,
                                0,
                                0,
                                0.5f,
                                0.5f,
                                0.5f,
                                1.0f));
    }

    @Test
    void blockHitAcceptsExactlyTheSixAxisFaceNormals() {
        int[][] normals = {
            {1, 0, 0},
            {-1, 0, 0},
            {0, 1, 0},
            {0, -1, 0},
            {0, 0, 1},
            {0, 0, -1}
        };

        for (int[] normal : normals) {
            assertDoesNotThrow(
                    () -> hitWithNormal(
                            normal[0], normal[1], normal[2]));
        }
    }

    @Test
    void blockHitRejectsInvalidAndExtremeFaceNormals() {
        int[][] invalidNormals = {
            {0, 0, 0},
            {1, 1, 0},
            {2, 0, 0},
            {Integer.MIN_VALUE, Integer.MIN_VALUE, 1}
        };

        for (int[] normal : invalidNormals) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> hitWithNormal(
                            normal[0], normal[1], normal[2]));
        }
    }

    @Test
    void blockHitRejectsNonFinitePointAndDistance() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        hitWithValues(
                                STONE,
                                Float.NaN,
                                0.5f,
                                0.5f,
                                1.0f));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        hitWithValues(
                                STONE,
                                0.5f,
                                Float.POSITIVE_INFINITY,
                                0.5f,
                                1.0f));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        hitWithValues(
                                STONE,
                                0.5f,
                                0.5f,
                                Float.NEGATIVE_INFINITY,
                                1.0f));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        hitWithValues(
                                STONE,
                                0.5f,
                                0.5f,
                                0.5f,
                                Float.NaN));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        hitWithValues(
                                STONE,
                                0.5f,
                                0.5f,
                                0.5f,
                                Float.POSITIVE_INFINITY));
    }

    @Test
    void blockHitRejectsNegativeDistanceAndNullBlockIdentity() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        hitWithValues(
                                STONE,
                                0.5f,
                                0.5f,
                                0.5f,
                                -0.01f));
        assertThrows(
                NullPointerException.class,
                () ->
                        hitWithValues(
                                null,
                                0.5f,
                                0.5f,
                                0.5f,
                                1.0f));
    }

    @Test
    void itemUseContextCarriesEmptyHandAndMissWithoutNulls() {
        ItemUseContext context =
                new ItemUseContext(
                        new EntityRef(9),
                        BodySlot.MOUTH,
                        Optional.empty(),
                        Optional.empty(),
                        InteractionAction.USE,
                        12,
                        900);

        assertEquals(12, context.tick());
        assertEquals(Optional.empty(), context.heldStack());
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new ItemUseContext(
                                new EntityRef(9),
                                BodySlot.MOUTH,
                                Optional.empty(),
                                Optional.empty(),
                                InteractionAction.USE,
                                -1,
                                900));
    }

    @Test
    void itemUseContextRejectsNullOptionalContainersAndNegativeTimestamp() {
        assertThrows(
                NullPointerException.class,
                () ->
                        new ItemUseContext(
                                new EntityRef(9),
                                BodySlot.MOUTH,
                                null,
                                Optional.empty(),
                                InteractionAction.USE,
                                12,
                                900));
        assertThrows(
                NullPointerException.class,
                () ->
                        new ItemUseContext(
                                new EntityRef(9),
                                BodySlot.MOUTH,
                                Optional.empty(),
                                null,
                                InteractionAction.USE,
                                12,
                                900));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new ItemUseContext(
                                new EntityRef(9),
                                BodySlot.MOUTH,
                                Optional.empty(),
                                Optional.empty(),
                                InteractionAction.USE,
                                12,
                                -1));
    }

    @Test
    void itemUseContextCarriesCanonicalHeldStack() throws ReflectiveOperationException {
        ItemStack heldStack = new ItemStack(STONE, 2);

        assertEquals(Optional.of(heldStack), itemUseContext(heldStack).heldStack());
        assertEquals(
                ItemStack.class,
                optionalElementType(ItemUseContext.class.getMethod("heldStack")));
    }

    @Test
    void blockFaceUsesStablePresentationOrderAndAxisNormals() {
        assertEquals(
                List.of(
                        BlockFace.EAST,
                        BlockFace.WEST,
                        BlockFace.UP,
                        BlockFace.DOWN,
                        BlockFace.SOUTH,
                        BlockFace.NORTH),
                List.of(BlockFace.values()));
        assertEquals(BlockFace.EAST, BlockFace.fromNormal(1, 0, 0));
        assertEquals(BlockFace.WEST, BlockFace.fromNormal(-1, 0, 0));
        assertEquals(BlockFace.UP, BlockFace.fromNormal(0, 1, 0));
        assertEquals(BlockFace.DOWN, BlockFace.fromNormal(0, -1, 0));
        assertEquals(BlockFace.SOUTH, BlockFace.fromNormal(0, 0, 1));
        assertEquals(BlockFace.NORTH, BlockFace.fromNormal(0, 0, -1));
        assertThrows(
                IllegalArgumentException.class,
                () -> BlockFace.fromNormal(0, 0, 0));
    }

    @Test
    void interactionModeUsesStablePresentationOrder() {
        assertEquals(
                List.of(
                        InteractionMode.NONE,
                        InteractionMode.BREAKING,
                        InteractionMode.PLACING,
                        InteractionMode.USING),
                List.of(InteractionMode.values()));
    }

    @Test
    void blockFaceDerivesFromHitAndRejectsNull() {
        assertEquals(
                BlockFace.EAST,
                BlockFace.fromHit(hitWithNormal(1, 0, 0)));
        assertThrows(
                NullPointerException.class,
                () -> BlockFace.fromHit(null));
    }

    @Test
    void interactionFailureReasonRequiresStableCode() {
        ResourceLocation code = ResourceLocation.parse("gaia:interaction_denied");

        assertEquals(code, new InteractionFailureReason(code).code());
        assertThrows(
                NullPointerException.class,
                () -> new InteractionFailureReason(null));
    }

    @Test
    void interactionViewModelExposesExactReadOnlyPresentationTypes()
            throws ReflectiveOperationException {
        assertEquals(
                Optional.class,
                InteractionViewModel.class.getMethod("target").getReturnType());
        assertEquals(
                BlockHitResult.class,
                optionalElementType(InteractionViewModel.class.getMethod("target")));
        assertEquals(
                Optional.class,
                InteractionViewModel.class.getMethod("hitFace").getReturnType());
        assertEquals(
                BlockFace.class,
                optionalElementType(InteractionViewModel.class.getMethod("hitFace")));
        assertEquals(
                double.class,
                InteractionViewModel.class.getMethod("progress").getReturnType());
        assertEquals(
                InteractionMode.class,
                InteractionViewModel.class.getMethod("mode").getReturnType());
        assertEquals(
                Optional.class,
                InteractionViewModel.class.getMethod("activeItem").getReturnType());
        assertEquals(
                ItemStackView.class,
                optionalElementType(InteractionViewModel.class.getMethod("activeItem")));
        assertEquals(
                Optional.class,
                InteractionViewModel.class.getMethod("failureReason").getReturnType());
        assertEquals(
                InteractionFailureReason.class,
                optionalElementType(
                        InteractionViewModel.class.getMethod("failureReason")));
    }

    @Test
    void stubInteractionViewModelAcceptsEmptyIdleState() {
        StubInteractionViewModel viewModel =
                new StubInteractionViewModel(
                        Optional.empty(),
                        Optional.empty(),
                        0.0,
                        InteractionMode.NONE,
                        Optional.empty(),
                        Optional.empty());

        assertEquals(InteractionMode.NONE, viewModel.mode());
        assertEquals(Optional.empty(), viewModel.target());
    }

    @Test
    void stubInteractionViewModelAcceptsConsistentPresentationState() {
        BlockHitResult hit = hitWithNormal(1, 0, 0);
        TestItemStackView item = new TestItemStackView(STONE, 2);
        InteractionFailureReason failure =
                new InteractionFailureReason(
                        ResourceLocation.parse("gaia:interaction_blocked"));

        StubInteractionViewModel viewModel =
                new StubInteractionViewModel(
                        Optional.of(hit),
                        Optional.of(BlockFace.EAST),
                        0.5,
                        InteractionMode.BREAKING,
                        Optional.of(item),
                        Optional.of(failure));

        assertEquals(Optional.of(hit), viewModel.target());
        assertEquals(Optional.of(BlockFace.EAST), viewModel.hitFace());
        assertEquals(Optional.of(item), viewModel.activeItem());
        assertEquals(Optional.of(failure), viewModel.failureReason());
    }

    @Test
    void stubInteractionViewModelRejectsNonFiniteAndOutOfRangeProgress() {
        assertInvalidProgress(Double.NaN);
        assertInvalidProgress(Double.POSITIVE_INFINITY);
        assertInvalidProgress(-0.01);
        assertInvalidProgress(1.01);
    }

    @Test
    void stubInteractionViewModelRejectsNullOptionalsAndMode() {
        assertThrows(
                NullPointerException.class,
                () ->
                        new StubInteractionViewModel(
                                null,
                                Optional.empty(),
                                0.0,
                                InteractionMode.NONE,
                                Optional.empty(),
                                Optional.empty()));
        assertThrows(
                NullPointerException.class,
                () ->
                        new StubInteractionViewModel(
                                Optional.empty(),
                                Optional.empty(),
                                0.0,
                                null,
                                Optional.empty(),
                                Optional.empty()));
    }

    @Test
    void stubInteractionViewModelRejectsMismatchedTargetAndFacePresence() {
        BlockHitResult hit = hitWithNormal(1, 0, 0);

        assertThrows(
                IllegalArgumentException.class,
                () -> stub(Optional.of(hit), Optional.empty(), Optional.empty()));
        assertThrows(
                IllegalArgumentException.class,
                () -> stub(Optional.empty(), Optional.of(BlockFace.EAST), Optional.empty()));
    }

    @Test
    void stubInteractionViewModelRejectsFaceThatDoesNotMatchTargetNormal() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        stub(
                                Optional.of(hitWithNormal(1, 0, 0)),
                                Optional.of(BlockFace.WEST),
                                Optional.empty()));
    }

    @Test
    void stubInteractionViewModelRejectsInvalidActiveItemView() {
        ItemStackView invalidItem =
                new ItemStackView() {
                    @Override
                    public ResourceLocation itemId() {
                        return null;
                    }

                    @Override
                    public int count() {
                        return 0;
                    }
                };

        assertThrows(
                IllegalArgumentException.class,
                () -> stub(Optional.empty(), Optional.empty(), Optional.of(invalidItem)));
    }

    private static BlockHitResult hitWithNormal(
            int normalX, int normalY, int normalZ) {
        return new BlockHitResult(
                0,
                0,
                0,
                normalX,
                normalY,
                normalZ,
                STONE,
                normalX,
                normalY,
                normalZ,
                0.5f,
                0.5f,
                0.5f,
                1.0f);
    }

    private static BlockHitResult hitWithValues(
            ResourceLocation block,
            float pointX,
            float pointY,
            float pointZ,
            float distance) {
        return new BlockHitResult(
                0,
                0,
                0,
                1,
                0,
                0,
                block,
                1,
                0,
                0,
                pointX,
                pointY,
                pointZ,
                distance);
    }

    private static ItemUseContext itemUseContext(
            ItemStack heldStack) {
        return new ItemUseContext(
                new EntityRef(9),
                BodySlot.MOUTH,
                Optional.of(heldStack),
                Optional.empty(),
                InteractionAction.USE,
                12,
                900);
    }

    private static void assertInvalidProgress(double progress) {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new StubInteractionViewModel(
                                Optional.empty(),
                                Optional.empty(),
                                progress,
                                InteractionMode.NONE,
                                Optional.empty(),
                                Optional.empty()));
    }

    private static StubInteractionViewModel stub(
            Optional<BlockHitResult> target,
            Optional<BlockFace> face,
            Optional<ItemStackView> activeItem) {
        return new StubInteractionViewModel(
                target,
                face,
                0.0,
                InteractionMode.NONE,
                activeItem,
                Optional.empty());
    }

    private static Type optionalElementType(Method method) {
        ParameterizedType optionalType = (ParameterizedType) method.getGenericReturnType();
        return optionalType.getActualTypeArguments()[0];
    }
}
