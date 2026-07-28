package com.gaia.interaction.feedback;

import com.overlord.assets.ResourceLocation;
import com.overlord.renderer.feedback.WorldItemVisual;
import com.overlord.renderer.texture.TextureRegion;
import com.overlord.worlditem.api.WorldItemId;
import com.overlord.worlditem.api.WorldItemSnapshot;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/** Main-thread presentation cache derived exclusively from immutable snapshots. */
public final class WorldItemVisualTracker {
    private final Function<ResourceLocation, TextureRegion> regionResolver;
    private final Map<WorldItemId, VisualEntry> visuals = new LinkedHashMap<>();

    public WorldItemVisualTracker(
            Function<ResourceLocation, TextureRegion> regionResolver) {
        this.regionResolver = Objects.requireNonNull(regionResolver, "regionResolver");
    }

    public List<WorldItemVisual> reconcile(List<WorldItemSnapshot> snapshots) {
        Objects.requireNonNull(snapshots, "snapshots");
        Map<WorldItemId, WorldItemSnapshot> uniqueSnapshots = new LinkedHashMap<>();
        for (WorldItemSnapshot snapshot : snapshots) {
            Objects.requireNonNull(snapshot, "world item snapshot");
            if (uniqueSnapshots.putIfAbsent(snapshot.id(), snapshot) != null) {
                throw new IllegalArgumentException(
                        "Duplicate world item id: " + snapshot.id());
            }
        }

        Map<WorldItemId, VisualEntry> next = new LinkedHashMap<>();
        for (WorldItemSnapshot snapshot : uniqueSnapshots.values()) {
            ResourceLocation itemId = snapshot.stack().itemId();
            VisualEntry existing = visuals.get(snapshot.id());
            TextureRegion region = existing != null && existing.itemId().equals(itemId)
                    ? existing.visual().region()
                    : Objects.requireNonNull(
                            regionResolver.apply(itemId),
                            "resolved texture region");
            WorldItemVisual candidate =
                    new WorldItemVisual(
                            snapshot.id(),
                            snapshot.revision(),
                            snapshot.positionX(),
                            snapshot.positionY(),
                            snapshot.positionZ(),
                            region);
            next.put(
                    snapshot.id(),
                    existing != null
                                    && existing.itemId().equals(itemId)
                                    && candidate.equals(existing.visual())
                            ? existing
                            : new VisualEntry(itemId, candidate));
        }

        visuals.clear();
        visuals.putAll(next);
        return visuals.values().stream()
                .map(VisualEntry::visual)
                .sorted(Comparator.comparingLong(visual -> visual.id().value()))
                .toList();
    }

    public void clear() {
        visuals.clear();
    }

    private record VisualEntry(ResourceLocation itemId, WorldItemVisual visual) {
        private VisualEntry {
            Objects.requireNonNull(itemId, "itemId");
            Objects.requireNonNull(visual, "visual");
        }
    }
}
