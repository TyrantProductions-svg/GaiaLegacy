package com.overlord.renderer.ui;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

public record UiBatchRun(
        UiTextureId texture,
        Optional<UiRect> clip,
        List<UiDrawCommand> commands) {
    public UiBatchRun {
        Objects.requireNonNull(texture, "texture");
        Objects.requireNonNull(clip, "clip");
        commands = List.copyOf(Objects.requireNonNull(commands, "commands"));
        if (commands.isEmpty()) {
            throw new IllegalArgumentException("UI batch run must contain at least one command");
        }
        for (UiDrawCommand command : commands) {
            if (command.texture() != texture || !command.clip().equals(clip)) {
                throw new IllegalArgumentException(
                        "every UI batch command must match the run texture and clip");
            }
        }
    }
}
