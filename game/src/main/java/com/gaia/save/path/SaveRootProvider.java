package com.gaia.save.path;

import java.nio.file.Path;

/** Resolves the per-platform root directory for GaiaLegacy save data. */
@FunctionalInterface
public interface SaveRootProvider {
    Path saveRoot();
}
