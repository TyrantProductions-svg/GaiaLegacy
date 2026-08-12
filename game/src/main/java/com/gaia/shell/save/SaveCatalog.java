package com.gaia.shell.save;

import java.util.List;

/** Read-only boundary for immutable local-save discovery snapshots. */
public interface SaveCatalog {
    List<SaveSummary> summaries();
}
