package com.gaia.shell.ui;

import com.overlord.renderer.ui.UiDrawCommand;
import com.overlord.renderer.ui.UiFrame;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Pure immutable composition of session UI below the product-shell UI. */
public final class ProductUiCompositor {
    private ProductUiCompositor() {}

    public static UiFrame combine(UiFrame sessionUi, UiFrame productUi) {
        Objects.requireNonNull(sessionUi, "sessionUi");
        Objects.requireNonNull(productUi, "productUi");
        List<UiDrawCommand> commands = new ArrayList<>(
                sessionUi.commands().size() + productUi.commands().size());
        commands.addAll(sessionUi.commands());
        commands.addAll(productUi.commands());
        return new UiFrame(commands);
    }
}
