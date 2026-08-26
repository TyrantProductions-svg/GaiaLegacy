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
    private static final int OUTCROP_HALO_RADIUS = 4;
    private static final GenerationStageContract CONTRACT =
            new GenerationStageContract(
                    ID, 1, OUTCROP_HALO_RADIUS);
    private final GenerationStageContract contract;
    private final GenerationStageContract outcropContract;
    private final TreeDecorationProvider trees;
    private final HybridCaveProvider.EntranceQuery entrances;

    public CompositeDecorationProvider(
            HybridCaveProvider.EntranceQuery entrances) {
        this(CONTRACT, entrances);
    }

    public CompositeDecorationProvider(
            GenerationStageContract contract,
            HybridCaveProvider.EntranceQuery entrances) {
        if (!ID.equals(contract.id())) {
            throw new IllegalArgumentException(
                    "Composite decoration contract must use " + ID);
        }
        this.contract = contract;
        this.outcropContract =
                contract.child(
                        OUTCROP_ID, contract.haloRadius());
        this.entrances =
                java.util.Objects.requireNonNull(
                        entrances, "entrances");
        this.trees =
                new TreeDecorationProvider(contract, entrances);
    }

    @Override
    public ResourceLocation id() {
        return ID;
    }

    @Override
    public GenerationStageContract contract() {
        return contract;
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
        GenerationStageContract.RegionRange xRange =
                contract.regionsForChunk(
                        region.worldOriginX(),
                        GameConfig.Chunk.SIZE,
                        CELL_SIZE);
        GenerationStageContract.RegionRange zRange =
                contract.regionsForChunk(
                        region.worldOriginZ(),
                        GameConfig.Chunk.SIZE,
                        CELL_SIZE);
        long minimumCellX = xRange.minimum();
        long maximumCellX = xRange.maximum();
        long minimumCellZ = zRange.minimum();
        long maximumCellZ = zRange.maximum();
        BiomeProvider biomes = new ContinuousBiomeProvider();
        HeightProvider heights = new BiomeShapedHeightProvider();
        for (long cellZ = minimumCellZ;
                cellZ <= maximumCellZ;
                cellZ++) {
            for (long cellX = minimumCellX;
                    cellX <= maximumCellX;
                    cellX++) {
                Optional<StableRegionAnchor> sampled =
                        StableRegionAnchor.sampleIfSafe(
                                context.sampler(),
                                outcropContract,
                                cellX,
                                cellZ,
                                CELL_SIZE);
                if (sampled.isEmpty()) {
                    continue;
                }
                StableRegionAnchor anchor =
                        sampled.orElseThrow();
                long rootX = anchor.worldX();
                long rootZ = anchor.worldZ();
                if (insideOriginReserve(rootX, rootZ)
                        || trees.conflicts(context, rootX, rootZ)
                        || entrances.hasEntranceNear(
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
                    long x =
                            Math.addExact(
                                    rootX, direction[0]);
                    long z =
                            Math.addExact(
                                    rootZ, direction[1]);
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
                                        outcropContract,
                                        cellX,
                                        0L,
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
                                                        outcropContract,
                                                        cellX,
                                                        0L,
                                                        cellZ,
                                                        3L)
                                                * 4.0);
                int orientation =
                        (int)
                                (context.sampler().unit(
                                                outcropContract,
                                                cellX,
                                                0L,
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
                        long x = Math.addExact(rootX, dx);
                        long z = Math.addExact(rootZ, dz);
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
                                                                outcropContract,
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

    private static boolean insideOriginReserve(
            long x, long z) {
        return x > -12
                && x < 12
                && z > -12
                && z < 12
                && (long) x * x + (long) z * z < 144L;
    }

    private static int writeWorld(
            GenerationRegion region,
            long worldX,
            int y,
            long worldZ,
            byte block,
            byte air) {
        long xValue =
                worldX - region.worldOriginX();
        long zValue =
                worldZ - region.worldOriginZ();
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
