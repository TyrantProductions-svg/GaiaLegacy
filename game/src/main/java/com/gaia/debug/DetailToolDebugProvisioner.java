package com.gaia.debug;

import com.overlord.assets.ResourceLocation;
import com.overlord.interaction.api.EntityRef;
import com.overlord.inventory.api.BodySlot;
import com.overlord.inventory.api.InventoryChangeRequest;
import com.overlord.inventory.api.InventoryChangeResult;
import com.overlord.inventory.api.InventoryService;
import com.overlord.inventory.api.InventoryView;
import com.overlord.inventory.api.ItemStack;
import java.util.Objects;
import java.util.Optional;

/** Explicit development-only provisioning through the canonical inventory service. */
public final class DetailToolDebugProvisioner {
    public static final ResourceLocation CHISEL = ResourceLocation.parse("gaia:chisel");
    public static final ResourceLocation STONE_UNIT =
            ResourceLocation.parse("gaia:stone_detail_unit");
    public static final ResourceLocation DIRT_UNIT =
            ResourceLocation.parse("gaia:dirt_detail_unit");

    private final InventoryService inventory;
    private final EntityRef owner;

    public DetailToolDebugProvisioner(InventoryService inventory, EntityRef owner) {
        this.inventory = Objects.requireNonNull(inventory, "inventory");
        this.owner = Objects.requireNonNull(owner, "owner");
    }

    public void provision(ResourceLocation detailUnit) {
        Objects.requireNonNull(detailUnit, "detailUnit");
        if (!detailUnit.equals(STONE_UNIT) && !detailUnit.equals(DIRT_UNIT)) {
            throw new IllegalArgumentException("unsupported debug detail unit: " + detailUnit);
        }
        replace(BodySlot.RIGHT_HAND, new ItemStack(CHISEL, 1));
        replace(BodySlot.LEFT_HAND, new ItemStack(detailUnit, 64));
    }

    private void replace(BodySlot slot, ItemStack stack) {
        InventoryView before = inventory.snapshot(owner).orElseThrow(
                () -> new IllegalStateException("debug inventory owner is unavailable"));
        InventoryChangeResult result = inventory.replaceSlot(new InventoryChangeRequest(
                owner, slot, before.revision(), Optional.of(stack)));
        if (result.status() != InventoryChangeResult.Status.APPLIED) {
            throw new IllegalStateException(
                    "debug detail provisioning failed with " + result.status());
        }
    }
}
