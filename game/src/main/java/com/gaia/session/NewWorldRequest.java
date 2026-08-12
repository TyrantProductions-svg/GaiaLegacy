package com.gaia.session;

import com.gaia.save.format.SaveGameId;
import java.util.Objects;

/** Validated immutable request to create one new finite-world session. */
public record NewWorldRequest(SaveGameId saveGameId, String displayName, long seed) {
    public NewWorldRequest {
        Objects.requireNonNull(saveGameId, "saveGameId");
        Objects.requireNonNull(displayName, "displayName");
        int codePoints = displayName.codePointCount(0, displayName.length());
        if (!displayName.equals(displayName.strip())
                || codePoints < 1
                || codePoints > 40
                || displayName.codePoints().anyMatch(codePoint ->
                        Character.isISOControl(codePoint)
                                || codePoint == '/'
                                || codePoint == '\\')) {
            throw new IllegalArgumentException("displayName must be a validated world name");
        }
    }
}
