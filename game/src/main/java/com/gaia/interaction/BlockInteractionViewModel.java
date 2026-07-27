package com.gaia.interaction;

import com.overlord.interaction.api.InteractionViewModel;

/** Phase 9A read-only extension of the protected Phase 7 presentation contract. */
public interface BlockInteractionViewModel extends InteractionViewModel {
    int crackStage();

    GameMode gameMode();
}
