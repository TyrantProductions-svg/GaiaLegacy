package com.overlord.renderer.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class UiDrawList {
    private final List<UiDrawCommand> commands = new ArrayList<>();
    private boolean sealed;

    public void append(UiDrawCommand command) {
        requireOpen();
        commands.add(Objects.requireNonNull(command, "command"));
    }

    public UiFrame seal() {
        requireOpen();
        sealed = true;
        return new UiFrame(commands);
    }

    private void requireOpen() {
        if (sealed) {
            throw new IllegalStateException("UI draw list is already sealed");
        }
    }
}
