package com.gaia.world.generation;

import com.gaia.blocks.BlockRegistry;
import com.overlord.assets.ResourceLocation;
import java.util.Objects;

public record GenerationBlockPalette(
        byte air,
        byte grass,
        byte dirt,
        byte stone) {
    public static GenerationBlockPalette from(
            BlockRegistry blocks) {
        Objects.requireNonNull(blocks, "blocks");
        return new GenerationBlockPalette(
                blocks.requireStoredId(
                        ResourceLocation.parse("gaia:air")),
                blocks.requireStoredId(
                        ResourceLocation.parse("gaia:grass")),
                blocks.requireStoredId(
                        ResourceLocation.parse("gaia:dirt")),
                blocks.requireStoredId(
                        ResourceLocation.parse("gaia:stone")));
    }
}
