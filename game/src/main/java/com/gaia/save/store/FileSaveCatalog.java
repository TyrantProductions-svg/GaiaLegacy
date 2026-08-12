package com.gaia.save.store;

import com.gaia.shell.save.SaveCatalog;
import com.gaia.shell.save.SaveSummary;
import java.util.List;
import java.util.Objects;

/** Filesystem-backed adapter for the shell's immutable save-catalog seam. */
public final class FileSaveCatalog implements SaveCatalog {
    private final SaveRepository repository;

    public FileSaveCatalog(SaveRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository");
    }

    @Override
    public List<SaveSummary> summaries() {
        return repository.summaries();
    }
}
