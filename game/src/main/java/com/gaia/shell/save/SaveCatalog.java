package com.gaia.shell.save;

import java.util.List;

/** Read-only boundary for future save discovery. */
public interface SaveCatalog {
    List<SaveSummary> summaries();
}
