package com.gaia.world.generation;

import com.overlord.assets.ResourceLocation;
import com.overlord.config.GameConfig;
import java.util.Optional;

public final class HybridCaveProvider implements CaveProvider {
    private static final ResourceLocation ID =
            ResourceLocation.parse("gaia:cave");
    private static final ResourceLocation TUNNEL_ID =
            ResourceLocation.parse("gaia:cave_tunnel");
    private static final int ENTRANCE_CELL_SIZE = 32;
    private static final int MAXIMUM_REACH = 96;
    private static final int ENTRANCE_STEPS = 12;
    private static final GenerationStageContract CONTRACT =
            new GenerationStageContract(
                    ID, 1, MAXIMUM_REACH);
    private final GenerationStageContract contract;
    private final GenerationStageContract tunnelContract;
    private final EntranceQuery entranceQuery;

    public HybridCaveProvider() {
        this(CONTRACT);
    }

    public HybridCaveProvider(
            GenerationStageContract contract) {
        if (!ID.equals(contract.id())) {
            throw new IllegalArgumentException(
                    "Hybrid cave contract must use " + ID);
        }
        this.contract = contract;
        this.tunnelContract =
                contract.child(
                        TUNNEL_ID, contract.haloRadius());
        this.entranceQuery = new EntranceQuery(this);
    }

    @Override
    public ResourceLocation id() {
        return ID;
    }

    @Override
    public GenerationStageContract contract() {
        return contract;
    }

    public EntranceQuery entranceQuery() {
        return entranceQuery;
    }

    @Override
    public GenerationStageResult generate(
            GenerationContext context, GenerationRegion region) {
        int samples = 0;
        int writes = 0;
        for (int z = 0; z < GameConfig.Chunk.SIZE; z++) {
            long worldZ = region.worldZLong(z);
            for (int x = 0; x < GameConfig.Chunk.SIZE; x++) {
                long worldX = region.worldXLong(x);
                int surface = region.getHeight(x, z);
                for (int y = context.config().cave().bedrockDepth();
                        y
                                <= surface
                                        - context.config()
                                                .cave()
                                                .surfaceBuffer();
                        y++) {
                    double low =
                            context.sampler().valueNoise3D(
                                    contract,
                                    worldX,
                                    y,
                                    worldZ,
                                    context.config().cave().scale()
                                            * (5.0 / 9.0),
                                    10L);
                    double high =
                            context.sampler().valueNoise3D(
                                    contract,
                                    worldX,
                                    y,
                                    worldZ,
                                    context.config().cave().scale()
                                            * (14.0 / 9.0),
                                    11L);
                    double depth =
                            Math.min(0.08, (surface - y) * 0.002);
                    samples++;
                    if (0.72 * low + 0.28 * high + depth
                                    >= context.config()
                                            .cave()
                                            .threshold()
                            && region.getBlock(x, y, z)
                                    != context.palette().air()) {
                        region.writeBlock(
                                x, y, z, context.palette().air());
                        writes++;
                    }
                }
            }
        }
        writes += carveTunnelSystems(context, region);
        return new GenerationStageResult(
                id(),
                GenerationStageResult.Status.SUCCEEDED,
                samples,
                writes,
                Optional.empty());
    }

    private int carveTunnelSystems(
            GenerationContext context, GenerationRegion region) {
        GenerationStageContract.RegionRange xRange =
                contract.regionsForChunk(
                        region.worldOriginX(),
                        GameConfig.Chunk.SIZE,
                        ENTRANCE_CELL_SIZE);
        GenerationStageContract.RegionRange zRange =
                contract.regionsForChunk(
                        region.worldOriginZ(),
                        GameConfig.Chunk.SIZE,
                        ENTRANCE_CELL_SIZE);
        long minimumCellX = xRange.minimum();
        long maximumCellX = xRange.maximum();
        long minimumCellZ = zRange.minimum();
        long maximumCellZ = zRange.maximum();
        int writes = 0;
        for (long cellZ = minimumCellZ;
                cellZ <= maximumCellZ;
                cellZ++) {
            for (long cellX = minimumCellX;
                    cellX <= maximumCellX;
                    cellX++) {
                Tunnel tunnel = tunnel(context, cellX, cellZ);
                if (tunnel != null) {
                    writes += carve(context, region, tunnel);
                }
            }
        }
        return writes;
    }

