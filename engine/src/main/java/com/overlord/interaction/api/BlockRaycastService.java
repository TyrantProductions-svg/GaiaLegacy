package com.overlord.interaction.api;

import java.util.Optional;
import org.joml.Vector3fc;

public interface BlockRaycastService {
    Optional<BlockHitResult> raycast(Vector3fc origin, Vector3fc direction, float maxDistance);
}
