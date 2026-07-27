package com.gaia.interaction;

import com.overlord.config.GameConfig;
import com.overlord.core.input.InputSnapshot;
import java.util.Objects;

public final class GameModeInputController {
    private final GameModeManager modes;

    public GameModeInputController(GameModeManager modes) {
        this.modes = Objects.requireNonNull(modes, "modes");
    }

    /** Returns true when the caller must stop interaction processing for this step. */
    public boolean handle(InputSnapshot input, long tick, Runnable cancelInteraction) {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(cancelInteraction, "cancelInteraction");
        if (!input.isKeyPressed(GameConfig.Input.KEY_TOGGLE_GAME_MODE)) {
            return false;
        }
        cancelInteraction.run();
        modes.toggle(tick);
        return true;
    }
}