    private Tunnel tunnel(
            GenerationContext context, long cellX, long cellZ) {
        Optional<StableRegionAnchor> sampled =
                StableRegionAnchor.sampleIfSafe(
                        context.sampler(),
                        tunnelContract,
                        cellX,
                        cellZ,
                        ENTRANCE_CELL_SIZE);
        if (sampled.isEmpty()) {
            return null;
        }
        StableRegionAnchor anchor = sampled.orElseThrow();
        if (insideOriginReserve(
                anchor.worldX(), anchor.worldZ())) {
            return null;
        }
        long rootX = anchor.worldX();
        long rootZ = anchor.worldZ();
        BiomeProvider biomes = new ContinuousBiomeProvider();
        BiomeSample biome =
                biomes.sample(context, rootX, rootZ);
        double probability =
                0.05 * biome.plains()
                        + 0.24 * biome.rollingHills()
                        + 0.34 * biome.rockyHighlands();
        if (context.sampler().unit(
                                tunnelContract,
                                cellX,
                                0L,
                                cellZ,
                                2L)
                        >= probability) {
            return null;
        }
        int surface =
                new BiomeShapedHeightProvider()
                        .sampleHeight(
                                context, rootX, rootZ, biome);
        double angle =
                context.sampler().unit(
                                tunnelContract,
                                cellX,
                                0L,
                                cellZ,
                                3L)
                        * StrictMath.PI
                        * 2.0;
        int length =
                56
                        + (int)
                                (context.sampler().unit(
                                                tunnelContract,
                                                cellX,
                                                0L,
                                                cellZ,
                                                4L)
                                        * 33.0);
        double drop =
                0.20
                        + context.sampler().unit(
                                        tunnelContract,
                                        cellX,
                                        0L,
                                        cellZ,
                                        5L)
                                * 0.12;
        return new Tunnel(
                rootX, surface, rootZ, angle, length, drop);
    }

