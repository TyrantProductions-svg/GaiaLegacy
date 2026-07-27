package com.overlord.core.input;

import java.util.Objects;
import java.util.List;
import java.util.Set;

public record InputSnapshot(
        Set<Integer> downKeys,
        Set<Integer> pressedKeys,
        Set<Integer> downMouseButtons,
        Set<Integer> pressedMouseButtons,
        List<Integer> scrollDeltas) {
    public static final int MAX_SCROLL_STEPS_PER_SAMPLE = 64;

    public InputSnapshot {
        downKeys = Set.copyOf(Objects.requireNonNull(downKeys, "downKeys"));
        pressedKeys = Set.copyOf(Objects.requireNonNull(pressedKeys, "pressedKeys"));
        downMouseButtons = Set.copyOf(
                Objects.requireNonNull(downMouseButtons, "downMouseButtons"));
        pressedMouseButtons = Set.copyOf(
                Objects.requireNonNull(pressedMouseButtons, "pressedMouseButtons"));
        scrollDeltas = List.copyOf(
                Objects.requireNonNull(scrollDeltas, "scrollDeltas"));
        long magnitude = 0;
        for (int delta : scrollDeltas) {
            if (delta == 0) {
                throw new IllegalArgumentException("scroll deltas must be non-zero");
            }
            magnitude += Math.abs((long) delta);
            if (magnitude > MAX_SCROLL_STEPS_PER_SAMPLE) {
                throw new IllegalArgumentException(
                        "scroll sample exceeds the fixed-update safety bound");
            }
        }
    }

    public InputSnapshot(Set<Integer> downKeys, Set<Integer> pressedKeys) {
        this(downKeys, pressedKeys, Set.of(), Set.of(), List.of());
    }

    public InputSnapshot(
            Set<Integer> downKeys, Set<Integer> pressedKeys, int scrollSteps) {
        this(downKeys, pressedKeys, Set.of(), Set.of(),
                scrollSteps == 0 ? List.of() : List.of(scrollSteps));
    }

    public InputSnapshot(
            Set<Integer> downKeys,
            Set<Integer> pressedKeys,
            List<Integer> scrollDeltas) {
        this(downKeys, pressedKeys, Set.of(), Set.of(), scrollDeltas);
    }

    public boolean isKeyDown(int key) {
        return downKeys.contains(key);
    }

    public boolean isKeyPressed(int key) {
        return pressedKeys.contains(key);
    }

    public boolean isMouseButtonDown(int button) {
        return downMouseButtons.contains(button);
    }

    public boolean isMouseButtonPressed(int button) {
        return pressedMouseButtons.contains(button);
    }

    public int scrollSteps() {
        return scrollDeltas.stream().mapToInt(Integer::intValue).sum();
    }

    public InputSnapshot heldOnly() {
        return new InputSnapshot(
                downKeys, Set.of(), downMouseButtons, Set.of(), List.of());
    }
}
