package com.gaia.worlditem;

import com.gaia.interaction.GameMode;
import com.overlord.config.GameConfig;
import com.overlord.core.input.InputSnapshot;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/** Stateless Shift+right priority router for pickup versus block placement. */
public final class WorldInteractionInputRouter {
    public RoutedWorldInteractionInput route(
            InputSnapshot input,
            GameMode mode,
            boolean noclip,
            boolean interactionEnabled) {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(mode, "mode");
        boolean shift = input.isKeyDown(GameConfig.Input.KEY_PICKUP_MODIFIER_LEFT)
                || input.isKeyDown(GameConfig.Input.KEY_PICKUP_MODIFIER_RIGHT);
        boolean pickupPressed = interactionEnabled
                && mode == GameMode.SURVIVAL
                && !noclip
                && !input.isKeyPressed(GameConfig.Input.KEY_TOGGLE_GAME_MODE)
                && shift
                && input.isMouseButtonPressed(GameConfig.Input.MOUSE_SECONDARY);
        if (!pickupPressed) {
            return new RoutedWorldInteractionInput(input, false);
        }

        Set<Integer> pressedMouseButtons = new HashSet<>(input.pressedMouseButtons());
        pressedMouseButtons.remove(GameConfig.Input.MOUSE_SECONDARY);
        InputSnapshot blockInput = new InputSnapshot(
                input.downKeys(),
                input.pressedKeys(),
                input.downMouseButtons(),
                pressedMouseButtons,
                input.scrollDeltas());
        return new RoutedWorldInteractionInput(blockInput, true);
    }
}
