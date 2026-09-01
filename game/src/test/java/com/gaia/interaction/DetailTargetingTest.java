package com.gaia.interaction;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.overlord.assets.ResourceLocation;
import com.overlord.interaction.api.BlockFace;
import com.overlord.interaction.api.BlockHitResult;
import com.overlord.physics.DetailRaycastTarget;
import com.overlord.physics.FullRaycastTarget;
import com.overlord.physics.RaycastCellTarget;
import com.overlord.voxel.LocalSubVoxelPosition;
import com.overlord.voxel.VoxelScale;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class DetailTargetingTest {
    private static final ResourceLocation STONE =
            ResourceLocation.parse("gaia:stone");

    @ParameterizedTest(name = "{0}")
    @MethodSource("fullFaces")
    void refinesAllFullFacesIntoExactQuarterCoordinates(
            BlockFace face,
            double worldX,
            double worldY,
            double worldZ,
            LocalSubVoxelPosition expected) {
        DetailPrecisionTarget target = DetailTargeting.removalTarget(
                hit(
                        10, 20, -4,
                        face,
                        worldX, worldY, worldZ,
                        FullRaycastTarget.INSTANCE,
                        19L));

        assertEquals(expected, target.localPosition());
        assertEquals(face, target.face());
        assertEquals(19L, target.observedChunkRevision());
        assertEquals(FullRaycastTarget.INSTANCE, target.representation());
    }

    @ParameterizedTest
    @MethodSource("quarterPlaneTies")
    void exactQuarterPlanesChooseHigherIntervalAndOuterEdgeClamps(
            double tangentialFraction, int expectedQuarter) {
        DetailPrecisionTarget target = DetailTargeting.removalTarget(
                hit(
                        10, 20, -4,
                        BlockFace.EAST,
                        11.0,
                        20.0 + tangentialFraction,
                        -3.5,
                        FullRaycastTarget.INSTANCE,
                        2L));

        assertEquals(expectedQuarter, target.localPosition().y());
    }

    @Test
    void usesCanonicalWorldPointForNegativeAndLargeCoordinates() {
        DetailPrecisionTarget negative = DetailTargeting.removalTarget(
                hit(
                        -7, 3, -9,
                        BlockFace.UP,
                        -6.75, 4.0, -8.25,
                        FullRaycastTarget.INSTANCE,
                        5L));
        DetailPrecisionTarget large = DetailTargeting.removalTarget(
                hit(
                        1_000_000_000, 4, -1_000_000_000,
                        BlockFace.NORTH,
                        1_000_000_000.75,
                        4.25,
                        -1_000_000_000.0,
                        FullRaycastTarget.INSTANCE,
                        6L));

        assertEquals(
                new LocalSubVoxelPosition(1, 3, 3),
                negative.localPosition());
        assertEquals(
                new LocalSubVoxelPosition(3, 1, 0),
                large.localPosition());
    }

    @Test
    void ignoresResidentPointWhenCanonicalWorldPointIsUnchanged() {
        BlockHitResult before = hit(
                3201, 7, -1599,
                BlockFace.SOUTH,
                3201.5, 7.75, -1598.0,
                FullRaycastTarget.INSTANCE,
                8L,
                15.5f, 7.75f, 1.0f);
        BlockHitResult rebased = hit(
                3201, 7, -1599,
                BlockFace.SOUTH,
                3201.5, 7.75, -1598.0,
                FullRaycastTarget.INSTANCE,
                8L,
                -0.5f, 7.75f, 1.0f);

        assertEquals(
                DetailTargeting.removalTarget(before),
                DetailTargeting.removalTarget(rebased));
    }

    @Test
    void consumesExistingDetailProvenanceWithoutRefinement() {
        LocalSubVoxelPosition local = new LocalSubVoxelPosition(2, 0, 3);
        DetailRaycastTarget provenance =
                new DetailRaycastTarget(VoxelScale.DETAIL_4, local);

        DetailPrecisionTarget target = DetailTargeting.removalTarget(
                hit(
                        -1, 2, 16,
                        BlockFace.WEST,
                        -1.0, 2.125, 16.875,
                        provenance,
                        41L));

        assertEquals(local, target.localPosition());
        assertEquals(provenance, target.representation());
        assertEquals(STONE, target.material());
    }

    private static Stream<Arguments> fullFaces() {
        return Stream.of(
                Arguments.of(
                        BlockFace.EAST,
                        11.0, 20.25, -3.5,
                        new LocalSubVoxelPosition(3, 1, 2)),
                Arguments.of(
                        BlockFace.WEST,
                        10.0, 20.25, -3.5,
                        new LocalSubVoxelPosition(0, 1, 2)),
                Arguments.of(
                        BlockFace.UP,
                        10.25, 21.0, -3.5,
                        new LocalSubVoxelPosition(1, 3, 2)),
                Arguments.of(
                        BlockFace.DOWN,
                        10.25, 20.0, -3.5,
                        new LocalSubVoxelPosition(1, 0, 2)),
                Arguments.of(
                        BlockFace.SOUTH,
                        10.25, 20.5, -3.0,
                        new LocalSubVoxelPosition(1, 2, 3)),
                Arguments.of(
                        BlockFace.NORTH,
                        10.25, 20.5, -4.0,
                        new LocalSubVoxelPosition(1, 2, 0)));
    }

    private static Stream<Arguments> quarterPlaneTies() {
        return Stream.of(
                Arguments.of(0.0, 0),
                Arguments.of(0.25, 1),
                Arguments.of(0.5, 2),
                Arguments.of(0.75, 3),
                Arguments.of(1.0, 3));
    }

    private static BlockHitResult hit(
            int x,
            int y,
            int z,
            BlockFace face,
            double worldX,
            double worldY,
            double worldZ,
            RaycastCellTarget representation,
            long revision) {
        return hit(
                x, y, z, face,
                worldX, worldY, worldZ,
                representation, revision,
                (float) worldX,
                (float) worldY,
                (float) worldZ);
    }

    private static BlockHitResult hit(
            int x,
            int y,
            int z,
            BlockFace face,
            double worldX,
            double worldY,
            double worldZ,
            RaycastCellTarget representation,
            long revision,
            float residentX,
            float residentY,
            float residentZ) {
        return new BlockHitResult(
                x, y, z,
                x + face.normalX(),
                y + face.normalY(),
                z + face.normalZ(),
                STONE,
                face.normalX(),
                face.normalY(),
                face.normalZ(),
                residentX,
                residentY,
                residentZ,
                1.0f,
                worldX,
                worldY,
                worldZ,
                revision,
                representation);
    }
}
