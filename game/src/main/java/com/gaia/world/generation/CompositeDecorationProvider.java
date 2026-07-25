package com.gaia.world.generation;

import com.overlord.assets.ResourceLocation;
import com.overlord.config.GameConfig;
import java.util.Optional;

public final class CompositeDecorationProvider
        implements DecorationProvider {
    private static final ResourceLocation ID =
            ResourceLocation.parse("gaia:decoration");
    private static final ResourceLocation OUTCROP_ID =
            ResourceLocation.parse("gaia:stone_outcrop");
    private static final int CELL_SIZE = 16;
    private final TreeDecorationProvider trees =
            new TreeDecorationProvider();

    @Override
    public ResourceLocation id() {
        return ID;
    }

    @Override
    public GenerationStageResult generate(
            GenerationContext context, GenerationRegion region) {
        int writes = trees.generate(context, region);
        writes += generateOutcrops(context, region);
        return new GenerationStageResult(
                id(),
                GenerationStageResult.Status.SUCCEEDED,
                256,
                writes,
                Optional.empty());
    }

    private int generateOutcrops(
            GenerationContext context, GenerationRegion region) {
        int writes = 0;
        int minimumCellX =
                cellCoordinate(
                        (long) region.key().worldOriginX() - 4);
        int maximumCellX =
                cellCoordinate(
                        (long) region.key().worldOriginX()
                                + GameConfig.Chunk.SIZE
                                + 3);
        int minimumCellZ =
                cellCoordinate(
                        (long) region.key().worldOriginZ() - 4);
        int maximumCellZ =
                cellCoordinate(
                        (long) region.key().worldOriginZ()
                                + GameConfig.Chunk.SIZE
                                + 3);
        BiomeProvider biomes = new ContinuousBiomeProvider();
        HeightProvider heights = new BiomeShapedHeightProvider();
        for (int cellZ = minimumCellZ;
                cellZ <= maximumCellZ;
                cellZ++) {
            for (int cellX = minimumCellX;
                    cellX <= maximumCellX;
                    cellX++) {
                Integer rootX =
                        worldCoordinate(
                                cellX,
                                context,
                                cellX,
                                cellZ,
                                0L);
                Integer rootZ =
                        worldCoordinate(
                                cellZ,
                                context,
                                cellX,
                                cellZ,
                                1L);
                if (rootX == null
                        || rootZ == null
                        || insideOriginReserve(rootX, rootZ)
                        || trees.conflicts(context, rootX, rootZ)
                        || HybridCaveProvider.hasEntranceNear(
                                context, rootX, rootZ, 8)) {
                    continue;
                }
                BiomeSample biome =
                        biomes.sample(context, rootX, rootZ);
                int rootSurface =
                        heights.sampleHeight(
                                context, rootX, rootZ, biome);
                int slope = 0;
                for (int[] direction :
                        new int[][] {
                            {-1, 0}, {1, 0}, {0, -1}, {0, 1}
                        }) {
                    Integer x =
                            addIfRepresentable(
                                    rootX, direction[0]);
                    Integer z =
                            addIfRepresentable(
                                    rootZ, direction[1]);
                    if (x == null || z == null) {
                        continue;
                    }
                    slope =
                            Math.max(
                                    slope,
                                    Math.abs(
                                            rootSurface
                                                    - heights.sampleHeight(
                                                            context,
                                                            x,
                                                            z,
                                                            biomes.sample(
                                                                    context,
                                                                    x,
                                                                    z))));
                }
                double slopeWeight =
                        Math.min(1.0, slope / 3.0);
                double densityScale =
                        768.0
                                / context.config()
                                        .decoration()
                                        .chanceDenominator();
                double probability =
                        Math.min(
                                1.0,
                                (0.01
                                                        * biome.rollingHills()
                                                + 0.85
                                                        * biome.rockyHighlands())
                                        * (0.35
                                                + 0.65
                                                        * slopeWeight)
                                        * densityScale);
                if (context.sampler().unit(
                                        OUTCROP_ID,
                                        cellX,
                                        0,
                                        cellZ,
                                        2L)
                                >= probability
                        || biome.rockyHighlands() < 0.45) {
                    continue;
                }
                int footprint =
                        2
                                + (int)
                                        (context.sampler().unit(
                                                        OUTCROP_ID,
                                                        cellX,
                                                        0,
                                                        cellZ,
                                                        3L)
                                                * 4.0);
                int orientation =
                        (int)
                                (context.sampler().unit(
                                                OUTCROP_ID,
                                                cellX,
                                                0,
                                                cellZ,
                                                4L)
                                        * 4.0);
                for (int index = 0; index < footprint; index++) {
                        int dx =
                                switch (orientation) {
                                    case 0 -> index / 2;
                                    case 1 -> -(index / 2);
                                    case 2 -> index % 2;
                                    default -> -(index % 2);
                                };
                        int dz =
                                switch (orientation) {
                                    case 0 -> index % 2;
                                    case 1 -> -(index % 2);
                                    case 2 -> index / 2;
                                    default -> -(index / 2);
                                };
                        Integer x =
                                addIfRepresentable(rootX, dx);
                        Integer z =
                                addIfRepresentable(rootZ, dz);
                        if (x == null || z == null) {
                            continue;
                        }
                        BiomeSample localBiome =
                                biomes.sample(context, x, z);
                        int surface =
                                heights.sampleHeight(
                                        context,
                                        x,
                                        z,
                                        localBiome);
                        int columnHeight =
                                1
                                        + (context.sampler().unit(
                                                                OUTCROP_ID,
                                                                x,
                                                                surface,
                                                                z,
                                                                5L)
                                                        < 0.12
                                                ? Math.max(
                                                        1,
                                                        context.config()
                                                                .decoration()
                                                                .maximumOutcropHeight()
                                                                - 1)
                                                : 0);
                        for (int y = 1; y <= columnHeight; y++) {
                            writes +=
                                    writeWorld(
                                            region,
                                            x,
                                            surface + y,
                                            z,
                                            context.palette().stone(),
                                            context.palette().air());
                        }
                }
            }
        }
        return writes;
    }

    private static int cellOffset(
            GenerationContext context,
            int cellX,
            int cellZ,
            long salt) {
        return (int)
                (context.sampler().unit(
                                OUTCROP_ID,
                                cellX,
                                0,
                                cellZ,
                                salt)
                        * CELL_SIZE);
    }

    private static int cellCoordinate(long worldCoordinate) {
        return Math.toIntExact(
                Math.floorDiv(worldCoordinate, CELL_SIZE));
    }

    private static Integer worldCoordinate(
            int baseCell,
            GenerationContext context,
            int sampleCellX,
            int sampleCellZ,
            long salt) {
        long value =
                (long) baseCell * CELL_SIZE
                        + cellOffset(
                                context,
                                sampleCellX,
                                sampleCellZ,
                                salt);
        if (value < Integer.MIN_VALUE
                || value > Integer.MAX_VALUE) {
            return null;
        }
        return (int) value;
    }

    private static Integer addIfRepresentable(
            int base, int offset) {
        long value = (long) base + offset;
        if (value < Integer.MIN_VALUE
                || value > Integer.MAX_VALUE) {
            return null;
        }
        return (int) value;
    }

    private static boolean insideOriginReserve(
            int x, int z) {
        return x > -12
                && x < 12
                && z > -12
                && z < 12
                && (long) x * x + (long) z * z < 144L;
    }

    private static int writeWorld(
            GenerationRegion region,
            int worldX,
            int y,
            int worldZ,
            byte block,
            byte air) {
        long xValue =
                (long) worldX
                        - region.key().worldOriginX();
        long zValue =
                (long) worldZ
                        - region.key().worldOriginZ();
        if (xValue < 0
                || xValue >= GameConfig.Chunk.SIZE
                || zValue < 0
                || zValue >= GameConfig.Chunk.SIZE
                || y < 0
                || y >= region.worldHeight()
                || region.getBlock(
                                (int) xValue, y, (int) zValue)
                        != air) {
            return 0;
        }
        region.writeBlock(
                (int) xValue, y, (int) zValue, block);
        return 1;
    }
}
