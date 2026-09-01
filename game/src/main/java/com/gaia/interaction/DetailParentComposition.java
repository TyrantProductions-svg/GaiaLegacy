package com.gaia.interaction;

import com.gaia.blocks.BlockDefinition;
import com.gaia.blocks.BlockRegistry;
import com.overlord.voxel.DetailCellState;
import java.util.Objects;
import java.util.Optional;

public record DetailParentComposition(
        int occupiedCount,
        BlockDefinition hardestMaterial,
        Optional<BlockDefinition> uniformMaterial,
        boolean fullCompatible) {
    public DetailParentComposition {
        if (occupiedCount < 1 || occupiedCount > DetailCellState.CELL_COUNT) {
            throw new IllegalArgumentException("occupiedCount must be within 1..64");
        }
        Objects.requireNonNull(hardestMaterial, "hardestMaterial");
        uniformMaterial = Objects.requireNonNull(uniformMaterial, "uniformMaterial");
        if (fullCompatible
                && (occupiedCount != DetailCellState.CELL_COUNT
                        || uniformMaterial.isEmpty()
                        || uniformMaterial.orElseThrow().item() == null)) {
            throw new IllegalArgumentException(
                    "full-compatible composition must be uniform 64/64 with a block item");
        }
    }

    public static DetailParentComposition from(
            DetailCellState detail, BlockRegistry registry) {
        Objects.requireNonNull(detail, "detail");
        Objects.requireNonNull(registry, "registry");

        int occupied = 0;
        BlockDefinition hardest = null;
        BlockDefinition first = null;
        boolean uniform = true;
        for (int index = 0; index < DetailCellState.CELL_COUNT; index++) {
            byte runtimeId = detail.blockIdAtIndex(index);
            if (runtimeId == 0) {
                continue;
            }
            BlockDefinition material;
            try {
                material = registry.require(runtimeId);
            } catch (IllegalArgumentException failure) {
                throw new IllegalArgumentException(
                        "Unknown occupied detail material id "
                                + Byte.toUnsignedInt(runtimeId)
                                + " at index "
                                + index,
                        failure);
            }
            occupied++;
            if (first == null) {
                first = material;
            } else if (!first.name().equals(material.name())) {
                uniform = false;
            }
            if (hardest == null || material.hardness() > hardest.hardness()) {
                hardest = material;
            }
        }
        if (occupied == 0 || hardest == null || first == null) {
            throw new IllegalArgumentException(
                    "DETAIL composition must contain an occupied material");
        }
        Optional<BlockDefinition> uniformMaterial =
                uniform ? Optional.of(first) : Optional.empty();
        boolean fullCompatible =
                occupied == DetailCellState.CELL_COUNT
                        && uniformMaterial.isPresent()
                        && first.item() != null;
        return new DetailParentComposition(
                occupied,
                hardest,
                uniformMaterial,
                fullCompatible);
    }

    public static DetailParentComposition fromSupported(
            DetailCellState detail, BlockRegistry registry) {
        DetailParentComposition composition = from(detail, registry);
        for (int index = 0; index < DetailCellState.CELL_COUNT; index++) {
            byte runtimeId = detail.blockIdAtIndex(index);
            if (runtimeId == 0) {
                continue;
            }
            BlockDefinition material = registry.require(runtimeId);
            if (registry.detailUnitForBlock(material.name()).isEmpty()) {
                throw new IllegalArgumentException(
                        "unsupported occupied detail material " + material.name());
            }
        }
        return composition;
    }
}
