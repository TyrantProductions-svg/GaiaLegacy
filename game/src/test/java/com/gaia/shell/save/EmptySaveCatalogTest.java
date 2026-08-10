package com.gaia.shell.save;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;

class EmptySaveCatalogTest {
    @Test
    void exposesAnEmptyImmutableCatalog() {
        SaveCatalog catalog = new EmptySaveCatalog();
        List<SaveSummary> summaries = catalog.summaries();

        assertTrue(summaries.isEmpty());
        assertThrows(UnsupportedOperationException.class, summaries::clear);
    }
}
