package com.gaia.interaction.feedback;

import com.overlord.assets.ResourceLocation;
import java.util.Objects;

/** Receives read-only failures from canonical item-to-region resolution. */
@FunctionalInterface
public interface VisualRegionDiagnostics {
    void report(ResourceLocation requestedItem, Throwable cause);

    static VisualRegionDiagnostics safe(VisualRegionDiagnostics delegate) {
        Objects.requireNonNull(delegate, "delegate");
        return (requestedItem, cause) -> {
            Objects.requireNonNull(requestedItem, "requestedItem");
            Objects.requireNonNull(cause, "cause");
            try {
                delegate.report(requestedItem, cause);
            } catch (RuntimeException | Error diagnosticFailure) {
                if (diagnosticFailure != cause) {
                    cause.addSuppressed(diagnosticFailure);
                }
            }
        };
    }
}
