package com.overlord.renderer.feedback;

public record FeedbackVisibility(
        boolean running,
        boolean cursorCaptured,
        boolean focused,
        boolean interactionBlocked) {
    public boolean showGameplayFeedback() {
        return running && cursorCaptured && focused && !interactionBlocked;
    }
}
