package com.gaia;

import com.overlord.core.input.InputSnapshot;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/** Owns the fixed-input and single capture/render policy for one game-loop frame. */
final class GameLoopFrameOrchestrator {
    private GameLoopFrameOrchestrator() {}

    static FixedBatch runFixedBatch(
            int fixedSteps,
            Supplier<InputSnapshot> inputConsumer,
            Consumer<InputSnapshot> fixedSystemStep) {
        if (fixedSteps < 0) {
            throw new IllegalArgumentException("fixedSteps must be non-negative");
        }
        Objects.requireNonNull(inputConsumer, "inputConsumer");
        Objects.requireNonNull(fixedSystemStep, "fixedSystemStep");
        if (fixedSteps == 0) {
            return new FixedBatch(Optional.empty(), 0);
        }

        InputSnapshot input = Objects.requireNonNull(
                inputConsumer.get(), "consumed fixed input");
        for (int step = 0; step < fixedSteps; step++) {
            fixedSystemStep.accept(step == 0 ? input : input.heldOnly());
        }
        return new FixedBatch(Optional.of(input), fixedSteps);
    }

    static <T> T captureAndRender(
            FixedBatch fixedBatch,
            Function<Optional<InputSnapshot>, T> capture,
            Consumer<T> render) {
        Objects.requireNonNull(fixedBatch, "fixedBatch");
        T captured = Objects.requireNonNull(capture, "capture")
                .apply(fixedBatch.presentationInput());
        Objects.requireNonNull(render, "render")
                .accept(Objects.requireNonNull(captured, "captured frame"));
        return captured;
    }

    record FixedBatch(Optional<InputSnapshot> presentationInput, int fixedSteps) {
        FixedBatch {
            presentationInput = Objects.requireNonNull(
                    presentationInput, "presentationInput");
            if (fixedSteps < 0) {
                throw new IllegalArgumentException("fixedSteps must be non-negative");
            }
            if (fixedSteps == 0 && presentationInput.isPresent()) {
                throw new IllegalArgumentException(
                        "zero-step batches cannot contain consumed input");
            }
            if (fixedSteps > 0 && presentationInput.isEmpty()) {
                throw new IllegalArgumentException(
                        "fixed-step batches must contain consumed input");
            }
        }

        static FixedBatch zeroSteps() {
            return new FixedBatch(Optional.empty(), 0);
        }
    }
}
