package com.gaia.world.generation;

import com.overlord.assets.ResourceLocation;
import com.overlord.config.GameConfig;
import java.util.Optional;

public final class TreeDecorationProvider {
    private static final ResourceLocation ID =
            ResourceLocation.parse("gaia:tree_decoration");
    private static final int CELL_SIZE = 8;
    private static final int CANOPY_RADIUS = 2;
    private static final int SPAWN_RESERVE_RADIUS = 12;
    private final GenerationStageContract contract;
    private final HybridCaveProvider.EntranceQuery entrances;

    public TreeDecorationProvider(
            GenerationStageContract decorationContract,
            HybridCaveProvider.EntranceQuery entrances) {
        this.contract =
                decorationContract.child(
                        ID, CANOPY_RADIUS);
        this.entrances =
                java.util.Objects.requireNonNull(
                        entrances, "entrances");
    }

    public int generate(
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
        for (long cellZ = minimumCellZ;
                cellZ <= maximumCellZ;
                cellZ++) {
            for (long cellX = minimumCellX;
                    cellX <= maximumCellX;
                    cellX++) {
                Tree tree = tree(context, cellX, cellZ);
                if (tree != null) {
                    writes += place(context, region, tree);
                }
            }
        }
        return writes;
    }

    boolean conflicts(
            GenerationContext context, long worldX, long worldZ) {
        long cellX = Math.floorDiv(worldX, CELL_SIZE);
        long cellZ = Math.floorDiv(worldZ, CELL_SIZE);
        for (long z = cellZ - 1; z <= cellZ + 1; z++) {
            for (long x = cellX - 1; x <= cellX + 1; x++) {
                Tree tree = tree(context, x, z);
                if (tree != null) {
                    long dx = (long) tree.x() - worldX;
                    long dz = (long) tree.z() - worldZ;
                    if (dx * dx + dz * dz <= 16L) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private Tree tree(
            GenerationContext context, long cellX, long cellZ) {
        Optional<StableRegionAnchor> sampled =
                StableRegionAnchor.sampleIfSafe(
                        context.sampler(),
                        contract,
                        cellX,
                        cellZ,
                        CELL_SIZE);
        if (sampled.isEmpty()) {
            return null;
        }
        StableRegionAnchor anchor = sampled.orElseThrow();
        long rootX = anchor.worldX();
        long rootZ = anchor.worldZ();
        if (insideSpawnReserve(rootX, rootZ)
                || entrances.hasEntranceNear(
                        context, rootX, rootZ, 8)) {
            return null;
        }
        BiomeProvider biomes = new ContinuousBiomeProvider();
        BiomeSample biome = biomes.sample(context, rootX, rootZ);
        double densityScale =
                768.0
                        / context.config()
                                .decoration()
                                .chanceDenominator();
        double probability =
                Math.min(
                        1.0,
                        (0.18 * biome.plains()
                                        + 0.58
                                                * biome.rollingHills()
                                        + 0.015
                                                * biome.rockyHighlands())
                                * densityScale);
        if (context.sampler().unit(
                                contract,
                                cellX,
                                0L,
                                cellZ,
                                2L)
                        >= probability) {
            return null;
        }
        HeightProvider heights = new BiomeShapedHeightProvider();
        int rootY =
                heights.sampleHeight(
                        context, rootX, rootZ, biome);
        int slope = 0;
        for (int[] offset :
                new int[][] {
                    {-1, 0}, {1, 0}, {0, -1}, {0, 1}
                }) {
            long x = Math.addExact(rootX, offset[0]);
            long z = Math.addExact(rootZ, offset[1]);
            slope =
                    Math.max(
                            slope,
                            Math.abs(
                                    rootY
                                            - heights.sampleHeight(
                                                    context,
                                                    x,
                                                    z,
                                                    biomes.sample(
                                                            context,
                                                            x,
                                                            z))));
        }
        if (slope > 1) {
            return null;
        }
        double priority =
                context.sampler().unit(
                        contract,
                        cellX,
                        0L,
                        cellZ,
                        3L);
        for (long neighborZ = cellZ - 1;
                neighborZ <= cellZ + 1;
                neighborZ++) {
            for (long neighborX = cellX - 1;
                    neighborX <= cellX + 1;
                    neighborX++) {
                if (neighborX == cellX
                        && neighborZ == cellZ) {
                    continue;
                }
                Optional<StableRegionAnchor> otherAnchor =
                        StableRegionAnchor.sampleIfSafe(
                                context.sampler(),
                                contract,
                                neighborX,
                                neighborZ,
                                CELL_SIZE);
                if (otherAnchor.isEmpty()) {
                    continue;
                }
                long dx =
                        otherAnchor.orElseThrow().worldX()
                                - rootX;
                long dz =
                        otherAnchor.orElseThrow().worldZ()
                                - rootZ;
                if (dx * dx + dz * dz >= 36L) {
                    continue;
                }
                double otherPriority =
                        context.sampler().unit(
                                contract,
                                neighborX,
                                0,
                                neighborZ,
                                3L);
                if (otherPriority > priority
                        || (otherPriority == priority
                                && (neighborX < cellX
                                        || (neighborX == cellX
                                                && neighborZ
                                                        < cellZ)))) {
                    return null;
                }
            }
        }
        int trunkHeight =
                4
                        + (int)
                                (context.sampler().unit(
                                                contract,
                                                cellX,
                                                0,
                                                cellZ,
                                                4L)
                                        * 4.0);
        return new Tree(rootX, rootY, rootZ, trunkHeight);
    }

    private int place(
            GenerationContext context,
            GenerationRegion region,
            Tree tree) {
        int writes = 0;
        for (int offset = 1; offset <= tree.trunkHeight(); offset++) {
            writes +=
                    writeWorld(
                            region,
                            tree.x(),
                            tree.y() + offset,
                            tree.z(),
                            context.palette().oakLog(),
                            context.palette().air(),
                            context.palette().oakLeaves());
        }
        int canopyY = tree.y() + tree.trunkHeight() - 1;
        for (int dy = -1; dy <= 1; dy++) {
            int radius = dy == 1 ? 1 : CANOPY_RADIUS;
            for (int dz = -radius; dz <= radius; dz++) {
                for (int dx = -radius; dx <= radius; dx++) {
                    long leafX =
                            Math.addExact(tree.x(), dx);
                    long leafZ =
                            Math.addExact(tree.z(), dz);
                    if (Math.abs(dx) == radius
                            && Math.abs(dz) == radius
                            && context.sampler().unit(
                                            contract,
                                            leafX,
                                            canopyY + dy,
                                            leafZ,
                                            5L)
                                    < 0.45) {
                        continue;
                    }
                    if (dx == 0
                            && dz == 0
                            && canopyY + dy
                                    <= tree.y()
                                            + tree.trunkHeight()) {
                        continue;
                    }
                    writes +=
                            writeWorld(
                                    region,
                                    leafX,
                                    canopyY + dy,
                                    leafZ,
                                    context.palette().oakLeaves(),
                                    context.palette().air(),
                                    context.palette().air());
                }
            }
        }
        writes +=
                writeWorld(
                        region,
                        tree.x(),
                        tree.y() + tree.trunkHeight() + 1,
                        tree.z(),
                        context.palette().oakLeaves(),
                        context.palette().air(),
                        context.palette().air());
        return writes;
    }

    static int writeWorld(
            GenerationRegion region,
            long worldX,
            int y,
            long worldZ,
            byte block,
            byte air,
            byte replaceable) {
        long localXValue =
                worldX - region.worldOriginX();
        long localZValue =
                worldZ - region.worldOriginZ();
        if (localXValue < 0
                || localXValue >= GameConfig.Chunk.SIZE
                || localZValue < 0
                || localZValue >= GameConfig.Chunk.SIZE
                || y < 0
                || y >= region.worldHeight()) {
            return 0;
        }
        int localX = (int) localXValue;
        int localZ = (int) localZValue;
        byte current = region.getBlock(localX, y, localZ);
        if (current != air && current != replaceable) {
            return 0;
        }
        region.writeBlock(localX, y, localZ, block);
        return 1;
    }

    private static boolean insideSpawnReserve(
            long x, long z) {
        return x > -SPAWN_RESERVE_RADIUS
                && x < SPAWN_RESERVE_RADIUS
                && z > -SPAWN_RESERVE_RADIUS
                && z < SPAWN_RESERVE_RADIUS
                && (long) x * x + (long) z * z
                        < (long) SPAWN_RESERVE_RADIUS
                                * SPAWN_RESERVE_RADIUS;
    }

    private record Tree(
            long x, int y, long z, int trunkHeight) {}
}
