package com.overlord.core.input;

import java.util.Objects;
import java.util.Set;

public record InputSnapshot(Set<Integer> downKeys, Set<Integer> pressedKeys) {
    public InputSnapshot {
        downKeys = Set.copyOf(Objects.requireNonNull(downKeys, "downKeys"));
        pressedKeys = Set.copyOf(Objects.requireNonNull(pressedKeys, "pressedKeys"));
    }

    public boolean isKeyDown(int key) {
        return downKeys.contains(key);
    }

    public boolean isKeyPressed(int key) {
        return pressedKeys.contains(key);
    }
}
