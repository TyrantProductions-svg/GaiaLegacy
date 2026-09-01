package com.gaia.ui;

import com.overlord.assets.ResourceLocation;
import java.util.Objects;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

public final class UiIconResolver {
    private final UiIconAtlas atlas;
    private final Consumer<ResourceLocation> diagnostics;
    private final Map<ResourceLocation, ResourceLocation> aliases;
    private final Set<ResourceLocation> diagnosed = ConcurrentHashMap.newKeySet();

    public UiIconResolver(UiIconAtlas atlas, Consumer<ResourceLocation> diagnostics) {
        this(atlas, Map.of(), diagnostics);
    }

    public UiIconResolver(
            UiIconAtlas atlas,
            Map<ResourceLocation, ResourceLocation> aliases,
            Consumer<ResourceLocation> diagnostics) {
        this.atlas = Objects.requireNonNull(atlas, "atlas");
        this.aliases = Map.copyOf(Objects.requireNonNull(aliases, "aliases"));
        this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
    }

    public UiIconResolver(UiIconAtlas atlas) {
        this(atlas, ignored -> {});
    }

    public UiIconDefinition resolve(ResourceLocation id) {
        Objects.requireNonNull(id, "id");
        ResourceLocation resolvedId = aliases.getOrDefault(id, id);
        UiIconDefinition definition = atlas.icons().get(resolvedId);
        if (definition != null) {
            return definition;
        }
        if (diagnosed.add(id)) {
            diagnostics.accept(id);
        }
        return atlas.fallback();
    }
}
