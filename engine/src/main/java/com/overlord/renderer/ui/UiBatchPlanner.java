package com.overlord.renderer.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class UiBatchPlanner {
    public List<UiBatchRun> plan(List<UiDrawCommand> commands) {
        Objects.requireNonNull(commands, "commands");
        if (commands.isEmpty()) {
            return List.of();
        }

        List<UiBatchRun> runs = new ArrayList<>();
        List<UiDrawCommand> currentCommands = new ArrayList<>();
        UiDrawCommand first = Objects.requireNonNull(commands.get(0), "command");
        currentCommands.add(first);

        for (int index = 1; index < commands.size(); index++) {
            UiDrawCommand command = Objects.requireNonNull(commands.get(index), "command");
            if (command.texture() == first.texture() && command.clip().equals(first.clip())) {
                currentCommands.add(command);
                continue;
            }
            runs.add(new UiBatchRun(first.texture(), first.clip(), currentCommands));
            first = command;
            currentCommands = new ArrayList<>();
            currentCommands.add(command);
        }
        runs.add(new UiBatchRun(first.texture(), first.clip(), currentCommands));
        return List.copyOf(runs);
    }
}
