package com.gaia.shell.save;

import java.util.List;

/** Phase 13 catalog adapter before save discovery exists. */
public final class EmptySaveCatalog implements SaveCatalog {
    @Override
    public List<SaveSummary> summaries() {
        return List.of();
    }
}
