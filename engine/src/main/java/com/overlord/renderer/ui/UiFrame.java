package com.overlord.renderer.ui;

import java.util.List;
import java.util.Objects;

public record UiFrame(List<UiDrawCommand> commands) {
    public UiFrame {
        commands = List.copyOf(Objects.requireNonNull(commands, "commands"));
    }

    public static UiFrame empty() {
        return new UiFrame(List.of());
    }
}
