package com.gaia.interaction.feedback;

import com.overlord.assets.ResourceLocation;
import com.overlord.renderer.feedback.WorldItemVisual;
import com.overlord.renderer.feedback.WorldItemFaceRegions;
import com.gaia.worlditem.WorldItemPresentationSnapshot;
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
    private final Function<ResourceLocation, WorldItemFaceRegions> faceResolver;
    private final Map<WorldItemId, VisualEntry> visuals = new LinkedHashMap<>();

    public WorldItemVisualTracker(
            Function<ResourceLocation, WorldItemFaceRegions> faceResolver) {
        this.faceResolver = Objects.requireNonNull(faceResolver, "faceResolver");
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

        List<VisualCandidate> candidates = uniqueSnapshots.values().stream()
                .map(snapshot -> new VisualCandidate(
                        snapshot.id(),
                        snapshot.revision(),
                        snapshot.positionX(),
                        snapshot.positionY(),
                        snapshot.positionZ(),
                        snapshot.stack().itemId()))
                .toList();
        return reconcileCandidates(candidates);
    }

    public List<WorldItemVisual> reconcilePhysical(
            List<WorldItemPresentationSnapshot> snapshots, float alpha) {
        Objects.requireNonNull(snapshots, "snapshots");
        if (!Float.isFinite(alpha) || alpha < 0.0f || alpha > 1.0f) {
            throw new IllegalArgumentException("alpha must be finite and in [0, 1]");
        }
        Map<WorldItemId, WorldItemPresentationSnapshot> unique = new LinkedHashMap<>();
        for (WorldItemPresentationSnapshot snapshot : snapshots) {
            Objects.requireNonNull(snapshot, "world item presentation snapshot");
            if (unique.putIfAbsent(snapshot.id(), snapshot) != null) {
                throw new IllegalArgumentException("Duplicate world item id: " + snapshot.id());
            }
        }
        List<VisualCandidate> candidates = unique.values().stream()
                .map(snapshot -> {
                    var item = snapshot.runtime().runtime().item();
                    return new VisualCandidate(
                            snapshot.id(),
                            snapshot.revision(),
                            snapshot.positionX(alpha),
                            snapshot.positionY(alpha),
                            snapshot.positionZ(alpha),
                            item.stack().itemId());
                })
                .toList();
        return reconcileCandidates(candidates);
    }

    private List<WorldItemVisual> reconcileCandidates(List<VisualCandidate> candidates) {
        Map<WorldItemId, VisualEntry> next = new LinkedHashMap<>();
        for (VisualCandidate snapshot : candidates) {
            ResourceLocation itemId = snapshot.itemId();
            VisualEntry existing = visuals.get(snapshot.id());
            WorldItemFaceRegions faces = existing != null && existing.itemId().equals(itemId)
                    ? existing.visual().faces()
                    : Objects.requireNonNull(
                            faceResolver.apply(itemId),
                            "resolved face regions");
            WorldItemVisual candidate =
                    new WorldItemVisual(
                            snapshot.id(),
                            snapshot.revision(),
                            snapshot.x(),
                            snapshot.y(),
                            snapshot.z(),
                            faces);
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

    private record VisualCandidate(
            WorldItemId id,
            long revision,
            double x,
            double y,
            double z,
            ResourceLocation itemId) {}
}
