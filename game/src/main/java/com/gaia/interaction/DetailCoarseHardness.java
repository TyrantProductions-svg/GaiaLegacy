package com.gaia.interaction;

import java.util.Objects;

public final class DetailCoarseHardness {
    private DetailCoarseHardness() {}

    public static float resolve(DetailParentComposition composition) {
        return Objects.requireNonNull(composition, "composition")
                .hardestMaterial()
                .hardness();
    }
}
