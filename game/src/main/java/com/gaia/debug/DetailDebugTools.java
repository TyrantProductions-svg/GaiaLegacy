package com.gaia.debug;

import com.gaia.blocks.BlockRegistry;
import com.overlord.assets.ResourceLocation;
import com.overlord.interaction.api.DetailMutationRequest;
import com.overlord.interaction.api.DetailMutationResult;
import com.overlord.interaction.api.DetailMutationService;
import com.overlord.interaction.api.FullToDetailRequest;
import com.overlord.interaction.api.InteractionContext;
import com.overlord.physics.BlockCollisionShapeResolver;
import com.overlord.physics.DetailCollisionBoxMerger;
import com.overlord.voxel.ChunkAvailability;
import com.overlord.voxel.ChunkKey;
import com.overlord.voxel.DetailCellState;
import com.overlord.voxel.FullCellState;
import com.overlord.voxel.LocalSubVoxelPosition;
import com.overlord.voxel.ParentCellObservation;
import com.overlord.voxel.ParentCellObservationResult;
import com.overlord.voxel.ParentCellState;
import com.overlord.voxel.World;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/** Bounded development adapter over typed observation and canonical mutation services. */
public final class DetailDebugTools {
    private final World world;
    private final BlockRegistry blocks;
    private final DetailMutationService mutations;
    private final BlockCollisionShapeResolver collisionShapes;
    private final DetailCollisionBoxMerger detailMerger;
    private final MeshDiagnosticSource meshDiagnostics;

    public DetailDebugTools(
            World world,
            BlockRegistry blocks,
            DetailMutationService mutations,
            BlockCollisionShapeResolver collisionShapes,
            DetailCollisionBoxMerger detailMerger) {
        this(
                world,
                blocks,
                mutations,
                collisionShapes,
                detailMerger,
                (key, revision) -> MeshStatus.none());
    }

    public DetailDebugTools(
            World world,
            BlockRegistry blocks,
            DetailMutationService mutations,
            BlockCollisionShapeResolver collisionShapes,
            DetailCollisionBoxMerger detailMerger,
            MeshDiagnosticSource meshDiagnostics) {
        this.world = Objects.requireNonNull(world, "world");
        this.blocks = Objects.requireNonNull(blocks, "blocks");
        this.mutations = Objects.requireNonNull(mutations, "mutations");
        this.collisionShapes = Objects.requireNonNull(collisionShapes, "collisionShapes");
        this.detailMerger = Objects.requireNonNull(detailMerger, "detailMerger");
        this.meshDiagnostics = Objects.requireNonNull(meshDiagnostics, "meshDiagnostics");
    }

    public Selection inspect(
            int x,
            int y,
            int z,
            LocalSubVoxelPosition selected) {
        Objects.requireNonNull(selected, "selected");
        ParentCellObservationResult result = world.observeCell(x, y, z);
        if (result.status() != ChunkAvailability.AVAILABLE) {
            throw new IllegalStateException(
                    "Cannot inspect " + result.status() + " Chunk "
                            + result.unavailableKey().orElseThrow());
        }
        ParentCellObservation observation = result.observation()
                .orElseThrow(() -> new IllegalStateException(
                        "Canonical parent coordinate is outside the resident world height"));
        ParentCellState state = observation.state();
        long occupancy = state instanceof DetailCellState detail
                ? detail.occupancyMask()
                : 0L;
        int occupied = state instanceof DetailCellState detail
                ? Long.bitCount(detail.occupancyMask())
                : (((FullCellState) state).blockId() == 0 ? 0 : 64);
        byte selectedId = state instanceof DetailCellState detail
                ? detail.blockId(selected)
                : ((FullCellState) state).blockId();
        Optional<ResourceLocation> selectedMaterial = selectedId == 0
                ? Optional.empty()
                : Optional.of(blocks.require(selectedId).name());
        String hash = state instanceof DetailCellState detail
                ? DetailFixturePattern.canonicalHash(detail)
                : String.format(
                        Locale.ROOT,
                        "FULL-%02X",
                        Byte.toUnsignedInt(((FullCellState) state).blockId()));
        int collisionBoxCount = collisionShapes
                .shapeFor(state, detailMerger)
                .boxes()
                .size();
        return new Selection(
                observation.worldX(),
                observation.y(),
                observation.worldZ(),
                observation.chunkKey(),
                observation.chunkRevision(),
                state,
                selected,
                occupancy,
                hash,
                selectedMaterial,
                occupied,
                collisionBoxCount,
                Objects.requireNonNull(
                        meshDiagnostics.inspect(
                                observation.chunkKey(), observation.chunkRevision()),
                        "mesh diagnostic"));
    }

