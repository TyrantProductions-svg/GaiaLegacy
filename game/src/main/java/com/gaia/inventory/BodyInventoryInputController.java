package com.gaia.inventory;

import com.overlord.config.GameConfig;
import com.overlord.core.input.InputSnapshot;
import com.overlord.interaction.api.EntityRef;
import com.overlord.inventory.api.BodySlot;
import java.util.Objects;
import java.util.Optional;

/** Converts input edges into public inventory-service operations. */
public final class BodyInventoryInputController {
    private final BodyInventoryService inventory;
    private final EntityRef owner;
    private final Optional<InventoryDropController> dropController;

    public BodyInventoryInputController(BodyInventoryService inventory, EntityRef owner) {
        this(inventory, owner, Optional.empty());
    }

    public BodyInventoryInputController(
            BodyInventoryService inventory,
            EntityRef owner,
            Optional<InventoryDropController> dropController) {
        this.inventory = Objects.requireNonNull(inventory, "inventory");
        this.owner = Objects.requireNonNull(owner, "owner");
        this.dropController = Objects.requireNonNull(dropController, "dropController");
    }

    public InventoryInputResult handle(InputSnapshot input) {
        return handle(input, 0L, Optional.empty());
    }

    public InventoryInputResult handle(
            InputSnapshot input,
            long tick,
            Optional<InventoryDropLocation> dropLocation) {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(dropLocation, "dropLocation");
        if (tick < 0) {
            throw new IllegalArgumentException("tick must be non-negative");
        }
        Optional<ActiveSlotChangeResult> selection = select(input);
        Optional<InventoryDropResult> drop = Optional.empty();
        if (input.isKeyPressed(GameConfig.Input.KEY_DROP)) {
            drop = Optional.of(drop(dropLocation, tick));
        }
        return new InventoryInputResult(selection, drop);
    }

    private Optional<ActiveSlotChangeResult> select(InputSnapshot input) {
        if (input.isKeyPressed(GameConfig.Input.KEY_SELECT_LEFT)) {
            return Optional.of(inventory.selectActiveSlot(owner, BodySlot.LEFT_HAND));
        }
        if (input.isKeyPressed(GameConfig.Input.KEY_SELECT_RIGHT)) {
            return Optional.of(inventory.selectActiveSlot(owner, BodySlot.RIGHT_HAND));
        }
        if (input.isKeyPressed(GameConfig.Input.KEY_SELECT_MOUTH)) {
            return Optional.of(inventory.selectActiveSlot(owner, BodySlot.MOUTH));
        }
        ActiveSlotChangeResult last = null;
        for (int delta : input.scrollDeltas()) {
            int remaining = delta;
            while (remaining != 0) {
                int step = remaining > 0 ? 1 : -1;
                last = inventory.cycleActiveSlot(owner, step);
                remaining -= step;
            }
        }
        return Optional.ofNullable(last);
    }

    private InventoryDropResult drop(
            Optional<InventoryDropLocation> location, long tick) {
        if (dropController.isEmpty() || location.isEmpty()) {
            return new InventoryDropResult(
                    InventoryDropResult.Status.WORLD_ITEM_UNAVAILABLE,
                    Optional.empty(),
                    Optional.empty());
        }
        InventoryDropLocation transform = location.orElseThrow();
        BodySlot activeSlot = inventory.viewModel(owner).orElseThrow().activeSlot();
        return dropController.orElseThrow().drop(
                owner, activeSlot,
                transform.positionX(), transform.positionY(), transform.positionZ(),
                transform.velocityX(), transform.velocityY(), transform.velocityZ(), tick);
    }
}
