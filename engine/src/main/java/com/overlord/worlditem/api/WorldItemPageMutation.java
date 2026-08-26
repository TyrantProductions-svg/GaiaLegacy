package com.overlord.worlditem.api;

import java.util.Objects;
import java.util.Optional;

/** Semantic page intent issued by the sole LogicalWorldItemService authority. */
public sealed interface WorldItemPageMutation {
    record Upsert(
            WorldItemPageSnapshot page,
            Optional<WorldItemPageDescriptor> expectedPrevious)
            implements WorldItemPageMutation {
        public Upsert {
            page = Objects.requireNonNull(page, "page");
            if (page.entries().isEmpty()) {
                throw new IllegalArgumentException(
                        "An empty WorldItem page must be represented as a remove");
            }
            expectedPrevious = Objects.requireNonNull(
                    expectedPrevious, "expectedPrevious");
            if (expectedPrevious.isPresent()) {
                WorldItemPageDescriptor expected = expectedPrevious.orElseThrow();
                if (!expected.chunkKey().equals(page.chunkKey())
                        || page.pageRevision() <= expected.pageRevision()) {
                    throw new IllegalArgumentException(
                            "page replacement must keep its key and advance revision");
                }
            }
        }
    }

    record Remove(WorldItemPageDescriptor expected) implements WorldItemPageMutation {
        public Remove {
            Objects.requireNonNull(expected, "expected");
        }
    }
}
