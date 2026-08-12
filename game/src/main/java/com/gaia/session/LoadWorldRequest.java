package com.gaia.session;

import com.gaia.save.format.SaveGameId;
import java.util.Objects;

/** Typed request to load one existing local world identity. */
public record LoadWorldRequest(SaveGameId saveGameId) {
    public LoadWorldRequest {
        Objects.requireNonNull(saveGameId, "saveGameId");
    }
}
