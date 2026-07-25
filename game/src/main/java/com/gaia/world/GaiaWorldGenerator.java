package com.gaia.world;

import com.gaia.world.generation.BlendedHeightProvider;
import com.gaia.world.generation.BiomeShapedHeightProvider;
import com.gaia.world.generation.CompositeDecorationProvider;
import com.gaia.world.generation.ContinuousBiomeProvider;
import com.gaia.world.generation.DefaultStrataDensityProvider;
import com.gaia.world.generation.DefaultSurfaceProvider;
import com.gaia.world.generation.NoiseCaveProvider;
import com.gaia.world.generation.HybridCaveProvider;
import com.gaia.world.generation.StagedWorldGenerator;
import com.gaia.world.generation.StoneOutcropDecorationProvider;
import com.gaia.world.generation.WorldGenerator;
import java.util.List;

public final class GaiaWorldGenerator {
    private GaiaWorldGenerator() {
    }

    public static WorldGenerator createDefault() {
        return new StagedWorldGenerator(
                List.of(
                        new ContinuousBiomeProvider(),
                        new BlendedHeightProvider(),
                        new DefaultStrataDensityProvider(),
                        new NoiseCaveProvider(),
                        new DefaultSurfaceProvider(),
                        new StoneOutcropDecorationProvider()));
    }

    public static WorldGenerator createVisualRevisionCandidate() {
        return new StagedWorldGenerator(
                List.of(
                        new ContinuousBiomeProvider(),
                        new BiomeShapedHeightProvider(),
                        new DefaultStrataDensityProvider(),
                        new HybridCaveProvider(),
                        new DefaultSurfaceProvider(),
                        new CompositeDecorationProvider()));
    }
}
