package com.gaia.interaction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gaia.blocks.BlockDefinition;
import com.gaia.blocks.ItemFormDefinition;
import com.overlord.assets.ResourceLocation;
import com.overlord.voxel.BlockFace;
import java.util.EnumMap;
import org.junit.jupiter.api.Test;

class BlockInteractionPolicyTest {
    @Test
    void survivalUsesHardnessAndCreativeIsImmediateWithoutTransfers() {
        BlockDefinition stone = block(1, "gaia:stone", 3);

        BreakRule survival = BlockInteractionPolicy.forMode(GameMode.SURVIVAL)
                .breakRule(stone, 2);
        BreakRule creative = BlockInteractionPolicy.forMode(GameMode.CREATIVE)
                .breakRule(stone, 2);

        assertTrue(survival.breakable());
        assertEquals(1.5, survival.requiredSeconds());
        assertTrue(BlockInteractionPolicy.forMode(GameMode.SURVIVAL).producesDrops());
        assertTrue(BlockInteractionPolicy.forMode(GameMode.SURVIVAL).consumesPlacement());
        assertEquals(0, creative.requiredSeconds());
        assertFalse(BlockInteractionPolicy.forMode(GameMode.CREATIVE).producesDrops());
        assertFalse(BlockInteractionPolicy.forMode(GameMode.CREATIVE).consumesPlacement());
    }

    @Test
    void hardnessZeroIsInstantButAirIsExplicitlyUnbreakable() {
        BreakRule zero = BlockInteractionPolicy.forMode(GameMode.SURVIVAL)
                .breakRule(block(2, "gaia:fragile", 0), 1);
        BreakRule air = BlockInteractionPolicy.forMode(GameMode.SURVIVAL)
                .breakRule(block(0, "gaia:air", 0), 1);

        assertTrue(zero.breakable());
        assertEquals(0, zero.requiredSeconds());
        assertFalse(air.breakable());
    }

    private static BlockDefinition block(int id, String name, float hardness) {
        ResourceLocation location = ResourceLocation.parse(name);
        ResourceLocation texture = ResourceLocation.parse("gaia:missing");
        EnumMap<BlockFace, ResourceLocation> textures = new EnumMap<>(BlockFace.class);
        for (BlockFace face : BlockFace.values()) {
            textures.put(face, texture);
        }
        return new BlockDefinition(
                id,
                location,
                ResourceLocation.parse("gaia:opaque"),
                textures,
                hardness,
                1,
                1,
                false,
                false,
                1,
                id == 0 ? null : new ItemFormDefinition(location, 64, false, false));
    }
}
