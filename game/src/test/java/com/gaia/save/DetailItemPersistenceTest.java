package com.gaia.save;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.gaia.save.codec.InventorySectionCodec;
import com.gaia.save.codec.WorldItemsSectionCodec;
import com.gaia.save.snapshot.InventorySaveSnapshot;
import com.gaia.save.snapshot.WorldItemsSaveSnapshot;
import com.overlord.assets.ResourceLocation;
import com.overlord.interaction.api.EntityRef;
import com.overlord.inventory.api.BodySlot;
import com.overlord.inventory.api.ItemStack;
import com.overlord.worlditem.api.WorldItemId;
import com.overlord.worlditem.api.WorldItemPhysicalState;
import com.overlord.worlditem.api.WorldItemRestoreEntry;
import com.overlord.worlditem.api.WorldItemRuntimeSnapshot;
import com.overlord.worlditem.api.WorldItemSnapshot;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class DetailItemPersistenceTest {
    private static final EntityRef OWNER = new EntityRef(91);
    private static final ResourceLocation CHISEL = ResourceLocation.parse("gaia:chisel");
    private static final ResourceLocation STONE_UNIT =
            ResourceLocation.parse("gaia:stone_detail_unit");
    private static final ResourceLocation DIRT_UNIT =
            ResourceLocation.parse("gaia:dirt_detail_unit");

    @Test
    void inventoryCodecRoundTripsStandalonePhase17IdentitiesGenerically() {
        InventorySaveSnapshot snapshot = new InventorySaveSnapshot(
                OWNER,
                Map.of(
                        BodySlot.RIGHT_HAND, new ItemStack(CHISEL, 1),
                        BodySlot.LEFT_HAND, new ItemStack(STONE_UNIT, 64),
                        BodySlot.MOUTH, new ItemStack(DIRT_UNIT, 1)),
                BodySlot.RIGHT_HAND,
                false,
                7L);
        InventorySectionCodec codec = new InventorySectionCodec();

        assertEquals(snapshot, codec.decode(codec.encode(snapshot)));
    }

    @Test
    void worldItemCodecRoundTripsDetailUnitIdentityWithoutBlockBacking() {
        WorldItemSnapshot item = new WorldItemSnapshot(
                new WorldItemId(3),
                new ItemStack(STONE_UNIT, 1),
                1.25, 2.5, -3.75,
                0.0, 0.1, 0.0,
                4L);
        WorldItemRestoreEntry entry = new WorldItemRestoreEntry(
                new WorldItemRuntimeSnapshot(item, Optional.of(OWNER), 10L, 10L),
                WorldItemPhysicalState.GROUNDED);
        WorldItemsSaveSnapshot snapshot = new WorldItemsSaveSnapshot(
                10L, List.of(entry), 4L, false);
        WorldItemsSectionCodec codec = new WorldItemsSectionCodec();

        assertEquals(snapshot, codec.decode(codec.encode(snapshot)));
    }
}
