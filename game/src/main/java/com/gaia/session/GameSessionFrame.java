package com.gaia.session;

import com.overlord.renderer.RenderFrameInput;
import java.util.Objects;

/** One immutable gameplay presentation captured by the session owner. */
public record GameSessionFrame(RenderFrameInput renderInput) {
    public GameSessionFrame {
        renderInput = Objects.requireNonNull(renderInput, "renderInput");
    }

    public GameSessionFrame copy() {
        return new GameSessionFrame(renderInput);
    }
}
