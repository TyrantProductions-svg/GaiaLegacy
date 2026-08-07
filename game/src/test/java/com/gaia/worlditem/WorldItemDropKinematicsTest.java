package com.gaia.worlditem;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.overlord.config.GameConfig;
import com.overlord.physics.Aabb;
import com.gaia.inventory.InventoryDropLocation;
import com.overlord.interaction.api.BlockHitResult;
import com.overlord.assets.ResourceLocation;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

class WorldItemDropKinematicsTest {
    @Test
    void qDropUsesApprovedPositionVelocityAndDoesNotMutateInputs() {
        Vector3f eye = new Vector3f(1.0f, 2.0f, 3.0f);
        Vector3f forward = new Vector3f(0.0f, 0.0f, -1.0f);
        Vector3f right = new Vector3f(1.0f, 0.0f, 0.0f);
        Vector3f originalEye = new Vector3f(eye);
        Vector3f originalForward = new Vector3f(forward);
        Vector3f originalRight = new Vector3f(right);

        InventoryDropLocation location = WorldItemDropKinematics.qDrop(
                eye, forward, right, 42L);

        assertEquals(1.0, location.positionX(), 1.0e-6);
        assertEquals(2.0, location.positionY(), 1.0e-6);
        assertEquals(2.6, location.positionZ(), 1.0e-6);
        assertEquals(-4.5, location.velocityZ(), 1.0e-6);
        assertEquals(1.25, location.velocityY(), 1.0e-6);
        assertTrue(Math.abs(location.velocityX()) <= 0.15 + 1.0e-6);
        assertEquals(originalEye, eye);
        assertEquals(originalForward, forward);
        assertEquals(originalRight, right);
    }

    @Test
    void qDropIsDeterministicPerEventAndVariationRemainsBounded() {
        Vector3f eye = new Vector3f();
        Vector3f forward = new Vector3f(0.0f, 0.0f, -1.0f);
        Vector3f right = new Vector3f(1.0f, 0.0f, 0.0f);

        InventoryDropLocation first = WorldItemDropKinematics.qDrop(
                eye, forward, right, 1234L);
        InventoryDropLocation repeated = WorldItemDropKinematics.qDrop(
                eye, forward, right, 1234L);
        InventoryDropLocation other = WorldItemDropKinematics.qDrop(
                eye, forward, right, 1235L);

        assertEquals(first, repeated);
        assertNotEquals(first.velocityX(), other.velocityX());
        assertTrue(Math.abs(first.velocityX()) <= 0.15 + 1.0e-6);
        assertTrue(Math.abs(other.velocityX()) <= 0.15 + 1.0e-6);
    }

    @Test
    void qDropRemainsFiniteForNearVerticalAndHorizontalCameraDirections() {
        Vector3f eye = new Vector3f(3, 8, -2);
        Vector3f right = new Vector3f(1, 0, 0);
        for (Vector3f forward : java.util.List.of(
                new Vector3f(1.0e-7f, 1, -1.0e-7f),
                new Vector3f(-1.0e-7f, -1, 1.0e-7f),
                new Vector3f(0, 0, -1))) {
            InventoryDropLocation location = WorldItemDropKinematics.qDrop(
                    eye, forward, right, 512L);

            assertFinite(location);
            assertEquals(
                    WorldItemDropKinematics.Q_FORWARD_OFFSET,
                    Math.sqrt(
                            square(location.positionX() - eye.x)
                                    + square(location.positionY() - eye.y)
                                    + square(location.positionZ() - eye.z)),
                    1.0e-5);
        }
    }

    @Test
    void qDropRejectsEveryNonFiniteInputVector() {
        Vector3f finite = new Vector3f(0, 0, -1);
        assertThrows(
                IllegalArgumentException.class,
                () -> WorldItemDropKinematics.qDrop(
                        new Vector3f(Float.NaN, 0, 0), finite, finite, 1));
        assertThrows(
                IllegalArgumentException.class,
                () -> WorldItemDropKinematics.qDrop(
                        finite, new Vector3f(0, Float.POSITIVE_INFINITY, 0), finite, 1));
        assertThrows(
                IllegalArgumentException.class,
                () -> WorldItemDropKinematics.qDrop(
                        finite, finite, new Vector3f(0, 0, Float.NEGATIVE_INFINITY), 1));
    }

