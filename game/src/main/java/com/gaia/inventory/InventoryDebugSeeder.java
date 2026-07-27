package com.gaia.inventory;

import com.overlord.interaction.api.EntityRef;
import com.overlord.inventory.api.BodySlot;
import com.overlord.inventory.api.InventoryChangeRequest;
import com.overlord.inventory.api.InventoryChangeResult;
import com.overlord.inventory.api.InventoryService;
import com.overlord.inventory.api.InventoryView;
import com.overlord.inventory.api.ItemStack;
import java.util.Objects;
import java.util.Optional;

/** Explicit developer-only injector which mutates only through InventoryService. */
public final class InventoryDebugSeeder {
    private final InventoryService inventory;
    private final EntityRef owner;
    private final DebugInventoryProfile profile;

    public InventoryDebugSeeder(
            InventoryService inventory,
            EntityRef owner,
            DebugInventoryProfile profile) {
        this.inventory = Objects.requireNonNull(inventory, "inventory");
        this.owner = Objects.requireNonNull(owner, "owner");
        this.profile = Objects.requireNonNull(profile, "profile");
    }

    public void seed() {
        replace(BodySlot.LEFT_HAND, Optional.of(profile.leftPartial()));
        replace(BodySlot.RIGHT_HAND, Optional.of(profile.rightFull()));
        replace(BodySlot.MOUTH, Optional.of(profile.mouthStack()));
    }

    public void clear() {
        replace(BodySlot.LEFT_HAND, Optional.empty());
        replace(BodySlot.RIGHT_HAND, Optional.empty());
        replace(BodySlot.MOUTH, Optional.empty());
    }

    public void fill() {
        replace(BodySlot.LEFT_HAND, Optional.of(profile.leftFull()));
        replace(BodySlot.RIGHT_HAND, Optional.of(profile.rightFull()));
        replace(BodySlot.MOUTH, Optional.of(profile.mouthStack()));
    }

    private void replace(BodySlot slot, Optional<ItemStack> replacement) {
        InventoryView before = inventory.snapshot(owner).orElseThrow(
                () -> new IllegalStateException("debug inventory owner is unavailable"));
        InventoryChangeResult result = inventory.replaceSlot(new InventoryChangeRequest(
                owner, slot, before.revision(), replacement));
        if (result.status() != InventoryChangeResult.Status.APPLIED) {
            throw new IllegalStateException(
                    "debug inventory command failed with " + result.status());
        }
    }
}
