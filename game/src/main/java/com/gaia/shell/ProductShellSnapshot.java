package com.gaia.shell;

import java.util.Optional;

/** Immutable product-shell state exposed to render and input layers. */
public record ProductShellSnapshot(
        ScreenId screen,
        Optional<ModalId> modal,
        Optional<ScreenReturnTarget> returnTarget) {}
