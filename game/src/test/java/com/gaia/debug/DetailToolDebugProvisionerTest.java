package com.gaia.debug;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.gaia.blocks.ItemFormDefinition;
import com.gaia.inventory.BodyInventoryService;
import com.gaia.inventory.DebugInventoryProfile;
import com.gaia.inventory.InventoryDebugCommands;
import com.gaia.inventory.InventoryDebugSeeder;
import com.gaia.inventory.InventorySnapshotFormatter;
import com.overlord.assets.ResourceLocation;
import com.overlord.core.thread.MainThreadGuard;
import com.overlord.interaction.api.EntityRef;
import com.overlord.inventory.api.BodySlot;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class DetailToolDebugProvisionerTest {
    private static final EntityRef OWNER = new EntityRef(81);
    private static final ResourceLocation CHISEL = ResourceLocation.parse("gaia:chisel");
    private static final ResourceLocation STONE_UNIT =
            ResourceLocation.parse("gaia:stone_detail_unit");
    private static final ResourceLocation DIRT_UNIT =
            ResourceLocation.parse("gaia:dirt_detail_unit");

    @Test
    void explicitProvisioningUsesPublicInventoryAuthorityForChiselAndSelectedUnits() {
        BodyInventoryService inventory = new BodyInventoryService(
                OWNER,
                itemId -> java.util.Optional.ofNullable(itemForms().get(itemId)),
                MainThreadGuard.captureCurrentThread(),
                ignored -> {});
        DetailToolDebugProvisioner provisioner = new DetailToolDebugProvisioner(inventory, OWNER);

        provisioner.provision(STONE_UNIT);

        var view = inventory.viewModel(OWNER).orElseThrow();
        assertEquals(CHISEL, view.inventory().stack(BodySlot.RIGHT_HAND).orElseThrow().itemId());
        assertEquals(1, view.inventory().stack(BodySlot.RIGHT_HAND).orElseThrow().count());
        assertEquals(STONE_UNIT, view.inventory().stack(BodySlot.LEFT_HAND).orElseThrow().itemId());
        assertEquals(64, view.inventory().stack(BodySlot.LEFT_HAND).orElseThrow().count());

        provisioner.provision(DIRT_UNIT);
        view = inventory.viewModel(OWNER).orElseThrow();
        assertEquals(DIRT_UNIT, view.inventory().stack(BodySlot.LEFT_HAND).orElseThrow().itemId());
        assertEquals(64, view.inventory().stack(BodySlot.LEFT_HAND).orElseThrow().count());
    }

    @Test
    void explicitCommandsProvisionStoneOrDirtWithoutAStartupSideEffect() {
        BodyInventoryService inventory = new BodyInventoryService(
                OWNER,
                itemId -> java.util.Optional.ofNullable(itemForms().get(itemId)),
                MainThreadGuard.captureCurrentThread(),
                ignored -> {});
        DetailToolDebugProvisioner provisioner = new DetailToolDebugProvisioner(inventory, OWNER);
        InventoryDebugCommands commands = new InventoryDebugCommands(
                new InventoryDebugSeeder(
                        inventory,
                        OWNER,
                        new DebugInventoryProfile(
                                new com.overlord.inventory.api.ItemStack(STONE_UNIT, 1),
                                new com.overlord.inventory.api.ItemStack(STONE_UNIT, 64),
                                new com.overlord.inventory.api.ItemStack(DIRT_UNIT, 64),
                                new com.overlord.inventory.api.ItemStack(CHISEL, 1))),
                inventory,
                OWNER,
                new InventorySnapshotFormatter(),
                provisioner);

        assertEquals(0, inventory.totalCount(OWNER, CHISEL));
        commands.execute("detail-dirt");
        assertEquals(1, inventory.totalCount(OWNER, CHISEL));
        assertEquals(64, inventory.totalCount(OWNER, DIRT_UNIT));
        assertEquals(0, inventory.totalCount(OWNER, STONE_UNIT));
    }

    private static Map<ResourceLocation, ItemFormDefinition> itemForms() {
        return Map.of(
                CHISEL, new ItemFormDefinition(CHISEL, 1, false, false),
                STONE_UNIT, new ItemFormDefinition(STONE_UNIT, 64, false, false),
                DIRT_UNIT, new ItemFormDefinition(DIRT_UNIT, 64, false, false));
    }
}
