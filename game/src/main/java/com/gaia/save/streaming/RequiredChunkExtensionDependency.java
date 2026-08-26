package com.gaia.save.streaming;

import com.gaia.save.format.SaveSectionId;
import java.util.Objects;

/** Exact post-transaction count of Chunks carrying one required extension. */
public record RequiredChunkExtensionDependency(
        SaveSectionId chunkExtensionId, int referenceCount) {
    public RequiredChunkExtensionDependency {
        chunkExtensionId = Objects.requireNonNull(
                chunkExtensionId, "chunkExtensionId");
        if (referenceCount < 0 || referenceCount > 1_024) {
            throw new IllegalArgumentException("dependency referenceCount exceeds its bound");
        }
    }
}