    @Test
    void qDropPreservesApprovedSpawnPointEvenWhenItOverlapsPlayerCollider() {
        Vector3f feet = new Vector3f(0, 0, 0);
        Vector3f eye = new Vector3f(0, GameConfig.Player.EYE_HEIGHT, 0);
        InventoryDropLocation location = WorldItemDropKinematics.qDrop(
                eye, new Vector3f(0, 0, -1), new Vector3f(1, 0, 0), 7L);
        Aabb player = new Aabb(
                -GameConfig.Player.WIDTH / 2,
                0,
                -GameConfig.Player.WIDTH / 2,
                GameConfig.Player.WIDTH / 2,
                GameConfig.Player.HEIGHT,
                GameConfig.Player.WIDTH / 2).translated(feet);
        Aabb item = new Aabb(-0.25f, -0.25f, -0.25f, 0.25f, 0.25f, 0.25f)
                .translated(new Vector3f(
                        (float) location.positionX(),
                        (float) location.positionY(),
                        (float) location.positionZ()));

        assertTrue(player.intersects(item));
        assertEquals(-0.40, location.positionZ(), 1.0e-6);
        assertFinite(location);
    }

    @Test
    void qDropExplicitDeterministicSeedsReachBothLateralExtremesWithoutExceedingThem() {
        Vector3f eye = new Vector3f();
        Vector3f forward = new Vector3f(0, 0, -1);
        Vector3f right = new Vector3f(1, 0, 0);
        InventoryDropLocation minimum = WorldItemDropKinematics.qDrop(
                eye, forward, right, 75_389L);
        InventoryDropLocation maximum = WorldItemDropKinematics.qDrop(
                eye, forward, right, 24_233L);

        assertTrue(minimum.velocityX() >= -0.15 - 1.0e-6);
        assertTrue(minimum.velocityX() < -0.14999);
        assertTrue(maximum.velocityX() <= 0.15 + 1.0e-6);
        assertTrue(maximum.velocityX() > 0.14999);
    }

    @Test
    void blockDropUsesBlockCenterAndMovesHorizontallyAwayFromPlayer() {
        BlockHitResult hit = new BlockHitResult(
                1, 2, 3, 2, 2, 3,
                ResourceLocation.parse("gaia:stone"), 1, 0, 0,
                2, 2.5f, 3.5f, 2);
        Vector3f playerPosition = new Vector3f(4.5f, 2.0f, 3.5f);

        InventoryDropLocation location = WorldItemDropKinematics.blockDrop(
                hit, playerPosition, 77L);

        assertEquals(1.5, location.positionX(), 1.0e-6);
        assertEquals(2.5, location.positionY(), 1.0e-6);
        assertEquals(3.5, location.positionZ(), 1.0e-6);
        assertEquals(1.40, location.velocityY(), 1.0e-6);
        double horizontalSpeed = Math.hypot(
                location.velocityX(), location.velocityZ());
        assertTrue(horizontalSpeed >= 1.25 - 1.0e-6);
        assertTrue(horizontalSpeed <= Math.hypot(1.75, 0.20) + 1.0e-6);
        double awayX = location.positionX() - playerPosition.x;
        double awayZ = location.positionZ() - playerPosition.z;
        assertTrue(location.velocityX() * awayX + location.velocityZ() * awayZ > 0.0);
    }

    @Test
    void blockDropKeepsApprovedOutwardSpeedSeparateFromBoundedLateralVariation() {
        BlockHitResult hit = new BlockHitResult(
                1, 2, 3, 2, 2, 3,
                ResourceLocation.parse("gaia:stone"), 1, 0, 0,
                2, 2.5f, 3.5f, 2);
        Vector3f playerPosition = new Vector3f(4.5f, 2.0f, 3.5f);

        for (long eventIdentity = 0; eventIdentity < 4_096; eventIdentity++) {
            InventoryDropLocation location = WorldItemDropKinematics.blockDrop(
                    hit, playerPosition, eventIdentity);
            double outwardSpeed = -location.velocityX();
            double lateralSpeed = -location.velocityZ();
            double horizontalResultant = Math.hypot(
                    location.velocityX(), location.velocityZ());

            assertTrue(outwardSpeed >= 1.25 - 1.0e-6);
            assertTrue(outwardSpeed <= 1.75 + 1.0e-6);
            assertTrue(Math.abs(lateralSpeed) <= 0.20 + 1.0e-6);
            assertTrue(horizontalResultant <= Math.hypot(1.75, 0.20) + 1.0e-6);
            assertEquals(1.40, location.velocityY(), 1.0e-6);
        }
    }

