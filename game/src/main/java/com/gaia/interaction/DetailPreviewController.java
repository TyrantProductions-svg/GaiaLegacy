package com.gaia.interaction;

import java.util.Objects;
import java.util.Optional;

/** Owns one current immutable precision preview and no domain capability. */
public final class DetailPreviewController {
    private DetailPlacementPreview current;

    public void publish(DetailPlacementPreview preview) {
        current = Objects.requireNonNull(preview, "preview");
    }

    public Optional<DetailPlacementPreview> current() {
        return Optional.ofNullable(current);
    }

    public void clear() {
        current = null;
    }

    public void onEligibilityChanged(boolean eligible) {
        if (!eligible) {
            clear();
        }
    }

    public void onFocusLost() {
        clear();
    }

    public void onSessionTransition() {
        clear();
    }
}
