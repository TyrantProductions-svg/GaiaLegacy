package com.overlord.worlditem.api;

import java.util.List;
import java.util.Optional;

/**
 * Narrow runtime extension beside the Phase 7 world-item service contract.
 * Implementations retain ownership of the canonical logical store.
 */
public interface WorldItemRuntimeAccess {
    List<WorldItemPhysicalSnapshot> physicalSnapshots();

    Optional<WorldItemPhysicalSnapshot> physicalSnapshot(WorldItemId itemId);

    /** True while automatic projection motion must preserve an exact persistence capture. */
    default boolean motionPinnedForPersistence(WorldItemId itemId) {
        return false;
    }

    WorldItemMotionUpdateResult updateMotion(WorldItemMotionUpdate update);
}