    public DetailMutationResult convert(
            Selection selection, InteractionContext context) {
        Selection required = Objects.requireNonNull(selection, "selection");
        FullCellState full = requireFull(required.state());
        ResourceLocation material = blocks.require(full.blockId()).name();
        return mutations.convertFullToDetail(new FullToDetailRequest(
                Objects.requireNonNull(context, "context"),
                required.x(),
                required.y(),
                required.z(),
                required.chunkRevision(),
                material));
    }

    public DetailMutationResult fill(
            Selection selection,
            InteractionContext context,
            ResourceLocation material) {
        return set(
                selection,
                context,
                Optional.of(Objects.requireNonNull(material, "material")));
    }

    public DetailMutationResult clear(
            Selection selection, InteractionContext context) {
        return set(selection, context, Optional.empty());
    }

    public FixtureApplication applyFixture(
            int x,
            int y,
            int z,
            DetailFixturePattern pattern,
            ResourceLocation primaryMaterial,
            ResourceLocation secondaryMaterial,
            InteractionContext context) {
        DetailFixturePattern requiredPattern = Objects.requireNonNull(pattern, "pattern");
        InteractionContext requiredContext = Objects.requireNonNull(context, "context");
        byte primaryId = blocks.requireStoredId(
                Objects.requireNonNull(primaryMaterial, "primaryMaterial"));
        byte secondaryId = blocks.requireStoredId(
                Objects.requireNonNull(secondaryMaterial, "secondaryMaterial"));
        DetailCellState desired = requiredPattern.state(primaryId, secondaryId);
        List<DetailMutationResult> results = new ArrayList<>(65);

        Selection initial = inspect(x, y, z, LocalSubVoxelPosition.fromIndex(0));
        if (initial.state() instanceof FullCellState full && full.blockId() != 0) {
            DetailMutationResult converted = convert(initial, requiredContext);
            results.add(converted);
            if (converted.status() != DetailMutationResult.Status.APPLIED) {
                return new FixtureApplication(requiredPattern, results, initial);
            }
        }

        for (int index = 0; index < DetailCellState.CELL_COUNT; index++) {
            LocalSubVoxelPosition position = LocalSubVoxelPosition.fromIndex(index);
            Selection current = inspect(x, y, z, position);
            byte currentId = blockId(current.state(), position);
            byte desiredId = desired.blockIdAtIndex(index);
            if (currentId == desiredId) {
                continue;
            }
            DetailMutationResult mutation = desiredId == 0
                    ? clear(current, requiredContext)
                    : fill(current, requiredContext, blocks.require(desiredId).name());
            results.add(mutation);
            if (mutation.status() != DetailMutationResult.Status.APPLIED) {
                return new FixtureApplication(requiredPattern, results, current);
            }
        }

        return new FixtureApplication(
                requiredPattern,
                results,
                inspect(x, y, z, LocalSubVoxelPosition.fromIndex(0)));
    }

    public String format(Selection selection) {
        Selection value = Objects.requireNonNull(selection, "selection");
        String material = value.selectedMaterial()
                .map(ResourceLocation::toString)
                .orElse("AIR");
        String representation = value.state() instanceof DetailCellState ? "DETAIL" : "FULL";
        return String.format(
                Locale.ROOT,
                "parent=[%d,%d,%d] chunk=%s revision=%d representation=%s "
                        + "occupancy=0x%016X hash=%s selected=[%d,%d,%d] "
                        + "material=%s occupied=%d collisionBoxes=%d meshPhase=%s "
                        + "lastKnownGood=%s meshDiagnostic=%s",
                value.x(), value.y(), value.z(), value.chunkKey(), value.chunkRevision(),
                representation, value.occupancyMask(), value.canonicalHash(),
                value.selected().x(), value.selected().y(), value.selected().z(),
                material, value.occupiedSubVoxels(), value.collisionBoxCount(),
                value.meshStatus().phase(), value.meshStatus().lastKnownGoodInstalled(),
                value.meshStatus().diagnostic().orElse("NONE"));
    }

