package com.gaia.worlditem;

import com.overlord.worlditem.api.WorldItemId;
import java.util.Objects;
import java.util.Optional;

/** Closed pickup outcome with conservation counts and committed-only receipt. */
public record WorldItemPickupResult(
        Status status,
        WorldItemId itemId,
        int originalWorldCount,
        int inventoryCommittedCount,
        int remainingWorldCount,
        Optional<WorldItemPickupReceipt> committedReceipt,
        Optional<Throwable> failure) {
    public WorldItemPickupResult {
        status = Objects.requireNonNull(status, "status");
        itemId = Objects.requireNonNull(itemId, "itemId");
        committedReceipt = Objects.requireNonNull(committedReceipt, "committedReceipt");
        failure = Objects.requireNonNull(failure, "failure");
        if (originalWorldCount < 0
                || inventoryCommittedCount < 0
                || remainingWorldCount < 0) {
            throw new IllegalArgumentException("pickup counts must be non-negative");
        }
        boolean applied = status == Status.PICKED_ALL
                || status == Status.PICKED_PARTIAL
                || status == Status.PICKED_WITH_NOTIFICATION_FAILURE;
        if (applied) {
            WorldItemPickupReceipt receipt = committedReceipt.orElseThrow(
                    () -> new IllegalArgumentException(
                            "applied pickup requires a committed receipt"));
            if (!receipt.itemId().equals(itemId)
                    || receipt.picked().count() != inventoryCommittedCount
                    || inventoryCommittedCount <= 0
                    || Math.addExact(inventoryCommittedCount, remainingWorldCount)
                            != originalWorldCount) {
                throw new IllegalArgumentException(
                        "applied pickup must contain exact conserved counts");
            }
            if (status == Status.PICKED_ALL && remainingWorldCount != 0) {
                throw new IllegalArgumentException("PICKED_ALL requires no remainder");
            }
            if (status == Status.PICKED_PARTIAL && remainingWorldCount <= 0) {
                throw new IllegalArgumentException("PICKED_PARTIAL requires a remainder");
            }
            if ((status == Status.PICKED_WITH_NOTIFICATION_FAILURE)
                    != failure.isPresent()) {
                throw new IllegalArgumentException(
                        "notification-failure status and diagnostic must agree");
            }
        } else {
            if (committedReceipt.isPresent()) {
                throw new IllegalArgumentException(
                        "non-applied pickup must not contain a committed receipt");
            }
            if (status == Status.UNKNOWN_ITEM
                    && (originalWorldCount != 0
                            || inventoryCommittedCount != 0
                            || remainingWorldCount != 0)) {
                throw new IllegalArgumentException("UNKNOWN_ITEM must contain zero counts");
            }
            boolean ordinaryFailure = status == Status.PICKUP_DELAYED
                    || status == Status.WORLD_ITEM_BUSY
                    || status == Status.INVENTORY_FULL
                    || status == Status.INVENTORY_REJECTED
                    || status == Status.WORLD_REJECTED;
            if (ordinaryFailure
                    && (inventoryCommittedCount != 0
                            || remainingWorldCount != originalWorldCount)) {
                throw new IllegalArgumentException(
                        "pre-barrier pickup result must preserve all canonical counts");
            }
            if ((status == Status.COMMIT_GUARANTEE_BROKEN
                            || status == Status.INDETERMINATE)
                    && failure.isEmpty()) {
                throw new IllegalArgumentException(
                        "fatal pickup result requires a diagnostic");
            }
        }
    }

    public enum Status {
        PICKED_ALL,
        PICKED_PARTIAL,
        PICKED_WITH_NOTIFICATION_FAILURE,
        PICKUP_DELAYED,
        UNKNOWN_ITEM,
        WORLD_ITEM_BUSY,
        INVENTORY_FULL,
        INVENTORY_REJECTED,
        WORLD_REJECTED,
        COMMIT_GUARANTEE_BROKEN,
        INDETERMINATE
    }
}
