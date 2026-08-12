package com.gaia.shell.ui;

import com.gaia.save.format.SaveGameId;
import java.util.Objects;

/** Stable identity for one action on one immutable world-slot row. */
public record WorldSlotControlId(
        SaveGameId saveGameId,
        WorldSlotAction action) implements UiControlId {
    public WorldSlotControlId {
        Objects.requireNonNull(saveGameId, "saveGameId");
        Objects.requireNonNull(action, "action");
    }

    public enum WorldSlotAction {
        SELECT,
        LOAD,
        DELETE,
        RECOVER
    }
}
