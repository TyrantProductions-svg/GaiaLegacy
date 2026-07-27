package com.gaia.world.generation;

import com.overlord.assets.ResourceLocation;
import com.overlord.config.GameConfig;
import com.overlord.voxel.BlockSize;
import java.util.Optional;

public final class AdaptiveSubdivider implements WorldGenerationStage {
    private static final ResourceLocation ID =
            ResourceLocation.parse("gaia:adaptive_subdivision");

    private static final long TERRAIN_SALT = 0L;
    private static final long CAVE_SALT = 1L;

    private static final double MIN_BLOCK_SIZE = 0.125; // SIZE_2 minimum

    private static final int CAVE_AIR_THRESHOLD = 3;

    @Override
    public ResourceLocation id() {
        return ID;
    }

    @Override
    public GenerationStageResult generate(
            GenerationContext context,
            GenerationRegion region) {
        int samples = 0;
        int writes = 0;

        writes += applyTerrainFractionalFill(context, region);
        samples += GameConfig.Chunk.SIZE * GameConfig.Chunk.SIZE;

        writes += applySlopeGapFill(context, region);
        samples += GameConfig.Chunk.SIZE * GameConfig.Chunk.SIZE * 4;

        writes += applyCaveSurfaceFill(context, region);

        return succeeded(samples, writes);
    }

    private int applyTerrainFractionalFill(
            GenerationContext context,
            GenerationRegion region) {
        int writes = 0;
        byte air = context.palette().air();

        for (int localZ = 0; localZ < GameConfig.Chunk.SIZE; localZ++) {
            for (int localX = 0; localX < GameConfig.Chunk.SIZE; localX++) {
                int worldX = region.worldX(localX);
                int worldZ = region.worldZ(localZ);

                double rawHeight = sampleRawHeight(context, worldX, worldZ, region.getBiome(localX, localZ));
                int intHeight = (int) Math.floor(rawHeight);
                double fraction = rawHeight - intHeight;

                if (fraction < MIN_BLOCK_SIZE) {
                    continue;
                }

                int currentHeight = region.getHeight(localX, localZ);
                if (intHeight != currentHeight) {
                    continue;
                }

                double remaining = fraction;
                int placeY = currentHeight;

                while (remaining >= MIN_BLOCK_SIZE && placeY < region.worldHeight()) {
                    if (remaining >= 0.5) {
                        placeBlock(region, localX, placeY, localZ, BlockSize.SIZE_8, air);
                        remaining -= 0.5;
                        placeY++;
                        writes++;
                    } else if (remaining >= 0.25) {
                        placeBlock(region, localX, placeY, localZ, BlockSize.SIZE_4, air);
                        remaining -= 0.25;
                        placeY++;
                        writes++;
                    } else if (remaining >= 0.125) {
                        placeBlock(region, localX, placeY, localZ, BlockSize.SIZE_2, air);
                        remaining -= 0.125;
                        placeY++;
                        writes++;
                    } else {
                        break;
                    }
                }
            }
        }
        return writes;
    }

    private int applySlopeGapFill(
            GenerationContext context,
            GenerationRegion region) {
        int writes = 0;
        byte air = context.palette().air();

        for (int localZ = 0; localZ < GameConfig.Chunk.SIZE; localZ++) {
            for (int localX = 0; localX < GameConfig.Chunk.SIZE; localX++) {
                int height = region.getHeight(localX, localZ);

                if (localX + 1 < GameConfig.Chunk.SIZE) {
                    int rightHeight = region.getHeight(localX + 1, localZ);
                    writes += fillSlopeGap(region, localX, localZ, height, localX + 1, localZ, rightHeight, air);
                }
                if (localZ + 1 < GameConfig.Chunk.SIZE) {
                    int forwardHeight = region.getHeight(localX, localZ + 1);
                    writes += fillSlopeGap(region, localX, localZ, height, localX, localZ + 1, forwardHeight, air);
                }
            }
        }
        return writes;
    }

    private int fillSlopeGap(
            GenerationRegion region,
            int x1, int z1, int height1,
            int x2, int z2, int height2,
            byte air) {
        int writes = 0;
        int diff = Math.abs(height1 - height2);

        if (diff <= 1) {
            return 0;
        }

        int lowerX = Math.min(x1, x2);
        int lowerZ = Math.min(z1, z2);
        int higherX = Math.max(x1, x2);
        int higherZ = Math.max(z1, z2);
        int lowerHeight = Math.min(height1, height2);
        int higherHeight = Math.max(height1, height2);

        int steps = higherHeight - lowerHeight;
        for (int step = 1; step < steps; step++) {
            double t = (double) step / steps;
            int gapY = lowerHeight + step;

            if (gapY >= region.worldHeight()) {
                break;
            }

            double gapSize = 1.0 - t;
            if (gapSize >= 0.5) {
                placeBlock(region, lowerX, gapY, lowerZ, BlockSize.SIZE_8, air);
                writes++;
            } else if (gapSize >= 0.25) {
                placeBlock(region, lowerX, gapY, lowerZ, BlockSize.SIZE_4, air);
                writes++;
            } else if (gapSize >= 0.125) {
                placeBlock(region, lowerX, gapY, lowerZ, BlockSize.SIZE_2, air);
                writes++;
            }
        }

        return writes;
    }