    private DetailMutationResult set(
            Selection selection,
            InteractionContext context,
            Optional<ResourceLocation> replacement) {
        Selection required = Objects.requireNonNull(selection, "selection");
        return mutations.setSubVoxel(new DetailMutationRequest(
                Objects.requireNonNull(context, "context"),
                required.x(),
                required.y(),
                required.z(),
                required.chunkRevision(),
                required.state(),
                required.selected(),
                replacement));
    }

    private static FullCellState requireFull(ParentCellState state) {
        if (!(state instanceof FullCellState full)) {
            throw new IllegalArgumentException("Selected parent is not FULL");
        }
        if (full.blockId() == 0) {
            throw new IllegalArgumentException(
                    "FULL AIR requires first-subvoxel placement, not uniform conversion");
        }
        return full;
    }

    private static byte blockId(
            ParentCellState state, LocalSubVoxelPosition position) {
        return state instanceof DetailCellState detail
                ? detail.blockId(position)
                : ((FullCellState) state).blockId();
    }

    public record Selection(
            int x,
            int y,
            int z,
            ChunkKey chunkKey,
            long chunkRevision,
            ParentCellState state,
            LocalSubVoxelPosition selected,
            long occupancyMask,
            String canonicalHash,
            Optional<ResourceLocation> selectedMaterial,
            int occupiedSubVoxels,
            int collisionBoxCount,
            MeshStatus meshStatus) {
        public Selection {
            chunkKey = Objects.requireNonNull(chunkKey, "chunkKey");
            state = Objects.requireNonNull(state, "state");
            selected = Objects.requireNonNull(selected, "selected");
            canonicalHash = Objects.requireNonNull(canonicalHash, "canonicalHash");
            selectedMaterial = Objects.requireNonNull(selectedMaterial, "selectedMaterial");
            meshStatus = Objects.requireNonNull(meshStatus, "meshStatus");
            if (chunkRevision <= 0L) {
                throw new IllegalArgumentException("chunkRevision must be positive");
            }
            if (occupiedSubVoxels < 0 || occupiedSubVoxels > DetailCellState.CELL_COUNT) {
                throw new IllegalArgumentException("occupiedSubVoxels must be between 0 and 64");
            }
            if (collisionBoxCount < 0 || collisionBoxCount > DetailCellState.CELL_COUNT) {
                throw new IllegalArgumentException("collisionBoxCount must be between 0 and 64");
            }
        }
    }

    @FunctionalInterface
    public interface MeshDiagnosticSource {
        MeshStatus inspect(ChunkKey key, long revision);
    }

    public record MeshStatus(
            String phase,
            boolean lastKnownGoodInstalled,
            Optional<String> diagnostic) {
        private static final int MAX_DIAGNOSTIC_CHARACTERS = 512;

        public MeshStatus(String phase, boolean lastKnownGoodInstalled, String diagnostic) {
            this(phase, lastKnownGoodInstalled, Optional.ofNullable(diagnostic));
        }

        public MeshStatus {
            phase = Objects.requireNonNull(phase, "phase");
            diagnostic = Objects.requireNonNull(diagnostic, "diagnostic");
            if (phase.isBlank() || phase.length() > 64) {
                throw new IllegalArgumentException("mesh phase must be bounded and nonblank");
            }
            diagnostic.ifPresent(value -> {
                if (value.isBlank() || value.length() > MAX_DIAGNOSTIC_CHARACTERS) {
                    throw new IllegalArgumentException(
                            "mesh diagnostic must be nonblank and at most 512 characters");
                }
            });
        }

        public static MeshStatus none() {
            return new MeshStatus("NONE", false, Optional.empty());
        }
    }

    public record FixtureApplication(
            DetailFixturePattern pattern,
            List<DetailMutationResult> mutations,
            Selection finalSelection) {
        public FixtureApplication {
            pattern = Objects.requireNonNull(pattern, "pattern");
            mutations = List.copyOf(mutations);
            finalSelection = Objects.requireNonNull(finalSelection, "finalSelection");
            if (mutations.size() > 65) {
                throw new IllegalArgumentException("fixture mutations must remain bounded by 65");
            }
        }
    }
}
