package com.gaia.save.streaming;

import com.gaia.save.format.SaveSectionId;
import java.util.Objects;

/** Explicit global extension replacement or removal. Omission retains bytes. */
public sealed interface StreamedGlobalExtensionMutation {
    record Upsert(StreamedGlobalExtension extension)
            implements StreamedGlobalExtensionMutation {
        public Upsert {
            Objects.requireNonNull(extension, "extension");
        }
    }

    record Remove(SaveSectionId sectionId) implements StreamedGlobalExtensionMutation {
        public Remove {
            Objects.requireNonNull(sectionId, "sectionId");
        }
    }
}
