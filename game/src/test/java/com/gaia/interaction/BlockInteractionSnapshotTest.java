package com.gaia.interaction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.overlord.assets.ResourceLocation;
import com.overlord.interaction.api.BlockFace;
import com.overlord.interaction.api.BlockHitResult;
import com.overlord.interaction.api.InteractionMode;
import com.overlord.inventory.api.ItemStackView;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class BlockInteractionSnapshotTest {
    @Test
    void copiesActiveItemViewIntoCanonicalImmutableSnapshot() {
        MutableItemView item = new MutableItemView();
        BlockInteractionSnapshot snapshot = new BlockInteractionSnapshot(
                Optional.empty(),
                Optional.empty(),
                0,
                InteractionMode.NONE,
                Optional.of(item),
                Optional.empty(),
                0,
                GameMode.SURVIVAL);

        item.count = 99;

        assertEquals(3, snapshot.activeItem().orElseThrow().count());
    }

    @Test
    void validatesCrackStageAndHitFaceConsistency() {
        BlockHitResult hit = new BlockHitResult(
                1, 2, 3,
                2, 2, 3,
                ResourceLocation.parse("gaia:stone"),
                1, 0, 0,
                2, 2.5f, 3.5f,
                1);

        assertThrows(IllegalArgumentException.class, () -> new BlockInteractionSnapshot(
                Optional.of(hit), Optional.of(BlockFace.WEST), 0.5,
                InteractionMode.BREAKING, Optional.empty(), Optional.empty(),
                4, GameMode.SURVIVAL));
        assertThrows(IllegalArgumentException.class, () -> new BlockInteractionSnapshot(
                Optional.of(hit), Optional.of(BlockFace.EAST), 0.5,
                InteractionMode.BREAKING, Optional.empty(), Optional.empty(),
                10, GameMode.SURVIVAL));
    }

    private static final class MutableItemView implements ItemStackView {
        private int count = 3;

        @Override
        public ResourceLocation itemId() {
            return ResourceLocation.parse("gaia:dirt");
        }

        @Override
        public int count() {
            return count;
        }
    }
}
