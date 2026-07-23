package com.overlord.assets;

import java.util.List;
import java.util.Objects;

public record ResourceIndex(
        String namespace,
        List<String> blocks,
        List<String> materials,
        List<String> atlases) {
    public ResourceIndex {
        Objects.requireNonNull(namespace, "namespace");
        blocks = List.copyOf(blocks);
        materials = List.copyOf(materials);
        atlases = List.copyOf(atlases);
    }
}
