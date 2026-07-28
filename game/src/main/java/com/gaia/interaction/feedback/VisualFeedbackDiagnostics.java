package com.gaia.interaction.feedback;

import com.overlord.interaction.api.BlockChangedEvent;

/** Receives presentation failures without granting visual code gameplay authority. */
@FunctionalInterface
public interface VisualFeedbackDiagnostics {
    void report(BlockChangedEvent event, Throwable failure);
}
