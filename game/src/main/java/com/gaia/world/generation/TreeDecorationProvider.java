package com.gaia.world.generation;

import com.overlord.assets.ResourceLocation;
import com.overlord.config.GameConfig;

public final class TreeDecorationProvider {
    private static final ResourceLocation ID =
            ResourceLocation.parse("gaia:tree_decoration");
    private static final int CELL_SIZE = 8;
    private static final int CANOPY_RADIUS = 2;
    private static final int SPAWN_RESERVE_RADIUS = 12;

    public int generate(
            GenerationContext context, GenerationRegion region) {
        int writes = 0;
        int minimumCellX =
                cellCoordinate(
                        (long) region.key().worldOriginX()
                                - CANOPY_RADIUS);
        int maximumCellX =
                cellCoordinate(
                        (long) region.key().worldOriginX()
                                + GameConfig.Chunk.SIZE
                                - 1
                                + CANOPY_RADIUS);
        int minimumCellZ =
                cellCoordinate(
                        (long) region.key().worldOriginZ()
                                - CANOPY_RADIUS);
        int maximumCellZ =
                cellCoordinate(
                        (long) region.key().worldOriginZ()
                                + GameConfig.Chunk.SIZE
                                - 1
                                + CANOPY_RADIUS);
        for (int cellZ = minimumCellZ;
                cellZ <= maximumCellZ;
                cellZ++) {
            for (int cellX = minimumCellX;
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
            GenerationContext context, int worldX, int worldZ) {
        int cellX = Math.floorDiv(worldX, CELL_SIZE);
        int cellZ = Math.floorDiv(worldZ, CELL_SIZE);
        for (int z = cellZ - 1; z <= cellZ + 1; z++) {
            for (int x = cellX - 1; x <= cellX + 1; x++) {
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

    private static Tree tree(
            GenerationContext context, int cellX, int cellZ) {
        Integer rootX =
                rootCoordinate(
                        cellX,
                        context,
                        cellX,
                        cellZ,
                        0L);
        Integer rootZ =
                rootCoordinate(
                        cellZ,
                        context,
                        cellX,
                        cellZ,
                        1L);
        if (rootX == null
                || rootZ == null
                || insideSpawnReserve(rootX, rootZ)
                || HybridCaveProvider.hasEntranceNear(
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
                                ID, cellX, 0, cellZ, 2L)
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
            Integer x = addIfRepresentable(rootX, offset[0]);
            Integer z = addIfRepresentable(rootZ, offset[1]);
            if (x == null || z == null) {
                continue;
            }
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
                        ID, cellX, 0, cellZ, 3L);
        for (int neighborZ = cellZ - 1;
                neighborZ <= cellZ + 1;
                neighborZ++) {
            for (int neighborX = cellX - 1;
                    neighborX <= cellX + 1;
                    neighborX++) {
                if (neighborX == cellX
                        && neighborZ == cellZ) {
                    continue;
                }
                Integer otherX =
                        rootCoordinate(
                                neighborX,
                                context,
                                neighborX,
                                neighborZ,
                                0L);
                Integer otherZ =
                        rootCoordinate(
                                neighborZ,
                                context,
                                neighborX,
                                neighborZ,
                                1L);
                if (otherX == null || otherZ == null) {
                    continue;
                }
                long dx = (long) otherX - rootX;
                long dz = (long) otherZ - rootZ;
                if (dx * dx + dz * dz >= 36L) {
                    continue;
                }
                double otherPriority =
                        context.sampler().unit(
                                ID,
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
                                                ID,
                                                cellX,
                                                0,
                                                cellZ,
                                                4L)
                                        * 4.0);
        return new Tree(rootX, rootY, rootZ, trunkHeight);
    }

    private static int place(
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
                            true);
        }
        int canopyY = tree.y() + tree.trunkHeight() - 1;
        for (int dy = -1; dy <= 1; dy++) {
            int radius = dy == 1 ? 1 : CANOPY_RADIUS;
            for (int dz = -radius; dz <= radius; dz++) {
                for (int dx = -radius; dx <= radius; dx++) {
                    Integer leafX =
                            addIfRepresentable(tree.x(), dx);
                    Integer leafZ =
                            addIfRepresentable(tree.z(), dz);
                    if (leafX == null || leafZ == null) {
                        continue;
                    }
                    if (Math.abs(dx) == radius
                            && Math.abs(dz) == radius
                            && context.sampler().unit(
                                            ID,
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
                                    false);
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
                        false);
        return writes;
    }

    private static int writeWorld(
            GenerationRegion region,
            int worldX,
            int y,
            int worldZ,
            byte block,
            byte air,
            boolean replaceLeaves) {
        int originX = region.key().worldOriginX();
        int originZ = region.key().worldOriginZ();
        long localXValue = (long) worldX - originX;
        long localZValue = (long) worldZ - originZ;
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
        if (current != air
                && !(replaceLeaves
                        && current == block)) {
            return 0;
        }
        region.writeBlock(localX, y, localZ, block);
        return 1;
    }

    private static int offset(
            GenerationContext context,
            int cellX,
            int cellZ,
            long salt) {
        return (int)
                (context.sampler().unit(
                                ID, cellX, 0, cellZ, salt)
                        * CELL_SIZE);
    }

    private static int cellCoordinate(long worldCoordinate) {
        return Math.toIntExact(
                Math.floorDiv(worldCoordinate, CELL_SIZE));
    }

    private static Integer rootCoordinate(
            int baseCell,
            GenerationContext context,
            int sampleCellX,
            int sampleCellZ,
            long salt) {
        long value =
                (long) baseCell * CELL_SIZE
                        + offset(
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

    private static boolean insideSpawnReserve(
            int x, int z) {
        return x > -SPAWN_RESERVE_RADIUS
                && x < SPAWN_RESERVE_RADIUS
                && z > -SPAWN_RESERVE_RADIUS
                && z < SPAWN_RESERVE_RADIUS
                && (long) x * x + (long) z * z
                        < (long) SPAWN_RESERVE_RADIUS
                                * SPAWN_RESERVE_RADIUS;
    }

    private record Tree(
            int x, int y, int z, int trunkHeight) {}
}
