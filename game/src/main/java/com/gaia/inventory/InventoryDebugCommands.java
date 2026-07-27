package com.gaia.inventory;

import com.overlord.inventory.api.BodyInventoryViewModel;
import java.util.Locale;
import java.util.Objects;

/** Command surface for explicit development-only seed, clear, fill, and print actions. */
public final class InventoryDebugCommands {
    private final InventoryDebugSeeder seeder;
    private final BodyInventoryService inventory;
    private final com.overlord.interaction.api.EntityRef owner;
    private final InventorySnapshotFormatter formatter;

    public InventoryDebugCommands(
            InventoryDebugSeeder seeder,
            BodyInventoryService inventory,
            com.overlord.interaction.api.EntityRef owner,
            InventorySnapshotFormatter formatter) {
        this.seeder = Objects.requireNonNull(seeder, "seeder");
        this.inventory = Objects.requireNonNull(inventory, "inventory");
        this.owner = Objects.requireNonNull(owner, "owner");
        this.formatter = Objects.requireNonNull(formatter, "formatter");
    }

    public DebugCommandResult execute(String command) {
        Objects.requireNonNull(command, "command");
        String normalized = command.trim().toLowerCase(Locale.ROOT);
        switch (normalized) {
            case "seed" -> seeder.seed();
            case "clear" -> seeder.clear();
            case "fill" -> seeder.fill();
            case "print" -> { }
            default -> {
                return new DebugCommandResult(
                        DebugCommandResult.Status.UNKNOWN_COMMAND, "unknown command: " + command);
            }
        }
        BodyInventoryViewModel viewModel = inventory.viewModel(owner).orElseThrow();
        return new DebugCommandResult(
                DebugCommandResult.Status.APPLIED, formatter.format(viewModel));
    }
}