    @Test
    void blockDropIsDeterministicForDegeneratePlayerDirection() {
        BlockHitResult hit = new BlockHitResult(
                1, 2, 3, 2, 2, 3,
                ResourceLocation.parse("gaia:stone"), 1, 0, 0,
                2, 2.5f, 3.5f, 2);
        Vector3f blockCenter = new Vector3f(1.5f, 2.5f, 3.5f);

        InventoryDropLocation first = WorldItemDropKinematics.blockDrop(
                hit, blockCenter, 99L);
        InventoryDropLocation repeated = WorldItemDropKinematics.blockDrop(
                hit, blockCenter, 99L);

        assertEquals(first, repeated);
        assertTrue(Math.hypot(first.velocityX(), first.velocityZ()) >= 1.25);
    }

    @Test
    void blockDropExplicitSeedsReachApprovedOutwardMinimumAndMaximum() {
        BlockHitResult hit = hit(1, 2, 3);
        Vector3f player = new Vector3f(4.5f, 2.5f, 3.5f);
        InventoryDropLocation minimum = WorldItemDropKinematics.blockDrop(
                hit, player, 32_876L);
        InventoryDropLocation maximum = WorldItemDropKinematics.blockDrop(
                hit, player, 96_026L);

        assertTrue(-minimum.velocityX() >= 1.25 - 1.0e-6);
        assertTrue(-minimum.velocityX() < 1.25001);
        assertTrue(-maximum.velocityX() <= 1.75 + 1.0e-6);
        assertTrue(-maximum.velocityX() > 1.74999);
        for (InventoryDropLocation location : java.util.List.of(minimum, maximum)) {
            assertTrue(Math.abs(location.velocityZ()) <= 0.20 + 1.0e-6);
            assertTrue(Math.hypot(location.velocityX(), location.velocityZ())
                    <= Math.hypot(1.75, 0.20) + 1.0e-6);
            assertEquals(1.40, location.velocityY(), 1.0e-6);
        }
    }

    @Test
    void blockDropHandlesVerticalPlayerAlignmentSameCenterAndNegativeCoordinates() {
        for (BlockDropCase edge : java.util.List.of(
                new BlockDropCase(hit(1, 2, 3), new Vector3f(1.5f, 9, 3.5f)),
                new BlockDropCase(hit(1, 2, 3), new Vector3f(1.5f, -9, 3.5f)),
                new BlockDropCase(hit(1, 2, 3), new Vector3f(1.5f, 2.5f, 3.5f)),
                new BlockDropCase(hit(-7, -2, -9), new Vector3f(-4, -2, -8)))) {
            InventoryDropLocation first = WorldItemDropKinematics.blockDrop(
                    edge.hit(), edge.player(), 991L);
            InventoryDropLocation repeated = WorldItemDropKinematics.blockDrop(
                    edge.hit(), edge.player(), 991L);

            assertEquals(first, repeated);
            assertFinite(first);
            double horizontal = Math.hypot(first.velocityX(), first.velocityZ());
            assertTrue(horizontal >= 1.25 - 1.0e-6);
            assertTrue(horizontal <= Math.hypot(1.75, 0.20) + 1.0e-6);
            assertEquals(edge.hit().blockX() + 0.5, first.positionX(), 1.0e-6);
            assertEquals(edge.hit().blockY() + 0.5, first.positionY(), 1.0e-6);
            assertEquals(edge.hit().blockZ() + 0.5, first.positionZ(), 1.0e-6);
        }
    }

    private static BlockHitResult hit(int x, int y, int z) {
        return new BlockHitResult(
                x, y, z, x + 1, y, z,
                ResourceLocation.parse("gaia:stone"), 1, 0, 0,
                x + 1, y + 0.5f, z + 0.5f, 2);
    }

    private static double square(double value) {
        return value * value;
    }

    private static void assertFinite(InventoryDropLocation location) {
        assertTrue(Double.isFinite(location.positionX()));
        assertTrue(Double.isFinite(location.positionY()));
        assertTrue(Double.isFinite(location.positionZ()));
        assertTrue(Double.isFinite(location.velocityX()));
        assertTrue(Double.isFinite(location.velocityY()));
        assertTrue(Double.isFinite(location.velocityZ()));
    }

    private record BlockDropCase(BlockHitResult hit, Vector3f player) {}
}
