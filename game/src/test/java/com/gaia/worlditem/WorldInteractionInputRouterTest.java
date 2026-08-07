package com.gaia.worlditem;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_F4;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT_SHIFT;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT_SHIFT;
import static org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_LEFT;
import static org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_RIGHT;

import com.gaia.interaction.GameMode;
import com.overlord.core.input.InputSnapshot;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

final class WorldInteractionInputRouterTest {
    private final WorldInteractionInputRouter router = new WorldInteractionInputRouter();

    @Test
    void shiftRightClaimsPickupAndRemovesOnlyPlacementEdge() {
        InputSnapshot input = input(Set.of(GLFW_KEY_LEFT_SHIFT), Set.of(),
                Set.of(GLFW_MOUSE_BUTTON_LEFT, GLFW_MOUSE_BUTTON_RIGHT),
                Set.of(GLFW_MOUSE_BUTTON_LEFT, GLFW_MOUSE_BUTTON_RIGHT));

        RoutedWorldInteractionInput routed = router.route(
                input, GameMode.SURVIVAL, false, true);

        assertTrue(routed.pickupPressed());
        assertFalse(routed.blockInput().isMouseButtonPressed(GLFW_MOUSE_BUTTON_RIGHT));
        assertTrue(routed.blockInput().isMouseButtonDown(GLFW_MOUSE_BUTTON_RIGHT));
        assertTrue(routed.blockInput().isMouseButtonPressed(GLFW_MOUSE_BUTTON_LEFT));
        assertEquals(input.downKeys(), routed.blockInput().downKeys());
        assertEquals(input.pressedKeys(), routed.blockInput().pressedKeys());
    }

    @Test
    void eitherShiftKeyCanClaimPickup() {
        assertTrue(router.route(chord(GLFW_KEY_LEFT_SHIFT), GameMode.SURVIVAL, false, true)
                .pickupPressed());
        assertTrue(router.route(chord(GLFW_KEY_RIGHT_SHIFT), GameMode.SURVIVAL, false, true)
                .pickupPressed());
    }

    @Test
    void ordinaryRightPressRemainsUnchangedForPlacement() {
        InputSnapshot input = input(Set.of(), Set.of(),
                Set.of(GLFW_MOUSE_BUTTON_RIGHT), Set.of(GLFW_MOUSE_BUTTON_RIGHT));

        RoutedWorldInteractionInput routed = router.route(input, GameMode.SURVIVAL, false, true);

        assertFalse(routed.pickupPressed());
        assertSame(input, routed.blockInput());
    }

    @Test
    void heldChordAndHeldOnlyCatchupDoNotRepeatPickup() {
        InputSnapshot edge = chord(GLFW_KEY_LEFT_SHIFT);
        InputSnapshot held = edge.heldOnly();

        assertFalse(router.route(held, GameMode.SURVIVAL, false, true).pickupPressed());
        assertTrue(router.route(held, GameMode.SURVIVAL, false, true)
                .blockInput().isMouseButtonDown(GLFW_MOUSE_BUTTON_RIGHT));
    }

    @Test
    void f4CancelsPickupAndIsPreservedForModeOwner() {
        InputSnapshot input = input(Set.of(GLFW_KEY_LEFT_SHIFT, GLFW_KEY_F4), Set.of(GLFW_KEY_F4),
                Set.of(GLFW_MOUSE_BUTTON_RIGHT), Set.of(GLFW_MOUSE_BUTTON_RIGHT));

        RoutedWorldInteractionInput routed = router.route(input, GameMode.SURVIVAL, false, true);

        assertFalse(routed.pickupPressed());
        assertSame(input, routed.blockInput());
        assertTrue(routed.blockInput().isKeyPressed(GLFW_KEY_F4));
        assertTrue(routed.blockInput().isMouseButtonPressed(GLFW_MOUSE_BUTTON_RIGHT));
    }

    @Test
    void creativeNoclipAndDisabledInteractionCannotClaimPickup() {
        InputSnapshot input = chord(GLFW_KEY_LEFT_SHIFT);
        assertFalse(router.route(input, GameMode.CREATIVE, false, true).pickupPressed());
        assertFalse(router.route(input, GameMode.SURVIVAL, true, true).pickupPressed());
        assertFalse(router.route(input, GameMode.SURVIVAL, false, false).pickupPressed());
        assertSame(input, router.route(input, GameMode.CREATIVE, false, true).blockInput());
        assertSame(input, router.route(input, GameMode.SURVIVAL, true, true).blockInput());
        assertSame(input, router.route(input, GameMode.SURVIVAL, false, false).blockInput());
    }

    private static InputSnapshot chord(int shiftKey) {
        return input(Set.of(shiftKey), Set.of(),
                Set.of(GLFW_MOUSE_BUTTON_RIGHT), Set.of(GLFW_MOUSE_BUTTON_RIGHT));
    }

    private static InputSnapshot input(
            Set<Integer> downKeys,
            Set<Integer> pressedKeys,
            Set<Integer> downMouse,
            Set<Integer> pressedMouse) {
        return new InputSnapshot(downKeys, pressedKeys, downMouse, pressedMouse, List.of());
    }
}
