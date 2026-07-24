package com.overlord.worlditem.api;

import com.overlord.inventory.api.ItemStack;
import java.util.Objects;
import java.util.Optional;

public record WorldItemSpawnResult(
        WorldItemSpawnRequest request,
        Status status,
        Optional<WorldItemSnapshot> item,
        Optional<ItemStack> remainder) {
    public WorldItemSpawnResult {
        request = Objects.requireNonNull(request, "request");
        status = Objects.requireNonNull(status, "status");
        item = Objects.requireNonNull(item, "item");
        remainder = Objects.requireNonNull(remainder, "remainder");
        if (status == Status.SPAWNED) {
            if (item.isEmpty() || remainder.isPresent()) {
                throw new IllegalArgumentException(
                        "SPAWNED requires an item and no remainder");
            }
            validateSpawnedItem(request, item.orElseThrow());
        } else if (item.isPresent() || !remainder.equals(Optional.of(request.stack()))) {
            throw new IllegalArgumentException(
                    "REJECTED requires no item and the full request remainder");
        }
    }

    private static void validateSpawnedItem(
            WorldItemSpawnRequest request, WorldItemSnapshot item) {
        if (!item.stack().equals(request.stack())
                || item.positionX() != request.positionX()
                || item.positionY() != request.positionY()
                || item.positionZ() != request.positionZ()
                || item.velocityX() != request.velocityX()
                || item.velocityY() != request.velocityY()
                || item.velocityZ() != request.velocityZ()) {
            throw new IllegalArgumentException(
                    "SPAWNED item must match the request stack, position, and velocity");
        }
    }

    public enum Status {
        SPAWNED,
        REJECTED
    }
}
