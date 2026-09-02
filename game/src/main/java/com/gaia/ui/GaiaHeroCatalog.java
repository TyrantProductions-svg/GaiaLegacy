package com.gaia.ui;

import com.overlord.assets.ResourceLocation;
import com.overlord.renderer.ui.UiTextureSampling;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Immutable metadata for deterministic project-owned product-shell hero images. */
public record GaiaHeroCatalog(
        List<Hero> heroes,
        String initialHero,
        int maximumResidentHeroPages) {
    public GaiaHeroCatalog {
        heroes = List.copyOf(heroes);
        if (heroes.isEmpty()) {
            throw new IllegalArgumentException("hero catalog must not be empty");
        }
        initialHero = Objects.requireNonNull(initialHero, "initialHero");
        Set<String> ids = new HashSet<>();
        for (Hero hero : heroes) {
            if (!ids.add(hero.id())) {
                throw new IllegalArgumentException("hero ids must be unique");
            }
        }
        if (!ids.contains(initialHero)) {
            throw new IllegalArgumentException("initial hero must exist in the catalog");
        }
        if (maximumResidentHeroPages != 1) {
            throw new IllegalArgumentException(
                    "the static vertical slice must own exactly one resident hero page");
        }
    }

    public Hero initial() {
        return heroes.stream()
                .filter(hero -> hero.id().equals(initialHero))
                .findFirst()
                .orElseThrow();
    }

    public record Hero(
            String id,
            ResourceLocation image,
            int width,
            int height,
            UiTextureSampling sampling,
            String pngSha256) {
        public Hero {
            id = Objects.requireNonNull(id, "id");
            image = Objects.requireNonNull(image, "image");
            sampling = Objects.requireNonNull(sampling, "sampling");
            pngSha256 = Objects.requireNonNull(pngSha256, "pngSha256");
            if (id.isBlank() || width != 1_280 || height != 720
                    || sampling != UiTextureSampling.LINEAR
                    || !pngSha256.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("hero metadata is invalid");
            }
        }
    }
}
