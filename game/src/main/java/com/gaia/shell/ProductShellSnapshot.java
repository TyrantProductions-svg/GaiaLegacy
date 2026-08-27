package com.gaia.shell;

import java.util.Optional;

/** Immutable product-shell state exposed to render and input layers. */
public record ProductShellSnapshot(
        ScreenId screen,
        Optional<ModalId> modal,
        Optional<ScreenReturnTarget> returnTarget,
        Optional<OperationProgressSnapshot> operationProgress,
        int operationAnimationStep) {
    public ProductShellSnapshot(
            ScreenId screen,
            Optional<ModalId> modal,
            Optional<ScreenReturnTarget> returnTarget) {
        this(screen, modal, returnTarget, Optional.empty(), 0);
    }

    public ProductShellSnapshot(
            ScreenId screen,
            Optional<ModalId> modal,
            Optional<ScreenReturnTarget> returnTarget,
            Optional<OperationProgressSnapshot> operationProgress) {
        this(screen, modal, returnTarget, operationProgress, 0);
    }

    public ProductShellSnapshot {
        java.util.Objects.requireNonNull(screen, "screen");
        java.util.Objects.requireNonNull(modal, "modal");
        java.util.Objects.requireNonNull(returnTarget, "returnTarget");
        operationProgress = java.util.Objects.requireNonNull(
                operationProgress, "operationProgress");
        if (operationAnimationStep < 0 || operationAnimationStep >= 60) {
            throw new IllegalArgumentException(
                    "operationAnimationStep is outside its presentation bound");
        }
        if (operationProgress.isPresent()
                && screen != ScreenId.LOADING
                && screen != ScreenId.SAVING) {
            throw new IllegalArgumentException(
                    "operation progress is only visible on loading or saving routes");
        }
    }
}