    private int applyCaveSurfaceFill(
            GenerationContext context,
            GenerationRegion region) {
        int writes = 0;
        byte air = context.palette().air();

        for (int localZ = 0; localZ < GameConfig.Chunk.SIZE; localZ++) {
            for (int y = 0; y < region.worldHeight(); y++) {
                for (int localX = 0; localX < GameConfig.Chunk.SIZE; localX++) {
                    byte block = region.getBlock(localX, y, localZ);
                    if (block == air) {
                        continue;
                    }

                    BlockSize currentSize = region.getBlockSize(localX, y, localZ);
                    if (currentSize != BlockSize.SIZE_16) {
                        continue;
                    }

                    if (!isCaveSurface(region, localX, y, localZ, air)) {
                        continue;
                    }

                    int airNeighbors = countAirNeighbors(region, localX, y, localZ, air);
                    if (airNeighbors >= CAVE_AIR_THRESHOLD) {
                        BlockSize caveSize = selectSizeForCaveExposure(airNeighbors);
                        if (caveSize != BlockSize.SIZE_16) {
                            region.writeBlockSize(localX, y, localZ, caveSize);
                            writes++;
                        }
                    }
                }
            }
        }
        return writes;
    }

    private double sampleRawHeight(
            GenerationContext context,
            int worldX,
            int worldZ,
            BiomeSample biome) {
        WorldGenerationConfig.HeightSettings settings = context.config().height();
        double detail = centeredNoise(context, worldX, worldZ, settings.detailScale(), TERRAIN_SALT);
        double ruggedness = centeredNoise(context, worldX, worldZ, settings.detailScale(), TERRAIN_SALT + 1);
        double plains = settings.plainsBase() + settings.plainsVariation() * detail;
        double hills = settings.hillsBase() + settings.hillsVariation() * (0.7 * detail + 0.3 * ruggedness);
        double highlands = settings.highlandsBase() + settings.highlandsVariation() * (0.35 * detail + 0.65 * ruggedness);
        double blended = biome.plains() * plains + biome.rollingHills() * hills + biome.rockyHighlands() * highlands;
        return Math.max(settings.minimumSurfaceHeight(), Math.min(settings.maximumSurfaceHeight(), blended));
    }

    private double centeredNoise(
            GenerationContext context,
            int worldX,
            int worldZ,
            double scale,
            long salt) {
        return context.sampler().valueNoise2D(ID, worldX, worldZ, scale, salt) * 2.0 - 1.0;
    }

    private void placeBlock(
            GenerationRegion region,
            int localX,
            int y,
            int localZ,
            BlockSize size,
            byte air) {
        if (y < 0 || y >= region.worldHeight()) {
            return;
        }

        byte existing = region.getBlock(localX, y, localZ);
        if (existing == air) {
            region.writeBlock(localX, y, localZ, region.getBlock(localX, Math.max(0, y - 1), localZ));
        }
        region.writeBlockSize(localX, y, localZ, size);
    }

    private BlockSize selectSizeForCaveExposure(int airNeighbors) {
        if (airNeighbors >= 5) {
            return BlockSize.SIZE_2;
        } else if (airNeighbors >= 4) {
            return BlockSize.SIZE_4;
        } else {
            return BlockSize.SIZE_8;
        }
    }

    private boolean isCaveSurface(
            GenerationRegion region,
            int localX,
            int y,
            int localZ,
            byte air) {
        if (y + 1 < region.worldHeight() && region.getBlock(localX, y + 1, localZ) == air) {
            return true;
        }
        if (y > 0 && region.getBlock(localX, y - 1, localZ) == air) {
            return true;
        }
        if (localX > 0 && region.getBlock(localX - 1, y, localZ) == air) {
            return true;
        }
        if (localX + 1 < GameConfig.Chunk.SIZE && region.getBlock(localX + 1, y, localZ) == air) {
            return true;
        }
        if (localZ > 0 && region.getBlock(localX, y, localZ - 1) == air) {
            return true;
        }
        if (localZ + 1 < GameConfig.Chunk.SIZE && region.getBlock(localX, y, localZ + 1) == air) {
            return true;
        }
        return false;
    }

    private int countAirNeighbors(
            GenerationRegion region,
            int localX,
            int y,
            int localZ,
            byte air) {
        int count = 0;
        if (y + 1 < region.worldHeight() && region.getBlock(localX, y + 1, localZ) == air) count++;
        if (y > 0 && region.getBlock(localX, y - 1, localZ) == air) count++;
        if (localX > 0 && region.getBlock(localX - 1, y, localZ) == air) count++;
        if (localX + 1 < GameConfig.Chunk.SIZE && region.getBlock(localX + 1, y, localZ) == air) count++;
        if (localZ > 0 && region.getBlock(localX, y, localZ - 1) == air) count++;
        if (localZ + 1 < GameConfig.Chunk.SIZE && region.getBlock(localX, y, localZ + 1) == air) count++;
        return count;
    }

    private GenerationStageResult succeeded(int samples, int writes) {
        return new GenerationStageResult(
                id(),
                GenerationStageResult.Status.SUCCEEDED,
                samples,
                writes,
                Optional.empty());
    }
}