    private boolean hasEntranceNearInternal(
            GenerationContext context,
            long worldX,
            long worldZ,
            int radius) {
        long cellX = Math.floorDiv(worldX, ENTRANCE_CELL_SIZE);
        long cellZ = Math.floorDiv(worldZ, ENTRANCE_CELL_SIZE);
        long radiusSquared = (long) radius * radius;
        int cellReach =
                Math.floorDiv(
                                contract.haloRadius()
                                        + radius
                                        + ENTRANCE_CELL_SIZE
                                        - 1,
                                ENTRANCE_CELL_SIZE);
        for (long z = cellZ - cellReach;
                z <= cellZ + cellReach;
                z++) {
            for (long x = cellX - cellReach;
                    x <= cellX + cellReach;
                    x++) {
                Tunnel tunnel = tunnel(context, x, z);
                if (tunnel == null) {
                    continue;
                }
                for (int step = 0;
                        step
                                < Math.min(
                                        ENTRANCE_STEPS,
                                        tunnel.length());
                        step++) {
                    double bend =
                            (context.sampler().valueNoise2D(
                                                    tunnelContract,
                                                    saturatedAdd(
                                                            tunnel.x(), step),
                                                    saturatedAdd(
                                                            tunnel.z(), -step),
                                                    0.035,
                                                    20L)
                                            - 0.5)
                                    * 1.1;
                    double angle = tunnel.angle() + bend;
                    double centerX =
                            tunnel.x()
                                    + StrictMath.cos(angle) * step;
                    double centerZ =
                            tunnel.z()
                                    + StrictMath.sin(angle) * step;
                    double dx = centerX - worldX;
                    double dz = centerZ - worldZ;
                    if (dx * dx + dz * dz <= radiusSquared) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private int carve(
            GenerationContext context,
            GenerationRegion region,
            Tunnel tunnel) {
        int writes = 0;
        for (int step = 0; step < tunnel.length(); step++) {
            double bend =
                    (context.sampler().valueNoise2D(
                                            tunnelContract,
                                            saturatedAdd(
                                                    tunnel.x(), step),
                                            saturatedAdd(
                                                    tunnel.z(), -step),
                                            0.035,
                                            20L)
                                    - 0.5)
                            * 1.1;
            double angle = tunnel.angle() + bend;
            double centerX =
                    tunnel.x()
                            + StrictMath.cos(angle) * step;
            double centerZ =
                    tunnel.z()
                            + StrictMath.sin(angle) * step;
            double centerY =
                    tunnel.surfaceY()
                            - 0.5
                            - tunnel.drop() * step
                            + StrictMath.sin(step * 0.17) * 1.25;
            double radius =
                    step < 5
                            ? 1.8 + step * 0.16
                            : 2.35
                                    + context.sampler().unit(
                                                    tunnelContract,
                                                    tunnel.x(),
                                                    step,
                                                    tunnel.z(),
                                                    21L)
                                            * 0.45;
            writes +=
                    carveSphere(
                            context,
                            region,
                            centerX,
                            centerY,
                            centerZ,
                            radius,
                            step);
        }
        return writes;
    }

    private static int carveSphere(
            GenerationContext context,
            GenerationRegion region,
            double centerX,
            double centerY,
            double centerZ,
            double radius,
            int tunnelStep) {
        int writes = 0;
        long minimumX =
                Math.max(
                        Integer.MIN_VALUE,
                        (long) StrictMath.floor(centerX - radius));
        long maximumX =
                Math.min(
                        Integer.MAX_VALUE,
                        (long) StrictMath.ceil(centerX + radius));
        int minimumY = (int) StrictMath.floor(centerY - radius);
        int maximumY = (int) StrictMath.ceil(centerY + radius);
        long minimumZ =
                Math.max(
                        Integer.MIN_VALUE,
                        (long) StrictMath.floor(centerZ - radius));
        long maximumZ =
                Math.min(
                        Integer.MAX_VALUE,
                        (long) StrictMath.ceil(centerZ + radius));
        double radiusSquared = radius * radius;
        for (long worldZValue = minimumZ;
                worldZValue <= maximumZ;
                worldZValue++) {
            int worldZ = (int) worldZValue;
            long localZValue =
                    worldZValue
                            - region.worldOriginZ();
            if (localZValue < 0
                    || localZValue >= GameConfig.Chunk.SIZE) {
                continue;
            }
            int localZ = (int) localZValue;
            for (long worldXValue = minimumX;
                    worldXValue <= maximumX;
                    worldXValue++) {
                long localXValue =
                        worldXValue
                                - region.worldOriginX();
                if (localXValue < 0
                        || localXValue >= GameConfig.Chunk.SIZE) {
                    continue;
                }
                int localX = (int) localXValue;
                for (int y = minimumY; y <= maximumY; y++) {
                    if (y < context.config().cave().bedrockDepth()
                            || y >= region.worldHeight()) {
                        continue;
                    }
                    if (!tunnelCellAllowed(
                            context,
                            worldXValue,
                            y,
                            worldZ,
                            tunnelStep)) {
                        continue;
                    }
                    double dx = worldXValue + 0.5 - centerX;
                    double dy = y + 0.5 - centerY;
                    double dz = worldZ + 0.5 - centerZ;
                    if (dx * dx + dy * dy + dz * dz
                                    <= radiusSquared
                            && region.getBlock(localX, y, localZ)
                                    != context.palette().air()) {
                        region.writeBlock(
                                localX,
                                y,
                                localZ,
                                context.palette().air());
                        writes++;
                    }
                }
            }
        }
        return writes;
    }

    static boolean tunnelCellAllowed(
            GenerationContext context,
            long worldX,
            int y,
            long worldZ,
            int tunnelStep) {
        if (tunnelStep < ENTRANCE_STEPS) {
            return true;
        }
        BiomeProvider biomes = new ContinuousBiomeProvider();
        int surface =
                new BiomeShapedHeightProvider()
                        .sampleHeight(
                                context,
                                worldX,
                                worldZ,
                                biomes.sample(
                                        context, worldX, worldZ));
        return y
                <= surface
                        - context.config()
                                .cave()
                                .surfaceBuffer();
    }

    private static boolean insideOriginReserve(
            long x, long z) {
        return x > -12
                && x < 12
                && z > -12
                && z < 12
                && x * x + z * z < 144L;
    }

    private static long saturatedAdd(long value, int delta) {
        return Math.addExact(value, delta);
    }

    private record Tunnel(
            long x,
            int surfaceY,
            long z,
            double angle,
            int length,
            double drop) {}

    public static final class EntranceQuery {
        private final HybridCaveProvider provider;

        private EntranceQuery(HybridCaveProvider provider) {
            this.provider = provider;
        }

        public boolean hasEntranceNear(
                GenerationContext context,
                long worldX,
                long worldZ,
                int radius) {
            return provider.hasEntranceNearInternal(
                    context, worldX, worldZ, radius);
        }
    }
}
