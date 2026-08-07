package com.gaia.interaction.feedback;

import com.overlord.renderer.feedback.BlockVisualCoordinate;
import com.overlord.renderer.feedback.TransientBlockVisual;
import com.overlord.renderer.feedback.VisualTransform;
import com.overlord.renderer.feedback.WorldItemFaceRegions;
import com.overlord.renderer.shader.WorldShaderUniforms;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Bounded CPU-only voxel presentation overrides. */
public final class TransientBlockVisualSystem implements AutoCloseable {
    public static final int DEFAULT_CAPACITY = WorldShaderUniforms.MAX_EXCLUDED_BLOCK_CELLS;
    public static final double PLACEMENT_DURATION_SECONDS = 0.14;
    public static final double BREAK_DURATION_SECONDS = 0.18;

    private final int capacity;
    private final LinkedHashMap<BlockVisualCoordinate, Transition> transitions =
            new LinkedHashMap<>();
    private boolean open = true;

    public TransientBlockVisualSystem() {
        this(DEFAULT_CAPACITY);
    }

    public TransientBlockVisualSystem(int capacity) {
        if (capacity <= 0 || capacity > DEFAULT_CAPACITY) {
            throw new IllegalArgumentException(
                    "capacity must be between 1 and " + DEFAULT_CAPACITY);
        }
        this.capacity = capacity;
    }

    public void registerPlacement(
            BlockVisualCoordinate coordinate,
            WorldItemFaceRegions faces,
            long eventIdentity) {
        register(coordinate, faces, TransientBlockVisual.Type.PLACEMENT, eventIdentity);
    }

    public void registerBreak(
            BlockVisualCoordinate coordinate,
            WorldItemFaceRegions faces,
            long eventIdentity) {
        register(coordinate, faces, TransientBlockVisual.Type.BREAK, eventIdentity);
    }

    public void update(double deltaSeconds) {
        requireOpen();
        if (!Double.isFinite(deltaSeconds) || deltaSeconds < 0) {
            throw new IllegalArgumentException("deltaSeconds must be finite and non-negative");
        }
        Iterator<Map.Entry<BlockVisualCoordinate, Transition>> iterator =
                transitions.entrySet().iterator();
        while (iterator.hasNext()) {
            Transition transition = iterator.next().getValue();
            transition.elapsed += deltaSeconds;
            if (transition.elapsed + 1.0e-12 >= transition.duration()) {
                iterator.remove();
            }
        }
    }

    public List<TransientBlockVisual> snapshot() {
        List<TransientBlockVisual> result = new ArrayList<>(transitions.size());
        for (Map.Entry<BlockVisualCoordinate, Transition> entry : transitions.entrySet()) {
            Transition transition = entry.getValue();
            result.add(new TransientBlockVisual(
                    entry.getKey(),
                    transition.faces,
                    transition.type,
                    transition.eventIdentity,
                    transition.transform()));
        }
        return List.copyOf(result);
    }

    public List<BlockVisualCoordinate> excludedCells() {
        return transitions.entrySet().stream()
                .map(Map.Entry::getKey)
                .toList();
    }

    public boolean isOpen() {
        return open;
    }

    public void clear() {
        requireOpen();
        transitions.clear();
    }

    @Override
    public void close() {
        if (!open) {
            return;
        }
        open = false;
        transitions.clear();
    }

    private void register(
            BlockVisualCoordinate coordinate,
            WorldItemFaceRegions faces,
            TransientBlockVisual.Type type,
            long eventIdentity) {
        requireOpen();
        BlockVisualCoordinate immutableCoordinate = Objects.requireNonNull(
                coordinate, "coordinate");
        WorldItemFaceRegions immutableFaces = Objects.requireNonNull(faces, "faces");
        TransientBlockVisual.Type immutableType = Objects.requireNonNull(type, "type");
        transitions.remove(immutableCoordinate);
        if (transitions.size() == capacity) {
            Iterator<BlockVisualCoordinate> oldest = transitions.keySet().iterator();
            oldest.next();
            oldest.remove();
        }
        transitions.put(immutableCoordinate,
                new Transition(immutableFaces, immutableType, eventIdentity));
    }

    private void requireOpen() {
        if (!open) {
            throw new IllegalStateException("transient block visual system is closed");
        }
    }

    private static final class Transition {
        private final WorldItemFaceRegions faces;
        private final TransientBlockVisual.Type type;
        private final long eventIdentity;
        private double elapsed;

        private Transition(
                WorldItemFaceRegions faces,
                TransientBlockVisual.Type type,
                long eventIdentity) {
            this.faces = faces;
            this.type = type;
            this.eventIdentity = eventIdentity;
        }

        private double duration() {
            return type == TransientBlockVisual.Type.PLACEMENT
                    ? PLACEMENT_DURATION_SECONDS
                    : BREAK_DURATION_SECONDS;
        }

        private VisualTransform transform() {
            float progress = (float) Math.min(1.0, elapsed / duration());
            if (type == TransientBlockVisual.Type.PLACEMENT) {
                float eased = 1.0f - cube(1.0f - progress);
                return new VisualTransform(
                        0, 0, 0, 0, 0, 0,
                        0.85f + 0.15f * eased,
                        1.0f);
            }
            float smooth = progress * progress * (3.0f - 2.0f * progress);
            return new VisualTransform(
                    0,
                    -0.025f * smooth,
                    0,
                    0,
                    0,
                    0,
                    1.0f - 0.28f * smooth,
                    1.0f - 0.45f * smooth);
        }
    }

    private static float cube(float value) {
        return value * value * value;
    }
}
