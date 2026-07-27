package com.gaia.inventory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gaia.blocks.ItemFormDefinition;
import com.overlord.assets.ResourceLocation;
import com.overlord.interaction.api.EntityRef;
import com.overlord.inventory.api.BodySlot;
import com.overlord.inventory.api.ItemStack;
import java.lang.reflect.Constructor;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class InventoryDebugToolsTest {
    private static final EntityRef OWNER = new EntityRef(11);
    private static final ResourceLocation DIRT = ResourceLocation.parse("gaia:dirt");
    private static final ResourceLocation STONE = ResourceLocation.parse("gaia:stone");
    private static final ResourceLocation LEAVES = ResourceLocation.parse("gaia:oak_leaves");

    @Test
    void seederUsesPublicServiceOperationsAndFormatterReadsSnapshotsOnly() {
        BodyInventoryService service = service();
        DebugInventoryProfile profile = new DebugInventoryProfile(
                new ItemStack(DIRT, 12),
                new ItemStack(DIRT, 64),
                new ItemStack(STONE, 64),
                new ItemStack(LEAVES, 1));
        InventoryDebugSeeder seeder = new InventoryDebugSeeder(service, OWNER, profile);

        seeder.seed();
        String seeded = new InventorySnapshotFormatter().format(
                service.viewModel(OWNER).orElseThrow());
        seeder.clear();
        seeder.fill();

        assertTrue(seeded.contains("LEFT_HAND=gaia:dirt x12"));
        assertTrue(seeded.contains("RIGHT_HAND=gaia:stone x64"));
        assertTrue(seeded.contains("MOUTH=gaia:oak_leaves x1"));
        assertEquals(64, service.snapshot(OWNER).orElseThrow()
                .stack(BodySlot.LEFT_HAND).orElseThrow().count());
        for (Constructor<?> constructor : InventoryDebugSeeder.class.getConstructors()) {
            for (Class<?> type : constructor.getParameterTypes()) {
                assertTrue(type != BodyInventory.class);
            }
        }
    }

    private static BodyInventoryService service() {
        Map<ResourceLocation, ItemFormDefinition> forms = Map.of(
                DIRT, new ItemFormDefinition(DIRT, 64, false, false),
                STONE, new ItemFormDefinition(STONE, 64, false, false),
                LEAVES, new ItemFormDefinition(LEAVES, 64, true, false));
        return new BodyInventoryService(
                OWNER, id -> Optional.ofNullable(forms.get(id)), event -> {});
    }
}
